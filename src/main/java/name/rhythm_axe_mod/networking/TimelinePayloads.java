package name.rhythm_axe_mod.networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑器可视化时间轴（屏幕覆盖层）的服务端→客户端网络包。
 *
 * 服务端（TimelineSync）每刻读取谱面工作副本 maps.editor.history[history_cursor]，
 * 裁剪出播放头附近的窗口数据，打包成 ShowPayload 推送；开关关闭或编辑器退出时发 HidePayload。
 * 客户端 TimelineGui 缓存最近一次 ShowPayload，在 HUD 上渲染七行时间轴（纯显示）。
 */
public final class TimelinePayloads {
	private TimelinePayloads() {
	}

	/** 变长 List 的 StreamCodec（26.1 ByteBufCodecs 无 list(StreamCodec)，手写编码：先写长度再逐个元素）。 */
	private static <T> StreamCodec<ByteBuf, List<T>> listOf(StreamCodec<ByteBuf, T> element) {
		return StreamCodec.of(
				(buf, list) -> {
					buf.writeInt(list.size());
					for (T t : list) {
						element.encode(buf, t);
					}
				},
				buf -> {
					int n = buf.readInt();
					List<T> out = new ArrayList<>(n);
					for (int i = 0; i < n; i++) {
						out.add(element.decode(buf));
					}
					return out;
				});
	}

	/** 客户端→服务端：报告客户端时间轴当前显示的区间长度（刻）。服务端按此区间裁剪推送数据。 */
	public record ClientWindowPayload(int windowLen) implements CustomPacketPayload {
		public static final Type<ClientWindowPayload> TYPE = new Type<>(Identifier.parse("rhythm_axe_mod:client_window"));
		public static final StreamCodec<ByteBuf, ClientWindowPayload> STREAM_CODEC =
				StreamCodec.composite(ByteBufCodecs.VAR_INT, ClientWindowPayload::windowLen, ClientWindowPayload::new);
		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 窗口内一个音符（仅渲染所需字段）。selected 供客户端画黄色选中描边。 */
	public record NoteEntry(int id, int time, int type, int duration, int color, float size, boolean followingPoint, boolean selected) {
		public static final StreamCodec<ByteBuf, NoteEntry> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, NoteEntry::id,
				ByteBufCodecs.VAR_INT, NoteEntry::time,
				ByteBufCodecs.VAR_INT, NoteEntry::type,
				ByteBufCodecs.VAR_INT, NoteEntry::duration,
				ByteBufCodecs.VAR_INT, NoteEntry::color,
				ByteBufCodecs.FLOAT, NoteEntry::size,
				ByteBufCodecs.BOOL, NoteEntry::followingPoint,
				ByteBufCodecs.BOOL, NoteEntry::selected,
				NoteEntry::new);

		@Override
		public String toString() {
			return "Note[" + type + "@" + time + "]";
		}
	}

	/** 窗口内一个时间点（red 已由服务端算好：bpm 或最终 tps 变化）。bpb 供客户端按段绘制小节线。 */
	public record TimingEntry(int time, float bpm, int tpb, int bpb, boolean red) {
		public static final StreamCodec<ByteBuf, TimingEntry> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, TimingEntry::time,
				ByteBufCodecs.FLOAT, TimingEntry::bpm,
				ByteBufCodecs.VAR_INT, TimingEntry::tpb,
				ByteBufCodecs.VAR_INT, TimingEntry::bpb,
				ByteBufCodecs.BOOL, TimingEntry::red,
				TimingEntry::new);
	}

	/** 窗口内一个事件点。 */
	public record EventEntry(int time, int commandCount) {
		public static final StreamCodec<ByteBuf, EventEntry> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, EventEntry::time,
				ByteBufCodecs.VAR_INT, EventEntry::commandCount,
				EventEntry::new);
	}

	/** 显示数据：播放头固定，内容随谱面播放滚动。 */
	public record ShowPayload(
			int playhead,
			boolean playing,
			int timelineLength,
			float bpm, int bpb, int tpb, // 播放头所在时间点参数
			float playSpeed,
			int noteSpeed, // 音符流速（note_speed options），状态栏"1.0x 16"里的 16
			int unsavedCount, // 未保存编辑数 = |history_cursor - saved_cursor|，状态栏标题左侧
			boolean hasEndTime, int endTime,
			String title, String artist,
			List<NoteEntry> notes, List<TimingEntry> timings, List<EventEntry> events
	) implements CustomPacketPayload {
		public static final Type<ShowPayload> TYPE = new Type<>(Identifier.parse("rhythm_axe_mod:timeline_show"));
		public static final StreamCodec<ByteBuf, ShowPayload> STREAM_CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeInt(p.playhead());
					buf.writeBoolean(p.playing());
					buf.writeInt(p.timelineLength());
					buf.writeFloat(p.bpm());
					buf.writeInt(p.bpb());
					buf.writeInt(p.tpb());
					buf.writeFloat(p.playSpeed());
					buf.writeInt(p.noteSpeed());
					buf.writeInt(p.unsavedCount());
					buf.writeBoolean(p.hasEndTime());
					buf.writeInt(p.endTime());
					ByteBufCodecs.STRING_UTF8.encode(buf, p.title());
					ByteBufCodecs.STRING_UTF8.encode(buf, p.artist());
					listOf(NoteEntry.CODEC).encode(buf, p.notes());
					listOf(TimingEntry.CODEC).encode(buf, p.timings());
					listOf(EventEntry.CODEC).encode(buf, p.events());
				},
				buf -> new ShowPayload(
						buf.readInt(),
						buf.readBoolean(),
						buf.readInt(),
						buf.readFloat(),
						buf.readInt(),
						buf.readInt(),
						buf.readFloat(),
						buf.readInt(),
						buf.readInt(),
						buf.readBoolean(),
						buf.readInt(),
						ByteBufCodecs.STRING_UTF8.decode(buf),
						ByteBufCodecs.STRING_UTF8.decode(buf),
						listOf(NoteEntry.CODEC).decode(buf),
						listOf(TimingEntry.CODEC).decode(buf),
						listOf(EventEntry.CODEC).decode(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 关闭/退出编辑器时下发，客户端隐藏时间轴。 */
	public record HidePayload() implements CustomPacketPayload {
		public static final Type<HidePayload> TYPE = new Type<>(Identifier.parse("rhythm_axe_mod:timeline_hide"));
		public static final StreamCodec<ByteBuf, HidePayload> STREAM_CODEC = StreamCodec.unit(new HidePayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
