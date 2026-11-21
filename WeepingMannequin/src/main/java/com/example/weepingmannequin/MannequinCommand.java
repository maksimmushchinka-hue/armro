package com.example.weepingmannequin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Команда /mannequin spawn | remove | list. */
public final class MannequinCommand implements TabExecutor {

    private static final List<String> SUB = List.of("spawn", "remove", "list");
    private final MannequinManager manager;

    public MannequinCommand(MannequinManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mannequin.admin")) {
            sender.sendMessage("§cНет прав.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§eИспользование: /" + label + " <spawn|remove|list>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§cТолько для игроков.");
                    return true;
                }
                try {
                    manager.spawn(p.getLocation());
                    sender.sendMessage("§aМанекен создан.");
                } catch (Exception ex) {
                    sender.sendMessage("§cНе удалось создать манекена: " + ex.getMessage());
                }
            }
            case "remove" -> {
                int n = manager.removeAll();
                sender.sendMessage("§aУдалено манекенов: " + n);
            }
            case "list" -> {
                if (manager.all().isEmpty()) {
                    sender.sendMessage("§eМанекенов нет.");
                    return true;
                }
                sender.sendMessage("§6Манекены (" + manager.all().size() + "):");
                int i = 1;
                for (MannequinData d : manager.snapshot()) {
                    Entity e = Bukkit.getEntity(d.getId());
                    Location l = (e instanceof ArmorStand && e.isValid()) ? e.getLocation() : d.lastLocation();
                    boolean loaded = e instanceof ArmorStand && e.isValid();
                    double x = l != null ? l.getX() : d.getX();
                    double y = l != null ? l.getY() : d.getY();
                    double z = l != null ? l.getZ() : d.getZ();
                    sender.sendMessage(String.format(Locale.ROOT, "§7%d. §f%s §7x=%.1f y=%.1f z=%.1f §8(%s)",
                            i++, d.getWorldName(), x, y, z, loaded ? "загружен" : "в выгруженном чанке"));
                }
            }
            default -> sender.sendMessage("§eИспользование: /" + label + " <spawn|remove|list>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : SUB) {
                if (s.startsWith(prefix)) out.add(s);
            }
        }
        return out;
    }
}
