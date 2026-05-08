package com.codex.communitystructures;

import com.google.gson.Gson;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CommunityStructureBlessing {
	private static final double BLESS_RADIUS = 10.0D;
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(4))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private static final Set<Long> DELIVERED_BLESSINGS = ConcurrentHashMap.newKeySet();

	private CommunityStructureBlessing() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(CommandManager.literal("bless")
				.executes(CommunityStructureBlessing::openBlessingInventory)));
	}

	private static int openBlessingInventory(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
		var nearby = CommunityStructureInstanceState.forWorld(player.getServerWorld()).nearby(player.getBlockPos(), BLESS_RADIUS);
		if (nearby.isEmpty()) {
			player.sendMessage(Text.literal("Stand within 10 blocks of a community structure to bless it."), false);
			return 0;
		}

		CommunityStructureInstanceState.Instance instance = nearby.get();
		SimpleInventory inventory = new SimpleInventory(9);
		player.openHandledScreen(new BlessingScreenFactory(instance, inventory));
		player.sendMessage(Text.literal("Place items in the blessing chest, then close it to send them to " + safeName(instance.creatorName()) + "."), false);
		return 1;
	}

	private static void sendBlessing(ServerPlayerEntity sender, CommunityStructureInstanceState.Instance instance, List<ItemStack> stacks) {
		if (stacks.isEmpty()) {
			sender.sendMessage(Text.literal("Blessing cancelled: no items were placed in the chest."), false);
			return;
		}

		List<OutgoingBlessingItem> items = new ArrayList<>();
		for (ItemStack stack : stacks) {
			if (stack.isEmpty()) {
				continue;
			}
			NbtElement encoded = stack.encode(sender.getRegistryManager());
			Identifier itemId = Registries.ITEM.getId(stack.getItem());
			items.add(new OutgoingBlessingItem(itemId.toString(), stack.getCount(), stack.getName().getString(), encoded.toString()));
		}

		if (items.isEmpty()) {
			sender.sendMessage(Text.literal("Blessing cancelled: no valid items were placed in the chest."), false);
			return;
		}

		OutgoingBlessing blessing = new OutgoingBlessing(
			instance.structureId(),
			instance.structureName(),
			instance.creatorId(),
			instance.creatorName(),
			sender.getUuidAsString(),
			sender.getGameProfile().getName(),
			instance.creatorId(),
			instance.creatorName(),
			items
		);

		CommunityStructureConfig config = CommunityStructures.config();
		if (config == null) {
			sender.sendMessage(Text.literal("Blessing failed: config is not loaded."), false);
			return;
		}

		HttpRequest request = HttpRequest.newBuilder(apiUri(config, "/api/blessings"))
			.version(HttpClient.Version.HTTP_1_1)
			.timeout(Duration.ofSeconds(10))
			.header("content-type", "application/json; charset=utf-8")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(blessing), StandardCharsets.UTF_8))
			.build();

		MinecraftServer server = sender.getServer();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.whenComplete((response, throwable) -> server.execute(() -> {
				if (throwable != null) {
					CommunityStructures.LOGGER.warn("Could not post structure blessing", throwable);
					sender.sendMessage(Text.literal("Blessing could not be sent and the offered items were consumed."), false);
					return;
				}
				if (response.statusCode() / 100 != 2) {
					sender.sendMessage(Text.literal("Blessing was rejected by the server and the offered items were consumed."), false);
					return;
				}
				sender.sendMessage(Text.literal("Blessing sent to " + safeName(instance.creatorName()) + "."), false);
			}));
	}

	public static void receiveBlessings(ServerPlayerEntity player, List<IncomingBlessing> blessings) {
		for (IncomingBlessing blessing : blessings) {
			receiveBlessing(player, blessing);
		}
	}

	private static void receiveBlessing(ServerPlayerEntity player, IncomingBlessing blessing) {
		if (blessing == null || blessing.id <= 0 || blessing.items == null || !DELIVERED_BLESSINGS.add(blessing.id)) {
			return;
		}
		if (blessing.toId == null || !blessing.toId.equals(player.getUuidAsString())) {
			return;
		}

		boolean deliveredAny = false;
		for (IncomingBlessingItem item : blessing.items) {
			ItemStack stack = decodeStack(player, item);
			if (stack.isEmpty()) {
				continue;
			}
			deliverStack(player, stack);
			deliveredAny = true;
			player.sendMessage(Text.literal(safeName(blessing.fromName) + " sent you ")
				.append(itemHoverText(stack))
				.append(Text.literal(".")), false);
		}

		if (deliveredAny) {
			claimBlessing(player.getServer(), blessing.id);
		}
	}

	private static ItemStack decodeStack(ServerPlayerEntity player, IncomingBlessingItem item) {
		if (item == null || item.stackNbt == null || item.stackNbt.isBlank()) {
			return ItemStack.EMPTY;
		}
		try {
			NbtCompound nbt = StringNbtReader.parse(item.stackNbt);
			return ItemStack.fromNbt(player.getRegistryManager(), nbt).orElse(ItemStack.EMPTY);
		} catch (CommandSyntaxException | RuntimeException exception) {
			CommunityStructures.LOGGER.warn("Could not decode blessing item {}", item.itemId, exception);
			return ItemStack.EMPTY;
		}
	}

	private static void deliverStack(ServerPlayerEntity player, ItemStack stack) {
		ItemStack remaining = stack.copy();
		if (!player.getInventory().insertStack(remaining) && !remaining.isEmpty()) {
			player.dropItem(remaining, false);
		} else if (!remaining.isEmpty()) {
			player.dropItem(remaining, false);
		}
	}

	private static void claimBlessing(MinecraftServer server, long id) {
		CommunityStructureConfig config = CommunityStructures.config();
		if (config == null) {
			return;
		}
		HttpRequest request = HttpRequest.newBuilder(apiUri(config, "/api/blessings/" + id + "/claim"))
			.version(HttpClient.Version.HTTP_1_1)
			.timeout(Duration.ofSeconds(8))
			.POST(HttpRequest.BodyPublishers.noBody())
			.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
			.whenComplete((response, throwable) -> {
				if (throwable != null) {
					CommunityStructures.LOGGER.debug("Could not claim blessing {}", id, throwable);
				}
			});
	}

	private static URI apiUri(CommunityStructureConfig config, String path) {
		String base = config.apiBaseUrl.replaceAll("/+$", "");
		return URI.create(base + path);
	}

	private static String safeName(String value) {
		if (value == null || value.isBlank()) {
			return "Player";
		}
		return value.replaceAll("[\\r\\n<>]", "").trim();
	}

	private static String itemSummary(ItemStack stack) {
		String name = stack.getName().getString();
		return stack.getCount() > 1 ? stack.getCount() + "x " + name : name;
	}

	private static MutableText itemHoverText(ItemStack stack) {
		return Text.literal("[" + itemSummary(stack) + "]")
			.formatted(Formatting.YELLOW)
			.styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackContent(stack))));
	}

	private record OutgoingBlessing(
		String structureId,
		String structureName,
		String creatorId,
		String creatorName,
		String fromId,
		String fromName,
		String toId,
		String toName,
		List<OutgoingBlessingItem> items
	) {
	}

	private record OutgoingBlessingItem(String itemId, int count, String name, String stackNbt) {
	}

	public static final class IncomingBlessing {
		long id;
		String structureId;
		String structureName;
		String creatorId;
		String creatorName;
		String fromId;
		String fromName;
		String toId;
		String toName;
		List<IncomingBlessingItem> items;
	}

	private static final class IncomingBlessingItem {
		String itemId;
		int count;
		String name;
		String stackNbt;
	}

	private static final class BlessingScreenFactory implements NamedScreenHandlerFactory {
		private final CommunityStructureInstanceState.Instance instance;
		private final SimpleInventory inventory;

		private BlessingScreenFactory(CommunityStructureInstanceState.Instance instance, SimpleInventory inventory) {
			this.instance = instance;
			this.inventory = inventory;
		}

		@Override
		public Text getDisplayName() {
			return Text.literal("Bless " + safeName(instance.creatorName()));
		}

		@Override
		public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
			return new BlessingScreenHandler(syncId, playerInventory, inventory, instance);
		}
	}

	private static final class BlessingScreenHandler extends GenericContainerScreenHandler {
		private final Inventory blessingInventory;
		private final CommunityStructureInstanceState.Instance instance;
		private boolean handled;

		private BlessingScreenHandler(int syncId, PlayerInventory playerInventory, Inventory blessingInventory, CommunityStructureInstanceState.Instance instance) {
			super(ScreenHandlerType.GENERIC_9X1, syncId, playerInventory, blessingInventory, 1);
			this.blessingInventory = blessingInventory;
			this.instance = instance;
		}

		@Override
		public void onClosed(PlayerEntity player) {
			if (!handled && player instanceof ServerPlayerEntity serverPlayer) {
				handled = true;
				List<ItemStack> stacks = new ArrayList<>();
				for (int slot = 0; slot < blessingInventory.size(); slot++) {
					ItemStack stack = blessingInventory.removeStack(slot);
					if (!stack.isEmpty()) {
						stacks.add(stack);
					}
				}
				sendBlessing(serverPlayer, instance, stacks);
			}
			super.onClosed(player);
		}
	}
}
