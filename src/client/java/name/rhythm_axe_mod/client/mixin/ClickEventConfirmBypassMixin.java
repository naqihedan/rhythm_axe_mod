package name.rhythm_axe_mod.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import name.rhythm_axe_mod.ModGameRules;

/**
 * 禁用 25w20a 引入的 run_command 确认弹窗。
 * 
 * 自 25w20a 起，sendUnattendedCommand 调用 verifyCommand 检查命令，
 * 若返回 PARSE_ERRORS / PERMISSIONS_REQUIRED 则弹出确认屏幕。
 * 
 * 本 Mixin 在 sendUnattendedCommand 头部检查 gamerule，若为 false
 * 则直接发送命令并 return，跳过确认逻辑。
 */
@Mixin(ClientPacketListener.class)
public class ClickEventConfirmBypassMixin {

    @Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
    private void onSendUnattendedCommand(String command, net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        // ── 检查单机服务器的 gamerule ────────────────────────
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld != null) {
                boolean shouldConfirm = overworld.getGameRules().get(ModGameRules.CONFIRM_COMMAND);
                if (!shouldConfirm) {
                    // gamerule=false → 直接发送命令，跳过确认逻辑
                    ((ClientCommonPacketListenerImpl)(Object)this).send(new ServerboundChatCommandPacket(command));
                    Minecraft.getInstance().setScreen(screen);
                    ci.cancel();
                }
                // gamerule=true → 走原版逻辑
            }
        }
        // 非单机模式 → 不干预
    }
}
