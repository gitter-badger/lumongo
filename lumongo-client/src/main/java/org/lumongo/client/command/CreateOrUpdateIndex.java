package org.lumongo.client.command;

import com.google.protobuf.ServiceException;
import org.lumongo.client.command.base.Command;
import org.lumongo.client.config.IndexConfig;
import org.lumongo.client.pool.LumongoConnection;
import org.lumongo.client.result.CreateIndexResult;
import org.lumongo.client.result.CreateOrUpdateIndexResult;
import org.lumongo.client.result.GetIndexesResult;
import org.lumongo.client.result.UpdateIndexResult;

/**
 * Creates a new index with all settings given or updates the IndexSettings on an existing index
 * @author mdavis
 *
 */
public class CreateOrUpdateIndex extends Command<CreateOrUpdateIndexResult> {
	private String indexName;
	private Integer numberOfSegments;
	private String uniqueIdField;
	private IndexConfig indexConfig;
	
	public CreateOrUpdateIndex(String indexName, Integer numberOfSegments, String uniqueIdField, IndexConfig indexConfig) {
		this.indexName = indexName;
		this.numberOfSegments = numberOfSegments;
		this.uniqueIdField = uniqueIdField;
		this.indexConfig = indexConfig;
	}

	public String getIndexName() {
		return indexName;
	}

	public Integer getNumberOfSegments() {
		return numberOfSegments;
	}

	public String getUniqueIdField() {
		return uniqueIdField;
	}

	public IndexConfig getIndexConfig() {
		return indexConfig;
	}

	public void setIndexConfig(IndexConfig indexConfig) {
		this.indexConfig = indexConfig;
	}

	public void setIndexName(String indexName) {
		this.indexName = indexName;
	}
	
	public void setNumberOfSegments(Integer numberOfSegments) {
		this.numberOfSegments = numberOfSegments;
	}
	
	public void setUniqueIdField(String uniqueIdField) {
		this.uniqueIdField = uniqueIdField;
	}
	
	@Override
	public CreateOrUpdateIndexResult execute(LumongoConnection lumongoConnection) throws ServiceException {
		CreateOrUpdateIndexResult result = new CreateOrUpdateIndexResult();
		
		GetIndexes gt = new GetIndexes();
		GetIndexesResult gtr = gt.execute(lumongoConnection);
		if (gtr.containsIndex(indexName)) {
			UpdateIndex ui = new UpdateIndex(indexName, indexConfig);
			UpdateIndexResult uir = ui.execute(lumongoConnection);
			result.setUpdateIndexResult(uir);
			return result;
		}
		
		CreateIndex ci = new CreateIndex(indexName, numberOfSegments, uniqueIdField, indexConfig);
		
		CreateIndexResult cir = ci.execute(lumongoConnection);
		result.setCreateIndexResult(cir);
		return result;
		
	}
	
}
