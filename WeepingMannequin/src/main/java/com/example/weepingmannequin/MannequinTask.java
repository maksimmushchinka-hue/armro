package com.example.weepingmannequin;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;

/** Основной цикл: раз в 2 тика двигает или замораживает каждого манекена. */
public final class MannequinTask extends BukkitRunnable {

    /** Период задачи в тиках. */
    public static final int INTERVAL_TICKS = 2;
    private static final long INTERVAL_MS = INTERVAL_TICKS * 50L;
    /** Высоты точек манекена (над ногами), которые проверяются на видимость: голова и грудь. */
    private static final double[] SAMPLE_HEIGHTS = {1.7, 1.0};
    /** Максимальный спуск за один шаг. */
    private static final int MAX_DROP = 3;

    private final WeepingMannequinPlugin plugin;
    private final MannequinManager manager;
    private final Random random = new Random();

    private final double activationRadius;
    private final double returnRadius;
    private final double viewAngle;
    private final double speedPerTick;
    private final double stopDistance;
    private final double volume;
    private final double soundRadius;
    private final long footstepMs;

    private long lastErrorLogMs;

    public MannequinTask(WeepingMannequinPlugin plugin, MannequinManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        FileConfiguration c = plugin.getConfig();
        this.activationRadius = c.getDouble("activation-radius", 50.0);
        this.returnRadius = c.getDouble("return-radius", 40.0);
        this.viewAngle = c.getDouble("view-angle", 60.0);
        this.speedPerTick = c.getDouble("speed-per-tick", 0.15);
        this.stopDistance = c.getDouble("stop-distance", 1.2);
        this.volume = c.getDouble("sound-volume", 0.5);
        this.soundRadius = c.getDouble("sound-radius", 10.0);
        this.footstepMs = (long) (c.getDouble("footstep-interval-seconds", 10.0) * 1000.0);
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (MannequinData d : manager.snapshot()) {
            try {
                tick(d, now);
            } catch (Exception ex) {
                // Ошибка одного манекена не должна ломать остальных; лог не чаще раза в 10 секунд
                if (now - lastErrorLogMs > 10_000L) {
                    lastErrorLogMs = now;
                    plugin.getLogger().log(Level.SEVERE, "Ошибка в цикле манекена " + d.getId(), ex);
                }
            }
        }
    }

    private void tick(MannequinData d, long now) {
        Entity ent = Bukkit.getEntity(d.getId());
        if (!(ent instanceof ArmorStand stand) || !stand.isValid()) return;

        World world = stand.getWorld();
        Location loc = stand.getLocation();
        // Не двигаем манекена в незагруженном чанке
        if (!world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;

        // Подходящие игроки: онлайн, живые, не наблюдатели, в этом же мире и в радиусе
        List<Player> players = new ArrayList<>();
        for (Player p : world.getNearbyPlayers(loc, activationRadius)) {
            if (!p.isOnline() || p.isDead()) continue;
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            if (!p.getWorld().equals(world)) continue;
            players.add(p);
        }
        if (players.isEmpty()) {
            d.setFrozen(false);
            return;
        }

        // --- Проверка «на меня смотрят»
        Player watcher = null;
        for (Player p : players) {
            if (isSeenBy(p, loc)) {
                watcher = p;
                break;
            }
        }

        if (watcher != null) {
            // Замираем: позицию не меняем. При первом замирании поворачиваемся к игроку.
            if (!d.isFrozen()) {
                d.setFrozen(true);
                faceTo(stand, watcher.getLocation());
                playFreezeSound(stand, d, now);
            }
            return;
        }
        d.setFrozen(false);

        // --- Никто не смотрит: идём к ближайшему игроку
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : players) {
            double dist = p.getLocation().distanceSquared(loc);
            if (dist < best) {
                best = dist;
                nearest = p;
            }
        }
        if (nearest == null) return;

        if (Math.sqrt(best) > returnRadius) {
            goHome(stand, d);
            return;
        }

        Location target = nearest.getLocation();
        double dx = target.getX() - loc.getX();
        double dz = target.getZ() - loc.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz <= stopDistance) {
            faceTo(stand, target);
            return;
        }
        double ux = dx / horiz;
        double uz = dz / horiz;
        double step = Math.min(speedPerTick * INTERVAL_TICKS, horiz - stopDistance + 0.01);

        Location next = findNextStep(loc, ux, uz, step);
        if (next == null) return; // упёрлись в стену или обрыв
        if (!world.isChunkLoaded(next.getBlockX() >> 4, next.getBlockZ() >> 4)) return;

        Location look = next.clone();
        look.setDirection(new Vector(ux, 0, uz));
        next.setYaw(look.getYaw());
        next.setPitch(0f);
        stand.teleport(next);
        d.setLast(next);

        // Тихие шаги позади игрока раз в N секунд движения
        d.setMovedMs(d.getMovedMs() + INTERVAL_MS);
        if (d.getMovedMs() >= footstepMs) {
            d.setMovedMs(0);
            playFootstep(nearest);
        }
    }

    // ------------------------------------------------------------ видимость

    /** Игрок видит манекена: угол < view-angle и нет блоков на пути луча. */
    private boolean isSeenBy(Player p, Location base) {
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        World w = base.getWorld();
        if (w == null) return false;
        for (double h : SAMPLE_HEIGHTS) {
            Vector to = new Vector(base.getX() - eye.getX(),
                    base.getY() + h - eye.getY(),
                    base.getZ() - eye.getZ());
            double dist = to.length();
            if (dist < 1.0E-6) return true;
            double angle = Math.toDegrees(look.angle(to));
            if (angle >= viewAngle) continue;
            // Если блок встретился раньше, чем манекен — он закрыт
            RayTraceResult hit = w.rayTraceBlocks(eye, to.clone().normalize(), dist,
                    FluidCollisionMode.NEVER, true);
            if (hit == null) return true;
        }
        return false;
    }

    // -------------------------------------------------------------- движение

    private Location findNextStep(Location cur, double ux, double uz, double step) {
        Location r = tryStep(cur, cur.getX() + ux * step, cur.getZ() + uz * step);
        if (r != null) return r;
        // Скольжение вдоль стены по одной из осей
        if (Math.abs(ux) > 0.1) {
            r = tryStep(cur, cur.getX() + Math.signum(ux) * step, cur.getZ());
            if (r != null) return r;
        }
        if (Math.abs(uz) > 0.1) {
            r = tryStep(cur, cur.getX(), cur.getZ() + Math.signum(uz) * step);
        }
        return r;
    }

    /** Подбирает высоту: шаг вверх на 1 блок, спуск до MAX_DROP, иначе null. */
    private Location tryStep(Location cur, double nx, double nz) {
        World w = cur.getWorld();
        if (w == null) return null;
        int bx = (int) Math.floor(nx);
        int bz = (int) Math.floor(nz);
        int by = (int) Math.floor(cur.getY() + 1.0E-4);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;

        if (isFree(w, bx, by, bz)) {
            // Впереди свободно — спускаемся, если под ногами пусто
            int y = by;
            int drop = 0;
            while (drop < MAX_DROP && y - 1 > w.getMinHeight() && isEmptyAir(w.getBlockAt(bx, y - 1, bz))) {
                y--;
                drop++;
            }
            Block below = w.getBlockAt(bx, y - 1, bz);
            if (below.isPassable() || below.isLiquid()) return null; // обрыв или вода
            return new Location(w, nx, y, nz);
        }

        // Впереди препятствие — пробуем подняться на 1 блок
        Block stepBlock = w.getBlockAt(bx, by, bz);
        if (stepBlock.isPassable()) return null; // мешает потолок, а не ступенька
        boolean headroom = w.getBlockAt(cur.getBlockX(), by + 2, cur.getBlockZ()).isPassable();
        if (headroom && isFree(w, bx, by + 1, bz)) {
            return new Location(w, nx, by + 1, nz);
        }
        return null;
    }

    /** Манекен высотой 2 блока: должны быть свободны блоки на уровне ног и головы. */
    private boolean isFree(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y + 1 >= w.getMaxHeight()) return false;
        Block a = w.getBlockAt(x, y, z);
        Block b = w.getBlockAt(x, y + 1, z);
        return a.isPassable() && !a.isLiquid() && b.isPassable() && !b.isLiquid();
    }

    private boolean isEmptyAir(Block b) {
        return b.isPassable() && !b.isLiquid();
    }

    private void goHome(ArmorStand stand, MannequinData d) {
        Location home = d.homeLocation();
        if (home == null || home.getWorld() == null) return;
        if (!home.getWorld().equals(stand.getWorld())) return; // без телепорта между мирами
        if (stand.getLocation().distanceSquared(home) < 0.25) return;
        if (!home.getWorld().isChunkLoaded(home.getBlockX() >> 4, home.getBlockZ() >> 4)) return;
        stand.teleport(home);
        d.setLast(home);
    }

    private void faceTo(ArmorStand stand, Location target) {
        Location l = stand.getLocation();
        Vector v = target.toVector().subtract(l.toVector());
        v.setY(0);
        if (v.lengthSquared() < 1.0E-6) return;
        l.setDirection(v);
        stand.setRotation(l.getYaw(), 0f);
    }

    // ---------------------------------------------------------------- звуки

    private void playFreezeSound(ArmorStand stand, MannequinData d, long now) {
        if (now - d.getLastFreezeSoundMs() < 3000L) return; // защита от мерцания при взгляде на край
        d.setLastFreezeSoundMs(now);
        Sound sound = random.nextInt(10) < 6 ? Sound.ENTITY_WARDEN_HEARTBEAT : Sound.BLOCK_SCULK_SENSOR_CLICKING;
        Location l = stand.getLocation();
        double r2 = soundRadius * soundRadius;
        for (Player p : l.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(l) <= r2) {
                p.playSound(l, sound, SoundCategory.HOSTILE, (float) volume, 1.0f);
            }
        }
    }

    /** Тихий звук шагов за спиной игрока. */
    private void playFootstep(Player p) {
        Location pl = p.getLocation();
        Vector back = pl.getDirection().setY(0);
        if (back.lengthSquared() < 1.0E-4) return;
        back.normalize().multiply(-3.0);
        Location behind = pl.clone().add(back);
        p.playSound(behind, Sound.BLOCK_STONE_STEP, SoundCategory.HOSTILE, 0.4f, 0.9f);
    }
}
