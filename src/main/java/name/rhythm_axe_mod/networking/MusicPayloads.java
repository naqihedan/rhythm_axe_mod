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

	/**
	 * 服务端→客户端：**游戏播放头**（毫秒）—— 「音乐对齐游戏」的目标位置。
	 *
	 * 播放头在服务端（编辑器 = maps.editor.playhead；游玩 = play_state.time + runtime.timing_points），
	 * 单人时客户端直接读集成服务端（零延迟）；**多人**下客户端拿不到服务端 storage，
	 * 就靠服务端每刻推送这个包（只发给收过 /playmusic 的玩家）。
	 * valid=false 表示当前没有可对齐的播放头（不在编辑/游玩中、对齐开关关闭、读取异常）→ 客户端跳过对齐。
	 */
	public record HeadPayload(int playheadMs, boolean valid) implements CustomPacketPayload {
		public static final Type<HeadPayload> TYPE = new Type<>(Identifier.parse("rhythm_axe_mod:music_head"));
		public static final StreamCodec<ByteBuf, HeadPayload> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, HeadPayload::playheadMs,
				ByteBufCodecs.BOOL, HeadPayload::valid,
				HeadPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端→服务端：音乐实际播放位置（诊断用，也为后续「音乐驱动播放头」预留）。
	 *
	 * audibleMs = 真正**出声**的位置（当前缓冲内偏移 + 已播完缓冲换算），单位毫秒（源时间轴）；
	 * queuedMs  = 已**排队**的位置（比出声位置提前若干缓冲）；
	 * 两者之差即声卡排队深度，用来确认"偏差"到底出在排队还是出在启动时刻。
	 */
	public record MusicPosPayload(int audibleMs, int queuedMs, float speed, boolean playing) implements CustomPacketPayload {
		public static final Type<MusicPosPayload> TYPE = new Type<>(Identifier.parse("rhythm_axe_mod:music_pos"));
		public static final StreamCodec<ByteBuf, MusicPosPayload> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, MusicPosPayload::audibleMs,
				ByteBufCodecs.VAR_INT, MusicPosPayload::queuedMs,
				ByteBufCodecs.FLOAT, MusicPosPayload::speed,
				ByteBufCodecs.BOOL, MusicPosPayload::playing,
				MusicPosPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
