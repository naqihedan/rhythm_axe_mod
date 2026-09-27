package name.rhythm_axe_mod;

/**
 * 客户端本地的 tick rate（客户端 {@code DeltaTrackerTimerMixin} 用它决定每刻真实毫秒数）。
 *
 * <p>写入方有两处，缺一不可：
 * <ul>
 *   <li>单人 / 集成服务端：服务端命令处理直接写（同进程），见 {@code TickCommandMixin#applyClientRate}；</li>
 *   <li>多人：服务端把值打包发过来，客户端收到后写，见 {@code TickratePayloads} / {@code TickrateSync}；
 *       另外 {@code MinecraftClientMixin} 进世界时重置为 20。</li>
 * </ul>
 * 注意最低只能到 20（加速才走本状态；变慢由原版 ticking state 包让客户端自己变慢）。
 */
public class TickrateState {
    private static volatile float clientTickRate = 20.0f;

    public static float getClientTickRate() {
        return clientTickRate;
    }

    public static void setClientTickRate(float tickRate) {
        if (tickRate > 20.0f) {
            clientTickRate = tickRate;
        } else {
            clientTickRate = 20.0f;
        }
    }
}
