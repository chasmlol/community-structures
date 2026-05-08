package com.codex.communitystructures;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
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
	private static final long DEATH_ROOM_FALLBACK_TTL_MILLIS = 60 * 1000L;
	private static final long LIVE_RECONNECT_DELAY_MILLIS = 5000L;
	private static final long NO_HISTORY_BACKLOG = Long.MAX_VALUE;
	private static final int POLL_TICKS = 40;
	private static final Gson GSON = new Gson();
	private static final Path CHAT_STATE_PATH = FabricLoader.getInstance().getConfigDir().resolve("community_structures").resolve("chat-state.json");
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(4))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private static final Map<UUID, Long> LAST_SEEN_MESSAGE_ID = new ConcurrentHashMap<>();
	private static final Map<UUID, ActiveRoom> ACTIVE_ROOMS = new ConcurrentHashMap<>();
	private static final Map<UUID, LiveChatConnection> LIVE_CONNECTIONS = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> NEXT_LIVE_RECONNECT_AT = new ConcurrentHashMap<>();
	private static volatile boolean chatStateLoaded;
	private static int tickCounter;

	private CommunityStructureChat() {
	}

	public static void register() {
		loadChatState();
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
			if (instance.creatorId().equals(senderId)) {
				return Optional.empty();
			}
			ActiveRoom room = ActiveRoom.fromVisitor(instance, senderId, sender.getGameProfile().getName());
			return Optional.of(new ChatRoute(room, instance.creatorId(), instance.creatorName()));
		}

		ActiveRoom active = ACTIVE_ROOMS.get(sender.getUuid());
		if (active == null || active.expired()) {
			ACTIVE_ROOMS.remove(sender.getUuid());
			return Optional.empty();
		}
		if (active.creatorId().equals(senderId) && !active.isSelfLoop()) {
			active.touch();
			return Optional.of(new ChatRoute(active, active.visitorId(), active.visitorName()));
		}
		if (active.remoteVisitorAllowed() && active.visitorId().equals(senderId)) {
			active.touch();
			return Optional.of(new ChatRoute(active, active.creatorId(), active.creatorName()));
		}
		return Optional.empty();
	}

	public static void receiveDeathChatSessions(ServerPlayerEntity player, List<DeathChatSession> sessions) {
		for (DeathChatSession session : sessions) {
			activateDeathChatRoom(player, session);
		}
	}

	public static void activateDeathHuntRoom(
		ServerPlayerEntity player,
		String huntId,
		String deadPlayerId,
		String deadPlayerName,
		String hunterId,
		String hunterName,
		long expiresAtMillis
	) {
		if (huntId == null || huntId.isBlank()) {
			return;
		}
		DeathChatSession session = new DeathChatSession();
		session.id = huntId;
		session.roomId = ActiveRoom.deathRoomId(huntId, deadPlayerId, hunterId);
		session.structureId = ActiveRoom.deathStructureId(huntId);
		session.structureName = safeName(deadPlayerName) + "'s lost gear";
		session.creatorId = deadPlayerId;
		session.creatorName = safeName(deadPlayerName);
		session.visitorId = hunterId;
		session.visitorName = safeName(hunterName);
		session.expiresAtMillis = expiresAtMillis;
		activateDeathChatRoom(player, session);
	}

	private static void activateDeathChatRoom(ServerPlayerEntity player, DeathChatSession session) {
		if (session == null || !session.includes(player.getUuidAsString())) {
			return;
		}
		ActiveRoom room = ActiveRoom.fromDeathSession(session);
		if (room == null || room.expired()) {
			return;
		}
		ACTIVE_ROOMS.put(player.getUuid(), room);
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
			.buildAsync(liveChatUri(config, playerId.toString(), player.getGameProfile().getName(), lastSeenForRequest(playerId)), connection)
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
		long after = lastSeenForRequest(playerId);
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
		List<CommunityStructureBlessing.IncomingBlessing> blessings = new ArrayList<>();
		if (envelope.blessing != null) {
			blessings.add(envelope.blessing);
		}
		if (envelope.blessings != null && !envelope.blessings.isEmpty()) {
			blessings.addAll(envelope.blessings);
		}
		List<CommunityStructureDeathHunt.IncomingDeathHunt> deathHunts = new ArrayList<>();
		if (envelope.deathHunt != null) {
			deathHunts.add(envelope.deathHunt);
		}
		if (envelope.deathHunts != null && !envelope.deathHunts.isEmpty()) {
			deathHunts.addAll(envelope.deathHunts);
		}
		List<CommunityStructureDeathHunt.IncomingDeathReturn> deathReturns = new ArrayList<>();
		if (envelope.deathReturn != null) {
			deathReturns.add(envelope.deathReturn);
		}
		if (envelope.deathReturns != null && !envelope.deathReturns.isEmpty()) {
			deathReturns.addAll(envelope.deathReturns);
		}
		List<DeathChatSession> deathChatSessions = new ArrayList<>();
		if (envelope.deathChatSession != null) {
			deathChatSessions.add(envelope.deathChatSession);
		}
		if (envelope.deathChatSessions != null && !envelope.deathChatSessions.isEmpty()) {
			deathChatSessions.addAll(envelope.deathChatSessions);
		}

		ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
		if (player != null) {
			if (!deathChatSessions.isEmpty()) {
				receiveDeathChatSessions(player, deathChatSessions);
			}
			if (!messages.isEmpty()) {
				receiveMessageList(player, messages);
			}
			if (!blessings.isEmpty()) {
				CommunityStructureBlessing.receiveBlessings(player, blessings);
			}
			if (!deathHunts.isEmpty()) {
				CommunityStructureDeathHunt.receiveDeathHunts(player, deathHunts);
			}
			if (!deathReturns.isEmpty()) {
				CommunityStructureDeathHunt.receiveDeathReturns(player, deathReturns);
			}
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
			rememberIncomingRoom(player, message);
		}
		updateLastSeen(player.getUuid(), lastSeen);
	}

	private static void rememberIncomingRoom(ServerPlayerEntity player, IncomingChatMessage message) {
		if (message.isDeathRoom()) {
			ActiveRoom existing = ACTIVE_ROOMS.get(player.getUuid());
			if (existing != null && existing.matches(message.roomId) && !existing.expired()) {
				existing.touch();
				return;
			}
			ACTIVE_ROOMS.put(player.getUuid(), ActiveRoom.fromIncomingDeath(message));
			return;
		}
		ACTIVE_ROOMS.put(player.getUuid(), ActiveRoom.fromIncoming(message));
	}

	private static long lastSeenForRequest(UUID playerId) {
		loadChatState();
		return LAST_SEEN_MESSAGE_ID.getOrDefault(playerId, NO_HISTORY_BACKLOG);
	}

	private static void updateLastSeen(UUID playerId, long lastSeen) {
		long previous = LAST_SEEN_MESSAGE_ID.getOrDefault(playerId, -1L);
		if (lastSeen <= previous) {
			return;
		}
		LAST_SEEN_MESSAGE_ID.put(playerId, lastSeen);
		saveChatState();
	}

	private static void loadChatState() {
		if (chatStateLoaded) {
			return;
		}
		synchronized (CommunityStructureChat.class) {
			if (chatStateLoaded) {
				return;
			}
			chatStateLoaded = true;
			if (!Files.exists(CHAT_STATE_PATH)) {
				return;
			}
			try (var reader = Files.newBufferedReader(CHAT_STATE_PATH)) {
				ChatState state = GSON.fromJson(reader, ChatState.class);
				if (state == null || state.lastSeenMessageIds == null) {
					return;
				}
				for (Map.Entry<String, Long> entry : state.lastSeenMessageIds.entrySet()) {
					try {
						UUID playerId = UUID.fromString(entry.getKey());
						long lastSeen = entry.getValue() == null ? 0L : entry.getValue();
						if (lastSeen > 0L) {
							LAST_SEEN_MESSAGE_ID.put(playerId, lastSeen);
						}
					} catch (IllegalArgumentException ignored) {
					}
				}
			} catch (IOException | RuntimeException exception) {
				CommunityStructures.LOGGER.debug("Could not load structure chat state {}", CHAT_STATE_PATH, exception);
			}
		}
	}

	private static synchronized void saveChatState() {
		try {
			Files.createDirectories(CHAT_STATE_PATH.getParent());
			ChatState state = new ChatState();
			for (Map.Entry<UUID, Long> entry : LAST_SEEN_MESSAGE_ID.entrySet()) {
				if (entry.getValue() != null && entry.getValue() > 0L) {
					state.lastSeenMessageIds.put(entry.getKey().toString(), entry.getValue());
				}
			}
			Path temp = CHAT_STATE_PATH.resolveSibling(CHAT_STATE_PATH.getFileName() + ".tmp");
			try (var writer = Files.newBufferedWriter(temp)) {
				GSON.toJson(state, writer);
			}
			try {
				Files.move(temp, CHAT_STATE_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicMoveException) {
				Files.move(temp, CHAT_STATE_PATH, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			CommunityStructures.LOGGER.debug("Could not save structure chat state {}", CHAT_STATE_PATH, exception);
		}
	}

	private static boolean shouldDeliver(ServerPlayerEntity player, IncomingChatMessage message) {
		String playerId = player.getUuidAsString();
		if (message.isDeathRoom()) {
			ActiveRoom active = ACTIVE_ROOMS.get(player.getUuid());
			if (active != null && active.expired()) {
				ACTIVE_ROOMS.remove(player.getUuid(), active);
				active = null;
			}
			return active != null && active.matches(message.roomId) && active.includes(playerId);
		}
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

	private static URI liveChatUri(CommunityStructureConfig config, String playerId, String playerName, long after) {
		String base = config.apiBaseUrl.replaceAll("/+$", "");
		if (base.startsWith("https://")) {
			base = "wss://" + base.substring("https://".length());
		} else if (base.startsWith("http://")) {
			base = "ws://" + base.substring("http://".length());
		}
		return URI.create(base + "/api/chat/live?playerId=" + encode(playerId) + "&playerName=" + encode(playerName) + "&after=" + after);
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
		private final boolean remoteVisitorAllowed;
		private final long expiresAtMillis;
		private long lastTouched;

		private ActiveRoom(String roomId, String structureId, String structureName, String creatorId, String creatorName, String visitorId, String visitorName) {
			this(roomId, structureId, structureName, creatorId, creatorName, visitorId, visitorName, false, 0L);
		}

		private ActiveRoom(String roomId, String structureId, String structureName, String creatorId, String creatorName, String visitorId, String visitorName, boolean remoteVisitorAllowed, long expiresAtMillis) {
			this.roomId = roomId;
			this.structureId = structureId;
			this.structureName = structureName;
			this.creatorId = creatorId;
			this.creatorName = creatorName;
			this.visitorId = visitorId;
			this.visitorName = visitorName;
			this.remoteVisitorAllowed = remoteVisitorAllowed;
			this.expiresAtMillis = expiresAtMillis;
			touch();
		}

		private static ActiveRoom fromVisitor(CommunityStructureInstanceState.Instance instance, String visitorId, String visitorName) {
			return new ActiveRoom(roomId(instance.structureId(), instance.creatorId(), visitorId), instance.structureId(), instance.structureName(), instance.creatorId(), instance.creatorName(), visitorId, visitorName);
		}

		private static ActiveRoom fromIncoming(IncomingChatMessage message) {
			return new ActiveRoom(message.roomId, message.structureId, message.structureName, message.creatorId, message.creatorName, message.visitorId, message.visitorName);
		}

		private static ActiveRoom fromIncomingDeath(IncomingChatMessage message) {
			return new ActiveRoom(
				message.roomId,
				message.structureId,
				message.structureName,
				message.creatorId,
				message.creatorName,
				message.visitorId,
				message.visitorName,
				true,
				System.currentTimeMillis() + DEATH_ROOM_FALLBACK_TTL_MILLIS
			);
		}

		private static ActiveRoom fromDeathSession(DeathChatSession session) {
			String roomId = session.roomId == null || session.roomId.isBlank()
				? deathRoomId(session.id, session.creatorId, session.visitorId)
				: session.roomId;
			String structureId = session.structureId == null || session.structureId.isBlank()
				? deathStructureId(session.id)
				: session.structureId;
			long expiresAt = session.expiryMillis();
			if (roomId.isBlank() || structureId.isBlank() || session.creatorId == null || session.creatorId.isBlank() || session.visitorId == null || session.visitorId.isBlank()) {
				return null;
			}
			return new ActiveRoom(roomId, structureId, session.structureName, session.creatorId, session.creatorName, session.visitorId, session.visitorName, true, expiresAt);
		}

		private static String roomId(String structureId, String creatorId, String visitorId) {
			return "structure:" + structureId + ":" + creatorId + ":" + visitorId;
		}

		private static String deathStructureId(String huntId) {
			return "death_hunt:" + safeName(huntId);
		}

		private static String deathRoomId(String huntId, String deadPlayerId, String hunterId) {
			return deathStructureId(huntId) + ":" + safeName(deadPlayerId) + ":" + safeName(hunterId);
		}

		private void touch() {
			lastTouched = System.currentTimeMillis();
		}

		private boolean expired() {
			if (expiresAtMillis > 0L) {
				return System.currentTimeMillis() > expiresAtMillis;
			}
			return System.currentTimeMillis() - lastTouched > ACTIVE_ROOM_TTL_MILLIS;
		}

		private boolean matches(String otherRoomId) {
			return roomId != null && roomId.equals(otherRoomId);
		}

		private boolean includes(String playerId) {
			return playerId != null && (playerId.equals(creatorId) || playerId.equals(visitorId));
		}

		private boolean remoteVisitorAllowed() {
			return remoteVisitorAllowed;
		}

		private boolean isSelfLoop() {
			return creatorId != null && creatorId.equals(visitorId);
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
		private DeathChatSession deathChatSession;
		private List<DeathChatSession> deathChatSessions;
		private CommunityStructureBlessing.IncomingBlessing blessing;
		private List<CommunityStructureBlessing.IncomingBlessing> blessings;
		private CommunityStructureDeathHunt.IncomingDeathHunt deathHunt;
		private List<CommunityStructureDeathHunt.IncomingDeathHunt> deathHunts;
		private CommunityStructureDeathHunt.IncomingDeathReturn deathReturn;
		private List<CommunityStructureDeathHunt.IncomingDeathReturn> deathReturns;
	}

	private static final class ChatState {
		private Map<String, Long> lastSeenMessageIds = new HashMap<>();
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

		private boolean isDeathRoom() {
			return (roomId != null && roomId.startsWith("death_hunt:"))
				|| (structureId != null && structureId.startsWith("death_hunt:"));
		}
	}

	public static final class DeathChatSession {
		private String id;
		private String roomId;
		private String structureId;
		private String structureName;
		private String creatorId;
		private String creatorName;
		private String visitorId;
		private String visitorName;
		private String expiresAt;
		private long expiresAtMillis;

		private boolean includes(String playerId) {
			return playerId != null && (playerId.equals(creatorId) || playerId.equals(visitorId));
		}

		private long expiryMillis() {
			if (expiresAtMillis > 0L) {
				return expiresAtMillis;
			}
			if (expiresAt != null && !expiresAt.isBlank()) {
				try {
					return java.time.Instant.parse(expiresAt).toEpochMilli();
				} catch (RuntimeException ignored) {
				}
			}
			return System.currentTimeMillis() + DEATH_ROOM_FALLBACK_TTL_MILLIS;
		}
	}
}
