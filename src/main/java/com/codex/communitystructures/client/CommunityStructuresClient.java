package com.codex.communitystructures.client;

import com.codex.communitystructures.CommunityStructureCapturePackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class CommunityStructuresClient implements ClientModInitializer {
	private static KeyBinding captureKey;
	private static KeyBinding captureAllBlocksKey;
	private static KeyBinding captureBridgeKey;
	private static KeyBinding cancelKey;
	private static CapturePreview preview = CapturePreview.inactive();

	@Override
	public void onInitializeClient() {
		CommunityStructuresUpdateChecker.register();

		captureKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.community_structures.capture",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			"key.categories.community_structures"
		));
		captureAllBlocksKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.community_structures.capture_all_blocks",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_L,
			"key.categories.community_structures"
		));
		captureBridgeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.community_structures.capture_bridge",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_N,
			"key.categories.community_structures"
		));
		cancelKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.community_structures.cancel_capture",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_J,
			"key.categories.community_structures"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			CommunityStructuresUpdateChecker.tick(client);

			while (captureKey.wasPressed()) {
				if (ClientPlayNetworking.canSend(CommunityStructureCapturePackets.CaptureActionPayload.ID)) {
					ClientPlayNetworking.send(new CommunityStructureCapturePackets.CaptureActionPayload(CommunityStructureCapturePackets.ACTION_TOGGLE_TRACKED));
				}
			}
			while (captureAllBlocksKey.wasPressed()) {
				if (ClientPlayNetworking.canSend(CommunityStructureCapturePackets.CaptureActionPayload.ID)) {
					ClientPlayNetworking.send(new CommunityStructureCapturePackets.CaptureActionPayload(CommunityStructureCapturePackets.ACTION_TOGGLE_ALL_BLOCKS));
				}
			}
			while (captureBridgeKey.wasPressed()) {
				if (ClientPlayNetworking.canSend(CommunityStructureCapturePackets.CaptureActionPayload.ID)) {
					ClientPlayNetworking.send(new CommunityStructureCapturePackets.CaptureActionPayload(CommunityStructureCapturePackets.ACTION_TOGGLE_BRIDGE));
				}
			}
			while (cancelKey.wasPressed()) {
				if (ClientPlayNetworking.canSend(CommunityStructureCapturePackets.CaptureActionPayload.ID)) {
					ClientPlayNetworking.send(new CommunityStructureCapturePackets.CaptureActionPayload(CommunityStructureCapturePackets.ACTION_CANCEL));
				}
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(CommunityStructureCapturePackets.CapturePreviewPayload.ID, (payload, context) -> {
			preview = payload.active()
				? new CapturePreview(payload.min(), payload.max(), payload.selectedBlocks(), payload.blockPositions())
				: CapturePreview.inactive();
		});
		ClientPlayNetworking.registerGlobalReceiver(CommunityStructureCapturePackets.UpdateAvailablePayload.ID, (payload, context) -> {
			CommunityStructuresUpdateChecker.receiveServerUpdate(payload.version(), payload.htmlUrl(), payload.assetName(), payload.assetUrl());
		});

		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			if (!preview.active() || preview.blockPositions().isEmpty()) {
				return;
			}
			MatrixStack matrices = context.matrixStack();
			if (matrices == null || context.consumers() == null) {
				return;
			}
			Vec3d camera = context.camera().getPos();
			VertexConsumer lines = context.consumers().getBuffer(RenderLayer.getLines());
			for (BlockPos pos : preview.blockPositions()) {
				Box box = new Box(
					pos.getX(),
					pos.getY(),
					pos.getZ(),
					pos.getX() + 1.0D,
					pos.getY() + 1.0D,
					pos.getZ() + 1.0D
				).offset(-camera.x, -camera.y, -camera.z);
				WorldRenderer.drawBox(matrices, lines, box, 0.2F, 1.0F, 0.25F, 0.95F);
			}
		});
	}

	private record CapturePreview(boolean active, BlockPos min, BlockPos max, int selectedBlocks, List<BlockPos> blockPositions) {
		private static CapturePreview inactive() {
			return new CapturePreview(false, BlockPos.ORIGIN, BlockPos.ORIGIN, 0, List.of());
		}

		private CapturePreview(BlockPos min, BlockPos max, int selectedBlocks, List<BlockPos> blockPositions) {
			this(true, min, max, selectedBlocks, List.copyOf(blockPositions));
		}
	}
}
