package org.lumongo.server.rest;

import com.cedarsoftware.util.io.JsonWriter;
import com.googlecode.protobuf.format.JsonFormat;
import org.lumongo.LumongoConstants;
import org.lumongo.cluster.message.Lumongo.CountRequest;
import org.lumongo.cluster.message.Lumongo.FacetRequest;
import org.lumongo.cluster.message.Lumongo.LMFacet;
import org.lumongo.cluster.message.Lumongo.QueryRequest;
import org.lumongo.cluster.message.Lumongo.QueryResponse;
import org.lumongo.server.indexing.LumongoIndexManager;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import java.util.List;

@Path(LumongoConstants.QUERY_URL)
public class QueryResource {

	private LumongoIndexManager indexManager;

	public QueryResource(LumongoIndexManager indexManager) {
		this.indexManager = indexManager;
	}

	@GET
	@Produces({ MediaType.APPLICATION_JSON })
	public String get(
					@QueryParam(LumongoConstants.INDEX) List<String> indexName,
					@QueryParam(LumongoConstants.QUERY) String query,
					@QueryParam(LumongoConstants.QUERY_FIELD) List<String> queryFields,
					@QueryParam(LumongoConstants.FILTER_QUERY) List<String> filterQueries,
					@QueryParam(LumongoConstants.AMOUNT) int amount,
					@QueryParam(LumongoConstants.FACET) List<String> facet,
					@QueryParam(LumongoConstants.PRETTY) boolean pretty) {

		QueryRequest.Builder qrBuilder = QueryRequest.newBuilder().addAllIndex(
						indexName);
		qrBuilder.setQuery(query);
		qrBuilder.setAmount(amount);
		
		if (queryFields != null) {
			for (String queryField : queryFields) {
				qrBuilder.addQueryField(queryField);
			}
		}
		
		if (filterQueries != null) {
			for (String filterQuery : filterQueries) {
				qrBuilder.addFilterQuery(filterQuery);
			}
		}
		
		FacetRequest.Builder frBuilder = FacetRequest.newBuilder();
		for (String f : facet) {
			CountRequest.Builder countBuilder = CountRequest.newBuilder();
			// TODO handle path
			LMFacet lmFacet = LMFacet.newBuilder().setLabel(f).build();

			frBuilder.addCountRequest(countBuilder.setFacetField(lmFacet));
		}
		qrBuilder.setFacetRequest(frBuilder);

		try {
			QueryResponse qr = indexManager.query(qrBuilder.build());

			String response = JsonFormat.printToString(qr);
			if (pretty) {
				response = JsonWriter.formatJson(response);
			}
			return response;
		}
		catch (Exception e) {
			throw new WebApplicationException(LumongoConstants.UNIQUE_ID
							+ " and " + LumongoConstants.FILE_NAME + " are required",
							LumongoConstants.INTERNAL_ERROR);
		}

	}
}
