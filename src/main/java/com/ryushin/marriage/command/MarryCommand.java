package com.ryushin.marriage.command;

import com.ryushin.marriage.MarriagePlugin;
import com.ryushin.marriage.data.Marriage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MarryCommand implements CommandExecutor {

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
            player.sendMessage("§cGunakan: /marry <propose|accept|deny|divorce|info> [player]");
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
                player.sendMessage("§cAksi tidak dikenal. Gunakan: propose, accept, deny, divorce, info");
                break;
        }
        return true;
    }

    private void handlePropose(Player sender, String[] args) {
        synchronized (marriageLock) {
            if (args.length < 2) {
                sender.sendMessage("§cGunakan: /marry propose <player>");
                return;
            }

            UUID senderUuid = sender.getUniqueId();
            long now = System.currentTimeMillis();
            Long cooldownEnd = cooldowns.get(senderUuid);

            if (cooldownEnd != null) {
                if (cooldownEnd > now) {
                    long remaining = Math.max(1, (cooldownEnd - now + 999L) / 1000L);
                    sender.sendMessage("§cTunggu " + remaining + " detik lagi sebelum membuat lamaran.");
                    return;
                }
                cooldowns.remove(senderUuid);
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer tidak ditemukan atau sedang offline.");
                return;
            }

            UUID targetUuid = target.getUniqueId();
            if (senderUuid.equals(targetUuid)) {
                sender.sendMessage("§cKamu tidak bisa melamar dirimu sendiri.");
                return;
            }

            if (plugin.getDatabase().getMarriageByPlayer(senderUuid) != null) {
                sender.sendMessage("§cKamu sudah menikah!");
                return;
            }

            if (plugin.getDatabase().getMarriageByPlayer(targetUuid) != null) {
                sender.sendMessage("§c" + target.getName() + " sudah menikah dengan orang lain.");
                return;
            }

            if (pendingProposals.containsKey(targetUuid)) {
                sender.sendMessage("§cPlayer tersebut masih memiliki lamaran yang belum dijawab.");
                return;
            }

            pendingProposals.put(targetUuid, senderUuid);
            cooldowns.put(senderUuid, now + COOLDOWN_SECONDS * 1000L);

            sender.sendMessage("§aLamaran terkirim ke " + target.getName() + "! Lamaran berlaku selama " + TIMEOUT_SECONDS + " detik.");
            target.sendMessage("§d" + sender.getName() + " melamarmu! Ketik §f/marry accept §datau §f/marry deny §d(dalam " + TIMEOUT_SECONDS + " detik).");

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
                            targetPlayer.sendMessage("§7Lamaran dari " + (senderPlayer != null ? senderPlayer.getName() : "player") + " telah kadaluarsa.");
                        }
                        if (senderPlayer != null) {
                            senderPlayer.sendMessage("§7Lamaranmu telah kadaluarsa.");
                        }
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
                player.sendMessage("§cTidak ada permintaan nikah yang menunggumu.");
                return;
            }

            // Re-check saat accept agar proposal tidak dapat digunakan
            // setelah salah satu pihak sudah menikah.
            if (plugin.getDatabase().getMarriageByPlayer(playerUuid) != null) {
                pendingProposals.remove(playerUuid);
                player.sendMessage("§cKamu sudah menikah.");
                return;
            }

            if (plugin.getDatabase().getMarriageByPlayer(proposerUuid) != null) {
                pendingProposals.remove(playerUuid);
                player.sendMessage("§cLamaran ini sudah tidak valid karena pengirimnya sudah menikah.");
                return;
            }

            Marriage marriage = new Marriage(proposerUuid, playerUuid);
            plugin.getDatabase().saveMarriage(marriage);
            pendingProposals.remove(playerUuid);

            Player proposer = Bukkit.getPlayer(proposerUuid);
            player.sendMessage("§aSelamat! Kamu sekarang menikah dengan " + (proposer != null ? proposer.getName() : "pasanganmu") + "!");
            if (proposer != null) {
                proposer.sendMessage("§a" + player.getName() + " menerima lamaranmu! Selamat menikah!");
            }
        }
    }

    private void handleDeny(Player player) {
        synchronized (marriageLock) {
            UUID proposerUuid = pendingProposals.remove(player.getUniqueId());
            if (proposerUuid == null) {
                player.sendMessage("§cTidak ada permintaan nikah yang menunggumu.");
                return;
            }

            player.sendMessage("§cKamu menolak permintaan nikah.");
            Player proposer = Bukkit.getPlayer(proposerUuid);
            if (proposer != null) {
                proposer.sendMessage("§c" + player.getName() + " menolak lamaranmu.");
            }
        }
    }

    private void handleDivorce(Player player) {
        synchronized (marriageLock) {
            Marriage marriage = plugin.getDatabase().getMarriageByPlayer(player.getUniqueId());
            if (marriage == null) {
                player.sendMessage("§cKamu belum menikah.");
                return;
            }

            UUID pasanganUuid = marriage.getOtherPlayer(player.getUniqueId());
            plugin.getDatabase().deleteMarriage(marriage.getId());

            player.sendMessage("§cKamu resmi bercerai.");
            Player pasangan = Bukkit.getPlayer(pasanganUuid);
            if (pasangan != null) {
                pasangan.sendMessage("§c" + player.getName() + " mengajukan perceraian denganmu.");
            }
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

        player.sendMessage("§d=== Status Pernikahan ===");
        player.sendMessage("§7Pasangan: §f" + namaPasangan);
        player.sendMessage("§7Bond Level: §f" + marriage.getBondLevel());
        player.sendMessage("§7Bond XP: §f" + marriage.getBondXp());
    }

    public void clearProposalsFor(UUID uuid) {
        synchronized (marriageLock) {
            pendingProposals.remove(uuid);
            pendingProposals.entrySet().removeIf(entry -> uuid.equals(entry.getValue()));
            cooldowns.remove(uuid);
        }
    }
}
