package com.MMFB.entitylogger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import java.sql.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class EntityLogger implements ClientModInitializer {

    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static final String DB_PATH = "logs/minecraft_entities.db";
    private Connection connection;
    
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

    @Override
    public void onInitializeClient() {
        System.out.println("[EntityLogger] Mod geladen. Initialisiere SQLite Datenbank...");
        
        initializeDatabase();
        
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (client.world == null) return;

            tickCounter++;

            // Komplette DB-Erneuerung alle 10 Ticks (2x pro Sekunde)
            if (tickCounter >= 10) { 
                tickCounter = 0;
                refreshDatabase();
            }
        });
    }

    private void initializeDatabase() {
        try {
            // Erstelle logs Verzeichnis falls es nicht existiert
            java.io.File logsDir = new java.io.File("logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }

            // SQLite JDBC Connection
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
            
            // Erstelle Tabellen
            createTables();
            
            System.out.println("[EntityLogger] Datenbank erfolgreich initialisiert: " + DB_PATH);
            
        } catch (SQLException e) {
            System.err.println("[EntityLogger] Fehler beim Initialisieren der Datenbank: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        String createEntityTable = """
            CREATE TABLE IF NOT EXISTS entities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                entity_type TEXT NOT NULL,
                uuid TEXT,
                health REAL,
                distance_to_player REAL
            )
        """;

        String createWorldTimeTable = """
            CREATE TABLE IF NOT EXISTS world_info (
                id INTEGER PRIMARY KEY,
                game_day INTEGER NOT NULL,
                game_time INTEGER NOT NULL,
                moon_phase TEXT,
                last_update INTEGER NOT NULL
            )
        """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createEntityTable);
            stmt.execute(createWorldTimeTable);
            
            // Stelle sicher, dass world_info nur einen Eintrag hat
            stmt.execute("INSERT OR IGNORE INTO world_info (id) VALUES (1)");
        }
    }

    private void refreshDatabase() {
        if (connection == null) return;

        try {
            // Lösche alle alten Daten für frischen Snapshot
            connection.setAutoCommit(false);
            
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM entities");
                
                // Aktualisiere World Info
                updateWorldInfo();
                
                // Logge alle aktuellen Entities
                logCurrentEntities();
                
                connection.commit();
                
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            
        } catch (SQLException e) {
            System.err.println("[EntityLogger] Fehler beim Erneuern der Datenbank: " + e.getMessage());
        }
    }

    private void updateWorldInfo() throws SQLException {
        World world = client.world;
        if (world == null) return;

        long time = world.getTimeOfDay() % 24000;
        int day = (int) (world.getTimeOfDay() / 24000) + 1;
        long currentTime = System.currentTimeMillis();

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

        String updateSQL = """
            UPDATE world_info SET 
                game_day = ?, 
                game_time = ?, 
                moon_phase = ?, 
                last_update = ? 
            WHERE id = 1
        """;
        
        try (PreparedStatement pstmt = connection.prepareStatement(updateSQL)) {
            pstmt.setInt(1, day);
            pstmt.setLong(2, time);
            pstmt.setString(3, moonPhase.isEmpty() ? null : moonPhase);
            pstmt.setLong(4, currentTime);
            pstmt.executeUpdate();
        }
    }

    private void logCurrentEntities() throws SQLException {
        String insertSQL = """
            INSERT INTO entities (name, x, y, z, entity_type, uuid, health, distance_to_player) 
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
            
            for (Entity entity : client.world.getEntities()) {
                String originalName = entity.getName().getString();
                String lowerName = originalName.toLowerCase();
                double x = entity.getX();
                double y = entity.getY();
                double z = entity.getZ();
                String uuid = entity.getUuidAsString();
                
                // Berechne Distanz zum Spieler
                double distanceToPlayer = 0;
                if (client.player != null) {
                    distanceToPlayer = Math.sqrt(
                        Math.pow(x - client.player.getX(), 2) +
                        Math.pow(y - client.player.getY(), 2) +
                        Math.pow(z - client.player.getZ(), 2)
                    );
                }
                
                // Health (falls verfügbar)
                Float health = null;
                if (entity instanceof net.minecraft.entity.LivingEntity living) {
                    health = living.getHealth();
                }

                // Prüfe auf Monster
                boolean isMonster = false;
                for (String monster : MONSTER_NAMES) {
                    if (lowerName.contains(monster) || originalName.toLowerCase().contains(monster)) {
                        pstmt.setString(1, originalName);
                        pstmt.setDouble(2, x);
                        pstmt.setDouble(3, y);
                        pstmt.setDouble(4, z);
                        pstmt.setString(5, "MONSTER");
                        pstmt.setString(6, uuid);
                        pstmt.setObject(7, health);
                        pstmt.setDouble(8, distanceToPlayer);
                        pstmt.addBatch();
                        isMonster = true;
                        break;
                    }
                }

                // Logge ALLE Spieler (nicht nur den eigenen)
                if (entity instanceof net.minecraft.entity.player.PlayerEntity) {
                    pstmt.setString(1, originalName);
                    pstmt.setDouble(2, x);
                    pstmt.setDouble(3, y);
                    pstmt.setDouble(4, z);
                    pstmt.setString(5, "PLAYER");
                    pstmt.setString(6, uuid);
                    pstmt.setObject(7, health);
                    pstmt.setDouble(8, distanceToPlayer);
                    pstmt.addBatch();
                }
            }
            
            // Führe alle Inserts auf einmal aus
            pstmt.executeBatch();
        }
    }

    // Cleanup beim Beenden
    public void closeDatabase() {
        if (connection != null) {
            try {
                connection.close();
                System.out.println("[EntityLogger] Datenbank-Verbindung geschlossen.");
            } catch (SQLException e) {
                System.err.println("[EntityLogger] Fehler beim Schließen der Datenbank: " + e.getMessage());
            }
        }
    }
}