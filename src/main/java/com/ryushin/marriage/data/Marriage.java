package com.ryushin.marriage.data;

import java.util.UUID;

public class Marriage {

    private int id;
    private UUID player1Uuid;
    private UUID player2Uuid;
    private int bondXp;
    private int bondLevel;
    private long marriedAt;

    // Constructor buat data BARU (belum punya id, karena id di-generate database)
    public Marriage(UUID player1Uuid, UUID player2Uuid) {
        this.player1Uuid = player1Uuid;
        this.player2Uuid = player2Uuid;
        this.bondXp = 0;
        this.bondLevel = 1;
        this.marriedAt = System.currentTimeMillis();
    }

    // Constructor buat data yang DIAMBIL dari database (sudah punya semua info)
    public Marriage(int id, UUID player1Uuid, UUID player2Uuid, int bondXp, int bondLevel, long marriedAt) {
        this.id = id;
        this.player1Uuid = player1Uuid;
        this.player2Uuid = player2Uuid;
        this.bondXp = bondXp;
        this.bondLevel = bondLevel;
        this.marriedAt = marriedAt;
    }

    // Helper: kalau saya kasih tahu UUID si A, ini balikin UUID pasangannya
    public UUID getOtherPlayer(UUID uuid) {
        if (uuid.equals(player1Uuid)) return player2Uuid;
        return player1Uuid;
    }

    // Setter ini dipakai BondManager setelah update database,
    // supaya objek Marriage yang lagi dipegang command lain (misal /marry info)
    // ikut ter-update tanpa perlu query ulang ke database.
    public void setBondXp(int bondXp) { this.bondXp = bondXp; }
    public void setBondLevel(int bondLevel) { this.bondLevel = bondLevel; }

    public int getId() { return id; }
    public UUID getPlayer1Uuid() { return player1Uuid; }
    public UUID getPlayer2Uuid() { return player2Uuid; }
    public int getBondXp() { return bondXp; }
    public int getBondLevel() { return bondLevel; }
    public long getMarriedAt() { return marriedAt; }
}
