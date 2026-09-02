package name.rhythm_axe_mod.networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 音乐播放相关的服务端→客户端网络包（编辑器/playmusic 指令用）。
 * 服务端解析命令并换算好起始毫秒数，客户端只负责播放。
 */
public final class MusicPayloads {
	private MusicPayloads() {
	}

	/** 播放：soundId=音效事件id，startMs=起始毫秒，speed=播放速率（保调），volume=音量0~1 */
	public record PlayPayload(String soundId, int startMs, float speed, float volume) implements CustomPacketPayload {
		public static final Type<PlayPayload> TYPE = new Type<>(Identifier.parse("rhythm_axe_mod:music_play"));
		public static final StreamCodec<ByteBuf, PlayPayload> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, PlayPayload::soundId,
				ByteBufCodecs.VAR_INT, PlayPayload::startMs,
				ByteBufCodecs.FLOAT, PlayPayload::speed,
				ByteBufCodecs.FLOAT, PlayPayload::volume,
				PlayPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record PreloadPayload(String soundId) implements CustomPacketPayload {
		public static final Type<PreloadPayload> TYPE = new Type<>(Identifier.parse("rhythm_axe_mod:music_preload"));
		public static final StreamCodec<ByteBuf, PreloadPayload> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, PreloadPayload::soundId,
				PreloadPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record PausePayload() implements CustomPacketPayload {
		public static final Type<PausePayload> TYPE = new Type<>(Identifier.parse("rhythm_axe_mod:music_pause"));
		public static final StreamCodec<ByteBuf, PausePayload> STREAM_CODEC = StreamCodec.unit(new PausePayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ResumePayload() implements CustomPacketPayload {
		public static final Type<ResumePayload> TYPE = new Type<>(Identifier.parse("rhythm_axe_mod:music_resume"));
		public static final StreamCodec<ByteBuf, ResumePayload> STREAM_CODEC = StreamCodec.unit(new ResumePayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record StopPayload() implements CustomPacketPayload {
		public static final Type<StopPayload> TYPE = new Type<>(Identifier.parse("rhythm_axe_mod:music_stop"));
		public static final StreamCodec<ByteBuf, StopPayload> STREAM_CODEC = StreamCodec.unit(new StopPayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
