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
	public static final int ACTION_TOGGLE_BRIDGE = 3;
	private static final int MAX_PREVIEW_BLOCKS = 20000;

	private CommunityStructureCapturePackets() {
	}

	public static void register() {
		PayloadTypeRegistry.playC2S().register(CaptureActionPayload.ID, CaptureActionPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(CapturePreviewPayload.ID, CapturePreviewPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(UpdateAvailablePayload.ID, UpdateAvailablePayload.CODEC);
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

	public record UpdateAvailablePayload(String version, String htmlUrl, String assetName, String assetUrl) implements CustomPayload {
		public static final Id<UpdateAvailablePayload> ID = new Id<>(Identifier.of(CommunityStructures.MOD_ID, "update_available"));
		public static final PacketCodec<RegistryByteBuf, UpdateAvailablePayload> CODEC = CustomPayload.codecOf(
			(payload, buffer) -> {
				buffer.writeString(payload.version);
				buffer.writeString(payload.htmlUrl);
				buffer.writeString(payload.assetName);
				buffer.writeString(payload.assetUrl);
			},
			buffer -> new UpdateAvailablePayload(
				buffer.readString(64),
				buffer.readString(512),
				buffer.readString(256),
				buffer.readString(1024)
			)
		);

		public UpdateAvailablePayload {
			version = clean(version);
			htmlUrl = clean(htmlUrl);
			assetName = clean(assetName);
			assetUrl = clean(assetUrl);
		}

		@Override
		public Id<UpdateAvailablePayload> getId() {
			return ID;
		}
	}

	private static String clean(String value) {
		return value == null ? "" : value;
	}
}
