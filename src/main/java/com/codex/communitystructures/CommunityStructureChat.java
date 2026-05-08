package com.codex.communitystructures;

import com.google.gson.Gson;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;

public final class CommunityStructureChat {
	private static final double CHAT_RADIUS = 10.0D;
	private static final long ACTIVE_ROOM_TTL_MILLIS = 5 * 60 * 1000L;
	private static final long LIVE_RECONNECT_DELAY_MILLIS = 5000L;
	private static final int POLL_TICKS = 40;
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(4))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private static final Map<UUID, Long> LAST_SEEN_MESSAGE_ID = new ConcurrentHashMap<>();
	private static final Map<UUID, ActiveRoom> ACTIVE_ROOMS = new ConcurrentHashMap<>();
	private static final Map<UUID, LiveChatConnection> LIVE_CONNECTIONS = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> NEXT_LIVE_RECONNECT_AT = new ConcurrentHashMap<>();
	private static int tickCounter;

	private CommunityStructureChat() {
	}

	public static void register() {
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(CommunityStructureChat::onChatMessage);
		ServerTickEvents.END_SERVER_TICK.register(CommunityStructureChat::pollMessages);
	}

	private static boolean onChatMessage(SignedMessage message, ServerPlayerEntity sender, net.minecraft.network.message.MessageType.Parameters params) {
		String text = message.getSignedContent();
		if (text == null || text.isBlank()) {
			return true;
		}

		Optional<ChatRoute> route = routeFor(sender);
		if (route.isEmpty()) {
			return true;
		}

		ChatRoute chatRoute = route.get();
		sendChatLine(sender, sender.getGameProfile().getName(), text);
		postMessage(sender.getServer(), outgoing(sender, chatRoute, text));
		ACTIVE_ROOMS.put(sender.getUuid(), chatRoute.activeRoom());
		return false;
	}

	private static Optional<ChatRoute> routeFor(ServerPlayerEntity sender) {
		String senderId = sender.getUuidAsString();
		Optional<CommunityStructureInstanceState.Instance> nearby = CommunityStructureInstanceState.forWorld(sender.getServerWorld()).nearby(sender.getBlockPos(), CHAT_RADIUS);
		if (nearby.isPresent()) {
			CommunityStructureInstanceState.Instance instance = nearby.get();
			ActiveRoom room = ActiveRoom.fromVisitor(instance, senderId, sender.getGameProfile().getName());
			if (!instance.creatorId().equals(senderId)) {
				return Optional.of(new ChatRoute(room, instance.creatorId(), instance.creatorName()));
			}
			return Optional.of(new ChatRoute(room, senderId, sender.getGameProfile().getName()));
		}

		ActiveRoom active = ACTIVE_ROOMS.get(sender.getUuid());
		if (active == null || active.expired()) {
			ACTIVE_ROOMS.remove(sender.getUuid());
			return Optional.empty();
		}
		if (active.creatorId().equals(senderId)) {
			active.touch();
			return Optional.of(new ChatRoute(active, active.visitorId(), active.visitorName()));
		}
		return Optional.empty();
	}

	private static OutgoingChatMessage outgoing(ServerPlayerEntity sender, ChatRoute route, String text) {
		ActiveRoom room = route.activeRoom();
		return new OutgoingChatMessage(
			room.roomId(),
			room.structureId(),
			room.structureName(),
			room.creatorId(),
			room.creatorName(),
			room.visitorId(),
			room.visitorName(),
			sender.getUuidAsString(),
			sender.getGameProfile().getName(),
			route.toId(),
			route.toName(),
			text
		);
	}

	private static void postMessage(MinecraftServer server, OutgoingChatMessage message) {
		CommunityStructureConfig config = CommunityStructures.config();
		if (config == null) {
			return;
		}
		HttpRequest request = HttpRequest.newBuilder(apiUri(config, "/api/chat/messages"))
			.version(HttpClient.Version.HTTP_1_1)
			.timeout(Duration.ofSeconds(8))
			.header("content-type", "application/json; charset=utf-8")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(message), StandardCharsets.UTF_8))
			.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.whenComplete((response, throwable) -> {
				if (throwable != null) {
					CommunityStructures.LOGGER.warn("Could not post structure chat message", throwable);
					return;
				}
				if (response.statusCode() / 100 != 2) {
					CommunityStructures.LOGGER.warn("Structure chat endpoint returned HTTP {}: {}", response.statusCode(), response.body());
				}
			});
	}

	private static void pollMessages(MinecraftServer server) {
		tickCounter++;
		if (tickCounter % POLL_TICKS != 0) {
			return;
		}
		LIVE_CONNECTIONS.forEach((playerId, connection) -> {
			if (server.getPlayerManager().getPlayer(playerId) == null) {
				connection.close();
			}
		});
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (!ensureLiveConnection(server, player)) {
				pollPlayer(server, player.getUuid());
			}
		}
	}

	private static boolean ensureLiveConnection(MinecraftServer server, ServerPlayerEntity player) {
		CommunityStructureConfig config = CommunityStructures.config();
		if (config == null) {
			return false;
		}

		UUID playerId = player.getUuid();
		LiveChatConnection existing = LIVE_CONNECTIONS.get(playerId);
		if (existing != null && existing.usable()) {
			return true;
		}
		if (existing != null) {
			LIVE_CONNECTIONS.remove(playerId, existing);
		}

		long now = System.currentTimeMillis();
		if (NEXT_LIVE_RECONNECT_AT.getOrDefault(playerId, 0L) > now) {
			return false;
		}
		NEXT_LIVE_RECONNECT_AT.put(playerId, now + LIVE_RECONNECT_DELAY_MILLIS);

		LiveChatConnection connection = new LiveChatConnection(server, playerId);
		LIVE_CONNECTIONS.put(playerId, connection);
		HTTP.newWebSocketBuilder()
			.buildAsync(liveChatUri(config, playerId.toString(), LAST_SEEN_MESSAGE_ID.getOrDefault(playerId, 0L)), connection)
			.whenComplete((webSocket, throwable) -> {
				if (throwable != null) {
					LIVE_CONNECTIONS.remove(playerId, connection);
					CommunityStructures.LOGGER.debug("Could not open structure chat live connection", throwable);
				}
			});
		return true;
	}

	private static void pollPlayer(MinecraftServer server, UUID playerId) {
		CommunityStructureConfig config = CommunityStructures.config();
		if (config == null) {
			return;
		}
		long after = LAST_SEEN_MESSAGE_ID.getOrDefault(playerId, Long.MAX_VALUE);
		URI uri = apiUri(config, "/api/chat/messages?playerId=" + encode(playerId.toString()) + "&after=" + after);
		HttpRequest request = HttpRequest.newBuilder(uri)
			.version(HttpClient.Version.HTTP_1_1)
			.timeout(Duration.ofSeconds(8))
			.header("accept", "application/json")
			.GET()
			.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.whenComplete((response, throwable) -> server.execute(() -> {
				if (throwable != null || response == null || response.statusCode() / 100 != 2) {
					return;
				}
				ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
				if (player == null) {
					return;
				}
				receiveMessages(player, response.body());
			}));
	}

	private static void receiveMessages(ServerPlayerEntity player, String body) {
		ChatPollResponse response;
		try {
			response = GSON.fromJson(body, ChatPollResponse.class);
		} catch (RuntimeException exception) {
			CommunityStructures.LOGGER.debug("Could not parse structure chat response", exception);
			return;
		}
		if (response == null || response.messages == null || response.messages.isEmpty()) {
			return;
		}

		receiveMessageList(player, response.messages);
	}

	private static void receiveLiveEnvelope(MinecraftServer server, UUID playerId, String body) {
		LiveChatEnvelope envelope;
		try {
			envelope = GSON.fromJson(body, LiveChatEnvelope.class);
		} catch (RuntimeException exception) {
			CommunityStructures.LOGGER.debug("Could not parse structure chat live message", exception);
			return;
		}
		if (envelope == null) {
			return;
		}

		List<IncomingChatMessage> messages = new ArrayList<>();
		if (envelope.message != null) {
			messages.add(envelope.message);
		}
		if (envelope.messages != null && !envelope.messages.isEmpty()) {
			messages.addAll(envelope.messages);
		}
		if (messages.isEmpty()) {
			return;
		}

		ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
		if (player != null) {
			receiveMessageList(player, messages);
		}
	}

	private static void receiveMessageList(ServerPlayerEntity player, List<IncomingChatMessage> messages) {
		long lastSeen = LAST_SEEN_MESSAGE_ID.getOrDefault(player.getUuid(), 0L);
		for (IncomingChatMessage message : messages) {
			if (message.id <= lastSeen) {
				continue;
			}
			lastSeen = Math.max(lastSeen, message.id);
			if (message.fromId == null) {
				continue;
			}
			if (message.fromId.equals(player.getUuidAsString()) && !message.isSelfTestLoop()) {
				continue;
			}
			if (!shouldDeliver(player, message)) {
				ACTIVE_ROOMS.remove(player.getUuid());
				continue;
			}
			sendChatLine(player, message.fromName, message.text);
			ACTIVE_ROOMS.put(player.getUuid(), ActiveRoom.fromIncoming(message));
		}
		LAST_SEEN_MESSAGE_ID.put(player.getUuid(), lastSeen);
	}

	private static boolean shouldDeliver(ServerPlayerEntity player, IncomingChatMessage message) {
		String playerId = player.getUuidAsString();
		if (playerId.equals(message.creatorId)) {
			return true;
		}
		return CommunityStructureInstanceState.forWorld(player.getServerWorld())
			.nearby(message.structureId, message.creatorId, player.getBlockPos(), CHAT_RADIUS)
			.isPresent();
	}

	private static void sendChatLine(ServerPlayerEntity player, String fromName, String text) {
		player.sendMessage(Text.literal("<" + safeName(fromName) + "> " + safeText(text)), false);
	}

	private static URI apiUri(CommunityStructureConfig config, String path) {
		String base = config.apiBaseUrl.replaceAll("/+$", "");
		return URI.create(base + path);
	}

	private static URI liveChatUri(CommunityStructureConfig config, String playerId, long after) {
		String base = config.apiBaseUrl.replaceAll("/+$", "");
		if (base.startsWith("https://")) {
			base = "wss://" + base.substring("https://".length());
		} else if (base.startsWith("http://")) {
			base = "ws://" + base.substring("http://".length());
		}
		return URI.create(base + "/api/chat/live?playerId=" + encode(playerId) + "&after=" + after);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String safeName(String value) {
		if (value == null || value.isBlank()) {
			return "Player";
		}
		return value.replaceAll("[\\r\\n<>]", "").trim();
	}

	private static String safeText(String value) {
		if (value == null) {
			return "";
		}
		return value.replaceAll("[\\r\\n]", "").trim();
	}

	private record ChatRoute(ActiveRoom activeRoom, String toId, String toName) {
	}

	private static final class LiveChatConnection implements WebSocket.Listener {
		private final MinecraftServer server;
		private final UUID playerId;
		private final StringBuilder pendingText = new StringBuilder();
		private volatile WebSocket webSocket;
		private volatile boolean open;
		private volatile boolean closed;

		private LiveChatConnection(MinecraftServer server, UUID playerId) {
			this.server = server;
			this.playerId = playerId;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			this.webSocket = webSocket;
			open = true;
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			pendingText.append(data);
			if (last) {
				String body = pendingText.toString();
				pendingText.setLength(0);
				server.execute(() -> receiveLiveEnvelope(server, playerId, body));
			}
			webSocket.request(1);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			open = false;
			closed = true;
			LIVE_CONNECTIONS.remove(playerId, this);
			NEXT_LIVE_RECONNECT_AT.put(playerId, System.currentTimeMillis() + LIVE_RECONNECT_DELAY_MILLIS);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			open = false;
			closed = true;
			LIVE_CONNECTIONS.remove(playerId, this);
			NEXT_LIVE_RECONNECT_AT.put(playerId, System.currentTimeMillis() + LIVE_RECONNECT_DELAY_MILLIS);
			CommunityStructures.LOGGER.debug("Structure chat live connection closed with an error", error);
		}

		private boolean usable() {
			return !closed;
		}

		private boolean open() {
			return open && !closed;
		}

		private void close() {
			closed = true;
			open = false;
			LIVE_CONNECTIONS.remove(playerId, this);
			WebSocket socket = webSocket;
			if (socket != null) {
				socket.sendClose(WebSocket.NORMAL_CLOSURE, "player offline");
			}
		}
	}

	private static final class ActiveRoom {
		private final String roomId;
		private final String structureId;
		private final String structureName;
		private final String creatorId;
		private final String creatorName;
		private final String visitorId;
		private final String visitorName;
		private long lastTouched;

		private ActiveRoom(String roomId, String structureId, String structureName, String creatorId, String creatorName, String visitorId, String visitorName) {
			this.roomId = roomId;
			this.structureId = structureId;
			this.structureName = structureName;
			this.creatorId = creatorId;
			this.creatorName = creatorName;
			this.visitorId = visitorId;
			this.visitorName = visitorName;
			touch();
		}

		private static ActiveRoom fromVisitor(CommunityStructureInstanceState.Instance instance, String visitorId, String visitorName) {
			return new ActiveRoom(roomId(instance.structureId(), instance.creatorId(), visitorId), instance.structureId(), instance.structureName(), instance.creatorId(), instance.creatorName(), visitorId, visitorName);
		}

		private static ActiveRoom fromIncoming(IncomingChatMessage message) {
			return new ActiveRoom(message.roomId, message.structureId, message.structureName, message.creatorId, message.creatorName, message.visitorId, message.visitorName);
		}

		private static String roomId(String structureId, String creatorId, String visitorId) {
			return "structure:" + structureId + ":" + creatorId + ":" + visitorId;
		}

		private void touch() {
			lastTouched = System.currentTimeMillis();
		}

		private boolean expired() {
			return System.currentTimeMillis() - lastTouched > ACTIVE_ROOM_TTL_MILLIS;
		}

		private String roomId() {
			return roomId;
		}

		private String structureId() {
			return structureId;
		}

		private String structureName() {
			return structureName;
		}

		private String creatorId() {
			return creatorId;
		}

		private String creatorName() {
			return creatorName;
		}

		private String visitorId() {
			return visitorId;
		}

		private String visitorName() {
			return visitorName;
		}
	}

	private record OutgoingChatMessage(
		String roomId,
		String structureId,
		String structureName,
		String creatorId,
		String creatorName,
		String visitorId,
		String visitorName,
		String fromId,
		String fromName,
		String toId,
		String toName,
		String text
	) {
	}

	private static final class ChatPollResponse {
		private List<IncomingChatMessage> messages;
	}

	private static final class LiveChatEnvelope {
		private IncomingChatMessage message;
		private List<IncomingChatMessage> messages;
	}

	private static final class IncomingChatMessage {
		private long id;
		private String roomId;
		private String structureId;
		private String structureName;
		private String creatorId;
		private String creatorName;
		private String visitorId;
		private String visitorName;
		private String fromId;
		private String fromName;
		private String toId;
		private String text;

		private boolean isSelfTestLoop() {
			return fromId != null
				&& fromId.equals(toId)
				&& creatorId != null
				&& creatorId.equals(visitorId);
		}
	}
}
