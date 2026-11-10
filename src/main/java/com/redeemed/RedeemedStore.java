package com.redeemed;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Players who are Redeemed Gem holders. Bliss gems are kept off these players. */
public final class RedeemedStore {
    private final RedeemedGemPlugin pl;
    private final File file;
    private final Set<UUID> players = new HashSet<>();

    public RedeemedStore(RedeemedGemPlugin pl) {
        this.pl = pl;
        this.file = new File(pl.getDataFolder(), "redeemed.yml");
        if (file.exists()) {
            for (String s : YamlConfiguration.loadConfiguration(file).getStringList("players")) {
                try { players.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (UUID u : players) l.add(u.toString());
        y.set("players", l);
        try { y.save(file); } catch (IOException e) { pl.getLogger().warning("Could not save redeemed.yml: " + e.getMessage()); }
    }

    public boolean has(UUID id) { return players.contains(id); }
    public void add(UUID id) { if (players.add(id)) save(); }
    public void remove(UUID id) { if (players.remove(id)) save(); }
    public Set<UUID> all() { return Collections.unmodifiableSet(players); }
}
