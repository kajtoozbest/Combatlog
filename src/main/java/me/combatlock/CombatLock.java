package me.combatlock;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CombatLock extends JavaPlugin implements Listener {

    // player -> time (ms) when tag expires
    private final Map<UUID, Long> tags = new HashMap<>();

    private long tagMillis;
    private boolean blockElytra;
    private boolean blockPearls;
    private boolean killOnLogout;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tagMillis = getConfig().getInt("tag-seconds", 15) * 1000L;
        blockElytra = getConfig().getBoolean("block-elytra", true);
        blockPearls = getConfig().getBoolean("block-ender-pearls", false);
        killOnLogout = getConfig().getBoolean("kill-on-logout", true);
        debug = getConfig().getBoolean("debug", true);

        Bukkit.getPluginManager().registerEvents(this, this);

        // once per second: expire tags + show countdown
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            tags.entrySet().removeIf(e -> {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null) return true;
                if (now >= e.getValue()) {
                    p.sendActionBar(Component.text("You are no longer in combat.", NamedTextColor.GREEN));
                    return true;
                }
                long left = (e.getValue() - now + 999) / 1000;
                p.sendActionBar(Component.text("In combat: " + left + "s", NamedTextColor.RED));
                return false;
            });
        }, 20L, 20L);
    }

    private boolean isTagged(Player p) {
        Long until = tags.get(p.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    private boolean bypass(Player p) {
        return p.hasPermission("combatlock.bypass");
    }

    private void tag(Player p) {
        if (bypass(p)) {
            if (debug) getLogger().info("Hit involving " + p.getName() + " ignored: has combatlock.bypass (op or wildcard permission)");
            return;
        }
        if (debug) getLogger().info("Tagged " + p.getName() + " for " + (tagMillis / 1000) + "s");
        boolean wasTagged = isTagged(p);
        tags.put(p.getUniqueId(), System.currentTimeMillis() + tagMillis);
        if (!wasTagged) {
            p.sendMessage(Component.text("You are in combat! Don't log out"
                    + (blockElytra ? " or use an elytra." : "."), NamedTextColor.RED));
        }
        // knock them out of the air if they were already gliding
        if (blockElytra && p.isGliding()) {
            p.setGliding(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player p) {
            attacker = p;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;
        if (debug) getLogger().info("Player hit: " + attacker.getName() + " -> " + victim.getName()
                + (e.isCancelled() ? " (event was cancelled by another plugin, counting anyway)" : ""));

        tag(victim);
        tag(attacker);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (!blockElytra) return;
        if (!(e.getEntity() instanceof Player p)) return;
        if (!e.isGliding()) return; // allow stopping
        if (isTagged(p)) {
            e.setCancelled(true);
            p.sendActionBar(Component.text("You can't use an elytra while in combat!", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearl(PlayerInteractEvent e) {
        if (!blockPearls) return;
        if (e.getItem() == null || e.getItem().getType() != org.bukkit.Material.ENDER_PEARL) return;
        if (isTagged(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("No ender pearls while in combat!", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!blockPearls) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && isTagged(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!isTagged(p)) {
            tags.remove(p.getUniqueId());
            return;
        }
        tags.remove(p.getUniqueId());
        if (killOnLogout) {
            // dies on the spot -> items drop where they logged out
            p.setHealth(0.0);
            Bukkit.broadcast(Component.text(p.getName() + " logged out in combat and lost their items!",
                    NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        tags.remove(e.getEntity().getUniqueId());
    }
}
