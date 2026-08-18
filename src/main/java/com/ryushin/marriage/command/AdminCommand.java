package com.ryushin.marriage.command;

import com.ryushin.marriage.MarriagePlugin;
import com.ryushin.marriage.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Command admin buat maintenance plugin sehari-hari:
 * - /marriageadmin reload  -> reload config.yml tanpa restart server
 * - /marriageadmin debug   -> lihat status debug mode saat ini
 * - /marriageadmin debug on|off -> nyalakan/matikan debug mode
 *
 * Permission "marriage.admin" sudah dicek otomatis oleh Bukkit lewat
 * permission-message di plugin.yml, tapi tetap dicek manual di sini
 * juga sebagai lapisan kedua (jaga-jaga console/plugin lain manggil langsung).
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private final MarriagePlugin plugin;

    public AdminCommand(MarriagePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("marriage.admin")) {
            sender.sendMessage(MessageUtil.get(plugin, "no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(MessageUtil.get(plugin, "admin-usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "debug" -> handleDebug(sender, args);
            default -> sender.sendMessage(MessageUtil.get(plugin, "admin-unknown-action"));
        }

        return true;
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadPluginConfig();
        sender.sendMessage(MessageUtil.get(plugin, "config-reloaded"));
    }

    private void handleDebug(CommandSender sender, String[] args) {
        // /marriageadmin debug (tanpa argumen tambahan) -> cuma tampilkan status
        if (args.length < 2) {
            String status = plugin.isDebug() ? "&aON" : "&cOFF";
            sender.sendMessage(MessageUtil.get(plugin, "debug-status", Map.of("status", status)));
            sender.sendMessage(MessageUtil.get(plugin, "debug-usage"));
            return;
        }

        boolean newState = switch (args[1].toLowerCase()) {
            case "on", "true", "enable" -> true;
            case "off", "false", "disable" -> false;
            default -> plugin.isDebug(); // input tidak dikenal -> biarkan tetap seperti sekarang
        };

        plugin.setDebug(newState);

        String status = newState ? "&aON" : "&cOFF";
        sender.sendMessage(MessageUtil.get(plugin, "debug-changed", Map.of("status", status)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("marriage.admin")) {
            return List.of(); // jangan kasih saran ke yang nggak punya izin
        }

        if (args.length == 1) {
            List<String> aksi = List.of("reload", "debug");
            List<String> hasil = new ArrayList<>();
            for (String a : aksi) {
                if (a.startsWith(args[0].toLowerCase())) {
                    hasil.add(a);
                }
            }
            return hasil;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            List<String> opsi = List.of("on", "off");
            List<String> hasil = new ArrayList<>();
            for (String o : opsi) {
                if (o.startsWith(args[1].toLowerCase())) {
                    hasil.add(o);
                }
            }
            return hasil;
        }

        return List.of();
    }
}
