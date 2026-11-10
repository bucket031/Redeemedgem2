package com.redeemed;

import com.redeemed.Gems.Stage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public final class AbilityListener implements Listener {
    private static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(10, 10, 10), 1.4f);
    private static final Particle.DustOptions GRAY = new Particle.DustOptions(Color.fromRGB(135, 135, 135), 1.2f);

    private final RedeemedGemPlugin pl;
    private final Map<UUID, Integer> charges = new HashMap<>();
    private final Map<UUID, Long> shackleReady = new HashMap<>();
    private final Map<UUID, Integer> slamCharges = new HashMap<>();
    private final Map<UUID, Long> slamReady = new HashMap<>();
    private final Map<UUID, Long> shackleActive = new HashMap<>();
    private final Map<UUID, Long> slamActive = new HashMap<>();
    private final Map<UUID, Integer> hitCount = new HashMap<>();
    /** Holder -> players currently drained by that holder's Limitless. */
    private final Map<UUID, Set<UUID>> limitVictims = new HashMap<>();
    private final Map<UUID, Long> limitReady = new HashMap<>();
    private final Map<UUID, Long> limitEnd = new HashMap<>();
    private final Map<UUID, Long> grounded = new HashMap<>();
    private final Map<UUID, Long> locked = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final NamespacedKey gainKey, drainKey, awakenedKey;
    private boolean internalDamage;
    private int tick;

    public AbilityListener(RedeemedGemPlugin pl) {
        this.pl = pl;
        this.gainKey = new NamespacedKey(pl, "limitless_gain");
        this.drainKey = new NamespacedKey(pl, "limitless_drain");
        this.awakenedKey = new NamespacedKey(pl, "awakened_hearts");
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 4L, 4L);
    }

    private double d(String path) { return pl.getConfig().getDouble(path); }
    private long ms(String secPath) { return (long) (d(secPath) * 1000.0); }
    private boolean isLocked(Player p, long now) { return locked.getOrDefault(p.getUniqueId(), 0L) > now; }
    private boolean isGrounded(LivingEntity e, long now) { return grounded.getOrDefault(e.getUniqueId(), 0L) > now; }

    private boolean validTarget(Player src, Player t) {
        return t != src && t.isOnline() && !t.isDead()
                && (t.getGameMode() == GameMode.SURVIVAL || t.getGameMode() == GameMode.ADVENTURE)
                && !pl.trust.isTrusted(src.getUniqueId(), t.getUniqueId());
    }

    // ------------------------------------------------------------------ Shackles
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (internalDamage) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity t)) return;
        Stage s = pl.gems.stageOf(p.getInventory().getItemInMainHand());
        if (s == null || s.ordinal() < 1) return;
        long now = System.currentTimeMillis();
        if (isLocked(p, now) || isGrounded(t, now)) return;
        if (t instanceof Player tp && pl.trust.isTrusted(p.getUniqueId(), tp.getUniqueId())) return;

        UUID id = p.getUniqueId();
        int max = (int) d("shackles.charges");
        int c = charges.getOrDefault(id, max);
        if (c <= 0) return;
        c--;
        charges.put(id, c);
        if (c == 0) shackleReady.put(id, now + ms("shackles.recharge-seconds"));

        shackleActive.put(id, now + ms("shackles.ground-seconds"));
        ground(t, ms("shackles.ground-seconds"));
        trueDamage(t, d("shackles.damage-hearts") * 2.0, p);
        p.getWorld().playSound(t.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 0.6f);
    }

    // ------------------------------------------------------------------ Passive
    /** Darkness every Nth hit, and a small chance of a one-hit Strength crit. Works with the gem in either hand. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPassiveHit(EntityDamageByEntityEvent e) {
        if (internalDamage) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity t)) return;
        Stage s = pl.gems.heldStage(p);
        if (s == null || s.ordinal() < 1) return;
        if (isLocked(p, System.currentTimeMillis())) return;
        if (t instanceof Player tp && pl.trust.isTrusted(p.getUniqueId(), tp.getUniqueId())) return;

        UUID id = p.getUniqueId();
        int every = Math.max(1, (int) d("passive.darkness-every-hits"));
        int hits = hitCount.getOrDefault(id, 0) + 1;
        if (hits >= every) {
            hits = 0;
            int ticks = (int) (ms("passive.darkness-seconds") / 50L);
            t.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, Math.max(20, ticks), 0, false, true, true));
            dust(t.getLocation().add(0, 1, 0), 20, 0.4, 0.7, 0.4);
        }
        hitCount.put(id, hits);

        if (Math.random() < d("passive.crit-chance")) {
            // Strength gives +3 damage per level, so Strength 4 = +12 for this one hit.
            e.setDamage(e.getDamage() + 3.0 * d("passive.crit-strength-level"));
            Location at = t.getLocation().add(0, 1, 0);
            t.getWorld().spawnParticle(Particle.CRIT, at, n(25), 0.4, 0.6, 0.4, 0.3);
            dust(at, 15, 0.4, 0.7, 0.4);
            t.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.8f);
        }
    }

    private void ground(LivingEntity t, long millis) {
        grounded.put(t.getUniqueId(), System.currentTimeMillis() + millis);
        if (t instanceof Mob m) {
            boolean was = m.isAware();
            m.setAware(false);
            Bukkit.getScheduler().runTaskLater(pl, () -> m.setAware(was), Math.max(1L, millis / 50L));
        }
        groundFx(t, millis);
    }

    /** Chains wrapped around the target for the whole ground time, then a short fade (about 1s longer). */
    private void groundFx(LivingEntity t, long millis) {
        final int ticks = (int) (millis / 50L) + 20;
        Location hit = t.getLocation().add(0, 1, 0);
        dust(hit, 40, 0.5, 0.8, 0.5);
        t.getWorld().spawnParticle(Particle.SQUID_INK, hit, n(10), 0.4, 0.7, 0.4, 0.05);
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (i >= ticks || t.isDead() || !t.isValid()) { cancel(); return; }
                Location b = t.getLocation();
                boolean held = i < ticks - 20;
                for (int k = 0; k < 3; k++) ring(b.clone().add(0, 0.15 + k * 0.8, 0), held ? 0.75 : 0.75 + (i - (ticks - 20)) * 0.04, 10);
                for (int sIdx = 0; sIdx < 2; sIdx++) {
                    double a = i * 0.5 + sIdx * Math.PI;
                    double y = (i % 20) / 20.0 * 1.9;
                    dust(b.clone().add(Math.cos(a) * 0.8, y, Math.sin(a) * 0.8), 2, 0.05, 0.05, 0.05);
                }
                if (i % 2 == 0) smoke(b.clone().add(0, 0.1, 0), 2, 0.4, 0.05, 0.4, 0.01);
                if (i % 4 == 0) ash(b.clone().add(0, 1, 0), 4, 0.5, 0.9, 0.5);
                i++;
            }
        }.runTaskTimer(pl, 0L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!isGrounded(p, System.currentTimeMillis())) return;
        Location f = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (f.getX() != to.getX() || f.getY() != to.getY() || f.getZ() != to.getZ()) {
            Location n = f.clone();
            n.setYaw(to.getYaw());
            n.setPitch(to.getPitch());
            e.setTo(n);
        }
    }

    // ------------------------------------------------------------------ Input
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null) return;
        Player p = e.getPlayer();
        Stage s = pl.gems.stageOf(it);
        long now = System.currentTimeMillis();

        if (isLocked(p, now)) {
            if (s != null || pl.gems.isGemLike(it)) {
                e.setCancelled(true);
                p.sendActionBar(Component.text("Your abilities are locked!", NamedTextColor.GRAY));
            }
            return;
        }
        if (s == null) return;

        if (e.getHand() == EquipmentSlot.HAND) {
            if (s.ordinal() >= 3) { e.setCancelled(true); limitless(p, now); }
        } else if (e.getHand() == EquipmentSlot.OFF_HAND) {
            if (s.ordinal() >= 2) {
                e.setCancelled(true);
                UUID id = p.getUniqueId();
                long last = lastClick.getOrDefault(id, 0L);
                if (now - last <= (long) d("general.double-click-ms")) { lastClick.remove(id); slam(p, now); }
                else lastClick.put(id, now);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!isLocked(p, System.currentTimeMillis())) return;
        if (pl.gems.isGemLike(e.getMainHandItem()) || pl.gems.isGemLike(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropLocked(PlayerDropItemEvent e) {
        ItemStack it = e.getItemDrop().getItemStack();
        if (isLocked(e.getPlayer(), System.currentTimeMillis()) && pl.gems.isGemLike(it)) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        endLimitless(id);
        grounded.remove(id); locked.remove(id); lastClick.remove(id); hitCount.remove(id);
        shackleActive.remove(id); slamActive.remove(id);
        // Anyone drained by someone else's Limitless gets their hearts back when they leave.
        clearMod(e.getPlayer(), drainKey);
        for (Set<UUID> v : limitVictims.values()) v.remove(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Attribute modifiers are saved with the player, so clear any Limitless leftovers (crash, force-stop).
        clearMod(e.getPlayer(), gainKey);
        clearMod(e.getPlayer(), drainKey);
    }

    /** Called on plugin disable so no temporary hearts outlive the plugin. */
    public void shutdown() {
        for (UUID id : new ArrayList<>(limitVictims.keySet())) endLimitless(id);
        for (Player p : Bukkit.getOnlinePlayers()) {
            clearMod(p, gainKey);
            clearMod(p, drainKey);
            clearMod(p, awakenedKey);
        }
    }

    // ------------------------------------------------------------------ Max-health modifiers
    /** Sets (or replaces) a max-health modifier. Returns the previous amount, 0 if there was none. */
    private double setMod(Player p, NamespacedKey key, double amount) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return 0;
        double prev = 0;
        for (AttributeModifier m : new ArrayList<>(a.getModifiers())) {
            if (!key.equals(m.getKey())) continue;
            if (m.getAmount() == amount) return amount;
            prev = m.getAmount();
            a.removeModifier(m);
        }
        a.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
        clampHealth(p, a);
        return prev;
    }

    private void clearMod(Player p, NamespacedKey key) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        boolean removed = false;
        for (AttributeModifier m : new ArrayList<>(a.getModifiers())) {
            if (key.equals(m.getKey())) { a.removeModifier(m); removed = true; }
        }
        if (removed) clampHealth(p, a);
    }

    private void clampHealth(Player p, AttributeInstance a) {
        double max = a.getValue();
        if (p.getHealth() > max) p.setHealth(max);
    }

    private void heal(Player p, double hp) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null || hp <= 0 || p.isDead()) return;
        p.setHealth(Math.min(a.getValue(), p.getHealth() + hp));
    }

    /** Awakened gem anywhere in the inventory = bonus max hearts. Runs about once per second. */
    private void updateAwakened() {
        double bonus = d("awakened.bonus-hearts") * 2.0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (bonus > 0 && pl.gems.stageInInventory(p) == Stage.AWAKENED) {
                double prev = setMod(p, awakenedKey, bonus);
                if (bonus > prev) heal(p, bonus - prev);
            } else {
                clearMod(p, awakenedKey);
            }
        }
    }

    // ------------------------------------------------------------------ Shattered Slam
    private void slam(Player p, long now) {
        UUID id = p.getUniqueId();
        long ready = slamReady.getOrDefault(id, 0L);
        if (ready > now) { p.sendActionBar(Component.text("Shattered Slam: " + time(secs(ready - now)), NamedTextColor.GRAY)); return; }
        int max = Math.max(1, (int) d("slam.charges"));
        int chargesLeft = slamCharges.getOrDefault(id, max) - 1;
        slamCharges.put(id, chargesLeft);
        if (chargesLeft <= 0) slamReady.put(id, now + ms("slam.cooldown-seconds"));
        slamActive.put(id, now + 1800L);

        double r = d("slam.radius");
        Location c = p.getLocation().add(0, 0.15, 0);
        p.getWorld().playSound(c, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 0.7f);
        p.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);

        c.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c, 1);
        smoke(c.clone().add(0, 0.5, 0), 30, 1.0, 0.3, 1.0, 0.05);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                double prog = Math.min(1.0, t / 10.0);
                double rr = Math.max(0.5, r * prog);
                int pts = (int) (rr * 10) + 16;
                ring(c, rr, pts);
                ring(c.clone().add(0, 0.6, 0), rr * 0.92, pts);
                ring(c.clone().add(0, 1.3, 0), rr * 0.8, pts);
                smoke(c, 6, rr * 0.5, 0.2, rr * 0.5, 0.02);
                ash(c.clone().add(0, 1, 0), 8, rr * 0.5, 0.8, rr * 0.5);
                if (prog >= 1.0 && t % 3 == 0) pillars(c, r, 12, 3.0);
                if (t >= 36) cancel();
                t++;
            }
        }.runTaskTimer(pl, 0L, 1L);

        for (Player t : p.getWorld().getNearbyPlayers(p.getLocation(), r)) {
            if (!validTarget(p, t)) continue;
            Vector dir = t.getLocation().toVector().subtract(p.getLocation().toVector());
            dir.setY(0);
            if (dir.lengthSquared() < 0.01) dir = new Vector(Math.random() - 0.5, 0, Math.random() - 0.5);
            dir.normalize().multiply(d("slam.fling-distance") / 8.0).setY(0.7);
            t.setVelocity(dir);
            trueDamage(t, d("slam.damage-hearts") * 2.0, p);
            dust(t.getLocation().add(0, 1, 0), 40, 0.5, 0.8, 0.5);
            t.getWorld().spawnParticle(Particle.SQUID_INK, t.getLocation().add(0, 1, 0), n(10), 0.4, 0.7, 0.4, 0.05);
        }
    }

    // ------------------------------------------------------------------ Limitless
    private void limitless(Player p, long now) {
        UUID id = p.getUniqueId();
        long ready = limitReady.getOrDefault(id, 0L);
        if (ready > now) { p.sendActionBar(Component.text("Limitless: " + time(secs(ready - now)), NamedTextColor.GRAY)); return; }
        long dur = ms("limitless.duration-seconds");
        limitEnd.put(id, now + dur);
        limitReady.put(id, now + dur + ms("limitless.cooldown-seconds"));

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
        // Everyone in range loses max hearts and the holder gains max hearts until the duration is over.
        double take = d("limitless.hearts-taken") * 2.0;
        Set<UUID> victims = new HashSet<>();
        for (Player t : p.getWorld().getNearbyPlayers(p.getLocation(), d("limitless.radius"))) {
            if (!validTarget(p, t)) continue;
            AttributeInstance a = t.getAttribute(Attribute.MAX_HEALTH);
            if (a == null) continue;
            clearMod(t, drainKey);
            double loss = Math.min(take, a.getValue() - 2.0); // always leave at least one heart
            if (loss <= 0) continue;
            setMod(t, drainKey, -loss);
            victims.add(t.getUniqueId());
            drainBeam(p, t);
        }
        limitVictims.put(id, victims);
        double give = d("limitless.hearts-given") * 2.0;
        if (give > 0) {
            double prev = setMod(p, gainKey, give);
            heal(p, give - prev);
        }
        burstRings(p.getLocation().add(0, 0.15, 0), d("limitless.radius"));
    }

    /** Ends Limitless for a holder: gives everyone's hearts back. Safe to call when it is not active. */
    private void endLimitless(UUID holder) {
        limitEnd.remove(holder);
        Set<UUID> victims = limitVictims.remove(holder);
        if (victims != null) {
            for (UUID v : victims) {
                Player t = Bukkit.getPlayer(v);
                if (t != null) clearMod(t, drainKey);
            }
        }
        Player p = Bukkit.getPlayer(holder);
        if (p != null) clearMod(p, gainKey);
    }

    private void tickLimitless(Player p, long now) {
        limitlessFx(p);
        // Keep the drain beams pulsing on everyone still drained.
        Set<UUID> victims = limitVictims.get(p.getUniqueId());
        if (victims != null && tick % 10 == 0) {
            for (UUID v : victims) {
                Player t = Bukkit.getPlayer(v);
                if (t != null && t.isOnline() && !t.isDead() && t.getWorld().equals(p.getWorld())) drainBeam(p, t);
            }
        }
        double lr = d("limitless.lock-radius");
        for (Player t : p.getWorld().getNearbyPlayers(p.getLocation(), lr)) {
            if (!validTarget(p, t)) continue;
            locked.put(t.getUniqueId(), now + ms("limitless.lock-seconds"));
            t.getWorld().spawnParticle(Particle.DUST, t.getLocation().add(0, 1, 0), 6, 0.3, 0.6, 0.3, 0, GRAY);
        }
        if (tick % 5 == 0) {
            int amt = (int) d("limitless.armor-mend-per-second");
            ItemStack[] armor = p.getInventory().getArmorContents();
            for (ItemStack a : armor) {
                if (a != null && a.getItemMeta() instanceof Damageable dm && dm.hasDamage()) {
                    dm.setDamage(Math.max(0, dm.getDamage() - amt));
                    a.setItemMeta(dm);
                }
            }
            p.getInventory().setArmorContents(armor);
        }
    }

    // ------------------------------------------------------------------ Tick / HUD
    private void tick() {
        long now = System.currentTimeMillis();
        tick++;
        shackleReady.entrySet().removeIf(en -> {
            if (now >= en.getValue()) { charges.remove(en.getKey()); return true; }
            return false;
        });
        slamReady.entrySet().removeIf(en -> {
            if (now >= en.getValue()) { slamCharges.remove(en.getKey()); return true; }
            return false;
        });
        shackleActive.values().removeIf(v -> v <= now);
        slamActive.values().removeIf(v -> v <= now);
        grounded.values().removeIf(v -> v <= now);
        locked.values().removeIf(v -> v <= now);

        Iterator<Map.Entry<UUID, Long>> it = limitEnd.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || p.isDead() || now >= en.getValue()) {
                UUID holder = en.getKey();
                it.remove();
                endLimitless(holder);
                continue;
            }
            tickLimitless(p, now);
        }
        if (tick % 5 == 0) { updateAwakened(); hud(now); }
    }

    private void hud(long now) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Stage s = pl.gems.heldStage(p);
            if (s == null || s == Stage.DORMANT) continue;
            UUID id = p.getUniqueId();
            List<Component> parts = new ArrayList<>();

            int max = Math.max(1, (int) d("shackles.charges"));
            long sr = shackleReady.getOrDefault(id, 0L);
            int left = charges.getOrDefault(id, max);
            parts.add(state(shackleActive.getOrDefault(id, 0L) > now, sr > now ? sr - now : 0, max > 1 ? left + "/" + max : null));

            if (s.ordinal() >= 3) {
                long end = limitEnd.getOrDefault(id, 0L), lr = limitReady.getOrDefault(id, 0L);
                parts.add(state(end > now, lr > now ? lr - now : 0, null));
            }
            if (s.ordinal() >= 2) {
                int smax = Math.max(1, (int) d("slam.charges"));
                long sl = slamReady.getOrDefault(id, 0L);
                int sleft = slamCharges.getOrDefault(id, smax);
                parts.add(state(slamActive.getOrDefault(id, 0L) > now, sl > now ? sl - now : 0, smax > 1 ? sleft + "/" + smax : null));
            }
            p.sendActionBar(Component.join(JoinConfiguration.separator(Component.text(" | ", NamedTextColor.DARK_GRAY)), parts));
        }
    }

    /** "Active..." while in use, the timer while recharging, otherwise "Ready" (with charges left, if the ability has charges). */
    private Component state(boolean active, long cooldownMs, String charges) {
        if (active) return Component.text("Active...", NamedTextColor.YELLOW);
        if (cooldownMs > 0) return Component.text(time(secs(cooldownMs)), NamedTextColor.RED);
        return Component.text("Ready" + (charges != null ? " (" + charges + ")" : ""), NamedTextColor.GREEN);
    }

    private static long secs(long millis) { return (millis + 999) / 1000; }

    /** 135 -> "2m 15s", 45 -> "45s". */
    static String time(long seconds) {
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    // ------------------------------------------------------------------ Helpers
    /** Damage that ignores armor and enchantments. Falls back to a lethal hit so totems and kill credit still work. */
    public void trueDamage(LivingEntity t, double hp, Player src) {
        if (t.isDead() || hp <= 0) return;
        internalDamage = true;
        try {
            double left = t.getHealth() - hp;
            if (left <= 0) {
                t.damage(10000.0, src);
            } else {
                t.setHealth(left);
                t.playEffect(EntityEffect.HURT);
            }
        } finally {
            internalDamage = false;
        }
    }

    // ------------------------------------------------------------------ Particles
    private int n(int base) {
        return Math.max(1, (int) Math.round(base * pl.getConfig().getDouble("effects.particle-multiplier", 2.0)));
    }

    private void dust(Location l, int count, double dx, double dy, double dz) {
        World w = l.getWorld();
        w.spawnParticle(Particle.DUST, l, n(count), dx, dy, dz, 0, BLACK);
        w.spawnParticle(Particle.DUST, l, n(count), dx, dy, dz, 0, GRAY);
    }

    private void smoke(Location l, int count, double dx, double dy, double dz, double speed) {
        l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l, n(count), dx, dy, dz, speed);
    }

    private void ash(Location l, int count, double dx, double dy, double dz) {
        l.getWorld().spawnParticle(Particle.ASH, l, n(count), dx, dy, dz, 0);
    }

    private void ring(Location c, double r, int pts) {
        World w = c.getWorld();
        int points = n(pts);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0, i % 2 == 0 ? BLACK : GRAY);
        }
    }

    /** Vertical black/gray columns standing on a circle. */
    private void pillars(Location c, double r, int count, double height) {
        World w = c.getWorld();
        for (int i = 0; i < count; i++) {
            double a = 2 * Math.PI * i / count;
            Location base = c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            for (double y = 0; y <= height; y += 0.35) {
                w.spawnParticle(Particle.DUST, base.clone().add(0, y, 0), 1, 0.05, 0, 0.05, 0, ((int) (y / 0.35)) % 2 == 0 ? BLACK : GRAY);
            }
            w.spawnParticle(Particle.LARGE_SMOKE, base.clone().add(0, 0.2, 0), n(1), 0.1, 0.1, 0.1, 0.01);
        }
    }

    /** Expanding rings that rush out when Limitless starts. */
    private void burstRings(Location c, double r) {
        new BukkitRunnable() {
            int t = 1;
            @Override public void run() {
                double rr = r * t / 10.0;
                ring(c, rr, (int) (rr * 8) + 16);
                ring(c.clone().add(0, 1.0, 0), rr * 0.9, (int) (rr * 8) + 16);
                smoke(c, 4, rr * 0.5, 0.2, rr * 0.5, 0.02);
                if (++t > 10) cancel();
            }
        }.runTaskTimer(pl, 0L, 1L);
    }

    /** Runs every 4 ticks while Limitless is active: ring, rotating spokes, pillars, lock-range ring, drifting ash. */
    private void limitlessFx(Player p) {
        Location c = p.getLocation().add(0, 0.1, 0);
        double r = d("limitless.radius");
        ring(c, r, 48);
        ring(c.clone().add(0, 0.5, 0), r * 0.97, 36);
        ring(c, Math.max(0.5, d("limitless.lock-radius")), 16);
        double phase = tick * 0.25;
        World w = c.getWorld();
        for (int s = 0; s < 6; s++) {
            double a = phase + s * Math.PI / 3.0;
            for (double dist = 1.0; dist < r; dist += 1.0) {
                w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * dist, 0.05, Math.sin(a) * dist), 1, 0, 0, 0, 0, ((int) dist) % 2 == 0 ? BLACK : GRAY);
            }
        }
        if (tick % 2 == 0) pillars(c, r, 8, 2.5);
        smoke(p.getLocation().add(0, 1, 0), 3, 0.6, 0.9, 0.6, 0.01);
        ash(p.getLocation().add(0, 1, 0), 6, 1.2, 1.0, 1.2);
    }

    /** A drain beam from the victim to the caster that keeps pulsing for 1.5s. */
    private void drainBeam(Player src, Player target) {
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (i++ >= 15 || !src.isOnline() || !target.isOnline()) { cancel(); return; }
                Location from = target.getLocation().add(0, 1, 0), to = src.getLocation().add(0, 1, 0);
                Vector v = to.toVector().subtract(from.toVector());
                double len = v.length();
                if (len < 0.1) return;
                v.normalize();
                int idx = 0;
                for (double k = 0; k < len; k += 0.3, idx++) {
                    from.getWorld().spawnParticle(Particle.DUST, from.clone().add(v.clone().multiply(k)), 1, 0.04, 0.04, 0.04, 0, idx % 2 == 0 ? BLACK : GRAY);
                }
                dust(target.getLocation().add(0, 1, 0), 6, 0.3, 0.6, 0.3);
            }
        }.runTaskTimer(pl, 0L, 2L);
    }

    public void particleBurst(Location c) {
        dust(c, 80, 0.6, 0.9, 0.6);
        smoke(c, 20, 0.6, 0.9, 0.6, 0.05);
        ash(c, 40, 0.8, 1.0, 0.8);
        burstRings(c.clone().subtract(0, 0.85, 0), 3.0);
    }
}
