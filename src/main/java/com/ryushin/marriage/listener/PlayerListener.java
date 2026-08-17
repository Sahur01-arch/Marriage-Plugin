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
     * Menyimpan jumlah Bond XP yang sudah diberikan
     * pada hari berjalan untuk setiap marriage/player.
     *
     * Key menggunakan UUID player.
     */
    private final Map<UUID, Integer> dailyBondXp = new HashMap<>();

    /*
     * Mencegah pasangan mendapatkan XP setiap tick.
     */
    private final Map<UUID, Long> lastBondGain = new HashMap<>();

    private BukkitTask bondTask;

    /*
     * Jarak minimum agar pasangan dianggap sedang bersama.
     */
    private static final long BOND_INTERVAL_TICKS = 100L; // 5 detik

    /*
     * XP yang diberikan setiap interval.
     *
     * Bisa nanti dipindahkan ke config.
     */
    private static final int BOND_XP_PER_INTERVAL = 5;

    /*
     * 1 hari dalam milliseconds.
     */
    private static final long ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L;

    public PlayerListener(MarriagePlugin plugin) {
        this.plugin = plugin;

        startBondTask();
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
                BOND_INTERVAL_TICKS,
                BOND_INTERVAL_TICKS
        );
    }

    /**
     * Proses Bond XP.
     */
    private void processBondXp() {

        /*
         * Jika sistem plugin sedang tidak aktif,
         * jangan melakukan proses apa pun.
         */
        if (plugin.getDatabase() == null) {
            return;
        }

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
             * Cek batas daily XP.
             */
            int maxDailyXp = plugin.getMaxDailyBondXp();

            int currentDailyXp =
                    dailyBondXp.getOrDefault(marriageKey, 0);

            if (currentDailyXp >= maxDailyXp) {
                continue;
            }

            /*
             * Jangan melebihi daily limit.
             */
            int xpToGive = Math.min(
                    BOND_XP_PER_INTERVAL,
                    maxDailyXp - currentDailyXp
            );

            if (xpToGive <= 0) {
                continue;
            }

            /*
             * Tandai bahwa XP sudah diberikan.
             */
            dailyBondXp.put(
                    marriageKey,
                    currentDailyXp + xpToGive
            );

            lastBondGain.put(
                    marriageKey,
                    now
            );

            /*
             * Bond XP belum dimasukkan ke Marriage/database
             * karena class Marriage saat ini belum memiliki
             * setter/addXp dan Database belum memiliki
             * updateMarriageBond().
             *
             * Setelah method database ditambahkan,
             * panggil update di bagian ini.
             */

            processBondReward(
                    player,
                    spouse,
                    marriage,
                    xpToGive
            );
        }

        cleanupDailyData();
    }

    /**
     * Tempat update Bond XP sebenarnya.
     *
     * Untuk sementara hanya melakukan proses validasi.
     * Method ini sengaja dipisahkan supaya nanti mudah
     * menghubungkan ke Database.
     */
    private void processBondReward(
            Player player,
            Player spouse,
            Marriage marriage,
            int xp
    ) {

        /*
         * TODO:
         *
         * marriage.addBondXp(xp);
         *
         * plugin.getDatabase().updateMarriageBond(
         *     marriage.getId(),
         *     marriage.getBondXp(),
         *     marriage.getBondLevel()
         * );
         *
         * Jangan menggunakan reflection untuk mengubah
         * field private Marriage.
         */

        /*
         * Feedback hanya jika memang ingin ditampilkan.
         * Saat database update sudah tersedia,
         * bagian ini bisa digunakan untuk memberi pesan
         * ketika level naik.
         */
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
     * Membersihkan data daily XP yang sudah lebih
     * dari satu hari.
     *
     * Implementasi sederhana:
     * seluruh cache dibersihkan setiap pergantian
     * hari berdasarkan timestamp terakhir.
     */
    private void cleanupDailyData() {

        /*
         * Karena dailyBondXp hanya menyimpan integer,
         * kita tidak bisa mengetahui umur masing-masing
         * entry.
         *
         * Cache ini akan dibersihkan berdasarkan timestamp
         * global pada implementasi berikutnya.
         */
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

        dailyBondXp.clear();
        lastBondGain.clear();
    }
}
