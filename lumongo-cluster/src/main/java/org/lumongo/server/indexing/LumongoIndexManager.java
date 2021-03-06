package org.lumongo.server.indexing;

import com.hazelcast.core.IExecutorService;
import com.hazelcast.core.Member;
import com.mongodb.MongoClient;
import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoIterable;
import org.apache.log4j.Logger;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.queryparser.classic.QueryParser.Operator;
import org.apache.lucene.search.Query;
import org.lumongo.cluster.message.Lumongo;
import org.lumongo.cluster.message.Lumongo.AssociatedDocument;
import org.lumongo.cluster.message.Lumongo.ClearRequest;
import org.lumongo.cluster.message.Lumongo.ClearResponse;
import org.lumongo.cluster.message.Lumongo.DeleteRequest;
import org.lumongo.cluster.message.Lumongo.DeleteResponse;
import org.lumongo.cluster.message.Lumongo.FacetRequest;
import org.lumongo.cluster.message.Lumongo.FetchRequest;
import org.lumongo.cluster.message.Lumongo.FetchResponse;
import org.lumongo.cluster.message.Lumongo.FetchType;
import org.lumongo.cluster.message.Lumongo.GetFieldNamesRequest;
import org.lumongo.cluster.message.Lumongo.GetFieldNamesResponse;
import org.lumongo.cluster.message.Lumongo.GetIndexesRequest;
import org.lumongo.cluster.message.Lumongo.GetIndexesResponse;
import org.lumongo.cluster.message.Lumongo.GetMembersRequest;
import org.lumongo.cluster.message.Lumongo.GetMembersResponse;
import org.lumongo.cluster.message.Lumongo.GetNumberOfDocsRequest;
import org.lumongo.cluster.message.Lumongo.GetNumberOfDocsResponse;
import org.lumongo.cluster.message.Lumongo.GetTermsRequest;
import org.lumongo.cluster.message.Lumongo.GetTermsResponse;
import org.lumongo.cluster.message.Lumongo.IndexCreateRequest;
import org.lumongo.cluster.message.Lumongo.IndexCreateResponse;
import org.lumongo.cluster.message.Lumongo.IndexDeleteRequest;
import org.lumongo.cluster.message.Lumongo.IndexDeleteResponse;
import org.lumongo.cluster.message.Lumongo.IndexMapping;
import org.lumongo.cluster.message.Lumongo.IndexSegmentResponse;
import org.lumongo.cluster.message.Lumongo.IndexSettings;
import org.lumongo.cluster.message.Lumongo.IndexSettingsResponse;
import org.lumongo.cluster.message.Lumongo.InternalQueryResponse;
import org.lumongo.cluster.message.Lumongo.LMFacet;
import org.lumongo.cluster.message.Lumongo.LMMember;
import org.lumongo.cluster.message.Lumongo.OptimizeRequest;
import org.lumongo.cluster.message.Lumongo.OptimizeResponse;
import org.lumongo.cluster.message.Lumongo.QueryRequest;
import org.lumongo.cluster.message.Lumongo.QueryResponse;
import org.lumongo.cluster.message.Lumongo.ResultDocument;
import org.lumongo.cluster.message.Lumongo.SegmentCountResponse;
import org.lumongo.cluster.message.Lumongo.SegmentMapping;
import org.lumongo.cluster.message.Lumongo.StoreRequest;
import org.lumongo.cluster.message.Lumongo.StoreResponse;
import org.lumongo.cluster.message.Lumongo.Term;
import org.lumongo.server.config.ClusterConfig;
import org.lumongo.server.config.IndexConfig;
import org.lumongo.server.config.LocalNodeConfig;
import org.lumongo.server.config.MongoConfig;
import org.lumongo.server.config.Nodes;
import org.lumongo.server.connection.InternalClient;
import org.lumongo.server.connection.SocketRequestFederator;
import org.lumongo.server.exceptions.IndexDoesNotExist;
import org.lumongo.server.exceptions.InvalidIndexConfig;
import org.lumongo.server.exceptions.SegmentDoesNotExist;
import org.lumongo.server.hazelcast.HazelcastManager;
import org.lumongo.server.hazelcast.ReloadIndexSettingsTask;
import org.lumongo.server.hazelcast.UnloadIndexTask;
import org.lumongo.server.searching.QueryCombiner;
import org.lumongo.server.searching.QueryWithFilters;
import org.lumongo.util.ClusterHelper;
import org.lumongo.util.LumongoThreadFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class LumongoIndexManager {
	private final static Logger log = Logger.getLogger(LumongoIndexManager.class);
	
	private final ReadWriteLock globalLock;
	
	private final ConcurrentHashMap<String, LumongoIndex> indexMap;
	private final InternalClient internalClient;
	
	private final ExecutorService pool;
	
	private HazelcastManager hazelcastManager;
	
	private MongoConfig mongoConfig;
	private ClusterConfig clusterConfig;
	
	private MongoClient mongo;
	
	public LumongoIndexManager(MongoConfig mongoConfig, ClusterConfig clusterConfig) throws UnknownHostException {
		this.globalLock = new ReentrantReadWriteLock(true);
		
		this.mongoConfig = mongoConfig;
		this.clusterConfig = clusterConfig;
		
		this.indexMap = new ConcurrentHashMap<String, LumongoIndex>();
		this.internalClient = new InternalClient(mongoConfig, clusterConfig);
		
		log.info("Using mongo <" + mongoConfig.getMongoHost() + ":" + mongoConfig.getMongoPort() + ">");
		this.mongo = new MongoClient(mongoConfig.getMongoHost(), mongoConfig.getMongoPort());
		
		this.pool = Executors.newCachedThreadPool(new LumongoThreadFactory("manager"));
		
	}
	
	public void init(HazelcastManager hazelcastManager) throws UnknownHostException, MongoException {
		globalLock.writeLock().lock();
		try {
			this.hazelcastManager = hazelcastManager;
		}
		finally {
			globalLock.writeLock().unlock();
		}
		
	}
	
	public void handleServerRemoved(Set<Member> currentMembers, Member memberRemoved, boolean master) {
		globalLock.writeLock().lock();
		try {
			if (master) {
				handleServerRemoved(currentMembers, memberRemoved);
			}
			
			internalClient.removeMember(memberRemoved);
		}
		finally {
			globalLock.writeLock().unlock();
		}
		
	}
	
	public void handleServerAdded(Set<Member> currentMembers, Member memberAdded, boolean master) throws Exception {
		globalLock.writeLock().lock();
		try {
			if (master) {
				// make sure we can resolve it before transfering segments
				Nodes nodes = ClusterHelper.getNodes(mongoConfig);
				@SuppressWarnings("unused")
				LocalNodeConfig localNodeConfig = nodes.find(memberAdded);
				
				handleServerAdded(currentMembers, memberAdded);
			}
			
			internalClient.addMember(memberAdded);
		}
		finally {
			globalLock.writeLock().unlock();
		}
		
	}
	
	public List<String> getIndexNames() {
		globalLock.writeLock().lock();
		
		try {
			ArrayList<String> indexNames = new ArrayList<String>();
			log.info("Searching database <" + mongoConfig.getDatabaseName() + "> for indexes");
			MongoDatabase db = mongo.getDatabase(mongoConfig.getDatabaseName());
			MongoIterable<String> allCollections = db.listCollectionNames();
			
			for (String collection : allCollections) {
				if (collection.endsWith(LumongoIndex.CONFIG_SUFFIX)) {
					String indexName = collection.substring(0, collection.length() - LumongoIndex.CONFIG_SUFFIX.length());
					indexNames.add(indexName);
				}
				
			}
			
			return indexNames;
		}
		finally {
			globalLock.writeLock().unlock();
		}
	}
	
	public void loadIndexes() {
		globalLock.writeLock().lock();
		
		try {
			log.info("Loading existing indexes");
			List<String> indexNames = getIndexNames();
			for (String indexName : indexNames) {
				try {
					loadIndex(indexName, true);
				}
				catch (Exception e) {
					log.error("Failed to load index <" + indexName + ">: " + e.getClass().getSimpleName() + ": ", e);
				}
			}
			log.info("Finished loading existing indexes");
		}
		finally {
			globalLock.writeLock().unlock();
		}
	}
	
	public IndexCreateResponse createIndex(IndexCreateRequest request) throws Exception {
		globalLock.writeLock().lock();
		try {
			log.info("Creating index: <" + request.getIndexName() + ">");
			
			IndexConfig indexConfig = new IndexConfig(request);
			
			String indexName = indexConfig.getIndexName();
			if (indexMap.containsKey(indexName)) {
				throw new Exception("Index <" + indexName + "> already exist");
			}
			LumongoIndex i = LumongoIndex.createIndex(hazelcastManager, mongoConfig, clusterConfig, indexConfig);
			indexMap.put(indexConfig.getIndexName(), i);
			i.loadAllSegments();
			i.forceBalance(hazelcastManager.getMembers());
			
			log.info("Created index: <" + request.getIndexName() + ">");
			
			return IndexCreateResponse.newBuilder().build();
		}
		finally {
			globalLock.writeLock().unlock();
		}
	}
	
	public void loadIndex(String indexName, boolean loadAllSegments) throws Exception {
		globalLock.writeLock().lock();
		try {
			LumongoIndex i = LumongoIndex.loadIndex(hazelcastManager, mongoConfig, mongo, clusterConfig, indexName);
			if (loadAllSegments) {
				i.loadAllSegments();
			}
			indexMap.put(indexName, i);
		}
		finally {
			globalLock.writeLock().unlock();
		}
	}
	
	private void handleServerAdded(Set<Member> currentMembers, Member memberAdded) {
		globalLock.writeLock().lock();
		try {
			for (String key : indexMap.keySet()) {
				LumongoIndex i = indexMap.get(key);
				i.handleServerAdded(currentMembers, memberAdded);
			}
		}
		finally {
			globalLock.writeLock().unlock();
		}
	}
	
	private void handleServerRemoved(Set<Member> currentMembers, Member memberRemoved) {
		globalLock.writeLock().lock();
		try {
			for (String key : indexMap.keySet()) {
				LumongoIndex i = indexMap.get(key);
				i.handleServerRemoved(currentMembers, memberRemoved);
			}
		}
		finally {
			globalLock.writeLock().unlock();
		}
	}
	
	public void updateSegmentMap(String indexName, Map<Member, Set<Integer>> newMemberToSegmentMap) throws Exception {
		globalLock.writeLock().lock();
		try {
			if (!indexMap.containsKey(indexName)) {
				loadIndex(indexName, false);
			}
			
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			i.updateSegmentMap(newMemberToSegmentMap);
		}
		finally {
			globalLock.writeLock().unlock();
		}
	}
	
	public IndexDeleteResponse deleteIndex(IndexDeleteRequest request) throws Exception {
		globalLock.writeLock().lock();
		try {
			String indexName = request.getIndexName();
			
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			
			Set<Member> currentMembers = hazelcastManager.getMembers();
			IExecutorService executorService = hazelcastManager.getExecutorService();
			
			Member self = hazelcastManager.getSelf();
			
			log.info("Unload index <" + indexName + "> for delete");
			for (Member m : currentMembers) {
				try {
					UnloadIndexTask uit = new UnloadIndexTask(m.getSocketAddress().getPort(), indexName);
					if (!self.equals(m)) {
						Future<Void> dt = executorService.submitToMember(uit, m);
						dt.get();
					}
					else {
						uit.call();
					}
				}
				catch (Exception e) {
					log.error(e.getClass().getSimpleName() + ": ", e);
				}
				
			}
			
			log.info("Deleting index <" + indexName + ">");
			i.deleteIndex();
			indexMap.remove(indexName);
			
			return IndexDeleteResponse.newBuilder().build();
		}
		finally {
			globalLock.writeLock().unlock();
		}
	}
	
	public void unloadIndex(String indexName) throws IndexDoesNotExist, CorruptIndexException, IOException {
		globalLock.writeLock().lock();
		try {
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			
			i.unload();
			indexMap.remove(indexName);
		}
		finally {
			globalLock.writeLock().unlock();
		}
	}
	
	public void shutdown() {
		
		log.info("Starting index manager shutdown");
		
		//TODO configure or force a lock acquire
		int waitSeconds = 10;
		
		log.info("Waiting for lock");
		boolean locked = false;
		try {
			locked = globalLock.writeLock().tryLock(waitSeconds, TimeUnit.SECONDS);
		}
		catch (InterruptedException e1) {
			
		}
		
		if (!locked) {
			log.info("Failed to get manager lock within <" + waitSeconds + "> seconds");
		}
		
		try {
			log.info("Stopping manager pool");
			pool.shutdownNow();
			
			log.info("Shutting down indexes");
			for (String indexName : indexMap.keySet()) {
				LumongoIndex i = indexMap.get(indexName);
				try {
					log.info("Unloading <" + indexName + ">");
					i.unload();
				}
				catch (Exception e) {
					log.error(e.getClass().getSimpleName() + ": ", e);
				}
			}
		}
		finally {
			if (locked) {
				globalLock.writeLock().unlock();
			}
		}
	}
	
	public void openConnections(Set<Member> members) throws Exception {
		
		globalLock.writeLock().lock();
		try {
			Member self = hazelcastManager.getSelf();
			for (Member m : members) {
				if (!self.equals(m)) {
					internalClient.addMember(m);
				}
			}
		}
		finally {
			globalLock.writeLock().unlock();
		}
		
	}
	
	public IndexSettingsResponse updateIndex(String indexName, IndexSettings request)
					throws IndexDoesNotExist, InvalidIndexConfig, MongoException, IOException {
		globalLock.readLock().lock();
		try {
			
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			
			i.updateIndexSettings(request);
			
			Set<Member> currentMembers = hazelcastManager.getMembers();
			IExecutorService executorService = hazelcastManager.getExecutorService();
			
			Member self = hazelcastManager.getSelf();
			
			for (Member m : currentMembers) {
				try {
					ReloadIndexSettingsTask rist = new ReloadIndexSettingsTask(m.getSocketAddress().getPort(), indexName);
					if (!self.equals(m)) {
						Future<Void> dt = executorService.submitToMember(rist, m);
						dt.get();
					}
					else {
						rist.call();
					}
				}
				catch (Exception e) {
					log.error(e.getClass().getSimpleName() + ": ", e);
				}
				
			}
			
			return IndexSettingsResponse.newBuilder().build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public void reloadIndexSettings(String indexName) throws Exception {
		globalLock.readLock().lock();
		try {
			
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			
			i.reloadIndexSettings();
			
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public DeleteResponse internalDeleteDocument(DeleteRequest deleteRequest) throws Exception {
		globalLock.readLock().lock();
		try {
			
			LumongoIndex i = indexMap.get(deleteRequest.getIndexName());
			if (i == null) {
				throw new IndexDoesNotExist(deleteRequest.getIndexName());
			}
			i.deleteDocument(deleteRequest);
			
			return DeleteResponse.newBuilder().build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public DeleteResponse deleteDocument(DeleteRequest deleteRequest) throws IndexDoesNotExist, CorruptIndexException, SegmentDoesNotExist, IOException,
					Exception {
		globalLock.readLock().lock();
		try {
			
			String indexName = deleteRequest.getIndexName();
			String uniqueId = deleteRequest.getUniqueId();
			
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			
			Member m = i.findMember(uniqueId);
			
			Member self = hazelcastManager.getSelf();
			
			if (!self.equals(m)) {
				return internalClient.executeDelete(m, deleteRequest);
			}
			else {
				return internalDeleteDocument(deleteRequest);
			}
			
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public StoreResponse storeInternal(StoreRequest storeRequest) throws Exception {
		globalLock.readLock().lock();
		try {
			String indexName = storeRequest.getIndexName();
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			
			i.storeInternal(storeRequest);
			
			return StoreResponse.newBuilder().build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public StoreResponse storeDocument(StoreRequest storeRequest) throws Exception {
		globalLock.readLock().lock();
		try {
			
			String uniqueId = storeRequest.getUniqueId();
			String indexName = storeRequest.getIndexName();
			
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			
			Member m = i.findMember(uniqueId);
			
			Member self = hazelcastManager.getSelf();
			
			if (!self.equals(m)) {
				return internalClient.executeStore(m, storeRequest);
			}
			else {
				return storeInternal(storeRequest);
			}
			
		}
		finally {
			globalLock.readLock().unlock();
		}
		
	}
	
	public FetchResponse fetch(FetchRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			
			String indexName = request.getIndexName();
			
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			
			FetchResponse.Builder frBuilder = FetchResponse.newBuilder();
			
			if (!FetchType.NONE.equals(request.getResultFetchType())) {
				ResultDocument resultDoc = i.getSourceDocument(request.getUniqueId(), request.getResultFetchType(), request.getDocumentFieldsList(),
								request.getDocumentMaskedFieldsList());
				if (null != resultDoc) {
					frBuilder.setResultDocument(resultDoc);
				}
			}
			if (!FetchType.NONE.equals(request.getAssociatedFetchType())) {
				if (request.hasFileName()) {
					AssociatedDocument ad = i.getAssociatedDocument(request.getUniqueId(), request.getFileName(), request.getAssociatedFetchType());
					if (ad != null) {
						frBuilder.addAssociatedDocument(ad);
					}
				}
				else {
					for (AssociatedDocument ad : i.getAssociatedDocuments(request.getUniqueId(), request.getAssociatedFetchType())) {
						frBuilder.addAssociatedDocument(ad);
					}
				}
			}
			return frBuilder.build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	private Map<String, QueryWithFilters> getQueryMap(QueryRequest queryRequest) throws Exception {
		globalLock.readLock().lock();
		try {
			
			List<String> indexNames = queryRequest.getIndexList();
			
			HashMap<String, QueryWithFilters> queryMap = new HashMap<String, QueryWithFilters>();
			for (String indexName : indexNames) {
				LumongoIndex i = indexMap.get(indexName);
				if (i == null) {
					throw new IndexDoesNotExist(indexName);
				}
				
				int minimumShouldMatch = queryRequest.getMinimumNumberShouldMatch();
				
				Operator operator = null;
				if (queryRequest.getDefaultOperator().equals(QueryRequest.Operator.OR)) {
					operator = Operator.OR;
				}
				else if (queryRequest.getDefaultOperator().equals(QueryRequest.Operator.AND)) {
					operator = Operator.AND;
				}
				else {
					//this should never happen
					log.error("Unknown operator type: <" + operator + ">");
				}
				
				Query query = i.getQuery(queryRequest.getQuery(), queryRequest.getQueryFieldList(), minimumShouldMatch, operator);
				
				if (queryRequest.hasFacetRequest()) {
					FacetRequest facetRequest = queryRequest.getFacetRequest();
					
					List<LMFacet> drillDownList = facetRequest.getDrillDownList();
					if (!drillDownList.isEmpty()) {
						FacetsConfig facetsConfig = i.getFacetsConfig();
						DrillDownQuery ddQuery = new DrillDownQuery(facetsConfig, query);
						
						for (LMFacet or : drillDownList) {
							ddQuery.add(or.getLabel(), or.getPathList().toArray(new String[0]));
						}
						
						query = ddQuery;
					}
				}
				
				QueryWithFilters queryWithFilters = new QueryWithFilters(query);
				
				for (String filter : queryRequest.getFilterQueryList()) {
					queryWithFilters.addFilterQuery(i.getQuery(filter, Collections.<String>emptyList(), 0, operator));
				}
				
				queryMap.put(indexName, queryWithFilters);
			}
			
			return queryMap;
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public QueryResponse query(final QueryRequest request) throws Exception {
		globalLock.readLock().lock();
		long start = System.currentTimeMillis();
		try {
			//log.info("Running query: <" + request.getQuery() + "> on indexes <" + request.getIndexList() + ">");
			
			log.info("Running query: <" + request + "> on indexes <" + request.getIndexList() + ">");
			
			final Map<String, QueryWithFilters> queryMap = getQueryMap(request);
			
			final Map<String, LumongoIndex> indexSegmentMap = new HashMap<>();
			for (String indexName : request.getIndexList()) {
				LumongoIndex i = indexMap.get(indexName);
				if (i == null) {
					throw new IndexDoesNotExist(indexName);
				}
				indexSegmentMap.put(indexName, i);
			}
			
			SocketRequestFederator<QueryRequest, InternalQueryResponse> queryFederator = new SocketRequestFederator<QueryRequest, InternalQueryResponse>(
							hazelcastManager, pool) {
				
				@Override
				public InternalQueryResponse processExternal(Member m, QueryRequest request) throws Exception {
					return internalClient.executeQuery(m, request);
				}
				
				@Override
				public InternalQueryResponse processInternal(QueryRequest request) throws Exception {
					return internalQuery(queryMap, request);
				}
			};
			
			List<InternalQueryResponse> results = queryFederator.send(request);
			
			QueryCombiner queryCombiner = new QueryCombiner(indexSegmentMap, request, results);
			
			queryCombiner.validate();
			
			QueryResponse qr = queryCombiner.getQueryResponse();
			
			if (!queryCombiner.isShort()) {
				return qr;
			}
			else {
				if (!request.getFetchFull()) {
					return query(request.toBuilder().setFetchFull(true).build());
				}
				
				throw new Exception("Full fetch request is short");
			}
			
		}
		finally {
			long end = System.currentTimeMillis();
			log.info("Finished query: <" + request.getQuery() + "> on indexes <" + request.getIndexList() + "> in " + (end - start) + "ms");
			
			globalLock.readLock().unlock();
		}
	}
	
	public InternalQueryResponse internalQuery(QueryRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			Map<String, QueryWithFilters> queryMap = getQueryMap(request);
			return internalQuery(queryMap, request);
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	private InternalQueryResponse internalQuery(Map<String, QueryWithFilters> queryMap, QueryRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			
			InternalQueryResponse.Builder internalQueryResponseBuilder = InternalQueryResponse.newBuilder();
			for (String indexName : queryMap.keySet()) {
				
				LumongoIndex i = indexMap.get(indexName);
				if (i == null) {
					throw new IndexDoesNotExist(indexName);
				}
				QueryWithFilters queryWithFilters = queryMap.get(indexName);
				
				IndexSegmentResponse isr = i.queryInternal(queryWithFilters, request);
				internalQueryResponseBuilder.addIndexSegmentResponse(isr);
			}
			
			return internalQueryResponseBuilder.build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public GetIndexesResponse getIndexes(GetIndexesRequest request) {
		globalLock.readLock().lock();
		try {
			GetIndexesResponse.Builder girB = GetIndexesResponse.newBuilder();
			girB.addAllIndexName(indexMap.keySet());
			return girB.build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public GetNumberOfDocsResponse getNumberOfDocsInternal(GetNumberOfDocsRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			String indexName = request.getIndexName();
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			return i.getNumberOfDocs(request.getRealTime());
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public GetNumberOfDocsResponse getNumberOfDocs(GetNumberOfDocsRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			String indexName = request.getIndexName();
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			int numberOfSegments = i.getNumberOfSegments();
			
			SocketRequestFederator<GetNumberOfDocsRequest, GetNumberOfDocsResponse> federator = new SocketRequestFederator<GetNumberOfDocsRequest, GetNumberOfDocsResponse>(
							hazelcastManager, pool) {
				
				@Override
				public GetNumberOfDocsResponse processExternal(Member m, GetNumberOfDocsRequest request) throws Exception {
					return internalClient.getNumberOfDocs(m, request);
				}
				
				@Override
				public GetNumberOfDocsResponse processInternal(GetNumberOfDocsRequest request) throws Exception {
					return getNumberOfDocsInternal(request);
				}
				
			};
			
			GetNumberOfDocsResponse.Builder responseBuilder = GetNumberOfDocsResponse.newBuilder();
			responseBuilder.setNumberOfDocs(0);
			List<GetNumberOfDocsResponse> responses = federator.send(request);
			
			List<SegmentCountResponse> segmentCountResponses = new ArrayList<SegmentCountResponse>();
			
			for (GetNumberOfDocsResponse r : responses) {
				responseBuilder.setNumberOfDocs(responseBuilder.getNumberOfDocs() + r.getNumberOfDocs());
				segmentCountResponses.addAll(r.getSegmentCountResponseList());
			}
			
			Collections.sort(segmentCountResponses, new Comparator<SegmentCountResponse>() {
				
				@Override
				public int compare(SegmentCountResponse o1, SegmentCountResponse o2) {
					return Integer.compare(o1.getSegmentNumber(), o2.getSegmentNumber());
				}
				
			});
			
			responseBuilder.addAllSegmentCountResponse(segmentCountResponses);
			
			GetNumberOfDocsResponse response = responseBuilder.build();
			
			HashSet<Integer> segments = new HashSet<Integer>();
			
			for (SegmentCountResponse r : response.getSegmentCountResponseList()) {
				segments.add(r.getSegmentNumber());
			}
			
			if (segments.size() != numberOfSegments) {
				throw new Exception("Expected <" + numberOfSegments + "> segments, found <" + segments.size() + "> segments");
			}
			
			for (int segmentNumber = 0; segmentNumber < numberOfSegments; segmentNumber++) {
				if (!segments.contains(segmentNumber)) {
					throw new Exception("Missing results for segment <" + segmentNumber + ">");
				}
			}
			
			return response;
			
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public ClearResponse clearIndex(ClearRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			SocketRequestFederator<ClearRequest, ClearResponse> federator = new SocketRequestFederator<ClearRequest, ClearResponse>(hazelcastManager, pool) {
				
				@Override
				public ClearResponse processExternal(Member m, ClearRequest request) throws Exception {
					return internalClient.clear(m, request);
				}
				
				@Override
				public ClearResponse processInternal(ClearRequest request) throws Exception {
					return clearInternal(request);
				}
				
			};
			
			// nothing in responses currently
			@SuppressWarnings("unused")
			List<ClearResponse> responses = federator.send(request);
			
			return ClearResponse.newBuilder().build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public ClearResponse clearInternal(ClearRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			String indexName = request.getIndexName();
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			i.clear();
			return ClearResponse.newBuilder().build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public OptimizeResponse optimize(OptimizeRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			SocketRequestFederator<OptimizeRequest, OptimizeResponse> federator = new SocketRequestFederator<OptimizeRequest, OptimizeResponse>(
							hazelcastManager, pool) {
				
				@Override
				public OptimizeResponse processExternal(Member m, OptimizeRequest request) throws Exception {
					return internalClient.optimize(m, request);
				}
				
				@Override
				public OptimizeResponse processInternal(OptimizeRequest request) throws Exception {
					return optimizeInternal(request);
				}
				
			};
			
			// nothing in responses currently
			@SuppressWarnings("unused")
			List<OptimizeResponse> responses = federator.send(request);
			
			return OptimizeResponse.newBuilder().build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public OptimizeResponse optimizeInternal(OptimizeRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			String indexName = request.getIndexName();
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			i.optimize();
			return OptimizeResponse.newBuilder().build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public GetFieldNamesResponse getFieldNames(GetFieldNamesRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			SocketRequestFederator<GetFieldNamesRequest, GetFieldNamesResponse> federator = new SocketRequestFederator<GetFieldNamesRequest, GetFieldNamesResponse>(
							hazelcastManager, pool) {
				
				@Override
				public GetFieldNamesResponse processExternal(Member m, GetFieldNamesRequest request) throws Exception {
					return internalClient.getFieldNames(m, request);
				}
				
				@Override
				public GetFieldNamesResponse processInternal(GetFieldNamesRequest request) throws Exception {
					return getFieldNamesInternal(request);
				}
				
			};
			
			Set<String> fieldNames = new HashSet<String>();
			List<GetFieldNamesResponse> responses = federator.send(request);
			for (GetFieldNamesResponse response : responses) {
				fieldNames.addAll(response.getFieldNameList());
			}
			
			GetFieldNamesResponse.Builder responseBuilder = GetFieldNamesResponse.newBuilder();
			responseBuilder.addAllFieldName(fieldNames);
			return responseBuilder.build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public GetFieldNamesResponse getFieldNamesInternal(GetFieldNamesRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			String indexName = request.getIndexName();
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			return i.getFieldNames();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public GetTermsResponse getTerms(GetTermsRequest request) throws Exception {
		
		globalLock.readLock().lock();
		try {
			
			SocketRequestFederator<GetTermsRequest, GetTermsResponse> federator = new SocketRequestFederator<GetTermsRequest, GetTermsResponse>(
							hazelcastManager, pool) {
				
				@Override
				public GetTermsResponse processExternal(Member m, GetTermsRequest request) throws Exception {
					return internalClient.getTerms(m, request);
				}
				
				@Override
				public GetTermsResponse processInternal(GetTermsRequest request) throws Exception {
					return getTermsInternal(request);
				}
				
			};
			
			List<GetTermsResponse> responses = federator.send(request);
			
			//not threaded but atomic long is convenient
			//TODO: maybe change to something else for speed
			TreeMap<String, AtomicLong> terms = new TreeMap<String, AtomicLong>();
			for (GetTermsResponse gtr : responses) {
				for (Term term : gtr.getTermList()) {
					if (!terms.containsKey(term.getValue())) {
						terms.put(term.getValue(), new AtomicLong());
					}
					terms.get(term.getValue()).addAndGet(term.getDocFreq());
				}
			}
			
			GetTermsResponse.Builder responseBuilder = GetTermsResponse.newBuilder();
			
			int amountToReturn = Math.min(request.getAmount(), terms.size());
			
			String value = null;
			Long frequency = null;
			
			for (int i = 0; (i < amountToReturn) && !terms.isEmpty(); ) {
				value = terms.firstKey();
				AtomicLong docFreq = terms.remove(value);
				frequency = docFreq.get();
				if (frequency >= request.getMinDocFreq()) {
					i++;
					responseBuilder.addTerm(Lumongo.Term.newBuilder().setValue(value).setDocFreq(frequency));
				}
			}
			if ((value != null) && (frequency != null)) {
				responseBuilder.setLastTerm(Lumongo.Term.newBuilder().setValue(value).setDocFreq(frequency).build());
			}
			
			return responseBuilder.build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public GetTermsResponse getTermsInternal(GetTermsRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			String indexName = request.getIndexName();
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			return i.getTerms(request);
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	//rest
	public void storeAssociatedDocument(String indexName, String uniqueId, String fileName, InputStream is, boolean compress,
					HashMap<String, String> metadataMap) throws Exception {
		globalLock.readLock().lock();
		try {
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			
			i.storeAssociatedDocument(uniqueId, fileName, is, compress, hazelcastManager.getClusterTime(), metadataMap);
			
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	//rest
	public InputStream getAssociatedDocumentStream(String indexName, String uniqueId, String fileName) throws IOException {
		globalLock.readLock().lock();
		try {
			LumongoIndex i = indexMap.get(indexName);
			if (i == null) {
				throw new IndexDoesNotExist(indexName);
			}
			
			return i.getAssociatedDocumentStream(uniqueId, fileName);
			
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
	public GetMembersResponse getMembers(GetMembersRequest request) throws Exception {
		globalLock.readLock().lock();
		try {
			Set<Member> members = hazelcastManager.getMembers();
			GetMembersResponse.Builder responseBuilder = GetMembersResponse.newBuilder();
			
			Nodes nodes = ClusterHelper.getNodes(mongoConfig);
			
			HashMap<Member, LMMember> memberMap = new HashMap<Member, LMMember>();
			
			for (Member m : members) {
				LocalNodeConfig localNodeConfig = nodes.find(m);
				
				InetAddress inetAddress = m.getSocketAddress().getAddress();
				
				String fullHostName = inetAddress.getCanonicalHostName();
				
				LMMember.Builder lmMemberBuilder = LMMember.newBuilder();
				lmMemberBuilder.setServerAddress(fullHostName);
				lmMemberBuilder.setExternalPort(localNodeConfig.getExternalServicePort());
				lmMemberBuilder.setInternalPort(localNodeConfig.getInternalServicePort());
				lmMemberBuilder.setHazelcastPort(localNodeConfig.getHazelcastPort());
				lmMemberBuilder.setRestPort(localNodeConfig.getRestPort());
				LMMember lmMember = lmMemberBuilder.build();
				responseBuilder.addMember(lmMember);
				memberMap.put(m, lmMember);
			}
			
			for (String indexName : indexMap.keySet()) {
				LumongoIndex i = indexMap.get(indexName);
				
				IndexMapping.Builder indexMappingBuilder = IndexMapping.newBuilder();
				indexMappingBuilder.setIndexName(indexName);
				indexMappingBuilder.setNumberOfSegments(i.getNumberOfSegments());
				
				Map<Integer, Member> segmentToMemberMap = i.getSegmentToMemberMap();
				for (Integer segmentNumber : segmentToMemberMap.keySet()) {
					Member m = segmentToMemberMap.get(segmentNumber);
					LMMember lmMember = memberMap.get(m);
					SegmentMapping segmentMapping = SegmentMapping.newBuilder().setSegmentNumber(segmentNumber).setMember(lmMember).build();
					indexMappingBuilder.addSegmentMapping(segmentMapping);
				}
				responseBuilder.addIndexMapping(indexMappingBuilder);
			}
			
			return responseBuilder.build();
		}
		finally {
			globalLock.readLock().unlock();
		}
	}
	
}
