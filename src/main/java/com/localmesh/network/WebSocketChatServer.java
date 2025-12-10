package com.localmesh.network;

import com.localmesh.config.ServerConfig;
import com.localmesh.model.Message;
import com.localmesh.model.User;
import com.localmesh.utils.JsonUtil;
import com.localmesh.storage.MessageStorage;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class WebSocketChatServer extends WebSocketServer {
	private final Map<WebSocket, User> clients = new ConcurrentHashMap<>();
	private final ServerConfig cfg;
	private final AtomicInteger connections = new AtomicInteger(0);
	private final MessageStorage storage = new MessageStorage();
	private static final Logger logger = LoggerFactory.getLogger(WebSocketChatServer.class);
	
	public WebSocketChatServer(ServerConfig cfg) {
		super(new InetSocketAddress(cfg.getPort()));
		this.cfg = cfg;
		try {
			new HttpWebInterface(8081, clients, storage);
		} catch (IOException e) {
			logger.info("Failed to start HTTP web interface: " + e getMessage());
		}
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake) {
		if (connections.incrementAndGet() > cfg.getMaxConnections()) {
			conn.close(1001, "Server full");
			connections.decrementAndGet();
			return;
		}
		// ПОЛЬЗОВАТЕЛЕЙ ПОКА ЧТО НЕТ
		User u = new User("anonymous");
		u.setStatus(User.Status.ONLINE);
		u.setLastSeen(System.currentTimeMillis());
		clients.put(conn, u);
		logger.info("New connection: " + conn.getRemoteSocketAddress() + " assignedId=" + u.getId());
		
		// кидаем клиенту id (информация)
		java.util.Map<String,Object> welcome = new java.util.HashMap<>();
		welcome.put("type", "welcome");
		welcome.put("userId", u.getId());
		welcome.put("name", u.getName());
		conn.send(JsonUtil.toJson(welcome));
		
		// отправим новому клиенту историю сообщений (как единый payload типа "history")
		java.util.Map<String,Object> histPayload = new java.util.HashMap<>();
		histPayload.put("type", "history");
		histPayload.put("messages", storage.getHistory());
		conn.send(JsonUtil.toJson(histPayload));
		
		broadcastUserList();
	}

	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote) {
		User u = clients.remove(conn);
		connections.decrementAndGet();
		if (u != null) {
			u.setStatus(User.Status.OFFLINE);
			u.setLastSeen(System.currentTimeMillis());
			logger.info("Connection closed: " + u.getId() + " reason=" + reason);
		} else {
			logger.error("Connection closed: unknown reason=" + reason);
		}
		broadcastUserList();
	}

	@Override
	public void onMessage(WebSocket conn, String message) {
		try {
			Map incoming = JsonUtil.fromJson(message, Map.class);
			String action = (String) incoming.get("action");
			
			if ("register".equals(action)) { // СЕЙЧАС ТОЛЬКО REGISTER
				String name = (String) incoming.getOrDefault("name", "anonymous");
				User u = clients.get(conn);
				if (u != null) {
					u.setName(name);
					u.setStatus(User.Status.ONLINE);
					u.setLastSeen(System.currentTimeMillis());
				}
				java.util.Map<String,Object> resp = new java.util.HashMap<>();
				resp.put("type", "registered");
				resp.put("userId", u != null ? u.getId() : null);
				resp.put("name", name);
				conn.send(JsonUtil.toJson(resp));
				broadcastUserList();
				return;
			}
			
			if ("message".equals(action)) {
				Message msg = JsonUtil.fromJson(message, Message.class);
				msg.setTimestamp(System.currentTimeMillis());
				if (msg.getType() == null || msg.getType() == Message.Type.TEXT || msg.getType() == Message.Type.SOS) {
					storage.addMessage(msg);
				}
				broadcast(JsonUtil.toJson(msg));
				return;
			}
			
			if ("sos".equals(action) || "SOS".equalsIgnoreCase((String) incoming.get("type"))) {
				User u = clients.get(conn); //определяем отправителя и его координаты
				if (u == null) {
					conn.send(JsonUtil.toJson(Collections.singletonMap("error", "unknown user")));
					return;
				}

				// пробуем извлечь координаты из payload (опционально клиент может не прислать их, тогда используются последние известные)
				Double lat = incoming.get("latitude") instanceof Number ? ((Number) incoming.get("latitude")).doubleValue() : null;
				Double lon = incoming.get("longitude") instanceof Number ? ((Number) incoming.get("longitude")).doubleValue() : null;

				if (lat != null && lon != null) {
					if (!isValidLatitude(lat) || !isValidLongitude(lon)) {
						conn.send(JsonUtil.toJson(Collections.singletonMap("error", "invalid coordinates")));
						return;
					}
					u.setLatitude(lat);
					u.setLongitude(lon);
					u.setLastUpdate(System.currentTimeMillis());
				}

				Message sosMsg = new Message();
				sosMsg.setFromUserId(u.getId());
				sosMsg.setFromUserName(u.getName());
				sosMsg.setTimestamp(System.currentTimeMillis());
				sosMsg.setType(Message.Type.SOS);
				sosMsg.setLatitude(u.getLatitude());
				sosMsg.setLongitude(u.getLongitude());
				sosMsg.setText((String) incoming.getOrDefault("text", "SOS"));

				logger.error("[SOS] user=" + u.getId() + " name=" + u.getName() + " lat=" + sosMsg.getLatitude() + " lon=" + sosMsg.getLongitude() + " time=" + sosMsg.getTimestamp());

				broadcast(JsonUtil.toJson(sosMsg));
				return;
			}
			
			if ("location".equals(action) || "location_update".equals(action)) {
				Double lat = incoming.get("latitude") instanceof Number ? ((Number) incoming.get("latitude")).doubleValue() : null;
				Double lon = incoming.get("longitude") instanceof Number ? ((Number) incoming.get("longitude")).doubleValue() : null;
				User u = clients.get(conn);
				if (u != null && lat != null && lon != null) {
					if (!isValidLatitude(lat) || !isValidLongitude(lon)) {
						conn.send(JsonUtil.toJson(Collections.singletonMap("error", "invalid coordinates")));
						return;
					}

					long now = System.currentTimeMillis();
					if (now - u.getLastUpdate() < LOCATION_THROTTLE_MS) {
						return;
					}

					u.setLatitude(lat);
					u.setLongitude(lon);
					u.setLastUpdate(now);

					Message locMsg = new Message();
					locMsg.setFromUserId(u.getId());
					locMsg.setFromUserName(u.getName());
					locMsg.setTimestamp(u.getLastUpdate());
					locMsg.setType(Message.Type.LOCATION_UPDATE);
					locMsg.setLatitude(lat);
					locMsg.setLongitude(lon);

					broadcast(JsonUtil.toJson(locMsg));

					broadcastUserList();
					return;
					} else {
					conn.send(JsonUtil.toJson(Collections.singletonMap("error", "invalid location payload")));
					return;
				}
			}
			conn.send(JsonUtil.toJson(Collections.singletonMap("error", "unknown action")));
		} catch (Exception e) {
			conn.send(JsonUtil.toJson(Collections.singletonMap("error", e.getMessage())));
		}
	}
	@Override
	public void onError(WebSocket conn, Exception ex) {
		logger.error("Server error: " + ex.getMessage());
	}

	@Override
	public void onStart() {
		logger.info("WebSocket server started on port " + cfg.getPort());
	}

	private void broadcastUserList() {
		Map<String, Object> users = new java.util.HashMap<>();
		users.put("type", "users");
		java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
		for (User u : clients.values()) {
			java.util.Map<String, Object> item = new java.util.HashMap<>();
			item.put("id", u.getId());
			item.put("name", u.getName());
			item.put("latitude", u.getLatitude());
			item.put("longitude", u.getLongitude());
			item.put("lastUpdate", u.getLastUpdate());
			item.put("status", u.getStatus().name());
			item.put("lastSeen", u.getLastSeen());
			list.add(item);
		}
		users.put("users", list);
		broadcast(JsonUtil.toJson(users));
	}
	
	private boolean isValidLatitude(double lat) {
		return lat >= -90.0 && lat <= 90.0;
	}


	private boolean isValidLongitude(double lon) {
		return lon >= -180.0 && lon <= 180.0;
	}
}