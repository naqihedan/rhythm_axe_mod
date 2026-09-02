package name.rhythm_axe_mod.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.TickCommand;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionProviderCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.Map;

import name.rhythm_axe_mod.RhythmAxeMod;
import name.rhythm_axe_mod.TickrateState;

@Mixin(TickCommand.class)
public class TickCommandMixin {

    private static final SimpleCommandExceptionType ERROR_INVALID_FORMAT =
        new SimpleCommandExceptionType(Component.literal("格式错误。请使用: <数字> (每秒刻, 1~1000), <数字>t (每秒刻, 1~1000), <数字>ms (每刻毫秒, 1~1000), 或 <数字>bpm <tpb> (拍每分钟和每拍刻数)"));
    private static final SimpleCommandExceptionType ERROR_BPM_NEEDS_TPB =
        new SimpleCommandExceptionType(Component.literal("BPM格式需要指定tpb参数。例如: tick rate 150bpm 8"));
    private static final SimpleCommandExceptionType ERROR_T_MS_HAS_TPB =
        new SimpleCommandExceptionType(Component.literal("t/ms格式不需要tpb参数。例如: tick rate 20t 或 tick rate 50ms"));
    private static final SimpleCommandExceptionType ERROR_RATE_TOO_HIGH =
        new SimpleCommandExceptionType(Component.literal("速率不能超过每秒1000刻"));

    // ── 1. 权限重定向 ──────────────────────────────────
    @Redirect(method = "register(Lcom/mojang/brigadier/CommandDispatcher;)V",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/Commands;hasPermission(Lnet/minecraft/server/permissions/PermissionCheck;)Lnet/minecraft/server/permissions/PermissionProviderCheck;"))
    private static PermissionProviderCheck<CommandSourceStack> redirectPermission(PermissionCheck permissionCheck) {
        if (permissionCheck == Commands.LEVEL_ADMINS) {
            return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
        }
        return Commands.hasPermission(permissionCheck);
    }

    // ── 2. 同步客户端速率 ──────────────────────────────
    @Inject(method = "setTickingRate(Lnet/minecraft/commands/CommandSourceStack;F)I",
            at = @At("HEAD"))
    private static void beforeSetTickingRate(CommandSourceStack source, float rate, CallbackInfoReturnable<Integer> cir) {
        // 备用来处理其他 mod/指令 通过原版 setTickingRate 设置速率的情况
        oldMsPerTickForInject = (long) source.getServer().tickRateManager().millisecondsPerTick();
    }

    @Inject(method = "setTickingRate(Lnet/minecraft/commands/CommandSourceStack;F)I",
            at = @At("RETURN"))
    private static void afterSetTickingRate(CommandSourceStack source, float rate, CallbackInfoReturnable<Integer> cir) {
        TickrateState.setClientTickRate(rate);
        // 通过 Accessor 立即调整计时字段（纳秒精度）
        adjustTimingDirectly(source);
    }

    /**
     * 通过 Accessor 用增量法调整 nextTickTimeNanos。
     * 不依赖绝对时间零点（不受 System.nanoTime vs System.currentTimeMillis 差异影响），
     * 只按新旧速率的差值平移时间线。
     */
    private static void adjustTimingDirectly(CommandSourceStack source) {
        try {
            MinecraftServerAccessor srv = (MinecraftServerAccessor) source.getServer();
            // 计算增量（毫秒），由调用方在 setTickRate 前后传入 oldMsPerTickForInject
            long deltaNs = ((long) (source.getServer().tickRateManager().millisecondsPerTick()) - oldMsPerTickForInject) * 1_000_000L;
            if (deltaNs != 0) {
                srv.setNextTickTimeNanos(srv.getNextTickTimeNanos() + deltaNs);
                if (!timingFieldFound) {
                    timingFieldFound = true;
                    RhythmAxeMod.LOGGER.info("[TickRate] 计时字段 MinecraftServer.nextTickTimeNanos 已定位，增量 {}ms ({}ns)", deltaNs / 1_000_000, deltaNs);
                }
            }
        } catch (Exception e) {
            if (!timingFieldFound) {
                RhythmAxeMod.LOGGER.warn("[TickRate] Accessor 失败，回退到反射模式");
                timingFieldFound = true;
            }
        }
    }

    /** 备用旧速率捕获，用于拦截原版 setTickingRate 的路径 */
    private static long oldMsPerTickForInject;

    /**
     * 尝试通过反射找到服务器内部计时字段(nextTickTime)并调整。
     * Minecraft 26.1 中该字段可能存在于 MinecraftServer 或 TickRateManager。
     * 若找不到匹配字段则静默跳过，不影响正常功能。
     */
    private static boolean timingFieldFound = false;
    private static String timingFieldLocation = null;

    private static void tryAdjustTiming(CommandSourceStack source, long delta) {
        try {
            // 先尝试 MinecraftServer
            Object server = source.getServer();
            if (tryAdjustField(server, delta, "MinecraftServer")) return;

            // 再尝试 TickRateManager
            Object mgr = source.getServer().tickRateManager();
            tryAdjustField(mgr, delta, "TickRateManager");
        } catch (Exception ignored) {}
    }

    /** 在给定对象中查找 long 类型的计时字段并调整 */
    private static boolean tryAdjustField(Object target, long delta, String location) {
        // 遍历类及其所有父类的 declared 字段
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (field.getType() == long.class) {
                    String name = field.getName().toLowerCase();
                    // 匹配包含 tick 和 (next 或 scheduled 或 wait 或 task) 的字段
                    if (name.contains("tick") && (name.contains("next") || name.contains("scheduled") || name.contains("wait") || name.contains("task"))) {
                        try {
                            field.setAccessible(true);
                            field.setLong(target, field.getLong(target) + delta);
                            if (!timingFieldFound) {
                                timingFieldFound = true;
                                timingFieldLocation = clazz.getSimpleName() + "." + field.getName();
                                RhythmAxeMod.LOGGER.info("[TickRate] 计时字段已定位: {}, 速率调整可立即生效", timingFieldLocation);
                            }
                            return true;
                        } catch (IllegalAccessException e) {
                            return false;
                        }
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }

        // 未找到：列出所有类层次中的 long 字段
        if (!timingFieldFound && location.contains("MinecraftServer")) {
            timingFieldFound = true;
            StringBuilder sb = new StringBuilder("[TickRate] 未找到计时字段。MinecraftServer 类层级中所有 long 字段:\n");
            clazz = target.getClass();
            while (clazz != null) {
                boolean hasLong = false;
                StringBuilder line = new StringBuilder("  " + clazz.getSimpleName() + ": ");
                for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                    if (field.getType() == long.class) {
                        line.append(field.getName()).append(", ");
                        hasLong = true;
                    }
                }
                if (hasLong) {
                    sb.append(line).append("\n");
                }
                clazz = clazz.getSuperclass();
            }
            RhythmAxeMod.LOGGER.warn(sb.toString());
        }
        return false;
    }

    // ── 3. 注册完成后替换 rate 参数 ─────────────────────
    @SuppressWarnings("unchecked")
    @Inject(method = "register(Lcom/mojang/brigadier/CommandDispatcher;)V", at = @At("RETURN"))
    private static void afterRegister(CommandDispatcher<CommandSourceStack> dispatcher, CallbackInfo ci) {
        try {
            CommandNode<CommandSourceStack> tickNode = dispatcher.getRoot().getChild("tick");
            if (tickNode == null) return;
            CommandNode<CommandSourceStack> rateNode = tickNode.getChild("rate");
            if (rateNode == null) return;

            Field childrenField = CommandNode.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            Map<String, CommandNode<CommandSourceStack>> children =
                (Map<String, CommandNode<CommandSourceStack>>) childrenField.get(rateNode);

            // 同时清理 arguments 映射（Brigadier 用这里做参数匹配）
            Field argumentsField = CommandNode.class.getDeclaredField("arguments");
            argumentsField.setAccessible(true);
            Map<String, ArgumentCommandNode<CommandSourceStack, ?>> arguments =
                (Map<String, ArgumentCommandNode<CommandSourceStack, ?>>) argumentsField.get(rateNode);

            // 去掉原版 FloatArgumentType 参数（从两个映射中都移除）
            children.remove("rate");
            arguments.remove("rate");

            // 构建我们的新参数节点
            ArgumentCommandNode<CommandSourceStack, ?> customArg =
                (ArgumentCommandNode<CommandSourceStack, ?>) Commands.argument("rateSpec", StringArgumentType.word())
                    .executes(ctx -> {
                        String spec = StringArgumentType.getString(ctx, "rateSpec");
                        return handleRateCommand(ctx.getSource(), spec, true);
                    })
                    .then(Commands.argument("sync", BoolArgumentType.bool())
                        .executes(ctx -> {
                            String spec = StringArgumentType.getString(ctx, "rateSpec");
                            boolean sync = BoolArgumentType.getBool(ctx, "sync");
                            return handleRateCommand(ctx.getSource(), spec, sync);
                        })
                    )
                    .then(Commands.argument("tpb", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            String spec = StringArgumentType.getString(ctx, "rateSpec");
                            int tpb = IntegerArgumentType.getInteger(ctx, "tpb");
                            return handleBpmCommand(ctx.getSource(), spec, tpb, 1.0f, true);
                        })
                        .then(Commands.argument("mult", FloatArgumentType.floatArg(0.01f, 100f))
                            .executes(ctx -> {
                                String spec = StringArgumentType.getString(ctx, "rateSpec");
                                int tpb = IntegerArgumentType.getInteger(ctx, "tpb");
                                float mult = FloatArgumentType.getFloat(ctx, "mult");
                                return handleBpmCommand(ctx.getSource(), spec, tpb, mult, true);
                            })
                            .then(Commands.argument("sync", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    String spec = StringArgumentType.getString(ctx, "rateSpec");
                                    int tpb = IntegerArgumentType.getInteger(ctx, "tpb");
                                    float mult = FloatArgumentType.getFloat(ctx, "mult");
                                    boolean sync = BoolArgumentType.getBool(ctx, "sync");
                                    return handleBpmCommand(ctx.getSource(), spec, tpb, mult, sync);
                                })
                            )
                        )
                        .then(Commands.argument("sync", BoolArgumentType.bool())
                            .executes(ctx -> {
                                String spec = StringArgumentType.getString(ctx, "rateSpec");
                                int tpb = IntegerArgumentType.getInteger(ctx, "tpb");
                                boolean sync = BoolArgumentType.getBool(ctx, "sync");
                                return handleBpmCommand(ctx.getSource(), spec, tpb, 1.0f, sync);
                            })
                        )
                    )
                    .build();

            // 同时添加到 children 和 arguments 映射
            children.put("rateSpec", customArg);
            arguments.put("rateSpec", customArg);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── 4. t / ms / 无单位 格式处理 ────────────────────
    private static int handleRateCommand(CommandSourceStack source, String spec, boolean sync) throws CommandSyntaxException {
        // bpm 格式不允许走这里
        String lower = spec.toLowerCase();
        if (lower.endsWith("bpm")) {
            if (spec.length() > 3) {
                throw ERROR_BPM_NEEDS_TPB.create();
            }
            throw ERROR_INVALID_FORMAT.create();
        }

        float rate = parseRateFromSpec(spec);
        String feedback = buildFeedback(spec, rate, sync);

        // 设置新速率 + 立即调整计时（增量法）
        oldMsPerTickForInject = (long) source.getServer().tickRateManager().millisecondsPerTick();
        source.getServer().tickRateManager().setTickRate(rate);
        adjustTimingDirectly(source);

        source.sendSuccess(() -> Component.literal(feedback), true);
        if (sync) {
            TickrateState.setClientTickRate(rate);
        } else {
            TickrateState.setClientTickRate(20.0f);
        }
        return (int) rate;
    }

    // ── 5. bpm + tpb + 倍率 格式处理 ──────────────────────────
    private static int handleBpmCommand(CommandSourceStack source, String spec, int tpb, float mult, boolean sync) throws CommandSyntaxException {
        String lower = spec.toLowerCase();
        if (!lower.endsWith("bpm")) {
            throw ERROR_T_MS_HAS_TPB.create();
        }

        String numStr = spec.substring(0, spec.length() - 3);
        float bpm;
        try {
            bpm = Float.parseFloat(numStr);
        } catch (NumberFormatException e) {
            throw ERROR_INVALID_FORMAT.create();
        }

        if (bpm <= 0 || tpb <= 0 || mult <= 0) {
            throw ERROR_INVALID_FORMAT.create();
        }

        // msPerTick 保留完整精度；倍率 = 播放速度（如 0.25x → tick rate 同步缩放）
        float msPerTick = (float)(1000.0 / (bpm * tpb / 60.0 * mult));
        float rate = 1000.0f / msPerTick;

        if (rate > 1000f) {
            throw ERROR_RATE_TOO_HIGH.create();
        }

        String syncStatus = sync ? "，客户端同步" : "，客户端已重置为20tps";
        String feedback = String.format(
            "游戏刻速率已改为%.2fms，%sbpm，%dtpb，×%.2f倍速，折合每秒%.1f刻%s",
            msPerTick, formatBpm(bpm), tpb, mult, rate, syncStatus
        );

        // 设置新速率 + 立即调整计时（增量法）
        oldMsPerTickForInject = (long) source.getServer().tickRateManager().millisecondsPerTick();
        source.getServer().tickRateManager().setTickRate(rate);
        adjustTimingDirectly(source);

        source.sendSuccess(() -> Component.literal(feedback), true);
        if (sync) {
            TickrateState.setClientTickRate(rate);
        } else {
            TickrateState.setClientTickRate(20.0f);
        }
        return (int) rate;
    }

    /** 如实反馈 BPM：整数不带小数位，小数完整显示（如 100 / 128.57）。 */
    private static String formatBpm(float bpm) {
        if (bpm == Math.rint(bpm)) {
            return String.valueOf((long) bpm);
        }
        return String.valueOf(bpm);
    }

    // ── 6. 解析 ────────────────────────────────────────
    private static float parseRateFromSpec(String spec) throws CommandSyntaxException {
        String lower = spec.toLowerCase();
        if (lower.endsWith("t")) {
            try {
                float rate = Float.parseFloat(spec.substring(0, spec.length() - 1));
                if (rate < 1f || rate > 1000f) throw ERROR_INVALID_FORMAT.create();
                return rate;
            } catch (NumberFormatException e) {
                throw ERROR_INVALID_FORMAT.create();
            }
        } else if (lower.endsWith("ms")) {
            try {
                int ms = Integer.parseInt(spec.substring(0, spec.length() - 2));
                if (ms < 1 || ms > 1000) throw ERROR_INVALID_FORMAT.create();
                return 1000.0f / ms;
            } catch (NumberFormatException e) {
                throw ERROR_INVALID_FORMAT.create();
            }
        } else {
            // 没有单位 → 默认为每秒刻数 (t 格式)
            try {
                float rate = Float.parseFloat(spec);
                if (rate < 1f || rate > 1000f) throw ERROR_INVALID_FORMAT.create();
                return rate;
            } catch (NumberFormatException e) {
                throw ERROR_INVALID_FORMAT.create();
            }
        }
    }

    private static String buildFeedback(String spec, float rate, boolean sync) {
        String syncStatus = sync ? "，客户端同步" : "，客户端已重置为20tps";
        String lower = spec.toLowerCase();
        if (lower.endsWith("t")) {
            float msPerTick = 1000.0f / rate;
            return String.format("已将每秒刻速率改为%.2f，折合每刻%.2f毫秒%s", rate, msPerTick, syncStatus);
        } else if (lower.endsWith("ms")) {
            int ms = Integer.parseInt(spec.substring(0, spec.length() - 2));
            return String.format("已将每刻速率改为%dms，折合每秒%.1f刻%s", ms, rate, syncStatus);
        } else {
            // 没有单位 → 默认为每秒刻数
            float msPerTick = 1000.0f / rate;
            return String.format("已将每秒刻速率改为%.2f，折合每刻%.2f毫秒%s", rate, msPerTick, syncStatus);
        }
    }
}
