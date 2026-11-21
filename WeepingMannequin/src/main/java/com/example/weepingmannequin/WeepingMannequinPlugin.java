package com.example.weepingmannequin;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Главный класс плагина «Плачущие ангелы» (WeepingMannequin). */
public final class WeepingMannequinPlugin extends JavaPlugin {

    private MannequinManager manager;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        DataStorage storage = new DataStorage(this);
        manager = new MannequinManager(this, storage);
        manager.loadFromDisk();

        getServer().getPluginManager().registerEvents(new MannequinListener(manager), this);

        PluginCommand cmd = getCommand("mannequin");
        if (cmd != null) {
            MannequinCommand handler = new MannequinCommand(manager);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        // Восстановление манекенов — через тик, когда миры уже загружены
        Bukkit.getScheduler().runTask(this, manager::restoreAll);

        // Основной цикл: каждые 2 тика
        task = new MannequinTask(this, manager).runTaskTimer(this, 20L, MannequinTask.INTERVAL_TICKS);
        getLogger().info("WeepingMannequin включён.");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (manager != null) manager.saveAll();
    }
}
