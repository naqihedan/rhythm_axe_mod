package name.rhythm_axe_mod.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.sound.sampled.AudioFormat;

import org.lwjgl.openal.AL10;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import name.rhythm_axe_mod.mixin.MinecraftServerAccessor;
import name.rhythm_axe_mod.music.MusicTime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;

/**
 * 演奏音乐播放器（阶段0，多人版）：**编辑器试听**与**正式游玩**共用同一条播放路径
 * （都由数据包 /playmusic 驱动，见《游玩谱面.md》音乐播放）。
 *
 * 思路：MC 26.1 的 JOrbisAudioStream 是纯顺序解码、没有 seek 接口，
 * 因此播放时把整首 OGG 一次性解码成 16bit PCM 存入内存，
 * 再通过 OpenAL 的队列缓冲流式喂给声卡；跳转 = 移动输出游标 + 重新排队。
 *
 * 变速采用 OLA（重叠相加）时域拉伸，真正保调：
 * 源被切成长 WIN 的正弦窗，窗起点间距 = HOP×speed 源帧，
 * 输出端窗间距固定 HOP，重叠相加后用 Σw² 归一化（1x 时精确重建、零失真），
 * AL_PITCH 恒为 1，音高不随速度变化。
 *
 * 由服务端 playmusic/pausemusic/resumemusic/stopmusic 数据包驱动，
 * 所有方法都在渲染线程执行（fabric 网络回调已调度到主线程）。
 * 每客户端一份单例，天然支持不同玩家各自播放。
 */
public class RhythmAxeMusic {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int BUFFER_COUNT = 4;
    private static final int BUFFER_SAMPLES = 4096; // 每个缓冲区的采样帧数（每声道，= 2×HOP）
    private static final int WIN = 4096;            // 时域拉伸窗长（帧）
    private static final int HOP = 2048;            // 输出 hop（= WIN/2，正弦窗 Σw²≡1 完美重建）
    /** 解码保护上限：最多解析 20 分钟的采样，防止异常文件死循环 */
    private static final long MAX_DECODE_SAMPLES = 20L * 60 * 48000;

    // ── 「音乐对齐游戏」自动对齐参数（音乐服从游戏，永不改动播放头；编辑器试听与正式游玩共用） ──
    /**
     * **0.25x 下实测标定的补偿量**（音乐 ms，负 = 让音频更晚；2026-09-21 用户用耳朵标定）。
     * 计分板 `audio_sync_offset` 缺失时用它；设成其它值（含 0 = 关掉补偿）则覆盖。
     * 实际应用时按 (1−speed)/(1−0.25) 缩放——见 {@link #syncOffsetApplied()}。
     */
    private static final int SYNC_A25_DEFAULT = -50;

    /**
     * 偏差容忍**下限**（**音乐源毫秒**，1x 下 ≈0.8 刻；实际取 max(此值, 每刻音乐毫秒/2 + 10)）：小于此值不动音频。
     * 必须 ≥ 半个刻 —— 采样相位未知，同一同步状态测到的 delta 天然在 [−Δ/2, +Δ/2] 间摆，
     * 门限比它小就会把正常摆动当成偏差，每修一次把音频推偏一点（越修越歪）。
     * 注意这是「允许音乐内容偏多少毫秒」，不随播放速率缩放：慢了（0.25x）时同样的音乐毫秒
     * 对应 4 倍真实时间，所以容差宁紧勿松，否则残留会被放大成可听的延后。
     */
    private static final int ALIGN_TOLERANCE_MS = 20;
    /** 需连续超阈的刻数（0.5s）才动手：避开「开局世界加载」那种一过性震荡。 */
    private static final int ALIGN_STREAK_TICKS = 20;
    /**
     * 窗口内偏差变化容忍**下限**：超过「max(此值, 每刻音乐毫秒)」= 还在被世界加载/GC 拖着跑 → 本次不对齐，重新取样。
     * 必须允许一整刻的摆幅：采样相位未知，同一同步状态测到的 delta 天然在 [0, 每刻音乐毫秒] 之间跳。
     */
    private static final int ALIGN_STABLE_DRIFT_MS = 30;
    /**
     * 单次最大位移（**真实毫秒**，1x 下即 400 音乐毫秒）：大偏差分几次收敛，避免一次跳掉一大段音乐。
     * 慢放时同一段音乐内容被拉长，用固定音乐毫秒会跳得越来越久 → 用真实毫秒折算出音乐毫秒（见 alignTick）。
     */
    private static final int ALIGN_MAX_STEP_MS = 400;
    /** 对齐后的冷却刻数（0.5s）：避免反复微调造成的可闻抖动。 */
    private static final int ALIGN_COOLDOWN_TICKS = 20;
    /** 多人推送值（{@link #setPushedPlayhead}）的有效期（ms）：服务端每刻推一次，这么久没更新就当失效。 */
    private static final int PUSHED_HEAD_TTL_MS = 500;
    /** 延迟补偿上限（ms）：ping/2 超过它就不补了（避免异常 ping 值把播放头拽飞）。 */
    private static final int PUSHED_HEAD_PING_CAP_MS = 100;

    private static int source = 0;
    private static int[] buffers = new int[BUFFER_COUNT];

    private static short[] pcm;        // 交错排列的 16bit PCM
    private static int channels;       // 声道数
    private static int sampleRate;     // 采样率
    private static int alFormat;       // AL_FORMAT_MONO16 / AL_FORMAT_STEREO16
    private static double outPos;      // 输出帧位置（分数，重采样后的时间轴）
    private static int playedBuffers;  // 本次排队以来「已播完」的缓冲区数（算真正出声位置用）
    private static double outputBase;  // 本次排队的起始输出帧（play/resume 时记录）
    private static float speed = 1f;   // 播放速率（OLA 时域拉伸，保调）
    private static boolean finished;   // PCM 已全部排入（等待声卡播完）
    private static boolean playing;    // 用户意图：正在播放
    private static float baseVolume = 1f;   // 命令音量（/playmusic 的 volume 参数），实际增益还要乘游戏内音量
    private static float appliedGain = -1f; // 上次写入 AL_GAIN 的值（-1 = 无效值，强制重写）
    private static String currentId;   // 当前曲目（用于反馈）
	private static long lastPosLogMs;  // 上次位置日志时间戳（诊断）
	private static int alignStreak;    // 连续超阈刻数（自动对齐判定用）
	private static int alignCooldown;  // 对齐后剩余冷却刻数
	private static int alignRefDelta;  // 窗口起点的偏差（判「是否已经稳住」）
	private static int seekResidual;   // 上次 seek 的落点残差（音乐 ms）：实测 delta − 目标
	private static boolean seekPending; // 刚 seek 过，等一次采样去量落点残差
	// 多人用：服务端每刻推送的游戏播放头（单人不用 —— 客户端直接读集成服务端，零延迟）
	private static int pushedHeadMs = -1;
	private static long pushedHeadAt;   // 收到时刻（System.currentTimeMillis）
	// 挂起起播（曲目尚未解码完）：绝不阻塞渲染线程，解完再用「当前播放头」起播
	private static String pendingId;
	private static int pendingStartMs;
	private static float pendingSpeed = 1f;
	private static float pendingVolume = 1f;
	private static long pendingSince;
	private static String lastWarmedId; // 最近一次播放/预热的曲目：资源包重载后自动重新预热它
	private static Object lastResourceManager; // 资源包重载检测（重载后 ResourceManager 为新实例）
    /** 正在预解码的曲目（去重：同一曲目并发解码两次会翻倍内存/GC 抖动，那正是"重进存档首次播放卡一下"的来源） */
    private static final java.util.Set<String> preloading = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // ==================== 对外接口（由网络包在渲染线程调用） ====================

    /** 当前曲目是否与给定 id 匹配（供 /stopsound 判定用）。 */
    public static boolean matches(Identifier id) {
        return currentId != null && id != null && currentId.equals(id.toString());
    }

    /** 是否正在播放（客户端状态）。 */
    public static boolean isPlaying() {
        return playing && pcm != null && sampleRate > 0;
    }

    /** 当前播放速率（保调变速倍率）。 */
    public static float currentSpeed() {
        return speed;
    }

    /**
     * 收到服务端推送的**游戏播放头**（{@code MusicPayloads.HeadPayload}，每刻一个）——多人对齐用。
     * valid=false 表示当前没有可对齐的目标（不在编辑/游玩中、对齐开关关闭、读取异常）。
     */
    public static void setPushedPlayhead(int playheadMs, boolean valid) {
        pushedHeadMs = valid ? Math.max(playheadMs, 0) : -1;
        pushedHeadAt = System.currentTimeMillis();
    }

    /**
     * 真正「出声」的位置（毫秒，源时间轴）；未播放返回 -1。
     *
     * = (本次排队起始输出帧 + 已播完缓冲×缓冲帧数 + 当前缓冲内采样偏移) × speed ÷ 采样率。
     * 与 {@link #queuedMs()} 的差 = 声卡排队深度（诊断用；排队位置天然提前若干缓冲）。
     */
    public static int audibleMs() {
        if (pcm == null || source == 0 || !playing || sampleRate <= 0) {
            return -1;
        }
        int offset = org.lwjgl.openal.AL11.alGetSourcei(source, org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET);
        double frames = outputBase + (double) playedBuffers * BUFFER_SAMPLES + Math.max(0, offset);
        return (int) (frames * speed * 1000.0 / sampleRate);
    }

    /** 已「排队」的位置（毫秒）：比出声位置提前若干缓冲。未播放返回 -1。 */
    public static int queuedMs() {
        if (pcm == null || !playing || sampleRate <= 0) {
            return -1;
        }
        return (int) (outPos * speed * 1000.0 / sampleRate);
    }

    // ==================== 音乐对齐游戏（自动） ====================

    /**
     * 每客户端刻调用一次（只许在 ClientTickEvents 里调，渲染帧回调会多算刻数）：
     * **把音频对齐到游戏播放头** —— 方向是「音乐服从游戏」，播放头一动不动。
     *
     * 目标位置（{@link #gamePlayheadMs()}）：编辑器试听 = maps.editor.playhead；
     * 正式游玩 = play_state.time（歌曲时间轴），两者都是服务端刻、由 {@link MusicTime} 换算成毫秒。
     * 偏差 delta = 出声位置 − 播放头毫秒（同一套源时间轴）。
     *
     * ★ **阶梯窗口 + 刻内相位**：播放头只在每刻跳一次（阶梯），音频却是连续走的 ⇒ 在刻内相位 φ 处采样，
     * delta 的应有值就是 φ×每刻音乐毫秒；而理想对齐（刻边界 delta = 0）会让音频在刻内一路领先到整刻
     * （慢放时可闻），故把目标整体下移半刻（**居中**）：
     * center = (φ−0.5)×每刻音乐毫秒 + 同步偏移，容差 = max({@link #ALIGN_TOLERANCE_MS}, 每刻音乐毫秒/2 + 10)。
     * ⚠️ **量纲**：delta / 播放头 / 音频位置都在**音乐源毫秒**轴上，而 mspt 是**真实毫秒**，
     * 每刻音乐毫秒 = 真实刻长 × 播放速率（speed<1 时真实刻更长，但每刻走过的音乐内容不变）。
     * 若直接拿 mspt 当音乐刻长，慢放时期望领先量会被放大 1/speed 倍（0.25x → 4 倍），
     * 稳态偏差就停在 center+容差（实测 ≈115 音乐毫秒 = 慢放下 460 真实毫秒），听感即「游戏延后」。
     * 判定条件：delta 出窗持续 {@link #ALIGN_STREAK_TICKS} 刻，且这段窗口内 delta 变化 ≤ max({@link #ALIGN_STABLE_DRIFT_MS}, 每刻音乐毫秒)
     * （后者是「采样相位未知」造成的固有摆动，必须容忍，否则慢放/长刻下稳定条件永不成立）
     * （= 已经「定住」，不是正被世界加载/GC 拖着跑）才动手；
     * 修正目标 = 期望位置（delta = center），单次最多挪 {@link #ALIGN_MAX_STEP_MS} ms，
     * 对齐后冷却 {@link #ALIGN_COOLDOWN_TICKS} 刻，避免反复微调产生可闻抖动。
     *
     * 为什么要求「稳住」：开局世界加载时播放头会一时落后于实时音乐（偏差从 −600ms 自己收敛回 0），
     * 那种阶段不该插手；真正要治的是「偏差定死在 100ms 以上」——它一旦定住就永远不会自己回去。
     */
    public static void alignTick() {
        if (!playing || pcm == null || sampleRate <= 0) {
            alignStreak = 0;
            alignCooldown = 0;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.isPaused()) {
            alignStreak = 0;
            return;
        }
        if (alignCooldown > 0) {
            alignCooldown--;
            return;
        }
        int playheadMs = gamePlayheadMs();
        int audible = audibleMs();
        if (playheadMs < 0 || audible < 0) {
            diagTick(mc, audible);   // 对齐关/拿不到播放头 → 照样周期打诊断（标定采样用）
            alignStreak = 0;
            return;
        }
        // ★ 量纲：每刻音乐毫秒 = 真实刻长 × 播放速率（见方法注释）
        int tickMs = currentTickMs(mc);
        int tickSrcMs = (int) Math.max(1, Math.round(tickMs * (double) speed));
        // ★★ 采样相位补偿：播放头是**阶梯值**（本刻刚跳上去、整刻不动），音频却连续走 ⇒ 在刻内相位 φ
        //   处采样，delta 的**应有值**就是 φ×每刻音乐毫秒，而不是 0、更不是「半刻」这个平均值。
        //   （拿平均值当目标的后果：允许偏差窗口整体上移半刻，慢放时“合法领先”被放大成几百 ms 的延后。）
        //   单机直接读服务端调度时钟（nextTickTimeNanos）拿到 φ；多人拿不到 → 退回平均半刻的期望值。
        MinecraftServer server = mc.getSingleplayerServer();
        float phase = server != null ? tickPhase(server) : -1f;   // <0 = 读不到服务端调度时钟
        float effPhase = phase < 0f ? 0.5f : phase;                // 读不到 → 退回「平均半刻」
        // ★ 相位补偿 + **居中**：理想对齐是「刻边界处偏差 0」，但那样在刻内音频会一路领先到整刻
        //   （慢放时每刻的真实时间被拉长 ⇒ 听感就是“音乐抢拍”，最大可达一个 mspt）。
        //   把目标整体下移半刻 ⇒ 音频相位误差在 [−Δ/2, +Δ/2] 摆动，最大提前量减半、听感居中。
        int center = Math.round((effPhase - 0.5f) * tickSrcMs) + syncOffsetApplied();
        // ★ 容差必须 ≥ 半个刻：先前的 20ms 比「期望领先」还小 ⇒ 连 3ms 的正常摆动都被判成偏差，
        //   每一次修正都把音频往前推一次，越修越提前（日志里 [MusicAlign] 偏差 -3ms 就是它）。
        int tol = Math.max(ALIGN_TOLERANCE_MS, Math.round(tickSrcMs / 2f) + 10);
        int stableDrift = Math.max(ALIGN_STABLE_DRIFT_MS, tickSrcMs + 10);
        int delta = audible - playheadMs;
        // ★ 刚 seek 过 → 先量一次「落点残差」：seek 的实际落点与目标之间总有几十毫秒差，
        //   不扣掉的话它会被当成新偏差 ⇒ 每隔几秒再 seek 一次（听感就是“音乐一直在自动调整”）。
        if (seekPending) {
            seekPending = false;
            seekResidual = delta - center;
            alignStreak = 0;
            alignCooldown = Math.max(alignCooldown, ALIGN_COOLDOWN_TICKS);
            return;
        }
        int eff = delta - center - seekResidual;   // 扣掉落点残差后的真实偏差（音乐 ms）
        if (eff >= -tol && eff <= tol) {
            alignStreak = 0;
            return;
        }
        // 偏差出窗：每秒补一行诊断（正常局静默，一出问题就留证据）
        long now = System.currentTimeMillis();
        if (now - lastPosLogMs >= 1000) {
            lastPosLogMs = now;
            LOGGER.info("[MusicPos] 出声={}ms 播放头={}ms delta={}ms 期望={}ms 残差={}ms 有效={}ms 相位={} 每刻音乐={}ms 偏移={}ms speed={}",
                    audible, playheadMs, delta, center, seekResidual, eff, String.format("%.2f", phase), tickSrcMs, syncOffsetApplied(), speed);
        }
        if (alignStreak == 0) {
            alignStreak = 1;
            alignRefDelta = delta;
            return;
        }
        alignStreak++;
        if (alignStreak < ALIGN_STREAK_TICKS) {
            return;
        }
        alignStreak = 0;
        if (Math.abs(delta - alignRefDelta) > stableDrift) {
            return; // 这段窗口内偏差还在大幅变化 → 重新取样，等它稳住再对齐
        }
        // ★ 朝期望位置（delta == center）挪：delta<0（音乐落后）→ 音频往前；delta>0（音乐超前）→ 音频往后。
        //   （曾写成 target = playhead + delta，那等于「挪到它现在的位置」，永远不生效。）
        int correction = eff;
        // 单次位移上限按**真实时间**限幅：慢放时源毫秒被拉长，固定音乐毫秒会跳得越来越久
        int maxStep = (int) Math.max(50, Math.round(ALIGN_MAX_STEP_MS * (double) speed));
        int applied = Math.min(Math.abs(correction), maxStep);
        int target = correction > 0 ? audible - applied : audible + applied;
        if (seekAudioTo(target)) {
            seekPending = true;   // 下一次采样去量落点残差，避免反复 seek
            alignCooldown = ALIGN_COOLDOWN_TICKS;
            LOGGER.info("[MusicAlign] 偏差 {}ms（播放头 {}ms / 出声 {}ms / 期望 {}ms）→ 音频已挪到 {}ms",
                    delta, playheadMs, audible, center, target);
        }
    }

    /**
     * 当前**真实**刻长（ms）：编辑器试听与正式游玩都会把 tick rate 设成「谱面刻长 ÷ 播放速度」，
     * 所以慢放时它变长。对齐窗口不能直接用它的毫秒数（那是真实时间，不是音乐时间），
     * 必须先 × {@link #speed} 换到音乐源时间轴——见 {@link #alignTick()} 的量纲说明。
     */
    private static int currentTickMs(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return 0;
        }
        return (int) Math.max(1, Math.round(server.tickRateManager().millisecondsPerTick()));
    }

    /**
     * 服务端当前刻的**刻内相位** φ ∈ [0,1]：0 = 本刻刚开始，1 = 本刻即将结束。
     *
     * 播放头是阶梯值（整刻不动），音频却连续走 ⇒ 在相位 φ 处采样，delta 的应有值 = φ × 每刻音乐毫秒。
     * **读不到调度时钟**（多人 / Accessor 失效 / 时钟异常）→ 返回 **-1**（哨兵；调用方退回「平均半刻」，
     * 日志里会直接表现为 `相位=-1.00`，别再用 0.5 冒充，那样无法分辨“恰好采在中点”与“根本没读到”）。
     * nextTickTimeNanos 与 System.nanoTime() 同源（服务器主循环就是拿两者比较来开新刻的）。
     */
    private static float tickPhase(MinecraftServer server) {
        try {
            MinecraftServerAccessor acc = (MinecraftServerAccessor) server;
            double mspt = server.tickRateManager().millisecondsPerTick();
            long nsPerTick = (long) (mspt * 1_000_000.0);
            if (nsPerTick <= 0) {
                return -1f;
            }
            long remain = acc.getNextTickTimeNanos() - System.nanoTime();
            float phase = 1f - (float) ((double) remain / (double) nsPerTick);
            if (phase < 0f) {
                phase = 0f;
            }
            if (phase > 1f) {
                phase = 1f;
            }
            return phase;
        } catch (Throwable t) {
            return -1f;
        }
    }

    /**
     * 用户可调的「音频同步偏移」基准（**0.25x 下所需的音乐毫秒**，options 计分板假玩家 {@code audio_sync_offset}；缺失 = 内置默认）。
     *
     * 为什么需要：测量链路里存在固定偏移（OLA 时域拉伸的半窗延迟、音频输出/驱动延迟等），
     * 它让 `delta` 报得比耳朵听到的偏一边 —— 对齐于是朝错误方向使劲（表现为“音乐越修越早/越晚”）。
     * 这个值无法从代码推出来（跟设备与音频实现有关），必须**用耳朵标定**：调它直到听起来同步。
     * 正值 = 目标 delta 变大 = 让音频更早；负值 = 让音频更晚。
     * 游戏内调法：`/scoreboard players set audio_sync_offset options -60`（在 **0.25x** 下调，其它速度会自动按比例缩放）。
     */
    private static int syncOffsetMs() {
        try {
            Minecraft mc = Minecraft.getInstance();
            MinecraftServer server = mc == null ? null : mc.getSingleplayerServer();
            if (server == null) {
                return SYNC_A25_DEFAULT;   // 多人拿不到服务端计分板（多人对齐本来就走推送播放头）
            }
            Objective obj = server.getScoreboard().getObjective("options");
            if (obj == null) {
                return SYNC_A25_DEFAULT;
            }
            ReadOnlyScoreInfo info = server.getScoreboard()
                    .getPlayerScoreInfo(ScoreHolder.forNameOnly("audio_sync_offset"), obj);
            return info == null ? SYNC_A25_DEFAULT : (int) info.value();
        } catch (Throwable t) {
            return SYNC_A25_DEFAULT;
        }
    }

    /**
     * 当前**实际应用**的同步偏移（音乐 ms）。
     *
     * ★ 2026-09-21 用户实测标定结论：补偿需求 **不是常数**，而是随速度连续变化、且 1x 归零——
     *   `-50` 在 0.25x 下正好同步，同值放到 1x 就让音乐明显偏晚 ⇒ 说明所需补偿 ∝ **(1 − speed)**。
     *   物理上对得上 OLA 时域拉伸的固有相位：输出的「最新窗」对应的源位置比报告位置偏
     *   (1−speed)·r（r = 窗内偏移），平均即正比于 (1−speed)。
     *
     * 因此把计分板 `audio_sync_offset` 的语义定为「**0.25x 下需要的补偿**」，
     * 实际应用时按 (1−speed)/(1−0.25) 缩放到当前速度：0.25x → 原值、0.5x → 2/3、0.75x → 1/3、1x → 0。
     * 换设备/换曲若要重新标定，仍只需在 0.25x 下调这一个值。
     */
    private static int syncOffsetApplied() {
        int a = syncOffsetMs();
        if (a == 0) {
            return 0;
        }
        float f = (1f - speed) / 0.75f;   // 0.25x → 1.0；0.5x → 0.667；1x → 0
        if (f < 0f) {
            f = 0f;
        } else if (f > 2f) {
            f = 2f;                        // speed < 0.1 之类的极端值：限幅，别把补偿放大到失控
        }
        return Math.round(a * f);
    }

    /**
     * 对齐被关掉/拿不到播放头时的周期诊断（每 2 秒一行）。
     *
     * 为什么需要：标定期会先把自动对齐关掉（否则它会把偏差拉走，读不到“纯偏差”），
     * 而关掉后 `gamePlayheadMs()` 返回 -1；这里**绕过对齐开关**直接读播放头
     * （{@link MusicTime#gamePlayhead}/{@link MusicTime#headMs} 不看开关），这样日志里仍有数据可采样。
     */
    private static void diagTick(Minecraft mc, int audible) {
        if (audible < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPosLogMs < 2000) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        int tick = MusicTime.gamePlayhead(server);
        if (tick == Integer.MIN_VALUE) {
            return;
        }
        lastPosLogMs = now;
        int phMs = (int) MusicTime.headMs(server, tick);
        LOGGER.info("[MusicPos] (对齐关) 出声={}ms 播放头={}ms delta={}ms speed={} 偏移={}ms",
                audible, phMs, audible - phMs, speed, syncOffsetApplied());
    }

    /**
     * 游戏播放头毫秒（「音乐对齐游戏」的目标位置）：
     * 编辑器试听 = maps.editor.playhead；正式游玩 = play_state.time —— 都由
     * {@link MusicTime#gamePlayheadMs} 从服务端状态读。
     * **单人**：直接读集成服务端（零延迟）；**多人**：用服务端每刻推送的值
     * （{@link #setPushedPlayhead}，超过 {@link #PUSHED_HEAD_TTL_MS} 没更新就当失效）；
     * 都没在跑 / 数据包把对齐开关设为 0 / 音乐尚未开始 → -1（调用方跳过对齐）。
     */
    private static int gamePlayheadMs() {
        try {
            Minecraft mc = Minecraft.getInstance();
            MinecraftServer server = mc == null ? null : mc.getSingleplayerServer();
            if (server != null) {
                return MusicTime.gamePlayheadMs(server);
            }
        } catch (Exception ignored) {
            // 落到推送值
        }
        // ★ ping/2 是**真实毫秒**，播放头是**音乐源毫秒** → 换算后再补偿（慢放同样适用）
        return System.currentTimeMillis() - pushedHeadAt <= PUSHED_HEAD_TTL_MS
                ? pushedHeadMs + (int) Math.round(halfPingMs() * (double) speed) : -1;
    }

    /**
     * 本机 RTT 的一半（ms，上限 {@link #PUSHED_HEAD_PING_CAP_MS}）：补偿推送值的单程延迟。
     *
     * 推送值算的是「服务端发出那一刻」的播放头，包到本地已过约半个 RTT —— 期间游戏时钟也在走，
     * 所以当前播放头 ≈ 推送值 + RTT/2。ping 拿不到时为 0（退化成不补偿，误差半个 RTT，仍在容忍窗口量级）。
     */
    private static int halfPingMs() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.getConnection() == null) {
                return 0;
            }
            var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info == null) {
                return 0;
            }
            return Math.min(Math.max(0, info.getLatency()) / 2, PUSHED_HEAD_PING_CAP_MS);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 把**音频**跳到指定源毫秒（不动播放头、不动 PCM）。用于「音乐对齐游戏」。
     * 定位算法与 {@link #play} 一致（输出帧 = 源帧 / speed），重新排队后从新位置续播。
     */
    private static boolean seekAudioTo(int targetMs) {
        if (pcm == null || sampleRate <= 0) {
            return false;
        }
        ensureAl();
        int frameCount = pcm.length / channels;
        double targetFrame = Math.min(Math.max(0, targetMs) * (double) sampleRate / 1000.0,
                Math.max(0, frameCount - 1));
        boolean wasPlaying = playing;
        AL10.alSourceStop(source);
        unqueueAll();
        outPos = Math.floor(targetFrame / speed);
        outputBase = outPos;   // 出声位置重新以新起点为基准
        playedBuffers = 0;
        finished = false;
        if (wasPlaying) {
            for (int i = 0; i < BUFFER_COUNT; i++) {
                queueNext(buffers[i]);
            }
            AL10.alSourcePlay(source);
        }
        return true;
    }

    /** 播放音效事件。startMs=起始毫秒，speed=播放速率（保调），volume=音量0~1。 */
    public static void play(String soundId, int startMs, float newSpeed, float volume) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getSoundManager() == null) {
                message("§c错误：客户端音频未就绪");
                return;
            }
            maybeClearCacheOnReload(mc);

            // 1. 通过音效事件 id 解析出实际 ogg 文件路径
            WeighedSoundEvents event = mc.getSoundManager().getSoundEvent(Identifier.parse(soundId));
            if (event == null) {
                message("§c错误：找不到音效事件 " + soundId);
                return;
            }
            Sound sound = event.getSound(mc.level == null ? RandomSource.create() : mc.level.getRandom());
            Identifier path = sound.getPath();
            if (path == null) {
                message("§c错误：音效事件 " + soundId + " 没有对应文件");
                return;
            }

            // 2. 缓存命中 → 立即起播；未命中 → **挂起**（绝不阻塞渲染线程，见 pendingTick）
            PcmData data;
            synchronized (cache) {
                data = cache.get(soundId);
            }
            if (data == null) {
                // ★ 老代码在这里 sleep 等预解码完成（或自己同步解一整首）：实测把渲染线程卡住
                //   700ms 以上（重进存档后更久），而且音乐一开口就比播放头晚同样多 → 整首歌都不齐。
                //   改为：起个后台解码线程后立刻返回，解码完在 pendingTick 里起播，
                //   并把「等掉的时间」补进起点，音乐落点自然对得上播放头。
                pendingId = soundId;
                pendingStartMs = startMs;
                pendingSpeed = newSpeed;
                pendingVolume = volume;
                pendingSince = System.currentTimeMillis();
                lastWarmedId = soundId;
                if (!preloading.contains(soundId)) {
                    preload(soundId);   // 没有在途预解码就自己起一个（后台线程）
                }
                message("§e正在解码音乐…（首次播放 / 重进存档后第一次，解码完成后自动开始）");
                return;
            }
            lastWarmedId = soundId;
            pendingId = null;
            startWithData(soundId, data, startMs, newSpeed, volume);
        } catch (Exception e) {
            LOGGER.error("播放音乐失败", e);
            stop();
            message("§c播放失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /**
     * 真正起播（PCM 已就绪）：定位 startMs → 填首批缓冲 → 开声。**必须在渲染线程调用**。
     */
    private static void startWithData(String soundId, PcmData data, int startMs, float newSpeed, float volume) {
        // ★ 起点锚定：包里的 startMs 是「服务端下指令那一刻」算的，等包到客户端已经过了 1~2 刻
        //   （25~50ms），直接用它起点天然偏早。起播这一瞬现取一次播放头更准：
        //   编辑器试听（playhead 刻）与正式游玩（time 刻，time==0 起播）用的是同一套换算，
        //   所以两种模式都能锚定；多人拿不到服务端状态（返回 -1）就用原值。
        //   偏差过大的情形（音乐与游戏播放头本就不在同一时间轴）不锚定，避免把起点拽到无关位置。
        int liveMs = gamePlayheadMs();
        if (liveMs >= 0 && Math.abs(liveMs - startMs) <= 3000) {
            startMs = liveMs;
        }
        // ★ 同步偏移也作用于起播锚点。**符号必须与 alignTick 的约定一致**：
        //   alignTick 把「报告出声位置」拉到 `播放头 + center`（center 含 offset），
        //   而这里的 startMs 就是「报告出声位置」的起点 ⇒ 同样要 **加** offset。
        //   （★ 2026-09-21 修正：原来写成 `startMs - offsetMs`，方向反了 —— offset=−50 时
        //    报告位置落在播放头**之后** 50ms，配上 OLA 固有提前量 β≈+50 就变成“听到的比播放头早 100ms”，
        //    于是每次起播/快进快退都要等自动对齐慢慢拽回来，听感正是“音乐一直在自动调整”。）
        //   这样「播放 / 快进快退（resync 重下 playmusic）/ 暂停→播放」都走同一条对齐好的路径。
        int offsetMs = syncOffsetApplied();
        if (offsetMs != 0) {
            startMs = Math.max(0, startMs + offsetMs);
        }
        pcm = data.pcm;
        channels = data.channels;
        sampleRate = data.sampleRate;
        alFormat = data.alFormat;
        currentId = soundId;

        // 初始化 OpenAL 资源
        ensureAl();
        AL10.alSourceStop(source);
        unqueueAll();

        // 定位到 startMs 并填入首批缓冲（OLA 保调变速）
        speed = Math.max(0.05f, Math.min(newSpeed, 1.9f));
        AL10.alSourcef(source, AL10.AL_PITCH, 1f);
        // ★ 实际增益 = 命令音量 × 游戏内音量设置（见 musicGain()）
        baseVolume = clamp01(volume);
        appliedGain = -1f;   // 新一次播放：强制写入一次
        refreshGain();
        int frameCount = pcm.length / channels;
        double startFrame = Math.min(Math.max(0, startMs) * (double) sampleRate / 1000.0, Math.max(0, frameCount - 1));
        // 输出帧时间轴：源帧 / speed（慢放时输出更长）
        outPos = Math.floor(startFrame / speed);
        outputBase = outPos;   // 出声位置 = outputBase + 已播完缓冲 + 当前缓冲内偏移
        playedBuffers = 0;
        finished = false;
        // 不刷「正在播放...」反馈（seek/播放重开时避免每次刷一条，保持与暂停一致静默）
        for (int i = 0; i < BUFFER_COUNT; i++) {
            queueNext(buffers[i]);
        }
        AL10.alSourcePlay(source);
        playing = true;
        alignStreak = 0;
        alignCooldown = 0;   // 新一次播放：允许自动对齐从头判定
        seekResidual = 0;    // 新一次播放：落点残差重新学
        seekPending = false;
    }

    /**
     * 挂起起播：上次 play 时曲目还没解码完（首次播放 / 重进存档后第一次）。
     * 解码一完成就在当刻起播，并补上「等解码花掉的时间」，让音乐落点贴近当前播放头
     * （补偿只是估算——游戏时钟在卡顿时走得比真实时间慢——剩下的偏差交给 alignTick）。
     */
    private static void pendingTick() {
        if (pendingId == null) {
            return;
        }
        PcmData data;
        synchronized (cache) {
            data = cache.get(pendingId);
        }
        if (data == null) {
            if (System.currentTimeMillis() - pendingSince > 20000) {
                pendingId = null;
                message("§c音乐解码超时，已放弃本次播放");
            }
            return;
        }
        String id = pendingId;
        float sp = pendingSpeed;
        float vol = pendingVolume;
        int elapsed = (int) Math.min(System.currentTimeMillis() - pendingSince, 10000L);
        pendingId = null;
        // 说明：期间若用户按了暂停/停止，pause()/stop() 已经把 pendingId 清掉，走不到这里
        LOGGER.info("[MusicStart] 解码等待 {}ms → 起点补偿后起播（{}ms）", elapsed, pendingStartMs + elapsed);
        startWithData(id, data, pendingStartMs + elapsed, sp, vol);
    }

    /**
     * 每客户端刻调用一次（渲染线程）：资源包重载处理（含重载后自动重新预热曲目）
     * + 挂起起播 + 自动对齐。**只许挂在 ClientTickEvents**——tick() 还会被渲染帧回调调用。
     */
    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        maybeClearCacheOnReload(mc);
        pendingTick();
        alignTick();
    }

    /** 暂停。未播放时无事发生（符合文档）。 */
    public static void pause() {
        if (pcm == null || !playing) {
            pendingId = null;   // 还在解码等待中就被暂停 → 不再补播
            return;
        }
        // ★ 排队位置（outPos）比真正「出声」的位置提前若干缓冲（约 300ms）。停之前必须把 outPos
        //   拉回出声位置：unqueueAll 会丢掉「已排入但还没播出去」的那几个缓冲，若仍按 outPos 续播，
        //   那段音乐就凭空消失 → 续播瞬间音乐前跳（实测暂停一次后偏差 +59~+129ms，音乐超前）。
        double audibleFrames = outputBase + (double) playedBuffers * BUFFER_SAMPLES
                + Math.max(0, org.lwjgl.openal.AL11.alGetSourcei(source, org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET));
        if (audibleFrames > 0) {
            outPos = Math.floor(audibleFrames);
        }
        // 用 alSourceStop 而非 alSourcePause：只有 stop 后 unqueue 才能清掉所有排队缓冲。
        // alSourcePause 会让未播完的缓冲一直留在队列里（alSourceUnqueueBuffers 只能清已播完的），
        // 之后 resume/play 会把这些残留缓冲一起播出来 → 提示音/错位/无声。
        // 播放位置由 outPos 记录，恢复时重新排队即可精确续播。
        AL10.alSourceStop(source);
        unqueueAll();
        playing = false;
        // 不再弹客户端聊天消息：与 playmusic 一致 —— 客户端反馈无法区分执行者，
        // 数据包（编辑器暂停/到尾自动暂停）调用时不该刷聊天栏；玩家手动 /pausemusic 时由服务端 feedbackIfPlayer 反馈。
    }

    /** 继续。未播放时无事发生。 */
    public static void resume() {
        if (pcm == null || playing) {
            return;
        }
        // 上下文可能已重建（设备切换/重载），确保作用于有效 source。
        ensureAl();
        outputBase = outPos;   // 续播：从当前排队位置重新计出声位置
        playedBuffers = 0;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            queueNext(buffers[i]);
        }
        AL10.alSourcePlay(source);
        playing = true;
        alignStreak = 0;
        alignCooldown = 0;   // 续播：重新判定（暂停期间偏差不会漂，通常无需动作）
        // 同上：不弹客户端聊天消息（玩家手动 /resumemusic 由服务端 feedbackIfPlayer 反馈）
    }

    /** 停止并释放音乐。 */
    public static void stop() {
        if (source != 0) {
            AL10.alSourceStop(source);
            unqueueAll();
        }
        pcm = null;
        playing = false;
        finished = false;
        baseVolume = 1f;
        appliedGain = -1f;
        currentId = null;
        alignStreak = 0;
        alignCooldown = 0;
        pendingId = null;
        // 同上：不弹客户端聊天消息（玩家手动 /stopmusic 由服务端 feedbackIfPlayer 反馈）
    }

    /** 每客户端刻调用：回收播完的缓冲并续上后续数据。 */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            // 退出世界自动停
            if (pcm != null) {
                stop();
            }
            return;
        }
        if (source == 0 || pcm == null) {
            return;
        }
        // ★ 跟随游戏内音量设置（玩家拖动「音乐」/「主音量」滑块即时生效）
        refreshGain();
        // ★ 音频设备切换/资源包重载/重进存档 → MC 重建 AL 上下文 → 旧 source 失效。
        //   检测到即重建并从中断处续播（否则切换播放设备后音乐静默停止，不跟随新设备）。
        if (!AL10.alIsSource(source)) {
            reattachOnStaleSource();
        }
        // ★ 游戏暂停（单人暂停菜单/`/tick freeze` 等）时暂停音乐，与 playsound 一致；
        //   恢复时若用户仍想播放则继续（playing 仅表示用户意图，不受此影响）。
        boolean gamePaused = mc.isPaused();
        if (gamePaused) {
            if (playing) {
                AL10.alSourcePause(source);
            }
        } else {
            if (playing && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PAUSED) {
                AL10.alSourcePlay(source);
            }
        }
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        playedBuffers += Math.max(0, processed);   // 已播完的缓冲区计入出声位置
        for (int i = 0; i < processed; i++) {
            int buf = AL10.alSourceUnqueueBuffers(source);
            queueNext(buf);
        }
        // 位置诊断/自动对齐日志改由 alignTick() 输出（出窗时每秒一行；对齐关时每 2 秒一行）—— 正常局静默
        // PCM 已全部排入，等最后几个缓冲播完自动停止
        if (finished) {
            int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
            int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            if (queued == 0 && state == AL10.AL_STOPPED) {
                playing = false;
            }
        }
    }

    /** 在本机聊天栏显示消息（每客户端本地反馈，多人也各自可见）。 */
    private static void message(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }

    /** 解码结果（可缓存）：交错 16bit PCM + 元数据。 */
    private static final class PcmData {
        final short[] pcm;
        final int channels;
        final int sampleRate;
        final int alFormat;

        PcmData(short[] pcm, int channels, int sampleRate, int alFormat) {
            this.pcm = pcm;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.alFormat = alFormat;
        }
    }

    /** 已解码曲目缓存：同一曲目第二次播放零延迟（编辑器反复试听/播放不重复解码） */
    private static final Map<String, PcmData> cache = new HashMap<>();

    /** 资源包重载后清空已解码曲目缓存（让下一次 playmusic 用新音频重新解码，同 playsound 重载行为）。 */
    public static void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    /**
     * 资源包重载（F3+T / /reload / **重进存档**）后 ResourceManager 会换成新实例；
     * 检测到实例变化即清空缓存，否则下一次 playmusic 会命中旧的 PCM 一直播旧音频。
     *
     * ★ 清空后**立刻在后台把上次播过的曲目重新解好**：重进存档后编辑器里第一次播放
     *   本来要现解一整首（渲染线程卡住 → 音乐起步就晚几百 ms），预热后就没这个坑了。
     */
    private static void maybeClearCacheOnReload(Minecraft mc) {
        if (mc == null) {
            return;
        }
        Object rm = mc.getResourceManager();
        if (rm != lastResourceManager) {
            lastResourceManager = rm;
            String warm = lastWarmedId != null ? lastWarmedId : currentId;
            synchronized (cache) {
                cache.clear();
            }
            if (warm != null) {
                preload(warm);   // 后台线程；此处已更新 lastResourceManager，不会再递归进来
            }
        }
    }

    /**
     * 预热解码（非阻塞）：**主线程只做**「解析音效路径 + 把文件读成字节」（毫秒级），
     * 耗时的 OGG 解码放到后台线程 —— 保证不阻塞渲染线程。
     *
     * 同一曲目并发调用会被**去重**：以前每次 preloadmusic 都新起一个线程，
     * 而编辑器「打开谱面」和「每次播放」都会各发一次 ⇒ 两个线程同时解整首，
     * 双份大数组 ⇒ GC 卡顿（实测服务器 "Can't keep up ... 2377ms"）。去重后只剩一次。
     */
    public static void preload(String soundId) {
        synchronized (cache) {
            if (cache.containsKey(soundId)) {
                return;
            }
        }
        if (!preloading.add(soundId)) {
            return; // 已在解码中，直接返回（去重）
        }
        lastWarmedId = soundId;   // 记住它：资源包重载（含重进存档）后自动重新预热
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getSoundManager() == null) {
                preloading.remove(soundId);
                return;
            }
            maybeClearCacheOnReload(mc);
            WeighedSoundEvents event = mc.getSoundManager().getSoundEvent(Identifier.parse(soundId));
            Sound sound = event == null ? null : event.getSound(RandomSource.create());
            Identifier path = sound == null ? null : sound.getPath();
            if (path == null) {
                preloading.remove(soundId);
                return;
            }
            Optional<Resource> res = mc.getResourceManager().getResource(path);
            if (res.isEmpty()) {
                preloading.remove(soundId);
                return;
            }
            byte[] bytes;
            try (InputStream in = res.get().open()) {
                bytes = in.readAllBytes();
            }
            Object rmAtStart = mc.getResourceManager();
            Thread t = new Thread(() -> {
                try (JOrbisAudioStream ogg = new JOrbisAudioStream(new java.io.ByteArrayInputStream(bytes))) {
                    PcmData data = decode(ogg);
                    synchronized (cache) {
                        // 期间资源包重载过（ResourceManager 换新）→ 丢弃本次结果，避免塞回旧音频
                        if (rmAtStart == lastResourceManager) {
                            cache.putIfAbsent(soundId, data);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("预解码失败: {}", soundId, e);
                } finally {
                    preloading.remove(soundId);
                }
            }, "rhythm-axe-preload");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            preloading.remove(soundId);
            LOGGER.warn("预解码准备失败: {}", soundId, e);
        }
    }

    /** 解码整首 OGG 为交错 16bit PCM（纯函数，供同步播放与后台预加载共用）。 */
    private static PcmData decode(JOrbisAudioStream ogg) throws IOException {
        AudioFormat fmt = ogg.getFormat();
        int ch = fmt.getChannels();
        int rate = (int) fmt.getSampleRate();
        int fmtAl = ch == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        if (ch > 2) {
            throw new IOException("不支持超过2声道的音频（" + ch + "声道）");
        }

        FloatArrayList floats = new FloatArrayList(Math.max(16, (int) (rate * 60L * ch)));
        while (ogg.readChunk(floats::add)) {
            if (floats.size() > MAX_DECODE_SAMPLES * ch) {
                throw new IOException("音频过长（超过20分钟），放弃解码");
            }
        }
        if (floats.isEmpty()) {
            throw new IOException("音频解码结果为空");
        }

        short[] out = new short[floats.size()];
        for (int i = 0; i < floats.size(); i++) {
            float f = floats.getFloat(i);
            f = Math.max(-1f, Math.min(1f, f));
            out[i] = (short) (f * 32767f);
        }
        return new PcmData(out, ch, rate, fmtAl);
    }

    /** 生成新的 source 与缓冲（上下文重建后旧句柄失效，需重建）。 */
    private static void createSourceBuffers() {
        source = AL10.alGenSources();
        AL10.alGenBuffers(buffers);
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSourcef(source, AL10.AL_GAIN, 1f);
        AL10.alSourcef(source, AL10.AL_PITCH, 1f);
        appliedGain = -1f;   // 新 source 增益回到 1.0 → 下一次 refreshGain 必须重写
    }

    // ==================== 音量跟随游戏设置 ====================

    /**
     * 游戏内音量设置里的**唱片机/音符盒**音量（0~1）= 「唱片机/音符盒」滑块 × 「主音量」滑块。
     *
     * ★ 为什么用 RECORDS 而不是 MUSIC（2026-09-21 实测踩坑后改）：谱面音乐是**玩法必需音频**
     *   （试听/游玩全靠它），而玩家几乎都会把「音乐」关掉来屏蔽原版背景音乐——若跟「音乐」，
     *   谱面音乐就一起消失了。playmusic 本就归属 **record 通道**（`/stopsound record` 能停它，
     *   见 SoundManagerMixin），所以音量跟「唱片机/音符盒」最贴切：可单独调、默认 100%、
     *   且关「音乐」不影响它；关「主音量」仍会全部静音（符合“总音量”直觉）。
     *
     * ★ 为什么必须自己乘：本播放器直接用 OpenAL 播放（不走 SoundManager/SoundEngine），
     *   而原版的音量分类是在 {@code SoundEngine.calculateVolume} 里乘上去的
     *   （{@code Options.getFinalSoundSourceVolume} = 分类音量 × 主音量；MASTER 自身除外）
     *   ——我们绕过了那一层，不乘就等于永远满音量：音量拖到 0 也照样出声。
     *
     * 音量为 0 时**不停止播放**（alSource 继续推进），只是增益为 0 —— 这样玩家把音量调回来
     * 就能立刻接着听，而音乐与谱面播放头的对齐关系也不会被破坏。
     */
    private static float musicGain() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return 1f;   // 音量设置拿不到（启动早期等）→ 按满音量，不误静音
        }
        return clamp01(mc.options.getFinalSoundSourceVolume(SoundSource.RECORDS));
    }

    /** 把「命令音量 × 游戏音量设置」写入声卡增益（值没变就不重复调用）。 */
    private static void refreshGain() {
        if (source == 0) {
            return;
        }
        float gain = clamp01(baseVolume * musicGain());
        if (Math.abs(gain - appliedGain) < 0.001f) {
            return;
        }
        appliedGain = gain;
        AL10.alSourcef(source, AL10.AL_GAIN, gain);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 惰性创建 OpenAL source 与缓冲。 */
    private static void ensureAl() {
        // 重载资源包(/reload)、重进存档、退出编辑器再进入后，OpenAL 上下文可能被重建，
        // 旧的 source/buffers 变成失效句柄；此时若仍用旧 source 去 alSourcePlay 会静默无声。
        // 用 alIsSource 检测失效则重新生成，保证每次 play 都作用于有效 source。
        if (source == 0 || !AL10.alIsSource(source)) {
            createSourceBuffers();
        }
    }

    /** 上下文重建（音频设备切换/重载/重进存档）后旧 source 失效：重建并按 outPos 无缝续播。 */
    private static void reattachOnStaleSource() {
        createSourceBuffers();
        // queueNext 完全由 outPos 决定（无累计状态），重建后可从中断处无缝继续。
        for (int i = 0; i < BUFFER_COUNT; i++) {
            queueNext(buffers[i]);
        }
        if (playing) {
            AL10.alSourcePlay(source);
        }
    }

    /** 清空声卡排队的所有缓冲。 */
    private static void unqueueAll() {
        if (source == 0) {
            return;
        }
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        while (queued-- > 0) {
            AL10.alSourceUnqueueBuffers(source);
        }
    }

    /**
     * 生成一个缓冲的输出帧并排队（OLA 时域拉伸，保调）。
     * 输出块 [b0, b0+BUFFER_SAMPLES) 由窗索引 i ∈ [iLo, iHi] 覆盖；
     * 窗 i 的源起点 si = i*HOP*speed（完全由 i 决定，无累计状态，块间无缝拼接）。
     * 窗内样本用 8 点 windowed-sinc 插值（音高不变），乘正弦乘积窗 w²，
     * 最后除以 Σw² 归一化（1x 时精确重建、零失真）。AL_PITCH 恒为 1。
     */
    private static void queueNext(int buf) {
        if (pcm == null || finished) {
            return;
        }
        int frameCount = pcm.length / channels;
        int blockLen = BUFFER_SAMPLES;
        long b0 = (long) Math.floor(outPos);
        float[] accum = new float[blockLen * channels];
        float[] wsum = new float[blockLen];
        int iLo = (int) Math.floor((b0 - WIN) / (double) HOP) + 1;
        int iHi = (int) Math.floor((b0 + blockLen - 1) / (double) HOP);
        boolean anyWeight = false;
        for (int i = iLo; i <= iHi; i++) {
            double si = (double) i * HOP * speed; // 窗源起点（源帧，分数）
            long o0 = (long) i * HOP;             // 窗输出起点
            for (int n = 0; n < blockLen; n++) {
                long o = (b0 + n) - o0;           // 窗内偏移
                if (o < 0 || o >= WIN) {
                    continue;
                }
                float w = (float) Math.sin(Math.PI * o / WIN); // 正弦窗（50% 重叠 Σw²≡1）
                if (w <= 1e-6f) {
                    continue;
                }
                double fp = si + o;               // 源位置（分数帧）
                if (fp < 0 || fp >= frameCount - 1) {
                    continue;
                }
                int i0 = (int) fp;
                float t = (float) (fp - i0);
                float w2 = w * w;                 // 乘积窗（分析窗×合成窗）
                for (int c = 0; c < channels; c++) {
                    float s = 0f;
                    float norm = 0f;
                    for (int k = -3; k <= 4; k++) {
                        int idx = i0 + k;
                        if (idx < 0 || idx >= frameCount) {
                            continue;
                        }
                        float d = t - k;
                        float ww;
                        if (d > -0.0001f && d < 0.0001f) {
                            ww = 1f;
                        } else {
                            ww = (float) (Math.sin(Math.PI * d) / (Math.PI * d));
                            ww *= (float) (0.5 + 0.5 * Math.cos(Math.PI * d / 4.0)); // Hann 窗（频域）
                        }
                        s += pcm[idx * channels + c] * ww;
                        norm += ww;
                    }
                    float v = norm > 0.0001f ? s / norm : 0f;
                    accum[n * channels + c] += v * w2;
                }
                wsum[n] += w2;
                anyWeight = true;
            }
        }
        outPos += blockLen;
        if (!anyWeight) {
            // 所有窗都已越过源末尾：本块全静音，PCM 排完
            finished = true;
            return;
        }
        ByteBuffer bb = ByteBuffer.allocateDirect(BUFFER_SAMPLES * channels * 2).order(ByteOrder.nativeOrder());
        ShortBuffer sb = bb.asShortBuffer();
        for (int n = 0; n < blockLen; n++) {
            float ws = wsum[n];
            for (int c = 0; c < channels; c++) {
                float v = ws > 1e-9f ? accum[n * channels + c] / ws : 0f;
                sb.put((short) (Math.max(-32768f, Math.min(32767f, v))));
            }
        }
        sb.flip();
        AL10.alBufferData(buf, alFormat, sb, sampleRate);
        AL10.alSourceQueueBuffers(source, buf);
    }
}
