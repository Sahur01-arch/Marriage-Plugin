package com.ryushin.marriage.listener;

import com.ryushin.marriage.MarriagePlugin;
import com.ryushin.marriage.data.Marriage;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerListener implements Listener {

    private final MarriagePlugin plugin;

    /*
     * Menyimpan jumlah Bond XP dari KEDEKATAN (proximity) yang sudah
     * diberikan pada hari berjalan, per pasangan (marriageKey).
     *
     * Ini TERPISAH dari bond.max-daily-xp (total XP dari semua sumber) -
     * ini cuma nge-cap sumber "berdekatan dengan pasangan" secara spesifik,
     * sesuai config bond.proximity.max-daily-xp.
     */
    private final Map<UUID, Integer> dailyProximityXp = new HashMap<>();

    /*
     * Mencegah pasangan mendapatkan XP setiap tick.
     */
    private final Map<UUID, Long> lastBondGain = new HashMap<>();

    /*
     * Kapan terakhir kali pesan "bond-xp-gained" dikirim untuk pasangan ini -
     * dipakai buat throttle notifikasi walau notify-xp-gained di config aktif,
     * biar tetap nggak spam chat tiap 5 detik.
     */
    private final Map<UUID, Long> lastNotifyTime = new HashMap<>();

    private BukkitTask bondTask;

    /*
     * Hari (epoch/ONE_DAY_MILLIS) terakhir kali dailyProximityXp direset.
     * Dipakai untuk mendeteksi pergantian hari supaya limit harian
     * benar-benar reset tiap hari, bukan menumpuk selamanya.
     */
    private long currentDayEpoch;

    // ===== Nilai-nilai berikut dibaca dari config.yml, lihat loadSettings() =====
    private long bondIntervalTicks;
    private int bondXpPerInterval;
    private int proximityMaxDailyXp;
    private boolean notifyXpGain;
    private long notifyCooldownMillis;

    private static final long ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L;

    public PlayerListener(MarriagePlugin plugin) {
        this.plugin = plugin;
        this.currentDayEpoch = System.currentTimeMillis() / ONE_DAY_MILLIS;

        loadSettings();
        startBondTask();
    }

    /**
     * Baca ulang semua pengaturan proximity XP dari config.yml.
     * Dipanggil saat plugin enable, dan dari MarriagePlugin.reloadPluginConfig()
     * supaya admin bisa ubah interval/XP/limit/notify tanpa restart server.
     */
    public void loadSettings() {
        var config = plugin.getConfig();

        int intervalSeconds = config.getInt("bond.proximity.interval-seconds", 5);
        bondIntervalTicks = intervalSeconds * 20L;

        bondXpPerInterval = config.getInt("bond.proximity.xp-per-interval", 5);
        proximityMaxDailyXp = config.getInt("bond.proximity.max-daily-xp", 200);
        notifyXpGain = config.getBoolean("bond.proximity.notify-xp-gained", false);

        int notifyCooldownSeconds = config.getInt("bond.proximity.notify-cooldown-seconds", 60);
        notifyCooldownMillis = notifyCooldownSeconds * 1000L;
    }

    /**
     * Restart scheduler task dengan interval terbaru dari config.
     * Dipanggil dari reloadSettings() kalau interval-nya berubah.
     */
    public void reloadSettings() {
        loadSettings();

        // Interval bisa berubah, jadi task lama di-cancel & bikin baru
        // dengan periode yang sesuai config terbaru.
        if (bondTask != null) {
            bondTask.cancel();
        }
        startBondTask();

        plugin.debugLog("PlayerListener settings direload: interval=" + (bondIntervalTicks / 20L)
                + "s, xpPerInterval=" + bondXpPerInterval
                + ", maxDailyXp=" + proximityMaxDailyXp
                + ", notify=" + notifyXpGain);
    }

    /**
     * Mengecek pasangan secara berkala.
     *
     * Tidak menggunakan PlayerMoveEvent agar server tidak melakukan
     * query database setiap kali player bergerak.
     */
    private void startBondTask() {

        bondTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::processBondXp,
                bondIntervalTicks,
                bondIntervalTicks
        );
    }

    /**
     * Proses Bond XP dari kedekatan pasangan.
     */
    private void processBondXp() {

        /*
         * Jika sistem plugin sedang tidak aktif,
         * jangan melakukan proses apa pun.
         */
        if (plugin.getDatabase() == null) {
            return;
        }

        cekPergantianHari();

        for (Player player : Bukkit.getOnlinePlayers()) {

            Marriage marriage =
                    plugin.getDatabase()
                            .getMarriageByPlayer(player.getUniqueId());

            if (marriage == null) {
                continue;
            }

            UUID spouseUuid =
                    marriage.getOtherPlayer(player.getUniqueId());

            if (spouseUuid == null) {
                continue;
            }

            Player spouse = Bukkit.getPlayer(spouseUuid);

            /*
             * Pasangan harus sedang online.
             */
            if (spouse == null || !spouse.isOnline()) {
                continue;
            }

            /*
             * Harus berada di world yang sama.
             */
            if (!player.getWorld().equals(spouse.getWorld())) {
                continue;
            }

            /*
             * Gunakan distanceSquared agar tidak perlu
             * menghitung sqrt setiap pengecekan.
             */
            double radius = plugin.getBondRadius();
            double maxDistanceSquared = radius * radius;

            if (player.getLocation()
                    .distanceSquared(spouse.getLocation())
                    > maxDistanceSquared) {
                continue;
            }

            /*
             * Pastikan marriage hanya diproses sekali
             * untuk pasangan yang sama.
             *
             * UUID dengan urutan deterministik.
             */
            UUID firstUuid;
            UUID secondUuid;

            if (player.getUniqueId().compareTo(spouseUuid) < 0) {
                firstUuid = player.getUniqueId();
                secondUuid = spouseUuid;
            } else {
                firstUuid = spouseUuid;
                secondUuid = player.getUniqueId();
            }

            UUID marriageKey = createMarriageKey(firstUuid, secondUuid);

            /*
             * Cegah pemberian XP dua kali pada interval yang sama.
             */
            long now = System.currentTimeMillis();

            Long lastGain = lastBondGain.get(marriageKey);

            if (lastGain != null
                    && now - lastGain < 4000L) {
                continue;
            }

            /*
             * Cek batas daily XP KHUSUS proximity (bond.proximity.max-daily-xp),
             * terpisah dari bond.max-daily-xp yang menaungi semua sumber XP.
             */
            int currentDailyXp =
                    dailyProximityXp.getOrDefault(marriageKey, 0);

            if (currentDailyXp >= proximityMaxDailyXp) {
                continue;
            }

            /*
             * Jangan melebihi daily limit.
             */
            int xpToGive = Math.min(
                    bondXpPerInterval,
                    proximityMaxDailyXp - currentDailyXp
            );

            if (xpToGive <= 0) {
                continue;
            }

            /*
             * Tandai bahwa XP sudah diberikan.
             */
            dailyProximityXp.put(
                    marriageKey,
                    currentDailyXp + xpToGive
            );

            lastBondGain.put(
                    marriageKey,
                    now
            );

            /*
             * Tentukan apakah pesan "+X XP" perlu dikirim kali ini:
             * - notify-xp-gained harus true di config
             * - DAN sudah lewat notify-cooldown-seconds sejak notifikasi terakhir
             * Ini lapisan anti-spam KEDUA di atas toggle on/off - jadi walau
             * admin nyalain notify, tetap nggak akan spam tiap 5 detik.
             */
            boolean kirimNotifikasi = false;

            if (notifyXpGain) {
                Long lastNotify = lastNotifyTime.get(marriageKey);
                if (lastNotify == null || now - lastNotify >= notifyCooldownMillis) {
                    kirimNotifikasi = true;
                    lastNotifyTime.put(marriageKey, now);
                }
            }

            processBondReward(
                    player,
                    spouse,
                    marriage,
                    xpToGive,
                    kirimNotifikasi
            );
        }
    }

    /**
     * Tempat update Bond XP sebenarnya.
     *
     * Diteruskan ke BondManager, yang akan:
     * - update bond_xp/bond_level ke database (via Database.updateBond)
     * - kirim pesan "bond-xp-gained" ke kedua pasangan HANYA kalau notify true
     * - kalau XP-nya cukup buat naik level, kirim pesan "bond-level-up" (SELALU,
     *   terlepas dari status notify - level up bukan spam, itu milestone)
     */
    private void processBondReward(
            Player player,
            Player spouse,
            Marriage marriage,
            int xp,
            boolean notify
    ) {

        plugin.getBondManager().addXp(marriage, xp, notify);
    }

    /**
     * Membuat key deterministik untuk pasangan.
     *
     * A + B dan B + A akan menghasilkan key yang sama.
     */
    private UUID createMarriageKey(
            UUID first,
            UUID second
    ) {

        return UUID.nameUUIDFromBytes(
                (
                        first.toString()
                                + ":"
                                + second.toString()
                ).getBytes()
        );
    }

    /**
     * Cek apakah sudah ganti hari sejak terakhir kali dailyProximityXp
     * direset. Kalau iya, kosongkan cache-nya supaya limit harian
     * benar-benar mulai dari 0 lagi tiap hari - bukan cuma numpuk terus
     * sampai plugin/server restart.
     */
    private void cekPergantianHari() {
        long hariIni = System.currentTimeMillis() / ONE_DAY_MILLIS;

        if (hariIni != currentDayEpoch) {
            dailyProximityXp.clear();
            lastNotifyTime.clear();
            currentDayEpoch = hariIni;
            plugin.debugLog("Reset harian: dailyProximityXp dikosongkan (hari baru).");
        }
    }

    /**
     * Ketika player keluar, bersihkan cache player.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        UUID uuid = event.getPlayer().getUniqueId();

        lastBondGain.entrySet().removeIf(
                entry -> entry.getKey().equals(uuid)
        );
    }

    /**
     * Tidak melakukan reset Bond XP ketika player join.
     *
     * Bond XP seharusnya tersimpan di SQLite,
     * bukan di memory.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {

        /*
         * Sengaja kosong untuk saat ini.
         *
         * Data marriage akan diambil dari database
         * ketika processBondXp() berjalan.
         */
    }

    /**
     * Dipanggil ketika plugin dimatikan.
     */
    public void shutdown() {

        if (bondTask != null) {
            bondTask.cancel();
            bondTask = null;
        }

        dailyProximityXp.clear();
        lastBondGain.clear();
        lastNotifyTime.clear();
    }
}
