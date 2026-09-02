package name.rhythm_axe_mod.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 MinecraftServer 中控制 tick 调度的私有字段 {@code nextTickTimeNanos}，
 * 用于在 /tick rate 命令执行后立即调整下一 tick 的计时。
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    /**
     * 下一 tick 的预期开始时间（纳秒时间戳，配合 System.nanoTime()）。
     * 服务器主循环用此值与当前时间比较来决定是否开始新 tick。
     * 在 Minecraft 26.1 (Mojang mapping) 中此字段名为 nextTickTimeNanos。
     */
    @Accessor("nextTickTimeNanos")
    long getNextTickTimeNanos();

    @Accessor("nextTickTimeNanos")
    void setNextTickTimeNanos(long nextTickTimeNanos);
}
