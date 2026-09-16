package com.chaosz.tarkovstamina.ui;

import com.chaosz.tarkovstamina.network.StaminaNetwork;
import com.chaosz.tarkovstamina.network.StatusScreenPacket;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 注册 /caf 命令
 * <p>
 * /caf           → 打开综合状态面板
 * /caf hud       → 打开体力条位置调整界面
 * /caf hud reset → 将体力条位置恢复默认（重置到屏幕底部居中）
 * </p>
 */
public final class StatusCommand {
    private StatusCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("caf")
                        .requires(src -> src.getEntity() instanceof ServerPlayer)
                        // /caf → 状态面板
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            StaminaNetwork.sendStatus(player, StatusScreenPacket.from(player));
                            return 1;
                        })
                        // /caf hud → 打开HUD位置调整
                        .then(Commands.literal("hud")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    StaminaNetwork.sendHudOpen(player);
                                    return 1;
                                })
                                // /caf hud reset → 恢复默认位置
                                .then(Commands.literal("reset")
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            StaminaNetwork.sendHudReset(player);
                                            player.displayClientMessage(
                                                    Component.literal("§a体力条位置已恢复默认"), true);
                                            return 1;
                                        }))
                        )
        );
    }
}