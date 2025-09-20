package com.MMFB.entitylogger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class EntityLogger implements ClientModInitializer {

    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static final File entityLogFile = new File("logs/entity_positions.log");
    private static final File worldLogFile = new File("logs/world_time.log");

    private int tickCounter = 0;

    // Vollständige Liste aller hostile Mobs (ohne enderman, zombified piglin, ghast)
    private static final Set<String> MONSTER_NAMES = new HashSet<>(Arrays.asList(
            // Grundlegende Monster
            "zombie", "skeleton", "creeper", "spider", "witch", "slime",
            
            // Nether Monster
            "blaze", "wither skeleton", "magma cube", "hoglin", "piglin", "piglin brute",
            "wither", "strider",
            
            // Ocean/Water Monster  
            "drowned", "guardian", "elder guardian", "pufferfish",
            
            // Varianten
            "husk", "stray", "bogged", "zombie villager", "cave spider",
            
            // Illager-Familie
            "pillager", "vindicator", "evoker", "ravager", "vex", "illusioner",
            
            // End Monster (ohne Enderman)
            "endermite", "shulker",
            
            // Neue Monster
            "warden", "breeze",
            
            // Jockey-Varianten
            "chicken jockey", "spider jockey", "skeleton horseman",
            
            // Seltene/Spezielle
            "silverfish", "killer bunny"
    ));

    static {
        if (!entityLogFile.getParentFile().exists()) {
            entityLogFile.getParentFile().mkdirs();
        }
        if (!worldLogFile.getParentFile().exists()) {
            worldLogFile.getParentFile().mkdirs();
        }
    }

    @Override
    public void onInitializeClient() {
        System.out.println("[EntityLogger] Mod geladen. Starte Logging...");

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (client.world == null) return;

            tickCounter++;

            if (tickCounter >= 20) { 
                tickCounter = 0;
                logEntities();
            }

            if (tickCounter % 100 == 0) { 
                logWorldTime();
            }
        });
    }

    private void logEntities() {
        try (FileWriter fw = new FileWriter(entityLogFile, false)) {
            for (Entity entity : client.world.getEntities()) {
                String originalName = entity.getName().getString();
                String lowerName = originalName.toLowerCase();

                // Prüfe auf Monster mit beiden Namen (original und lowercase)
                boolean isMonster = false;
                for (String monster : MONSTER_NAMES) {
                    if (lowerName.contains(monster) || originalName.toLowerCase().contains(monster)) {
                        double x = entity.getX();
                        double y = entity.getY();
                        double z = entity.getZ();
                        // Verwende den ORIGINAL Namen (mit Großbuchstaben) für bessere Lesbarkeit
                        fw.write(String.format("%s at [%.1f, %.1f, %.1f]%n", originalName, x, y, z));
                        isMonster = true;
                        break;
                    }
                }

                // Immer den Spieler loggen
                if (entity == client.player) {
                    double x = entity.getX();
                    double y = entity.getY();
                    double z = entity.getZ();
                    fw.write(String.format("%s at [%.1f, %.1f, %.1f]%n",
                            originalName, x, y, z));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void logWorldTime() {
        World world = client.world;
        if (world == null) return;

        long time = world.getTimeOfDay() % 24000; // Tageszeit im Spiel
        int day = (int) (world.getTimeOfDay() / 24000) + 1;

        String moonPhase = "";
        if (time >= 13000 && time <= 23000) { // Nachtzeit
            int phase = (int) ((world.getTimeOfDay() / 24000) % 8);
            switch (phase) {
                case 0 -> moonPhase = "Vollmond 8/8";
                case 1 -> moonPhase = "Abnehmend 7/8";
                case 2 -> moonPhase = "Abnehmend 6/8";
                case 3 -> moonPhase = "Abnehmend 5/8";
                case 4 -> moonPhase = "Neumond 4/8";
                case 5 -> moonPhase = "Zunehmend 3/8";
                case 6 -> moonPhase = "Zunehmend 2/8";
                case 7 -> moonPhase = "Zunehmend 1/8";
            }
        }

        try (FileWriter fw = new FileWriter(worldLogFile, false)) { 
            fw.write(String.format("Tag %d, Zeit: %d ticks%s%n",
                    day, time, moonPhase.isEmpty() ? "" : ", Mondphase: " + moonPhase));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}