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
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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

    private static int source = 0;
    private static int[] buffers = new int[BUFFER_COUNT];

    private static short[] pcm;        // 交错排列的 16bit PCM
    private static int channels;       // 声道数
    private static int sampleRate;     // 采样率
    private static int alFormat;       // AL_FORMAT_MONO16 / AL_FORMAT_STEREO16
    private static double outPos;      // 输出帧位置（分数，重采样后的时间轴）
    private static float speed = 1f;   // 播放速率（OLA 时域拉伸，保调）
    private static boolean finished;   // PCM 已全部排入（等待声卡播完）
    private static boolean playing;    // 用户意图：正在播放
    private static String currentId;   // 当前曲目（用于反馈）
	private static long lastPosLogMs;  // 上次位置日志时间戳（诊断）
	private static Object lastResourceManager; // 资源包重载检测（重载后 ResourceManager 为新实例）
    // ==================== 对外接口（由网络包在渲染线程调用） ====================

    /** 当前曲目是否与给定 id 匹配（供 /stopsound 判定用）。 */
    public static boolean matches(Identifier id) {
        return currentId != null && id != null && currentId.equals(id.toString());
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

            // 2. 读出并全量解码 OGG（缓存命中零延迟；未命中同步解码并缓存）
            PcmData data;
            synchronized (cache) {
                data = cache.get(soundId);
            }
            if (data == null) {
                Optional<Resource> res = mc.getResourceManager().getResource(path);
                if (res.isEmpty()) {
                    message("§c错误：找不到音频文件 " + path);
                    return;
                }
                try (InputStream in = res.get().open(); JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
                    data = decode(ogg);
                }
                synchronized (cache) {
                    cache.put(soundId, data);
                }
            }
            pcm = data.pcm;
            channels = data.channels;
            sampleRate = data.sampleRate;
            alFormat = data.alFormat;
            currentId = soundId;

            // 3. 初始化 OpenAL 资源
            ensureAl();
            AL10.alSourceStop(source);
            unqueueAll();

            // 4. 定位到 startMs 并填入首批缓冲（OLA 保调变速）
            speed = Math.max(0.05f, Math.min(newSpeed, 1.9f));
            AL10.alSourcef(source, AL10.AL_PITCH, 1f);
            AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0f, Math.min(volume, 1f)));
            int frameCount = pcm.length / channels;
            double startFrame = Math.min(Math.max(0, startMs) * (double) sampleRate / 1000.0, Math.max(0, frameCount - 1));
            // 输出帧时间轴：源帧 / speed（慢放时输出更长）
            outPos = Math.floor(startFrame / speed);
            finished = false;
            // 不刷「正在播放...」反馈（seek/播放重开时避免每次刷一条，保持与暂停一致静默）
            for (int i = 0; i < BUFFER_COUNT; i++) {
                queueNext(buffers[i]);
            }
            AL10.alSourcePlay(source);
            playing = true;
        } catch (Exception e) {
            LOGGER.error("播放音乐失败", e);
            stop();
            message("§c播放失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** 暂停。未播放时无事发生（符合文档）。 */
    public static void pause() {
        if (pcm == null || !playing) {
            return;
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
        for (int i = 0; i < BUFFER_COUNT; i++) {
            queueNext(buffers[i]);
        }
        AL10.alSourcePlay(source);
        playing = true;
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
        for (int i = 0; i < processed; i++) {
            int buf = AL10.alSourceUnqueueBuffers(source);
            queueNext(buf);
        }
		// 每秒输出一次当前位置日志（诊断音乐推进速度；音乐位置 = outPos×speed×1000/sampleRate）
		if (playing) {
			long now = System.currentTimeMillis();
			if (now - lastPosLogMs >= 1000) {
				lastPosLogMs = now;
				LOGGER.info("[MusicPos] outPos={} speed={} sampleRate={} → 音乐位置≈{}ms",
						outPos, speed, sampleRate, (long) (outPos * speed * 1000.0 / sampleRate));
			}
		}
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
     * 资源包重载（F3+T / /reload）后 ResourceManager 会换成新实例；
     * 检测到实例变化即清空缓存，否则下一次 playmusic 会命中旧的 PCM 一直播旧音频。
     */
    private static void maybeClearCacheOnReload(Minecraft mc) {
        Object rm = mc.getResourceManager();
        if (rm != lastResourceManager) {
            lastResourceManager = rm;
            synchronized (cache) {
                cache.clear();
            }
        }
    }

    /**
     * 后台预解码（preloadmusic 命令触发）：解码完放入缓存，
     * 之后的 playmusic 直接命中缓存，消除整首解码的启动延迟（实测整首解码约 0.1s）。
     */
    public static void preload(String soundId) {
        synchronized (cache) {
            if (cache.containsKey(soundId)) {
                return;
            }
        }
        Thread t = new Thread(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null || mc.getSoundManager() == null) {
                    return;
                }
                maybeClearCacheOnReload(mc);
                WeighedSoundEvents event = mc.getSoundManager().getSoundEvent(Identifier.parse(soundId));
                if (event == null) {
                    return;
                }
                Sound sound = event.getSound(RandomSource.create());
                Identifier path = sound.getPath();
                if (path == null) {
                    return;
                }
                Optional<Resource> res = mc.getResourceManager().getResource(path);
                if (res.isEmpty()) {
                    return;
                }
                try (InputStream in = res.get().open(); JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
                    PcmData data = decode(ogg);
                    synchronized (cache) {
                        cache.putIfAbsent(soundId, data);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("预解码失败: {}", soundId, e);
            }
        }, "rhythm-axe-preload");
        t.setDaemon(true);
        t.start();
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
