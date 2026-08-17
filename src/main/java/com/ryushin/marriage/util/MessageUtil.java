package com.ryushin.marriage.util;

import com.ryushin.marriage.MarriagePlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;

/**
 * Helper terpusat buat ambil pesan dari config.yml (path "messages.<key>"),
 * ganti placeholder (%player%, %level%, dst), lalu translate kode warna (&).
 *
 * Kenapa dipisah jadi class sendiri:
 * - Semua command/manager tinggal panggil MessageUtil.get(...) daripada
 *   masing-masing nulis getConfig().getString(...) + replace + translate sendiri.
 * - Kalau admin server ganti config.yml (reload), pesan otomatis ikut berubah
 *   tanpa perlu recompile plugin.
 */
public final class MessageUtil {

    private MessageUtil() {
        // utility class, tidak perlu di-instantiate
    }

    /**
     * Ambil pesan dari config.yml path "messages.<key>", tempel prefix di depan,
     * translate kode warna "&", tanpa placeholder tambahan.
     */
    public static String get(MarriagePlugin plugin, String key) {
        return get(plugin, key, Map.of());
    }

    /**
     * Sama seperti get(plugin, key), tapi dengan placeholder.
     * Contoh pemanggilan:
     *   MessageUtil.get(plugin, "bond-level-up", Map.of("level", "5"))
     * akan ganti semua "%level%" di dalam string config jadi "5".
     */
    public static String get(MarriagePlugin plugin, String key, Map<String, String> placeholders) {
        FileConfiguration config = plugin.getConfig();

        String prefix = config.getString("messages.prefix", "");
        String raw = config.getString("messages." + key);

        if (raw == null) {
            // Fallback biar server tidak error kalau key belum ada di config.yml,
            // sekaligus kasih tahu admin bahwa key-nya hilang.
            plugin.getLogger().warning("[MessageUtil] Key 'messages." + key + "' tidak ditemukan di config.yml");
            return ChatColor.translateAlternateColorCodes('&', prefix + "&c[pesan hilang: " + key + "]");
        }

        String hasil = prefix + raw;

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            hasil = hasil.replace("%" + entry.getKey() + "%", entry.getValue());
        }

        return ChatColor.translateAlternateColorCodes('&', hasil);
    }
}
