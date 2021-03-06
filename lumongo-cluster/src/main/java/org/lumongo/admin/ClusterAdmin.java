package org.lumongo.admin;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.lumongo.LumongoConstants;
import org.lumongo.admin.help.LumongoHelpFormatter;
import org.lumongo.admin.help.RequiredOptionException;
import org.lumongo.server.config.ClusterConfig;
import org.lumongo.server.config.LocalNodeConfig;
import org.lumongo.server.config.MongoConfig;
import org.lumongo.util.ClusterHelper;
import org.lumongo.util.LogUtil;
import org.lumongo.util.ServerNameHelper;

import java.io.File;
import java.util.Arrays;

public class ClusterAdmin {
	private static final String MONGO_CONFIG = "mongoConfig";
	private static final String NODE_CONFIG = "nodeConfig";
	private static final String CLUSTER_CONFIG = "clusterConfig";
	private static final String ADDRESS = "address";
	private static final String HAZELCAST_PORT = "hazelcastPort";
	private static final String COMMAND = "command";

	public static enum Command {
		createCluster,
		updateCluster,
		removeCluster,
		showCluster,
		registerNode,
		removeNode,
		listNodes,
	}

	public static void main(String[] args) throws Exception {
		LogUtil.loadLogConfig();

		OptionParser parser = new OptionParser();
		OptionSpec<File> mongoConfigArg = parser.accepts(MONGO_CONFIG).withRequiredArg().ofType(File.class).describedAs("Mongo properties file");
		OptionSpec<File> nodeConfigArg = parser.accepts(NODE_CONFIG).withRequiredArg().ofType(File.class).describedAs("Node properties file");
		OptionSpec<File> clusterConfigArg = parser.accepts(CLUSTER_CONFIG).withRequiredArg().ofType(File.class).describedAs("Cluster properties file");
		OptionSpec<String> serverAddressArg = parser.accepts(ADDRESS).withRequiredArg().describedAs("Specific server address manually for node commands");
		OptionSpec<Integer> hazelcastPortArg = parser.accepts(HAZELCAST_PORT).withRequiredArg().ofType(Integer.class)
						.describedAs("Hazelcast port if multiple instances on one server for node commands");
		OptionSpec<Command> commandArg = parser.accepts(COMMAND).withRequiredArg().ofType(Command.class).required()
						.describedAs("Command to run " + Arrays.toString(Command.values()));

		try {
			OptionSet options = parser.parse(args);

			File mongoConfigFile = options.valueOf(mongoConfigArg);
			File nodeConfigFile = options.valueOf(nodeConfigArg);
			File clusterConfigFile = options.valueOf(clusterConfigArg);
			String serverAddress = options.valueOf(serverAddressArg);
			Integer hazelcastPort = options.valueOf(hazelcastPortArg);

			Command command = options.valueOf(commandArg);

			if (mongoConfigFile == null) {
				throw new RequiredOptionException(MONGO_CONFIG, command.toString());
			}

			MongoConfig mongoConfig = MongoConfig.getNodeConfig(mongoConfigFile);

			LocalNodeConfig localNodeConfig = null;
			if (nodeConfigFile != null) {
				localNodeConfig = LocalNodeConfig.getNodeConfig(nodeConfigFile);
			}

			ClusterConfig clusterConfig = null;
			if (clusterConfigFile != null) {
				clusterConfig = ClusterConfig.getClusterConfig(clusterConfigFile);
			}

			if (Command.createCluster.equals(command)) {
				System.out.println("Creating cluster in database <" + mongoConfig.getDatabaseName() + "> on mongo server <" + mongoConfig.getMongoHost() + ">");
				if (clusterConfig == null) {
					throw new RequiredOptionException(CLUSTER_CONFIG, command.toString());
				}
				ClusterHelper.saveClusterConfig(mongoConfig, clusterConfig);
				System.out.println("Created cluster");
			}
			else if (Command.updateCluster.equals(command)) {
				System.out.println("Updating cluster in database <" + mongoConfig.getDatabaseName() + "> on mongo server <" + mongoConfig.getMongoHost() + ">");
				if (clusterConfig == null) {
					throw new RequiredOptionException(CLUSTER_CONFIG, command.toString());
				}
				ClusterHelper.saveClusterConfig(mongoConfig, clusterConfig);
			}
			else if (Command.removeCluster.equals(command)) {
				System.out.println("Removing cluster from database <" + mongoConfig.getDatabaseName() + "> on mongo server <" + mongoConfig.getMongoHost()
								+ ">");
				ClusterHelper.removeClusterConfig(mongoConfig);
			}
			else if (Command.showCluster.equals(command)) {
				try {
					System.out.println(ClusterHelper.getClusterConfig(mongoConfig));
				}
				catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}
			else if (Command.registerNode.equals(command)) {
				if (localNodeConfig == null) {
					throw new RequiredOptionException(NODE_CONFIG, command.toString());
				}
				if (serverAddress == null) {
					serverAddress = ServerNameHelper.getLocalServer();
				}

				System.out.println("Registering node with server address <" + serverAddress + ">");

				ClusterHelper.registerNode(mongoConfig, localNodeConfig, serverAddress);
			}
			else if (Command.removeNode.equals(command)) {
				if (serverAddress == null) {
					serverAddress = ServerNameHelper.getLocalServer();
				}

				if (hazelcastPort == null) {
					hazelcastPort = LumongoConstants.DEFAULT_HAZELCAST_PORT;
				}

				System.out.println("Removing node with server address <" + serverAddress + "> and hazelcastPort <" + hazelcastPort + ">");

				ClusterHelper.removeNode(mongoConfig, serverAddress, hazelcastPort);
			}
			else if (Command.listNodes.equals(command)) {
				System.out.println(ClusterHelper.getNodes(mongoConfig));
			}
			else {
				System.err.println(command + " not supported");
			}

		}
		catch (OptionException e) {
			System.err.println("ERROR: " + e.getMessage());
			parser.formatHelpWith(new LumongoHelpFormatter());
			parser.printHelpOn(System.err);
		}

	}
}
