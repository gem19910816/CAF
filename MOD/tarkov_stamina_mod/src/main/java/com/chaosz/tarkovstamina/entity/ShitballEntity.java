package com.chaosz.tarkovstamina.entity;

import com.chaosz.tarkovstamina.item.StaminaEntities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 屎投掷物实体
 * <p>
 * 右键 caf:shit（未蹲下）投掷，命中造成 1 点伤害 + 反胃 10 秒。
 * </p>
 */
public class ShitballEntity extends ThrowableItemProjectile {
    private static final ResourceLocation SHIT_ITEM = ResourceLocation.tryBuild("caf", "shit");
    private static final int NAUSEA_TICKS = 200;
    private static final float IMPACT_DAMAGE = 1.0F;

    public ShitballEntity(EntityType<? extends ShitballEntity> type, Level level) {
        super(type, level);
    }

    public ShitballEntity(Level level, LivingEntity owner) {
        super(StaminaEntities.SHITBALL.get(), owner, level);
    }

    @Override
    protected Item getDefaultItem() {
        Item item = ForgeRegistries.ITEMS.getValue(SHIT_ITEM);
        if (item != null && item != Items.AIR) {
            return item;
        }
        return Items.SNOWBALL;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (this.level().isClientSide()) return;

        Entity target = result.getEntity();
        target.hurt(this.damageSources().thrown(this, this.getOwner()),
                IMPACT_DAMAGE);

        if (target instanceof LivingEntity living) {
            Entity ownerEnt = this.getOwner();
            living.addEffect(
                    new MobEffectInstance(MobEffects.CONFUSION, NAUSEA_TICKS, 0),
                    ownerEnt instanceof LivingEntity livingOwner ? livingOwner : null);
        }
    }
}