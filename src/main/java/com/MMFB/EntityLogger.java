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
                refreshAllTables();
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
            
            // Aktiviere WAL-Modus für bessere Concurrent-Performance
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode = WAL");
                stmt.execute("PRAGMA synchronous = NORMAL"); // Etwas schneller als FULL
                stmt.execute("PRAGMA cache_size = 10000"); // Mehr Cache für bessere Performance
                stmt.execute("PRAGMA temp_store = memory"); // Temporäre Daten im RAM
            }
            
            // Erstelle Tabellen
            createTables();
            
            System.out.println("[EntityLogger] Datenbank erfolgreich initialisiert: " + DB_PATH);
            
        } catch (SQLException e) {
            System.err.println("[EntityLogger] Fehler beim Initialisieren der Datenbank: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        // Tabelle für Monster
        String createMonsterTable = """
            CREATE TABLE IF NOT EXISTS monsters (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                distance_to_player REAL NOT NULL,
                health REAL,
                uuid TEXT
            )
        """;

        // Tabelle für Spieler
        String createPlayerTable = """
            CREATE TABLE IF NOT EXISTS players (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                distance_to_player REAL NOT NULL,
                health REAL,
                uuid TEXT
            )
        """;

        // Tabelle für World Time
        String createWorldTimeTable = """
            CREATE TABLE IF NOT EXISTS world_time (
                id INTEGER PRIMARY KEY,
                game_day INTEGER NOT NULL,
                game_ticks INTEGER NOT NULL,
                moon_phase TEXT,
                time_text TEXT NOT NULL,
                last_update INTEGER NOT NULL
            )
        """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createMonsterTable);
            stmt.execute(createPlayerTable);
            stmt.execute(createWorldTimeTable);
            
            // Keine manuelle Initialisierung nötig - INSERT OR REPLACE funktioniert immer
        }
    }

    private void refreshAllTables() {
        if (connection == null) return;

        try {
            // Auto-commit ausschalten für bessere Performance
            connection.setAutoCommit(false);
            
            try (Statement stmt = connection.createStatement()) {
                // Lösche alle alten Daten für frischen Snapshot
                stmt.executeUpdate("DELETE FROM monsters");
                stmt.executeUpdate("DELETE FROM players");
                
                // Aktualisiere alle Tabellen
                updateWorldTime();
                logMonsters();
                logPlayers();
                
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

    private void updateWorldTime() throws SQLException {
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

        // Erstelle exakt das gleiche Format wie in der alten Log-Version
        String timeText = String.format("Tag %d, Zeit: %d ticks%s",
                day, time, moonPhase.isEmpty() ? "" : ", Mondphase: " + moonPhase);

        // INSERT OR REPLACE statt UPDATE - funktioniert immer
        String insertSQL = """
            INSERT OR REPLACE INTO world_time (id, game_day, game_ticks, moon_phase, time_text, last_update) 
            VALUES (1, ?, ?, ?, ?, ?)
        """;
        
        try (PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
            pstmt.setInt(1, day);
            pstmt.setLong(2, time);
            pstmt.setString(3, moonPhase.isEmpty() ? null : moonPhase);
            pstmt.setString(4, timeText);
            pstmt.setLong(5, currentTime);
            pstmt.executeUpdate();
        }
    }

    private void logMonsters() throws SQLException {
        String insertSQL = """
            INSERT INTO monsters (name, x, y, z, distance_to_player, health, uuid) 
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
            
            for (Entity entity : client.world.getEntities()) {
                String originalName = entity.getName().getString();
                String lowerName = originalName.toLowerCase();
                
                // Prüfe auf Monster
                boolean isMonster = false;
                for (String monster : MONSTER_NAMES) {
                    if (lowerName.contains(monster) || originalName.toLowerCase().contains(monster)) {
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

                        pstmt.setString(1, originalName);
                        pstmt.setDouble(2, x);
                        pstmt.setDouble(3, y);
                        pstmt.setDouble(4, z);
                        pstmt.setDouble(5, distanceToPlayer);
                        pstmt.setObject(6, health);
                        pstmt.setString(7, uuid);
                        pstmt.addBatch();
                        isMonster = true;
                        break;
                    }
                }
            }
            
            // Führe alle Inserts auf einmal aus
            pstmt.executeBatch();
        }
    }

    private void logPlayers() throws SQLException {
        String insertSQL = """
            INSERT INTO players (name, x, y, z, distance_to_player, health, uuid) 
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
            
            for (Entity entity : client.world.getEntities()) {
                // Logge ALLE Spieler (nicht nur den eigenen)
                if (entity instanceof net.minecraft.entity.player.PlayerEntity) {
                    String originalName = entity.getName().getString();
                    double x = entity.getX();
                    double y = entity.getY();
                    double z = entity.getZ();
                    String uuid = entity.getUuidAsString();
                    
                    // Berechne Distanz zum Spieler (für andere Spieler)
                    double distanceToPlayer = 0;
                    if (client.player != null && entity != client.player) {
                        distanceToPlayer = Math.sqrt(
                            Math.pow(x - client.player.getX(), 2) +
                            Math.pow(y - client.player.getY(), 2) +
                            Math.pow(z - client.player.getZ(), 2)
                        );
                    }
                    // Für den eigenen Spieler ist die Distanz 0
                    
                    // Health (falls verfügbar)
                    Float health = null;
                    if (entity instanceof net.minecraft.entity.LivingEntity living) {
                        health = living.getHealth();
                    }

                    pstmt.setString(1, originalName);
                    pstmt.setDouble(2, x);
                    pstmt.setDouble(3, y);
                    pstmt.setDouble(4, z);
                    pstmt.setDouble(5, distanceToPlayer);
                    pstmt.setObject(6, health);
                    pstmt.setString(7, uuid);
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