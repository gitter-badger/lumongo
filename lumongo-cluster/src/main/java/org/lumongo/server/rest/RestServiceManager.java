package org.lumongo.server.rest;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.lumongo.server.config.LocalNodeConfig;
import org.lumongo.server.indexing.IndexManager;

import com.sun.net.httpserver.HttpServer;

public class RestServiceManager {
	
	private LocalNodeConfig localNodeConfig;
	
	private final int restPort;
	
	private HttpServer httpServer;
	
	private IndexManager indexManger;
	
	public RestServiceManager(LocalNodeConfig localNodeConfig, IndexManager indexManager) {
		this.indexManger = indexManager;
		this.restPort = localNodeConfig.getRestPort();
		
	}
	
	public void start() throws IOException {
		InetSocketAddress address = new InetSocketAddress(restPort);
		httpServer = HttpServer.create(address, 0);
		httpServer.start();
		httpServer.createContext("/associatedDocs", new AssociatedHandler(indexManger));
	}
	
	public void shutdown() {
		httpServer.stop(10);
	}
}