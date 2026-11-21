package com.example.weepingmannequin;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerProfile;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Управляет манекенами: создание, удаление, реестр, сохранение и восстановление.
 *
 * Важно: ArmorStand и так сохраняется самим сервером вместе с чанком. Чтобы не получить
 * дубликаты, при старте мы НЕ спавним заново, а ищем сущность по UUID и пересоздаём манекен
 * только если его реально нет (удалён, убит и т.п.).
 */
public final class MannequinManager {

    private final WeepingMannequinPlugin plugin;
    private final DataStorage storage;
    private final NamespacedKey markerKey;
    private final NamespacedKey homeKey;
    private final Map<UUID, MannequinData> records = new LinkedHashMap<>();

    public MannequinManager(WeepingMannequinPlugin plugin, DataStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        // Ключ в точности как в ТЗ (с одной «n»)
        this.markerKey = new NamespacedKey(plugin, "weeping_manequin");
        this.homeKey = new NamespacedKey(plugin, "home");
    }

    public void loadFromDisk() {
        records.clear();
        records.putAll(storage.load());
    }

    /** Копия списка записей — безопасно итерировать, пока реестр меняется. */
    public List<MannequinData> snapshot() {
        return new ArrayList<>(records.values());
    }

    public boolean isTracked(UUID id) {
        return records.containsKey(id);
    }

    public MannequinData get(UUID id) {
        return records.get(id);
    }

    public boolean isMannequin(Entity e) {
        if (!(e instanceof ArmorStand)) return false;
        return "true".equals(e.getPersistentDataContainer().get(markerKey, PersistentDataType.STRING));
    }

    // ---------------------------------------------------------------- создание

    /** Создаёт нового манекена на месте игрока и регистрирует его. */
    public ArmorStand spawn(Location where) {
        Location loc = where.clone();
        loc.setPitch(0f);
        ArmorStand stand = createStand(loc, loc);
        MannequinData d = new MannequinData(stand.getUniqueId(), loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw());
        records.put(d.getId(), d);
        saveAll();
        return stand;
    }

    private ArmorStand createStand(Location loc, Location home) {
        World w = loc.getWorld();
        return w.spawn(loc, ArmorStand.class, as -> configure(as, home));
    }

    private void configure(ArmorStand as, Location home) {
        as.setSmall(false);
        as.setInvulnerable(true);
        as.setGravity(false);   // двигаем вручную, чтобы ходить по земле
        // marker=true: нулевой хитбокс — по манекену нельзя попасть и нельзя взаимодействовать.
        // На rayTraceBlocks это не влияет (луч проверяет только блоки). Если понадобится
        // проверять сущности (rayTraceEntities), marker-стойку луч НЕ найдёт — тогда нужно
        // выключить marker и использовать small/обычный хитбокс.
        as.setMarker(true);
        as.setArms(true);
        as.setBasePlate(false);
        as.setVisible(true);
        as.setSilent(true);
        as.setPersistent(true);
        as.setRemoveWhenFarAway(false);
        as.setCanPickupItems(false);

        EntityEquipment eq = as.getEquipment();
        if (eq != null) {
            eq.setHelmet(createHead());
            eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, Color.fromRGB(40, 40, 45)));
            eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, Color.fromRGB(25, 25, 30)));
            eq.setBoots(dyed(Material.LEATHER_BOOTS, Color.fromRGB(15, 15, 15)));
        }

        PersistentDataContainer pdc = as.getPersistentDataContainer();
        pdc.set(markerKey, PersistentDataType.STRING, "true");
        pdc.set(homeKey, PersistentDataType.STRING,
                home.getX() + "," + home.getY() + "," + home.getZ());
    }

    private ItemStack createHead() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        String url = plugin.getConfig().getString("head-skin-url", "");
        if (url != null && !url.isBlank() && head.getItemMeta() instanceof SkullMeta meta) {
            try {
                PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "Mannequin");
                profile.getTextures().setSkin(URI.create(url.trim()).toURL());
                meta.setOwnerProfile(profile);
                head.setItemMeta(meta);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Некорректный head-skin-url, использую стандартную голову", ex);
            }
        }
        return head;
    }

    private ItemStack dyed(Material mat, Color color) {
        ItemStack item = new ItemStack(mat);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ------------------------------------------------- восстановление / усыновление

    /** Проверяет все записи (вызывается после старта сервера). */
    public void restoreAll() {
        for (MannequinData d : snapshot()) {
            ensureExists(d);
        }
    }

    /** Вызывается при загрузке мира — подтягивает манекенов этого мира. */
    public void restoreWorld(World world) {
        for (MannequinData d : snapshot()) {
            if (d.getWorldName().equals(world.getName())) {
                ensureExists(d);
            }
        }
    }

    /** Грузит чанк манекена и, если сущности нет, создаёт её заново. */
    private void ensureExists(MannequinData d) {
        World w = d.getWorld();
        if (w == null) return; // мир ещё не загружен — повторим на WorldLoadEvent
        int cx = (int) Math.floor(d.getX()) >> 4;
        int cz = (int) Math.floor(d.getZ()) >> 4;
        try {
            w.getChunkAtAsync(cx, cz).thenAccept(chunk ->
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!records.containsKey(d.getId())) return; // уже удалён командой
                        Entity e = Bukkit.getEntity(d.getId());
                        if (e instanceof ArmorStand) return;          // жив — ничего не делаем
                        respawn(d);
                    }, 5L));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Не удалось проверить манекена " + d.getId(), ex);
        }
    }

    /** Пересоздаёт манекена на последней известной позиции (новый UUID). */
    public void respawn(MannequinData d) {
        try {
            Location last = d.lastLocation();
            Location home = d.homeLocation();
            if (last == null || home == null) return;
            records.remove(d.getId());
            ArmorStand stand = createStand(last, home);
            d.setId(stand.getUniqueId());
            d.setFrozen(false);
            records.put(d.getId(), d);
            saveAll();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось пересоздать манекена", ex);
        }
    }

    /** Отложенное пересоздание (используется при смерти манекена). */
    public void scheduleRespawn(MannequinData d) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (records.containsKey(d.getId()) && !(Bukkit.getEntity(d.getId()) instanceof ArmorStand)) {
                respawn(d);
            }
        });
    }

    /** Подхватывает манекенов, загруженных вместе с чанком (если их нет в реестре). */
    public void adopt(List<Entity> entities) {
        boolean changed = false;
        for (Entity e : entities) {
            if (!(e instanceof ArmorStand) || !isMannequin(e)) continue;
            if (records.containsKey(e.getUniqueId())) continue;
            Location loc = e.getLocation();
            double hx = loc.getX(), hy = loc.getY(), hz = loc.getZ();
            String raw = e.getPersistentDataContainer().get(homeKey, PersistentDataType.STRING);
            if (raw != null) {
                try {
                    String[] p = raw.split(",");
                    hx = Double.parseDouble(p[0]);
                    hy = Double.parseDouble(p[1]);
                    hz = Double.parseDouble(p[2]);
                } catch (Exception ignored) {
                    // оставляем текущую позицию как точку спавна
                }
            }
            MannequinData d = new MannequinData(e.getUniqueId(), loc.getWorld().getName(),
                    hx, hy, hz, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw());
            records.put(d.getId(), d);
            changed = true;
        }
        if (changed) saveAll();
    }

    /** Чанк выгружается — запоминаем позиции манекенов и сохраняем в data.yml. */
    public void handleUnload(List<Entity> entities) {
        boolean changed = false;
        for (Entity e : entities) {
            if (!(e instanceof ArmorStand) || !isMannequin(e)) continue;
            MannequinData d = records.get(e.getUniqueId());
            if (d != null) {
                d.setLast(e.getLocation());
                changed = true;
            }
        }
        if (changed) saveAll();
    }

    // ------------------------------------------------------------------ удаление

    /** Удаляет всех манекенов (и загруженных, и в выгруженных чанках). Возвращает количество. */
    public int removeAll() {
        Set<UUID> removed = new HashSet<>();
        for (MannequinData d : snapshot()) {
            records.remove(d.getId());
            Entity e = Bukkit.getEntity(d.getId());
            if (e == null) {
                World w = d.getWorld();
                if (w != null) {
                    try {
                        w.getChunkAt((int) Math.floor(d.getX()) >> 4, (int) Math.floor(d.getZ()) >> 4);
                        e = Bukkit.getEntity(d.getId());
                    } catch (Exception ex) {
                        plugin.getLogger().log(Level.WARNING, "Не удалось загрузить чанк манекена", ex);
                    }
                }
            }
            if (e != null) {
                e.remove();
                removed.add(e.getUniqueId());
            }
        }
        // Подчищаем «потеряшек» — манекены с меткой, которых нет в реестре
        for (World w : Bukkit.getWorlds()) {
            for (ArmorStand a : w.getEntitiesByClass(ArmorStand.class)) {
                if (isMannequin(a)) {
                    a.remove();
                    removed.add(a.getUniqueId());
                }
            }
        }
        int count = removed.size();
        saveAll();
        return count;
    }

    // ----------------------------------------------------------------- сохранение

    /** Обновляет позиции у загруженных манекенов и пишет data.yml. */
    public void saveAll() {
        for (MannequinData d : records.values()) {
            Entity e = Bukkit.getEntity(d.getId());
            if (e != null && e.isValid()) {
                d.setLast(e.getLocation());
            }
        }
        storage.save(records.values());
    }

    public Collection<MannequinData> all() {
        return records.values();
    }
}
