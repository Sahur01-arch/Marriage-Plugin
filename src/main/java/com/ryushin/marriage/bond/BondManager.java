package com.ryushin.marriage.bond;

import com.ryushin.marriage.MarriagePlugin;
import com.ryushin.marriage.data.Marriage;
import com.ryushin.marriage.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Mengatur penambahan Bond XP dan kenaikan Bond Level.
 *
 * Kurva level dibuat SIMPEL & configurable: XP dibutuhkan buat naik ke level
 * berikutnya = (bond.xp-per-level di config.yml) * level saat ini.
 * Contoh kalau xp-per-level = 100:
 *   Level 1 -> 2 butuh 100 XP
 *   Level 2 -> 3 butuh 200 XP
 *   Level 3 -> 4 butuh 300 XP
 * Admin server bisa ubah "bond.xp-per-level" di config.yml tanpa recompile.
 */
public class BondManager {

    private final MarriagePlugin plugin;

    public BondManager(MarriagePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Tambahkan XP ke satu pernikahan, otomatis cek & proses level up
     * kalau XP-nya cukup (bisa naik lebih dari 1 level sekaligus).
     *
     * @param marriage data pernikahan yang mau ditambah XP-nya
     * @param amount   jumlah XP yang ditambahkan (harus > 0)
     */
    public void addXp(Marriage marriage, int amount) {
        if (amount <= 0) return;

        int xpPerLevel = plugin.getConfig().getInt("bond.xp-per-level", 100);

        int currentXp = marriage.getBondXp() + amount;
        int currentLevel = marriage.getBondLevel();
        boolean naikLevel = false;

        // while, bukan if -> jaga-jaga kalau XP yang ditambahkan besar
        // dan cukup buat naik lebih dari 1 level sekaligus
        while (currentXp >= xpPerLevel * currentLevel) {
            currentXp -= xpPerLevel * currentLevel;
            currentLevel++;
            naikLevel = true;
        }

        // Simpan hasil akhir ke database (1x update, bukan tiap level naik)
        plugin.getDatabase().updateBond(marriage.getId(), currentXp, currentLevel);

        // Update juga objek Marriage di memory, biar kalau dipakai lagi
        // setelah addXp() (misal buat /marry info) datanya sudah sinkron
        marriage.setBondXp(currentXp);
        marriage.setBondLevel(currentLevel);

        notifyXpGained(marriage, amount, currentXp);

        if (naikLevel) {
            notifyLevelUp(marriage, currentLevel);
        }
    }

    private void notifyXpGained(Marriage marriage, int amount, int totalXp) {
        String pesan = MessageUtil.get(plugin, "bond-xp-gained", Map.of(
                "xp", String.valueOf(amount),
                "total", String.valueOf(totalXp)
        ));

        kirimKeKeduaPasangan(marriage, pesan);
    }

    private void notifyLevelUp(Marriage marriage, int newLevel) {
        // Ini bagian yang diminta: pesan level up diambil dari config.yml,
        // key "messages.bond-level-up", placeholder %level%
        String pesan = MessageUtil.get(plugin, "bond-level-up", Map.of(
                "level", String.valueOf(newLevel)
        ));

        kirimKeKeduaPasangan(marriage, pesan);
    }

    /**
     * Kirim pesan ke kedua pasangan kalau mereka online.
     * Kalau salah satu offline, pesan cuma dikirim ke yang online -
     * tidak error, cuma di-skip.
     */
    private void kirimKeKeduaPasangan(Marriage marriage, String pesan) {
        Player p1 = Bukkit.getPlayer(marriage.getPlayer1Uuid());
        Player p2 = Bukkit.getPlayer(marriage.getPlayer2Uuid());

        if (p1 != null) p1.sendMessage(pesan);
        if (p2 != null) p2.sendMessage(pesan);
    }
}
