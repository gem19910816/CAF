package com.chaosz.tarkovstamina.client;

import com.chaosz.tarkovstamina.network.StaminaSyncPacket;

public final class ClientStaminaState {
    private static float stamina = 100.0F;
    private static float maximum = 100.0F;
    private static int cooldown;
    private static int maximumCooldown = 60;
    private static boolean sprinting;
    private static boolean exhausted;
    private static boolean hidden;
    private static boolean received;

    private ClientStaminaState() {
    }

    public static void accept(StaminaSyncPacket packet) {
        stamina = packet.stamina();
        maximum = Math.max(1.0F, packet.maximum());
        cooldown = Math.max(0, packet.cooldown());
        maximumCooldown = Math.max(1, packet.maximumCooldown());
        sprinting = packet.sprinting();
        // Keep compatibility with older servers that did not populate the flag.
        exhausted = packet.exhausted() || (!packet.hidden() && packet.stamina() <= 0.0F);
        hidden = packet.hidden();
        received = true;
    }

    public static float stamina() {
        return stamina;
    }

    public static float maximum() {
        return maximum;
    }

    public static int cooldown() {
        return cooldown;
    }

    public static int maximumCooldown() {
        return maximumCooldown;
    }

    public static boolean sprinting() {
        return sprinting;
    }

    public static boolean exhausted() {
        return exhausted;
    }

    public static boolean hidden() {
        return hidden;
    }

    public static boolean received() {
        return received;
    }
}
