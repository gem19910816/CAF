package com.chaosz.tarkovstamina.item;

import com.chaosz.tarkovstamina.TarkovStamina;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class StaminaEntities {
    public static final DeferredRegister<EntityType<?>> REGISTRY =
            DeferredRegister.create(Registries.ENTITY_TYPE, TarkovStamina.MOD_ID);

    public static final RegistryObject<EntityType<com.chaosz.tarkovstamina.entity.ShitballEntity>> SHITBALL =
            REGISTRY.register("shitball", () -> EntityType.Builder
                    .<com.chaosz.tarkovstamina.entity.ShitballEntity>of(
                            com.chaosz.tarkovstamina.entity.ShitballEntity::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build("shitball"));

    private StaminaEntities() {
    }
}