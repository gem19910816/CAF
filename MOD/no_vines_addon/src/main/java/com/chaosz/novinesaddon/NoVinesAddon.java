package com.chaosz.novinesaddon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Mod(NoVinesAddon.MODID)
public class NoVinesAddon {

    public static final String MODID = "no_vines_addon";
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public NoVinesAddon() {
        MinecraftForge.EVENT_BUS.register(this);
        patchProfiles();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        patchProfiles();
    }

    private static void patchProfiles() {
        Path profilesDir = FMLPaths.CONFIGDIR.get().resolve("lostcities").resolve("profiles");
        if (!Files.isDirectory(profilesDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(profilesDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(NoVinesAddon::patchProfile);
        } catch (IOException e) {
            LOGGER.error("NoVinesAddon: failed to list profiles directory", e);
        }
    }

    private static void patchProfile(Path profilePath) {
        try {
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(profilePath, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            JsonObject lostcity = root.getAsJsonObject("lostcity");
            if (lostcity == null) {
                return;
            }
            JsonElement vineChance = lostcity.get("vineChance");
            if (vineChance == null || !vineChance.isJsonPrimitive() || vineChance.getAsDouble() <= 0.0) {
                return;
            }
            lostcity.addProperty("vineChance", 0.0);
            try (Writer writer = Files.newBufferedWriter(profilePath, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            LOGGER.info("NoVinesAddon: set vineChance=0.0 in {}", profilePath);
        } catch (Exception e) {
            LOGGER.error("NoVinesAddon: failed to patch profile {}", profilePath, e);
        }
    }
}
