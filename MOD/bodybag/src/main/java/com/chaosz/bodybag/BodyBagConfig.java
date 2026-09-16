package com.chaosz.bodybag;

import net.minecraftforge.common.ForgeConfigSpec;

public class BodyBagConfig {

    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    /** Maximum distance (blocks) from which a corpse can be collected. */
    public static final ForgeConfigSpec.DoubleValue COLLECT_RANGE;
    /** Duration in ticks (20 ticks = 1 second) for the collection. */
    public static final ForgeConfigSpec.IntValue COLLECT_DURATION_TICKS;

    static {
        BUILDER.comment("Body Bag (裹尸袋) server-side configuration").push("bodybag");

        COLLECT_RANGE = BUILDER
                .comment("Maximum distance in blocks between player and corpse to start & maintain collection.")
                .defineInRange("collect_range", 5.0D, 1.0D, 32.0D);

        COLLECT_DURATION_TICKS = BUILDER
                .comment("How many ticks the player must hold right-click to complete collection (20 ticks = 1 second).")
                .defineInRange("collect_duration_ticks", 200, 20, 1200);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}