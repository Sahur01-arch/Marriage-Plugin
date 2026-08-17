package com.ryushin.marriage.command;

import com.ryushin.marriage.MarriagePlugin;
import com.ryushin.marriage.data.FamilyRelation;
import com.ryushin.marriage.data.Marriage;
import com.ryushin.marriage.data.RelationType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FamilyCommand implements CommandExecutor {

    private final MarriagePlugin plugin;

    // Adopsi yang lagi nunggu jawaban: key = calon anak, value = calon orang tua yang ngajak
    private final Map<UUID, UUID> pendingAdoptions = new HashMap<>();

    private static final int TIMEOUT_SECONDS = 30;

    public FamilyCommand(MarriagePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Command ini cuma bisa dijalankan oleh player.");
            return true;
        }

        if (!plugin.isFamilySystemEnabled()) {
            player.sendMessage("§cSistem keluarga sedang dinonaktifkan.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§cGunakan: /family <adopt|accept|deny|list|info> [player]");
            return true;
        }

        String aksi = args[0].toLowerCase();

        switch (aksi) {
            case "adopt" -> handleAdopt(player, args);
            case "accept" -> handleAcceptAdoption(player);
            case "deny" -> handleDenyAdoption(player);
            case "list" -> handleList(player);
            case "info" -> handleInfo(player, args);
            case "remove" -> handleRemoveChild(player, args);
            default -> player.sendMessage("§cAksi tidak dikenal.");
        }

        return true;
    }

    private void handleAdopt(Player sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cGunakan: /family adopt <player>");
            return;
        }

        if (!plugin.getConfig().getBoolean("family.allow-adoption", true)) {
            sender.sendMessage("§cFitur adopsi sedang dinonaktifkan.");
            return;
        }

        // Syarat: harus sudah menikah dulu baru bisa adopsi
        Marriage marriage = plugin.getDatabase().getMarriageByPlayer(sender.getUniqueId());
        if (marriage == null) {
            sender.sendMessage("§cKamu harus menikah dulu sebelum bisa mengadopsi anak.");
            return;
        }

        // Cek limit jumlah anak
        int maxChildren = plugin.getConfig().getInt("family.max-children", 4);
        int currentChildren = plugin.getDatabase().countChildren(sender.getUniqueId());
        if (currentChildren >= maxChildren) {
            sender.sendMessage("§cKamu sudah mencapai batas maksimal anak (" + maxChildren + ").");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer tidak ditemukan atau sedang offline.");
            return;
        }

        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage("§cKamu tidak bisa mengadopsi diri sendiri.");
            return;
        }

        // Cek target sudah punya orang tua belum
        List<FamilyRelation> targetParents = plugin.getDatabase().getRelationsByPlayer(target.getUniqueId(), RelationType.CHILD);
        if (!targetParents.isEmpty()) {
            sender.sendMessage("§c" + target.getName() + " sudah punya orang tua.");
            return;
        }

        pendingAdoptions.put(target.getUniqueId(), sender.getUniqueId());

        sender.sendMessage("§aPermintaan adopsi terkirim ke " + target.getName() + "!");
        target.sendMessage("§d" + sender.getName() + " ingin mengadopsimu sebagai anak! Ketik /family accept dalam " + TIMEOUT_SECONDS + " detik.");

        UUID targetUuid = target.getUniqueId();
        UUID senderUuid = sender.getUniqueId();

        new BukkitRunnable() {
            @Override
            public void run() {
                UUID masihAda = pendingAdoptions.get(targetUuid);
                if (masihAda != null && masihAda.equals(senderUuid)) {
                    pendingAdoptions.remove(targetUuid);
                    Player t = Bukkit.getPlayer(targetUuid);
                    if (t != null) t.sendMessage("§7Permintaan adopsi sudah kadaluarsa.");
                }
            }
        }.runTaskLater(plugin, TIMEOUT_SECONDS * 20L);
    }

    private void handleAcceptAdoption(Player player) {
        UUID parentUuid = pendingAdoptions.get(player.getUniqueId());
        if (parentUuid == null) {
            player.sendMessage("§cTidak ada permintaan adopsi yang menunggumu.");
            return;
        }

        // Ambil data pernikahan si orang tua, biar pasangannya juga otomatis jadi orang tua
        Marriage marriage = plugin.getDatabase().getMarriageByPlayer(parentUuid);
        UUID spouseUuid = marriage != null ? marriage.getOtherPlayer(parentUuid) : null;

        UUID childUuid = player.getUniqueId();

        // Simpan relasi: parentUuid adalah PARENT dari childUuid, dan sebaliknya
        plugin.getDatabase().saveFamilyRelation(new FamilyRelation(parentUuid, childUuid, RelationType.PARENT));
        plugin.getDatabase().saveFamilyRelation(new FamilyRelation(childUuid, parentUuid, RelationType.CHILD));

        // Kalau si orang tua punya pasangan, pasangannya juga otomatis jadi orang tua
        if (spouseUuid != null) {
            plugin.getDatabase().saveFamilyRelation(new FamilyRelation(spouseUuid, childUuid, RelationType.PARENT));
            plugin.getDatabase().saveFamilyRelation(new FamilyRelation(childUuid, spouseUuid, RelationType.CHILD));
        }

        pendingAdoptions.remove(player.getUniqueId());

        OfflinePlayer parent = Bukkit.getOfflinePlayer(parentUuid);
        player.sendMessage("§aKamu sekarang menjadi anak dari keluarga " + parent.getName() + "!");

        Player parentOnline = Bukkit.getPlayer(parentUuid);
        if (parentOnline != null) {
            parentOnline.sendMessage("§a" + player.getName() + " menerima adopsimu!");
        }
    }

    private void handleDenyAdoption(Player player) {
        UUID parentUuid = pendingAdoptions.remove(player.getUniqueId());
        if (parentUuid == null) {
            player.sendMessage("§cTidak ada permintaan adopsi yang menunggumu.");
            return;
        }

        player.sendMessage("§cKamu menolak permintaan adopsi.");
        Player parent = Bukkit.getPlayer(parentUuid);
        if (parent != null) {
            parent.sendMessage("§c" + player.getName() + " menolak adopsimu.");
        }
    }

    private void handleList(Player player) {
        UUID uuid = player.getUniqueId();

        List<FamilyRelation> children = plugin.getDatabase().getRelationsByPlayer(uuid, RelationType.PARENT);
        List<FamilyRelation> parents = plugin.getDatabase().getRelationsByPlayer(uuid, RelationType.CHILD);

        player.sendMessage("§d=== Keluarga " + player.getName() + " ===");

        // Tampilkan orang tua
        if (!parents.isEmpty()) {
            player.sendMessage("§7Orang tua:");
            for (FamilyRelation rel : parents) {
                String nama = Bukkit.getOfflinePlayer(rel.getRelatedUuid()).getName();
                player.sendMessage("  §f- " + nama);
            }

            // Cari kakek/nenek: orang tua dari orang tua
            player.sendMessage("§7Kakek/Nenek:");
            for (FamilyRelation ortu : parents) {
                List<FamilyRelation> kakekNenek = plugin.getDatabase().getRelationsByPlayer(ortu.getRelatedUuid(), RelationType.CHILD);
                for (FamilyRelation kn : kakekNenek) {
                    String nama = Bukkit.getOfflinePlayer(kn.getRelatedUuid()).getName();
                    player.sendMessage("  §f- " + nama);
                }
            }
        }

        // Tampilkan anak
        if (!children.isEmpty()) {
            player.sendMessage("§7Anak:");
            for (FamilyRelation rel : children) {
                String nama = Bukkit.getOfflinePlayer(rel.getRelatedUuid()).getName();
                player.sendMessage("  §f- " + nama);
            }
        }

        if (parents.isEmpty() && children.isEmpty()) {
            player.sendMessage("§7Kamu belum punya relasi keluarga.");
        }
    }

    /**
     * Emergency patch:
     * /family remove <player>
     *
     * Mengeluarkan anak dari keluarga dan menghapus kedua sisi relasi.
     */
    private void handleRemoveChild(Player sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cGunakan: /family remove <player>");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        UUID childUuid;

        if (target != null) {
            childUuid = target.getUniqueId();
        } else {
            OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(args[1]);
            childUuid = offlineTarget.getUniqueId();
        }

        UUID parentUuid = sender.getUniqueId();

        List<FamilyRelation> parentRelations =
                plugin.getDatabase().getRelationsByPlayer(
                        parentUuid,
                        RelationType.PARENT
                );

        FamilyRelation relation = parentRelations.stream()
                .filter(r -> r.getRelatedUuid().equals(childUuid))
                .findFirst()
                .orElse(null);

        if (relation == null) {
            sender.sendMessage("§cPlayer tersebut bukan anakmu.");
            return;
        }

        plugin.getDatabase().deleteFamilyRelation(
                parentUuid,
                childUuid,
                RelationType.PARENT
        );

        plugin.getDatabase().deleteFamilyRelation(
                childUuid,
                parentUuid,
                RelationType.CHILD
        );

        sender.sendMessage(
                "§a" + args[1] + " berhasil dikeluarkan dari keluarga."
        );

        if (target != null) {
            target.sendMessage(
                    "§eKamu tidak lagi terdaftar sebagai anak dari "
                            + sender.getName() + "."
            );
        }
    }

    private void handleInfo(Player player, String[] args) {
        // Sederhana: /family info tanpa target = tampilkan punya sendiri
        handleList(player);
    }

    public void clearAdoptionsFor(UUID uuid) {
        pendingAdoptions.remove(uuid);
        pendingAdoptions.values().removeIf(v -> v.equals(uuid));
    }
}
