package com.caf.bt7274;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** World-scoped ownership survives chunk unloading, player death and dimension changes. */
public final class TitanRegistry extends SavedData {
    private final Map<UUID,UUID> active=new HashMap<>();
    private final Map<UUID,Long> cooldown=new HashMap<>();
    private final Set<UUID> revoked=new HashSet<>();
    private static final Factory<TitanRegistry> FACTORY=new Factory<>(TitanRegistry::new,TitanRegistry::load);
    public static TitanRegistry get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(FACTORY,"bt7274_pilot_registry");}
    public UUID active(UUID owner){return active.get(owner);}
    public long cooldown(UUID owner){return cooldown.getOrDefault(owner,0L);}
    public boolean revoked(UUID titan){return revoked.contains(titan);}
    public void bind(UUID owner,UUID titan,long until){active.put(owner,titan);cooldown.put(owner,until);setDirty();}
    public void release(UUID owner,UUID titan){if(titan.equals(active.get(owner))){active.remove(owner);setDirty();}}
    public void recall(UUID owner){UUID titan=active.remove(owner);if(titan!=null){revoked.add(titan);setDirty();}}
    public void acknowledgeRevocation(UUID titan){if(revoked.remove(titan))setDirty();}
    private static TitanRegistry load(CompoundTag tag,HolderLookup.Provider provider){
        var r=new TitanRegistry();
        for(var raw:tag.getList("Pilots",10)){
            var t=(CompoundTag)raw;if(!t.hasUUID("Owner"))continue;UUID owner=t.getUUID("Owner");
            if(t.hasUUID("Titan"))r.active.put(owner,t.getUUID("Titan"));r.cooldown.put(owner,t.getLong("Until"));
        }
        for(var raw:tag.getList("Revoked",10)){var t=(CompoundTag)raw;if(t.hasUUID("Id"))r.revoked.add(t.getUUID("Id"));}return r;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider provider){
        ListTag pilots=new ListTag();Set<UUID> ids=new HashSet<>(cooldown.keySet());ids.addAll(active.keySet());
        for(UUID owner:ids){CompoundTag t=new CompoundTag();t.putUUID("Owner",owner);if(active.containsKey(owner))t.putUUID("Titan",active.get(owner));t.putLong("Until",cooldown(owner));pilots.add(t);}
        tag.put("Pilots",pilots);ListTag revokedTags=new ListTag();
        for(UUID id:revoked){var t=new CompoundTag();t.putUUID("Id",id);revokedTags.add(t);}tag.put("Revoked",revokedTags);return tag;
    }
}
