package com.example.weepingmannequin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Хендлер файла data.yml: чтение и запись позиций манекенов. */
public final class DataStorage {

    private final WeepingMannequinPlugin plugin;
    private final File file;

    public DataStorage(WeepingMannequinPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    /** Загружает все записи. При ошибках чтения отдельные записи пропускаются. */
    public Map<UUID, MannequinData> load() {
        Map<UUID, MannequinData> result = new LinkedHashMap<>();
        if (!file.exists()) return result;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("mannequins");
        if (root == null) return result;

        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                String world = s.getString("world");
                if (world == null) continue;
                MannequinData d = new MannequinData(
                        id, world,
                        s.getDouble("home.x"), s.getDouble("home.y"), s.getDouble("home.z"),
                        s.getDouble("pos.x"), s.getDouble("pos.y"), s.getDouble("pos.z"),
                        (float) s.getDouble("pos.yaw"));
                result.put(id, d);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Не удалось прочитать запись манекена: " + key, ex);
            }
        }
        return result;
    }

    /** Сохраняет все записи в data.yml. */
    public void save(Collection<MannequinData> all) {
        YamlConfiguration cfg = new YamlConfiguration();
        for (MannequinData d : all) {
            String p = "mannequins." + d.getId();
            cfg.set(p + ".world", d.getWorldName());
            cfg.set(p + ".home.x", d.getHomeX());
            cfg.set(p + ".home.y", d.getHomeY());
            cfg.set(p + ".home.z", d.getHomeZ());
            cfg.set(p + ".pos.x", d.getX());
            cfg.set(p + ".pos.y", d.getY());
            cfg.set(p + ".pos.z", d.getZ());
            cfg.set(p + ".pos.yaw", (double) d.getYaw());
        }
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить data.yml", ex);
        }
    }
}
