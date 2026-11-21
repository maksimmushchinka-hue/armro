package com.example.weepingmannequin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Запись об одном манекене: мир, точка спавна и последняя известная позиция,
 * а также рантайм-состояние (заморожен ли, таймеры звуков).
 */
public final class MannequinData {

    private UUID id;
    private final String worldName;
    private final double homeX, homeY, homeZ;
    private double x, y, z;
    private float yaw;

    // Рантайм-состояние (в data.yml не сохраняется)
    private boolean frozen;
    private long lastFreezeSoundMs;
    private long movedMs;

    public MannequinData(UUID id, String worldName,
                         double homeX, double homeY, double homeZ,
                         double x, double y, double z, float yaw) {
        this.id = id;
        this.worldName = worldName;
        this.homeX = homeX;
        this.homeY = homeY;
        this.homeZ = homeZ;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getWorldName() { return worldName; }
    public double getHomeX() { return homeX; }
    public double getHomeY() { return homeY; }
    public double getHomeZ() { return homeZ; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }

    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }
    public long getLastFreezeSoundMs() { return lastFreezeSoundMs; }
    public void setLastFreezeSoundMs(long v) { this.lastFreezeSoundMs = v; }
    public long getMovedMs() { return movedMs; }
    public void setMovedMs(long v) { this.movedMs = v; }

    /** Запоминает последнюю известную позицию (чтобы сохранить её в data.yml). */
    public void setLast(Location loc) {
        if (loc == null) return;
        this.x = loc.getX();
        this.y = loc.getY();
        this.z = loc.getZ();
        this.yaw = loc.getYaw();
    }

    /** Мир или null, если он сейчас не загружен — без NPE при работе с другими мирами. */
    public World getWorld() {
        return Bukkit.getWorld(worldName);
    }

    public Location homeLocation() {
        World w = getWorld();
        return w == null ? null : new Location(w, homeX, homeY, homeZ, yaw, 0f);
    }

    public Location lastLocation() {
        World w = getWorld();
        return w == null ? null : new Location(w, x, y, z, yaw, 0f);
    }
}
