package io.stalk.mod.watcher;


import io.stalk.common.api.NODE_WATCHER;
import io.stalk.common.api.PUBLISH_MANAGER;
import io.stalk.common.api.SESSION_MANAGER;
import io.stalk.common.server.zk.ZooKeeperClient;
import io.stalk.common.server.zk.ZooKeeperClient.Credentials;
import io.stalk.common.server.zk.ZooKeeperConnectionException;
import io.stalk.common.server.zk.ZooKeeperUtils;

import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;

public class NodeWatcher extends BusModBase implements Handler<Message<JsonObject>> {


	private Logger log;

	private ZooKeeperClient zkClient;
	private String 			address;
	private String 			rootPath;
	private boolean 		isReady;
	private boolean 		isWatching;
	private boolean			isCreated;

	public void start() {

		super.start();

		log 							= container.getLogger();
		address 						= getOptionalStringConfig(	NODE_WATCHER.ADDRESS	, NODE_WATCHER.DEFAULT.ADDRESS);
		rootPath						= getOptionalStringConfig(	NODE_WATCHER.ROOT_PATH	, NODE_WATCHER.DEFAULT.ROOT_PATH);

		JsonArray 	zookeeperServers 	= getOptionalArrayConfig(NODE_WATCHER.ZOOKEEPER_SERVERS, null);
		int 		zookeeperTomeout 	= getOptionalIntConfig(		NODE_WATCHER.TIMEOUT	, NODE_WATCHER.DEFAULT.TIMEOUT);

		/* connect zookeeper servers */
		try {

			zkClient = new ZooKeeperClient(zookeeperTomeout, Credentials.NONE, zookeeperServers);
			isReady = true;
		} catch (ZooKeeperConnectionException e) {
			ERROR("zookeeper is not existed [%s]", zookeeperServers.encode());
			isReady = false;
			e.printStackTrace();
		}

		eb.registerHandler(address, this);
	}

	@Override
	public void stop() {
		try {
			super.stop();
			zkClient.close();
		} catch (Exception e) {
		}
	}

	@Override
	public void handle(Message<JsonObject> message) {
		String action = message.body.getString("action");

		if(isReady){

			if(NODE_WATCHER.ACTION.CREATE_NODE.equals(action)){

				if(isCreated){

					sendOK(message);

				}else{

					try {
						createNode(
								message.body.getString("channel"),
								message.body.getObject("data")
								);

						isCreated = true;

						// OK 
						sendOK(message);

					} catch (ZooKeeperConnectionException 
							| InterruptedException
							| KeeperException e) {
						e.printStackTrace();

						// ERROR
						sendError(message, e.getMessage());

					}
				}

			}else if(NODE_WATCHER.ACTION.START_WATCHING.equals(action)){

				if(!isWatching){
					watching();
					isWatching = false;
				}
				sendOK(message);

			}else if(NODE_WATCHER.ACTION.INFO_CHANNEL.equals(action)){ // for monitoring.

				String channel = message.body.getString("channel");
				if(StringUtils.isNotBlank(channel)){
					try {
						zkClient.get().delete(rootPath+"/"+channel, -1);
						sendOK(message);
					} catch (InterruptedException | KeeperException
							| ZooKeeperConnectionException e) {
						sendError(message, e.getMessage());
					}
					
				}else{
					sendError(message, "The name of channel is empty");
				}

			}else if(NODE_WATCHER.ACTION.INFO_CHANNEL.equals(action)){ // for monitoring.
				List<String> channels = null;
				try {
					channels = zkClient.get().getChildren(rootPath, false);
				} catch (KeeperException | InterruptedException
						| ZooKeeperConnectionException e) {
					// ERROR
					sendError(message, e.getMessage());
				}

				if(channels != null && channels.size() > 0){
					JsonArray chs = new JsonArray();
					for(String channel : channels){
						chs.addString(channel);
					}
					sendOK(message, new JsonObject().putArray("channels", chs));
				}else{
					sendError(message, "channel is not exists.");
				}
			}

		}else{
			sendError(message, "zookeeper is not ready");
		}

	}

	private void watching(){
		/* 2. watching the children nodes */
		try {

			ZooKeeperUtils.ensurePath(zkClient, ZooDefs.Ids.OPEN_ACL_UNSAFE, rootPath);

			List<String> channels = zkClient.get().getChildren(rootPath, new Watcher() {
				public void process(WatchedEvent event) {
					try {
						List<String> channels = zkClient.get().getChildren(rootPath, this);
						DEBUG("** WATCHED ** %s %s", rootPath, channels);
						refreshNode(channels);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			});

			if(channels.size() > 0){

				refreshNode(channels);

			}else{

				ERROR(" message server is not existed from [%s]..", rootPath);

			}

		} catch (KeeperException | InterruptedException
				| ZooKeeperConnectionException e) {
			e.printStackTrace();
			ERROR("%s", e.getMessage());
		}
	}

	private void createNode(final String channel, final JsonObject data) throws ZooKeeperConnectionException, InterruptedException, KeeperException{

		ZooKeeperUtils.ensurePath(zkClient, ZooDefs.Ids.OPEN_ACL_UNSAFE, rootPath);

		if (zkClient.get().exists(rootPath+"/"+channel, false) == null) {

			DEBUG("create node [%s]", data.encode());

			zkClient.get().create(
					rootPath+"/"+channel, 
					data.encode().getBytes(), 
					ZooDefs.Ids.OPEN_ACL_UNSAFE, 
					CreateMode.EPHEMERAL);
		}

	}

	private void refreshNode(List<String> channels) throws KeeperException, InterruptedException, ZooKeeperConnectionException{

		if(channels.size() > 0){

			JsonArray servers = new JsonArray();
			JsonArray redises = new JsonArray();

			for(String channel : channels){

				JsonObject nodes = new JsonObject(
						new String(zkClient.get().getData(rootPath + "/"+channel, false, null))
						);

				servers.addObject(nodes.getObject("server").putString("channel", channel));
				redises.addObject(nodes.getObject("redis").putString("channel", channel));

			}

			eb.publish(SESSION_MANAGER.DEFAULT.ADDRESS
					, new JsonObject()
			.putString("action", SESSION_MANAGER.ACTION.REFRESH_NODES)
			.putArray("channels", servers) 
					);

			eb.publish(PUBLISH_MANAGER.DEFAULT.ADDRESS
					, new JsonObject()
			.putString("action", PUBLISH_MANAGER.ACTION.REFRESH_NODES)
			.putArray("channels", redises) 
					);

		}else{

			ERROR("message server is not existed from [%s]", rootPath);

			eb.publish(SESSION_MANAGER.DEFAULT.ADDRESS, 
					new JsonObject().putString("action", SESSION_MANAGER.ACTION.DESTORY_NODES));

			eb.publish(PUBLISH_MANAGER.DEFAULT.ADDRESS, 
					new JsonObject().putString("action", PUBLISH_MANAGER.ACTION.DESTORY_NODES));

		}

	}

	protected void DEBUG(String message, Object... args ){
		if(log != null) log.debug("[MOD::NODE] "+String.format(message, args));
	}

	protected void ERROR(String message, Object... args ){
		if(log != null) log.error("[MOD::NODE] "+String.format(message, args));
	}

}
