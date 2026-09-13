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
import name.rhythm_axe_mod.music.MusicTime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;

/**
 * 编辑器音乐播放器（阶段0，多人版）。
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

    // ── 「音乐对齐游戏」自动对齐参数（音乐服从游戏，永不改动播放头） ──
    /** 偏差容忍（ms，≈1.6 刻）：小于此值不动音频。 */
    private static final int ALIGN_TOLERANCE_MS = 40;
    /** 需连续超阈的刻数（0.5s）才动手：避开「开局世界加载」那种一过性震荡。 */
    private static final int ALIGN_STREAK_TICKS = 20;
    /** 窗口内偏差变化超过此值 = 还在被世界加载/GC 拖着跑 → 本次不对齐，重新取样。 */
    private static final int ALIGN_STABLE_DRIFT_MS = 30;
    /** 单次最大位移（ms）：大偏差分几次收敛，避免一次跳掉一大段音乐。 */
    private static final int ALIGN_MAX_STEP_MS = 400;
    /** 对齐后的冷却刻数（0.5s）：避免反复微调造成的可闻抖动。 */
    private static final int ALIGN_COOLDOWN_TICKS = 20;

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
    private static String currentId;   // 当前曲目（用于反馈）
	private static long lastPosLogMs;  // 上次位置日志时间戳（诊断）
	private static int alignStreak;    // 连续超阈刻数（自动对齐判定用）
	private static int alignCooldown;  // 对齐后剩余冷却刻数
	private static int alignRefDelta;  // 窗口起点的偏差（判「是否已经稳住」）
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
     * 偏差 = 出声位置 − 播放头毫秒（同一套源时间轴，见 {@link MusicTime}）。
     * 判定条件：|偏差| 持续 {@link #ALIGN_STREAK_TICKS} 刻超 {@link #ALIGN_TOLERANCE_MS} ms，
     * 且这 0.5s 内偏差变化 ≤ {@link #ALIGN_STABLE_DRIFT_MS} ms（= 已经「定住」，
     * 不是正被世界加载/GC 拖着跑）才动手；单次最多挪 {@link #ALIGN_MAX_STEP_MS} ms，
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
        int playheadMs = editorPlayheadMs();
        int audible = audibleMs();
        if (playheadMs < 0 || audible < 0) {
            alignStreak = 0;
            return;
        }
        int delta = audible - playheadMs;
        if (Math.abs(delta) <= ALIGN_TOLERANCE_MS) {
            alignStreak = 0;
            return;
        }
        // 偏差超阈：每秒补一行诊断（正常局静默，一出问题就留证据）
        long now = System.currentTimeMillis();
        if (now - lastPosLogMs >= 1000) {
            lastPosLogMs = now;
            LOGGER.info("[MusicPos] 出声={}ms 播放头={}ms delta={}ms 已排队={}ms speed={}",
                    audible, playheadMs, delta, queuedMs(), speed);
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
        if (Math.abs(delta - alignRefDelta) > ALIGN_STABLE_DRIFT_MS) {
            return; // 这 0.5s 内偏差还在大幅变化 → 重新取样，等它稳住再对齐
        }
        // ★ 朝播放头方向挪：delta<0（音乐落后）→ 音频往前；delta>0（音乐超前）→ 音频往后。
        //   （曾写成 target = playhead + delta，那等于「挪到它现在的位置」，永远不生效。）
        int applied = Math.min(Math.abs(delta), ALIGN_MAX_STEP_MS);
        int target = delta > 0 ? audible - applied : audible + applied;
        if (seekAudioTo(target)) {
            alignCooldown = ALIGN_COOLDOWN_TICKS;
            LOGGER.info("[MusicAlign] 偏差 {}ms（播放头 {}ms / 出声 {}ms）→ 音频已挪到 {}ms，剩余 {}ms",
                    delta, playheadMs, audible, target, playheadMs - target);
        }
    }

    /**
     * 编辑器播放头毫秒。**只有单人集成服务端**才读（多人没有该存储，直接放弃对齐）；
     * 未打开编辑器 / 数据包把 audio_align 设为 0b → -1。
     */
    private static int editorPlayheadMs() {
        try {
            Minecraft mc = Minecraft.getInstance();
            MinecraftServer server = mc == null ? null : mc.getSingleplayerServer();
            return server == null ? -1 : MusicTime.editorPlayheadMs(server);
        } catch (Exception e) {
            return -1;
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
        //   （25~50ms），直接用它起点天然偏早。起播这一瞬现取一次播放头更准（编辑器外拿不到就用原值）。
        //   偏差过大的情形（如游玩模式音乐有自己的时间轴）不锚定，避免把起点拽到无关位置。
        int liveMs = editorPlayheadMs();
        if (liveMs >= 0 && Math.abs(liveMs - startMs) <= 3000) {
            startMs = liveMs;
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
        AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0f, Math.min(volume, 1f)));
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
        message("§a音乐已暂停");
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
        message("§a音乐已继续");
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
        currentId = null;
        alignStreak = 0;
        alignCooldown = 0;
        pendingId = null;
        message("§a音乐已停止");
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
        // 位置诊断/自动对齐日志改由 alignTick() 输出（只在偏差 >40ms 时打印，正常局静默）
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
        if (mc != null && mc.gui != null) {
            mc.gui.getChat().addClientSystemMessage(Component.literal(msg));
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
