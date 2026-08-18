package com.ryushin.marriage;

import com.ryushin.marriage.command.MarryCommand;
import com.ryushin.marriage.command.FamilyCommand;
import com.ryushin.marriage.command.AdminCommand;
import com.ryushin.marriage.data.Database;
import com.ryushin.marriage.bond.BondManager;
import com.ryushin.marriage.listener.PlayerListener;
import com.ryushin.marriage.listener.FamilyListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class MarriagePlugin extends JavaPlugin {
    private Database database;
    private BondManager bondManager;
    private static MarriagePlugin instance;

    private double bondRadius;
    private int maxDailyBondXp;
    private boolean familySystemEnabled;
    private boolean debug;

    private MarryCommand marryCommand;
    private FamilyCommand familyCommand;
    private PlayerListener playerListener;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        loadConfigValues();

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning(
                    "Gagal membuat folder data plugin: "
                            + getDataFolder().getAbsolutePath()
            );
        }

        String databaseFileName = getConfig().getString("database.file-name", "marriage.db");
        File dbFile = new File(getDataFolder(), databaseFileName);

        database = new Database(dbFile);
        database.connect();
        database.initTables();

        bondManager = new BondManager(this);

        /*
         * Register command memakai object command yang disimpan.
         * getCommand() dicek terlebih dahulu agar tidak terkena
         * NullPointerException jika plugin.yml belum mendaftarkan command.
         */
        marryCommand = new MarryCommand(this);
        registerCommand("marry", marryCommand);

        familyCommand = new FamilyCommand(this);
        registerCommand("family", familyCommand);

        registerCommand("marriageadmin", new AdminCommand(this));

        /*
         * FamilyListener menggunakan instance FamilyCommand yang sama
         * dengan command /family.
         */
        getServer().getPluginManager().registerEvents(
                new FamilyListener(familyCommand, marryCommand),
                this
        );

        playerListener = new PlayerListener(this);
        getServer().getPluginManager().registerEvents(
                playerListener,
                this
        );

        getLogger().info("MarriagePlugin Berhasil Dimuat!");
    }

    @Override
    public void onDisable() {
        marryCommand = null;
        familyCommand = null;

        if (playerListener != null) {
            playerListener.shutdown();
            playerListener = null;
        }

        if (database != null) {
            database.close();
            database = null;
        }

        instance = null;

        getLogger().info("MarriagePlugin Dinonaktifkan");
    }

    /**
     * Register command secara aman.
     * Jika command tidak ditemukan di plugin.yml, log akan menjelaskan
     * command mana yang belum didaftarkan.
     */
    private void registerCommand(
            String commandName,
            org.bukkit.command.CommandExecutor executor
    ) {
        PluginCommand command = getCommand(commandName);

        if (command == null) {
            getLogger().severe(
                    "Command '/" + commandName
                            + "' tidak ditemukan di plugin.yml! "
                            + "Pastikan command tersebut terdaftar."
            );
            return;
        }

        command.setExecutor(executor);
        if (executor instanceof org.bukkit.command.TabCompleter tabCompleter) {
            command.setTabCompleter(tabCompleter);
        }
    }

    private void loadConfigValues() {
        var config = getConfig();

        bondRadius = config.getDouble(
                "bond.radius",
                5.0
        );

        maxDailyBondXp = config.getInt(
                "bond.max-daily-xp",
                500
        );

        familySystemEnabled = config.getBoolean(
                "family.enabled",
                true
        );

        debug = config.getBoolean("debug", false);
    }

    public void reloadPluginConfig() {
        reloadConfig();
        loadConfigValues();
        getLogger().info("Config berhasil di-reload. Debug mode: " + (debug ? "ON" : "OFF"));
    }

    /**
     * Cek apakah debug mode aktif. Dipakai class lain (command, listener,
     * bond manager) buat memutuskan apakah perlu cetak log tambahan.
     */
    public boolean isDebug() {
        return debug;
    }

    /**
     * Toggle debug mode secara langsung (misal dari /marriageadmin debug on/off),
     * sekaligus disimpan ke config.yml supaya settingnya tetap nyala
     * walau server di-restart.
     */
    public void setDebug(boolean debug) {
        this.debug = debug;
        getConfig().set("debug", debug);
        saveConfig();
    }

    /**
     * Log ke console HANYA kalau debug mode aktif. Dipanggil dari mana saja
     * (command, listener, bond manager) tanpa perlu cek isDebug() manual
     * tiap kali - cek-nya sudah ditaruh di sini, sekali saja.
     */
    public void debugLog(String message) {
        if (debug) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    public Database getDatabase() {
        return database;
    }

    public BondManager getBondManager() {
        return bondManager;
    }

    public double getBondRadius() {
        return bondRadius;
    }

    public int getMaxDailyBondXp() {
        return maxDailyBondXp;
    }

    public boolean isFamilySystemEnabled() {
        return familySystemEnabled;
    }

    public static MarriagePlugin getInstance() {
        return instance;
    }
}
