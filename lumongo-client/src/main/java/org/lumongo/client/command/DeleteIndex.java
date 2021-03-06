package org.lumongo.client.command;

import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;
import org.lumongo.client.command.base.SimpleCommand;
import org.lumongo.client.pool.LumongoConnection;
import org.lumongo.client.result.DeleteIndexResult;
import org.lumongo.cluster.message.Lumongo.ExternalService;
import org.lumongo.cluster.message.Lumongo.IndexDeleteRequest;
import org.lumongo.cluster.message.Lumongo.IndexDeleteResponse;

/**
 * Deletes an index.  If index does not exist throwns an exception
 * @author mdavis
 *
 */
public class DeleteIndex extends SimpleCommand<IndexDeleteRequest, DeleteIndexResult> {

	private String indexName;

	public DeleteIndex(String indexName) {
		this.indexName = indexName;
	}

	@Override
	public IndexDeleteRequest getRequest() {
		return IndexDeleteRequest.newBuilder().setIndexName(indexName).build();
	}

	@Override
	public DeleteIndexResult execute(LumongoConnection lumongoConnection) throws ServiceException {
		ExternalService.BlockingInterface service = lumongoConnection.getService();

		RpcController controller = lumongoConnection.getController();

		IndexDeleteResponse indexDeleteResponse = service.deleteIndex(controller, getRequest());

		return new DeleteIndexResult(indexDeleteResponse);
	}

}
