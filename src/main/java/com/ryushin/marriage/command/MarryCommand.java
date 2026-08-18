package com.ryushin.marriage.command;

import com.ryushin.marriage.MarriagePlugin;
import com.ryushin.marriage.data.Marriage;
import com.ryushin.marriage.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MarryCommand implements CommandExecutor, TabCompleter {

    private final MarriagePlugin plugin;
    private final Map<UUID, UUID> pendingProposals = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Object marriageLock = new Object();

    private static final int TIMEOUT_SECONDS = 120;
    private static final int COOLDOWN_SECONDS = 60;

    public MarryCommand(MarriagePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Command ini hanya bisa digunakan oleh player.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage(MessageUtil.get(plugin, "usage-marry"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "propose":
                handlePropose(player, args);
                break;
            case "accept":
                handleAccept(player);
                break;
            case "deny":
                handleDeny(player);
                break;
            case "divorce":
                handleDivorce(player);
                break;
            case "info":
                handleInfo(player);
                break;
            default:
                player.sendMessage(MessageUtil.get(plugin, "unknown-action"));
                break;
        }
        return true;
    }

    private void handlePropose(Player sender, String[] args) {
        synchronized (marriageLock) {
            if (args.length < 2) {
                sender.sendMessage(MessageUtil.get(plugin, "usage-propose"));
                return;
            }

            UUID senderUuid = sender.getUniqueId();
            long now = System.currentTimeMillis();
            Long cooldownEnd = cooldowns.get(senderUuid);

            if (cooldownEnd != null) {
                if (cooldownEnd > now) {
                    long remaining = Math.max(1, (cooldownEnd - now + 999L) / 1000L);
                    sender.sendMessage(MessageUtil.get(plugin, "cooldown-active",
                            Map.of("seconds", String.valueOf(remaining))));
                    return;
                }
                cooldowns.remove(senderUuid);
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(MessageUtil.get(plugin, "player-not-found"));
                return;
            }

            UUID targetUuid = target.getUniqueId();
            if (senderUuid.equals(targetUuid)) {
                sender.sendMessage(MessageUtil.get(plugin, "cannot-propose-self"));
                return;
            }

            if (plugin.getDatabase().getMarriageByPlayer(senderUuid) != null) {
                sender.sendMessage(MessageUtil.get(plugin, "already-married-self"));
                return;
            }

            if (plugin.getDatabase().getMarriageByPlayer(targetUuid) != null) {
                sender.sendMessage(MessageUtil.get(plugin, "target-already-married",
                        Map.of("player", target.getName())));
                return;
            }

            if (pendingProposals.containsKey(targetUuid)) {
                sender.sendMessage(MessageUtil.get(plugin, "target-has-pending-proposal"));
                return;
            }

            pendingProposals.put(targetUuid, senderUuid);
            cooldowns.put(senderUuid, now + COOLDOWN_SECONDS * 1000L);

            sender.sendMessage(MessageUtil.get(plugin, "propose-sent", Map.of(
                    "player", target.getName(),
                    "timeout", String.valueOf(TIMEOUT_SECONDS)
            )));
            target.sendMessage(MessageUtil.get(plugin, "propose-received", Map.of(
                    "player", sender.getName(),
                    "timeout", String.valueOf(TIMEOUT_SECONDS)
            )));

            plugin.debugLog("Propose: " + sender.getName() + " -> " + target.getName());

            new BukkitRunnable() {
                @Override
                public void run() {
                    synchronized (marriageLock) {
                        UUID current = pendingProposals.get(targetUuid);
                        if (!senderUuid.equals(current)) {
                            return;
                        }

                        pendingProposals.remove(targetUuid);

                        Player targetPlayer = Bukkit.getPlayer(targetUuid);
                        Player senderPlayer = Bukkit.getPlayer(senderUuid);

                        if (targetPlayer != null) {
                            String namaSender = senderPlayer != null ? senderPlayer.getName() : "player";
                            targetPlayer.sendMessage(MessageUtil.get(plugin, "propose-expired-target",
                                    Map.of("player", namaSender)));
                        }
                        if (senderPlayer != null) {
                            senderPlayer.sendMessage(MessageUtil.get(plugin, "propose-expired-sender"));
                        }

                        plugin.debugLog("Propose timeout: " + senderUuid + " -> " + targetUuid);
                    }
                }
            }.runTaskLater(plugin, TIMEOUT_SECONDS * 20L);
        }
    }

    private void handleAccept(Player player) {
        synchronized (marriageLock) {
            UUID playerUuid = player.getUniqueId();
            UUID proposerUuid = pendingProposals.get(playerUuid);

            if (proposerUuid == null) {
                player.sendMessage(MessageUtil.get(plugin, "no-pending-proposal"));
                return;
            }

            // Re-check saat accept agar proposal tidak dapat digunakan
            // setelah salah satu pihak sudah menikah.
            if (plugin.getDatabase().getMarriageByPlayer(playerUuid) != null) {
                pendingProposals.remove(playerUuid);
                player.sendMessage(MessageUtil.get(plugin, "already-married-self"));
                return;
            }

            if (plugin.getDatabase().getMarriageByPlayer(proposerUuid) != null) {
                pendingProposals.remove(playerUuid);
                player.sendMessage(MessageUtil.get(plugin, "proposal-invalid-already-married"));
                return;
            }

            Marriage marriage = new Marriage(proposerUuid, playerUuid);
            plugin.getDatabase().saveMarriage(marriage);
            pendingProposals.remove(playerUuid);

            Player proposer = Bukkit.getPlayer(proposerUuid);
            String namaProposer = proposer != null ? proposer.getName() : "pasanganmu";
            player.sendMessage(MessageUtil.get(plugin, "marriage-success", Map.of("player", namaProposer)));
            if (proposer != null) {
                proposer.sendMessage(MessageUtil.get(plugin, "marriage-success-notify-proposer",
                        Map.of("player", player.getName())));
            }

            plugin.debugLog("Marriage created: " + proposerUuid + " + " + playerUuid);
        }
    }

    private void handleDeny(Player player) {
        synchronized (marriageLock) {
            UUID proposerUuid = pendingProposals.remove(player.getUniqueId());
            if (proposerUuid == null) {
                player.sendMessage(MessageUtil.get(plugin, "no-pending-proposal"));
                return;
            }

            player.sendMessage(MessageUtil.get(plugin, "propose-denied"));
            Player proposer = Bukkit.getPlayer(proposerUuid);
            if (proposer != null) {
                proposer.sendMessage(MessageUtil.get(plugin, "propose-denied-notify",
                        Map.of("player", player.getName())));
            }
        }
    }

    private void handleDivorce(Player player) {
        synchronized (marriageLock) {
            Marriage marriage = plugin.getDatabase().getMarriageByPlayer(player.getUniqueId());
            if (marriage == null) {
                player.sendMessage(MessageUtil.get(plugin, "not-married"));
                return;
            }

            UUID pasanganUuid = marriage.getOtherPlayer(player.getUniqueId());
            plugin.getDatabase().deleteMarriage(marriage.getId());

            player.sendMessage(MessageUtil.get(plugin, "divorce-success"));
            Player pasangan = Bukkit.getPlayer(pasanganUuid);
            if (pasangan != null) {
                pasangan.sendMessage(MessageUtil.get(plugin, "divorce-notify",
                        Map.of("player", player.getName())));
            }

            plugin.debugLog("Divorce: " + player.getUniqueId() + " x " + pasanganUuid);
        }
    }

    private void handleInfo(Player player) {
        Marriage marriage = plugin.getDatabase().getMarriageByPlayer(player.getUniqueId());
        if (marriage == null) {
            player.sendMessage("§7Kamu belum menikah.");
            return;
        }

        UUID pasanganUuid = marriage.getOtherPlayer(player.getUniqueId());
        Player pasangan = Bukkit.getPlayer(pasanganUuid);
        String namaPasangan = pasangan != null ? pasangan.getName() : Bukkit.getOfflinePlayer(pasanganUuid).getName();
        if (namaPasangan == null) {
            namaPasangan = pasanganUuid.toString();
        }

        boolean online = pasangan != null;
        String statusOnline = online ? "§a● Online" : "§7● Offline";

        int xpPerLevel = plugin.getConfig().getInt("bond.xp-per-level", 100);
        int level = marriage.getBondLevel();
        int xp = marriage.getBondXp();
        int xpDibutuhkan = xpPerLevel * level;

        String progressBar = buatProgressBar(xp, xpDibutuhkan, 20);
        String lamaMenikah = formatDurasi(System.currentTimeMillis() - marriage.getMarriedAt());

        player.sendMessage("§8§m                                        ");
        player.sendMessage("§d§l          ❤ STATUS PERNIKAHAN ❤");
        player.sendMessage("§8§m                                        ");
        player.sendMessage("");
        player.sendMessage(" §7Pasangan   §8» §f" + namaPasangan + "  " + statusOnline);
        player.sendMessage(" §7Menikah sejak §8» §f" + lamaMenikah + " yang lalu");
        player.sendMessage("");
        player.sendMessage(" §7Bond Level §8» §e★ Level " + level);
        player.sendMessage(" " + progressBar + " §7" + xp + "§8/§7" + xpDibutuhkan + " §fXP");
        player.sendMessage("");
        player.sendMessage("§8§m                                        ");
    }

    /**
     * Bikin progress bar teks pakai karakter blok, contoh hasil:
     * "§d██████████§7░░░░░░░░░░"
     */
    private String buatProgressBar(int current, int max, int panjang) {
        if (max <= 0) max = 1; // jaga-jaga cegah pembagian dengan nol

        int terisi = (int) Math.min(panjang, Math.round((current / (double) max) * panjang));
        int kosong = panjang - terisi;

        StringBuilder bar = new StringBuilder("§d");
        bar.append("█".repeat(Math.max(0, terisi)));
        bar.append("§7");
        bar.append("░".repeat(Math.max(0, kosong)));

        return bar.toString();
    }

    /**
     * Ubah selisih waktu (milidetik) jadi teks durasi ringkas,
     * contoh: "3 hari 4 jam" atau "12 menit".
     */
    private String formatDurasi(long selisihMillis) {
        long detik = selisihMillis / 1000;
        long hari = detik / 86400;
        long jam = (detik % 86400) / 3600;
        long menit = (detik % 3600) / 60;

        if (hari > 0) {
            return hari + " hari " + jam + " jam";
        } else if (jam > 0) {
            return jam + " jam " + menit + " menit";
        } else if (menit > 0) {
            return menit + " menit";
        } else {
            return "baru saja";
        }
    }

    public void clearProposalsFor(UUID uuid) {
        synchronized (marriageLock) {
            pendingProposals.remove(uuid);
            pendingProposals.entrySet().removeIf(entry -> uuid.equals(entry.getValue()));
            cooldowns.remove(uuid);
        }
    }

    /**
     * Saran auto-complete saat player ngetik /marry lalu tekan Tab.
     * - Argumen ke-1 (aksi): saring dari daftar tetap sesuai huruf yang sudah diketik.
     * - Argumen ke-2 (nama player): CUMA muncul buat aksi "propose", karena
     *   accept/deny/divorce/info nggak butuh nama target (otomatis dari data
     *   proposal/marriage yang tersimpan).
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> aksi = List.of("propose", "accept", "deny", "divorce", "info");
            List<String> hasil = new ArrayList<>();
            for (String a : aksi) {
                if (a.startsWith(args[0].toLowerCase())) {
                    hasil.add(a);
                }
            }
            return hasil;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("propose")) {
            List<String> hasil = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    hasil.add(p.getName());
                }
            }
            return hasil;
        }

        return List.of(); // tidak ada saran buat argumen selanjutnya
    }
}
