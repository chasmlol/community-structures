package com.codex.communitystructures;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class CommunityStructureCapturePackets {
	public static final int ACTION_TOGGLE_TRACKED = 0;
	public static final int ACTION_CANCEL = 1;
	public static final int ACTION_TOGGLE_ALL_BLOCKS = 2;
	private static final int MAX_PREVIEW_BLOCKS = 20000;

	private CommunityStructureCapturePackets() {
	}

	public static void register() {
		PayloadTypeRegistry.playC2S().register(CaptureActionPayload.ID, CaptureActionPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(CapturePreviewPayload.ID, CapturePreviewPayload.CODEC);
	}

	public record CaptureActionPayload(int action) implements CustomPayload {
		public static final Id<CaptureActionPayload> ID = new Id<>(Identifier.of(CommunityStructures.MOD_ID, "capture_action"));
		public static final PacketCodec<RegistryByteBuf, CaptureActionPayload> CODEC = CustomPayload.codecOf(
			(payload, buffer) -> buffer.writeInt(payload.action),
			buffer -> new CaptureActionPayload(buffer.readInt())
		);

		@Override
		public Id<CaptureActionPayload> getId() {
			return ID;
		}
	}

	public record CapturePreviewPayload(boolean active, BlockPos min, BlockPos max, int selectedBlocks, List<BlockPos> blockPositions) implements CustomPayload {
		public static final Id<CapturePreviewPayload> ID = new Id<>(Identifier.of(CommunityStructures.MOD_ID, "capture_preview"));
		public static final PacketCodec<RegistryByteBuf, CapturePreviewPayload> CODEC = CustomPayload.codecOf(
			(payload, buffer) -> {
				buffer.writeBoolean(payload.active);
				buffer.writeBlockPos(payload.min);
				buffer.writeBlockPos(payload.max);
				buffer.writeInt(payload.selectedBlocks);
				buffer.writeInt(payload.blockPositions.size());
				for (BlockPos pos : payload.blockPositions) {
					buffer.writeBlockPos(pos);
				}
			},
			buffer -> {
				boolean active = buffer.readBoolean();
				BlockPos min = buffer.readBlockPos();
				BlockPos max = buffer.readBlockPos();
				int selectedBlocks = buffer.readInt();
				int blockCount = buffer.readInt();
				if (blockCount < 0 || blockCount > MAX_PREVIEW_BLOCKS) {
					throw new IllegalArgumentException("Capture preview block count is out of range: " + blockCount);
				}
				List<BlockPos> blockPositions = new ArrayList<>(blockCount);
				for (int index = 0; index < blockCount; index++) {
					blockPositions.add(buffer.readBlockPos());
				}
				return new CapturePreviewPayload(active, min, max, selectedBlocks, blockPositions);
			}
		);

		public CapturePreviewPayload {
			blockPositions = List.copyOf(blockPositions);
		}

		@Override
		public Id<CapturePreviewPayload> getId() {
			return ID;
		}
	}
}
