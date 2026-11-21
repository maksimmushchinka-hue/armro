package com.example.weepingmannequin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;

/** Слушатели событий: загрузка/выгрузка чанков, загрузка мира, смерть манекена. */
public final class MannequinListener implements Listener {

    private final MannequinManager manager;

    public MannequinListener(MannequinManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        manager.adopt(event.getEntities());
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        manager.handleUnload(event.getEntities());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        manager.restoreWorld(event.getWorld());
    }

    /** Если манекен убит (например, /kill) — воскрешаем его на последней позиции. */
    @EventHandler
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.getCause() != EntityRemoveEvent.Cause.DEATH) return;
        MannequinData d = manager.get(event.getEntity().getUniqueId());
        if (d == null) return;
        d.setLast(event.getEntity().getLocation());
        manager.scheduleRespawn(d);
    }
}
