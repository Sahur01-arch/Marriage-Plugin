package com.ryushin.marriage.command;

import com.ryushin.marriage.MarriagePlugin;
import com.ryushin.marriage.data.FamilyRelation;
import com.ryushin.marriage.data.Marriage;
import com.ryushin.marriage.data.RelationType;
import com.ryushin.marriage.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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

public class FamilyCommand implements CommandExecutor, TabCompleter {

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
            player.sendMessage(MessageUtil.get(plugin, "family-disabled"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(MessageUtil.get(plugin, "family-usage"));
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
            default -> player.sendMessage(MessageUtil.get(plugin, "family-unknown-action"));
        }

        return true;
    }

    private void handleAdopt(Player sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MessageUtil.get(plugin, "family-usage-adopt"));
            return;
        }

        if (!plugin.getConfig().getBoolean("family.allow-adoption", true)) {
            sender.sendMessage(MessageUtil.get(plugin, "family-adoption-disabled"));
            return;
        }

        // Syarat: harus sudah menikah dulu baru bisa adopsi
        Marriage marriage = plugin.getDatabase().getMarriageByPlayer(sender.getUniqueId());
        if (marriage == null) {
            sender.sendMessage(MessageUtil.get(plugin, "family-must-be-married"));
            return;
        }

        // Cek limit jumlah anak
        int maxChildren = plugin.getConfig().getInt("family.max-children", 4);
        int currentChildren = plugin.getDatabase().countChildren(sender.getUniqueId());
        if (currentChildren >= maxChildren) {
            sender.sendMessage(MessageUtil.get(plugin, "family-max-children",
                    Map.of("max", String.valueOf(maxChildren))));
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(MessageUtil.get(plugin, "player-not-found"));
            return;
        }

        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(MessageUtil.get(plugin, "family-cannot-adopt-self"));
            return;
        }

        // Cek target sudah punya orang tua belum
        List<FamilyRelation> targetParents = plugin.getDatabase().getRelationsByPlayer(target.getUniqueId(), RelationType.CHILD);
        if (!targetParents.isEmpty()) {
            sender.sendMessage(MessageUtil.get(plugin, "family-target-has-parent",
                    Map.of("player", target.getName())));
            return;
        }

        pendingAdoptions.put(target.getUniqueId(), sender.getUniqueId());

        sender.sendMessage(MessageUtil.get(plugin, "family-adopt-sent", Map.of("player", target.getName())));
        target.sendMessage(MessageUtil.get(plugin, "family-adopt-received", Map.of(
                "player", sender.getName(),
                "timeout", String.valueOf(TIMEOUT_SECONDS)
        )));

        plugin.debugLog("Adopt request: " + sender.getName() + " -> " + target.getName());

        UUID targetUuid = target.getUniqueId();
        UUID senderUuid = sender.getUniqueId();

        new BukkitRunnable() {
            @Override
            public void run() {
                UUID masihAda = pendingAdoptions.get(targetUuid);
                if (masihAda != null && masihAda.equals(senderUuid)) {
                    pendingAdoptions.remove(targetUuid);
                    Player t = Bukkit.getPlayer(targetUuid);
                    if (t != null) t.sendMessage(MessageUtil.get(plugin, "family-adopt-expired"));
                    plugin.debugLog("Adopt timeout: " + senderUuid + " -> " + targetUuid);
                }
            }
        }.runTaskLater(plugin, TIMEOUT_SECONDS * 20L);
    }

    private void handleAcceptAdoption(Player player) {
        UUID parentUuid = pendingAdoptions.get(player.getUniqueId());
        if (parentUuid == null) {
            player.sendMessage(MessageUtil.get(plugin, "family-no-pending-adoption"));
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
        player.sendMessage(MessageUtil.get(plugin, "family-adopt-success", Map.of("player", parent.getName())));

        Player parentOnline = Bukkit.getPlayer(parentUuid);
        if (parentOnline != null) {
            parentOnline.sendMessage(MessageUtil.get(plugin, "family-adopt-success-notify",
                    Map.of("player", player.getName())));
        }

        plugin.debugLog("Adoption confirmed: " + parentUuid + " adopted " + childUuid);
    }

    private void handleDenyAdoption(Player player) {
        UUID parentUuid = pendingAdoptions.remove(player.getUniqueId());
        if (parentUuid == null) {
            player.sendMessage(MessageUtil.get(plugin, "family-no-pending-adoption"));
            return;
        }

        player.sendMessage(MessageUtil.get(plugin, "family-adopt-denied"));
        Player parent = Bukkit.getPlayer(parentUuid);
        if (parent != null) {
            parent.sendMessage(MessageUtil.get(plugin, "family-adopt-denied-notify",
                    Map.of("player", player.getName())));
        }
    }

    private void handleList(Player player) {
        UUID uuid = player.getUniqueId();

        List<FamilyRelation> children = plugin.getDatabase().getRelationsByPlayer(uuid, RelationType.PARENT);
        List<FamilyRelation> parents = plugin.getDatabase().getRelationsByPlayer(uuid, RelationType.CHILD);

        // Kakek/nenek dihitung dari 2 langkah: orang tua dari tiap orang tuaku
        List<UUID> kakekNenekUuid = new java.util.ArrayList<>();
        for (FamilyRelation ortu : parents) {
            List<FamilyRelation> ortuDariOrtu = plugin.getDatabase()
                    .getRelationsByPlayer(ortu.getRelatedUuid(), RelationType.CHILD);
            for (FamilyRelation kn : ortuDariOrtu) {
                kakekNenekUuid.add(kn.getRelatedUuid());
            }
        }

        player.sendMessage("§8§m                                        ");
        player.sendMessage("§d§l          ❀ POHON KELUARGA ❀");
        player.sendMessage("§8§m                                        ");
        player.sendMessage("");

        tampilkanBagian(player, "Orang Tua", parents.stream().map(FamilyRelation::getRelatedUuid).toList());
        tampilkanBagian(player, "Kakek/Nenek", kakekNenekUuid);
        tampilkanBagian(player, "Anak", children.stream().map(FamilyRelation::getRelatedUuid).toList());

        if (parents.isEmpty() && children.isEmpty()) {
            player.sendMessage(" §7Kamu belum punya relasi keluarga.");
            player.sendMessage("");
        }

        player.sendMessage("§8§m                                        ");
    }

    /**
     * Cetak 1 bagian pohon keluarga (Orang Tua / Kakek-Nenek / Anak) dengan
     * status online/offline tiap anggotanya. Kalau list-nya kosong, bagian
     * ini di-skip total (nggak nampilin header kosong).
     */
    private void tampilkanBagian(Player viewer, String label, List<UUID> uuids) {
        if (uuids.isEmpty()) return;

        sendLine(viewer, " §7" + label + " §8(" + uuids.size() + ")");
        for (UUID relUuid : uuids) {
            Player online = Bukkit.getPlayer(relUuid);
            String nama = online != null ? online.getName() : Bukkit.getOfflinePlayer(relUuid).getName();
            if (nama == null) nama = relUuid.toString();

            String status = online != null ? "§a● Online" : "§7● Offline";
            sendLine(viewer, "   §8» §f" + nama + "  " + status);
        }
        sendLine(viewer, "");
    }

    // Helper kecil biar baris di atas nggak kepanjangan - cuma alias sendMessage
    private void sendLine(Player p, String msg) {
        p.sendMessage(msg);
    }

    /**
     * /family remove <player>
     * Mengeluarkan anak dari keluarga dan menghapus kedua sisi relasi.
     */
    private void handleRemoveChild(Player sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MessageUtil.get(plugin, "family-usage-remove"));
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
            sender.sendMessage(MessageUtil.get(plugin, "family-not-your-child"));
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

        sender.sendMessage(MessageUtil.get(plugin, "family-remove-success", Map.of("player", args[1])));

        if (target != null) {
            target.sendMessage(MessageUtil.get(plugin, "family-remove-notify",
                    Map.of("player", sender.getName())));
        }

        plugin.debugLog("Family relation removed: " + parentUuid + " x " + childUuid);
    }

    private void handleInfo(Player player, String[] args) {
        // Sederhana: /family info tanpa target = tampilkan punya sendiri
        handleList(player);
    }

    public void clearAdoptionsFor(UUID uuid) {
        pendingAdoptions.remove(uuid);
        pendingAdoptions.values().removeIf(v -> v.equals(uuid));
    }

    /**
     * Saran auto-complete /family.
     * - Argumen ke-2 cuma disaranin buat aksi yang memang butuh nama player
     *   (adopt, remove) - accept/deny/list/info nggak perlu argumen tambahan.
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> aksi = List.of("adopt", "accept", "deny", "list", "info", "remove");
            List<String> hasil = new ArrayList<>();
            for (String a : aksi) {
                if (a.startsWith(args[0].toLowerCase())) {
                    hasil.add(a);
                }
            }
            return hasil;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("adopt") || args[0].equalsIgnoreCase("remove"))) {
            List<String> hasil = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    hasil.add(p.getName());
                }
            }
            return hasil;
        }

        return List.of();
    }
}
