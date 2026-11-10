package com.redeemed;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Gems {
    public enum Stage {
        DORMANT("Dormant", NamedTextColor.DARK_GRAY),
        UNAWAKENED("Unawakened", NamedTextColor.GRAY),
        AWAKENING("Awakening", NamedTextColor.WHITE),
        AWAKENED("Awakened", NamedTextColor.WHITE);

        public final String label;
        public final TextColor color;
        Stage(String label, TextColor color) { this.label = label; this.color = color; }
        public String modelKey() { return "redeemedgem:" + name().toLowerCase(Locale.ROOT); }
    }

    public static final String[] DISGUISES = {"strength", "fire", "wealth", "speed", "puff", "astra", "life", "flux"};
    private static final Map<String, TextColor> GEM_COLORS = Map.of(
            "strength", NamedTextColor.RED, "fire", NamedTextColor.GOLD, "wealth", NamedTextColor.YELLOW,
            "speed", NamedTextColor.GREEN, "puff", NamedTextColor.WHITE, "astra", NamedTextColor.DARK_PURPLE,
            "life", NamedTextColor.LIGHT_PURPLE, "flux", NamedTextColor.AQUA);

    private final RedeemedGemPlugin pl;
    private final NamespacedKey stageKey, disguiseKey;

    public Gems(RedeemedGemPlugin pl) {
        this.pl = pl;
        this.stageKey = new NamespacedKey(pl, "rg_stage");
        this.disguiseKey = new NamespacedKey(pl, "rg_disguise");
    }

    public static String cap(String s) { return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }

    /** Small-caps text, the look of the Bliss gem tooltip headers. */
    public static String sc(String s) {
        String from = "abcdefghijklmnopqrstuvwxyz";
        String to = "\u1D00\u0299\u1D04\u1D05\u1D07\uA730\u0262\u029C\u026A\u1D0A\u1D0B\u029F\u1D0D\u0274\u1D0F\u1D18\uA7AF\u0280\uA731\u1D1B\u1D1C\u1D20\u1D21x\u028F\u1D22";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
            int i = from.indexOf(ch);
            sb.append(i >= 0 ? to.charAt(i) : ch);
        }
        return sb.toString();
    }

    private static Component t(String s, TextColor c) { return Component.text(s, c).decoration(TextDecoration.ITALIC, false); }
    private static Component tb(String s, TextColor c) { return t(s, c).decoration(TextDecoration.BOLD, true); }
    private static Component blank() { return Component.empty().decoration(TextDecoration.ITALIC, false); }
    private static Component item(String s, TextColor c) {
        return Component.text("- ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false).append(t(s, c));
    }
    private Component header(String s) {
        String icon = pl.getConfig().getString("lore-icon", "\u2666");
        return t(icon + " ", NamedTextColor.GRAY).append(tb(sc(s), NamedTextColor.WHITE));
    }

    /** Builds a Redeemed Gem. Without the resource pack it shows as an enchanted prismarine shard. */
    public ItemStack create(Stage stage, String disguise) {
        ItemStack it = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(stageKey, PersistentDataType.STRING, stage.name());
        List<Component> lore = new ArrayList<>();
        if (disguise != null) {
            m.getPersistentDataContainer().set(disguiseKey, PersistentDataType.STRING, disguise);
            TextColor col = GEM_COLORS.getOrDefault(disguise, NamedTextColor.WHITE);
            m.displayName(tb(sc(cap(disguise) + " Gem"), col));
            // The real Bliss model: prismarine shard + custom_model_data, so it looks identical in hand.
            int cmd = pl.getConfig().getInt("disguise-model-data." + disguise, 0);
            if (cmd > 0) {
                CustomModelDataComponent c = m.getCustomModelDataComponent();
                c.setFloats(List.of((float) cmd));
                m.setCustomModelDataComponent(c);
            }
            lore.add(t("(Pristine + 5)", NamedTextColor.GREEN));
            if (disguise.equals("strength")) strengthLore(lore);
        } else {
            m.displayName(tb(sc("Redeemed Gem"), stage.color));
            m.setItemModel(NamespacedKey.fromString(stage.modelKey()));
            redeemedLore(lore, stage);
        }
        m.lore(lore);
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    /** Matches the Strength Gem tooltip layout: title, tier line, tagline, then PASSIVES / ABILITY / POWERS. */
    private void strengthLore(List<Component> l) {
        l.add(tb(sc("Have the strength of an army"), NamedTextColor.WHITE));
        l.add(blank());
        l.add(header("Passives"));
        l.add(item("Strength", NamedTextColor.WHITE));
        l.add(item("Enchants Sharpness", NamedTextColor.WHITE));
        l.add(item("Bloodthorns", NamedTextColor.WHITE));
        l.add(blank());
        l.add(header("Ability"));
        l.add(item("Bounty Hunter", NamedTextColor.RED));
        l.add(blank());
        l.add(header("Powers"));
        l.add(item(sc("Frailer"), NamedTextColor.WHITE));
        l.add(item(sc("Nullify"), NamedTextColor.WHITE));
        l.add(blank());
        l.add(item(sc("Chad strength"), NamedTextColor.RED));
        l.add(item(sc("Chad strength"), NamedTextColor.RED));
    }

    private void redeemedLore(List<Component> l, Stage s) {
        l.add(t("(" + s.label + ")", NamedTextColor.GRAY));
        l.add(tb(sc(pl.getConfig().getString("lore-tagline", "Beyond every gem")), NamedTextColor.WHITE));
        l.add(blank());
        l.add(header("Passives"));
        l.add(item("Disguise", NamedTextColor.WHITE));
        l.add(item("No energy", NamedTextColor.WHITE));
        l.add(blank());
        l.add(header("Ability"));
        l.add(s.ordinal() >= 3 ? item("Limitless", NamedTextColor.WHITE) : item("???", NamedTextColor.DARK_GRAY));
        l.add(blank());
        l.add(header("Powers"));
        l.add(s.ordinal() >= 1 ? item(sc("Shackles"), NamedTextColor.WHITE) : item("???", NamedTextColor.DARK_GRAY));
        l.add(s.ordinal() >= 2 ? item(sc("Shattered slam"), NamedTextColor.WHITE) : item("???", NamedTextColor.DARK_GRAY));
    }

    public Stage stageOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(stageKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Stage.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    public String disguiseOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(disguiseKey, PersistentDataType.STRING);
    }

    public boolean isRedeemed(ItemStack it) { return stageOf(it) != null; }

    public Stage heldStage(Player p) {
        Stage a = stageOf(p.getInventory().getItemInMainHand());
        Stage b = stageOf(p.getInventory().getItemInOffHand());
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    public Stage stageInInventory(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            Stage s = stageOf(it);
            if (s != null) return s;
        }
        return null;
    }

    public void setStage(Player p, Stage stage) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isRedeemed(it)) inv.setItem(i, create(stage, disguiseOf(it)));
        }
    }

    /** disguise == null reverts to the plain Redeemed Gem texture. */
    public void setDisguise(Player p, String disguise) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            Stage s = stageOf(it);
            if (s != null) inv.setItem(i, create(s, disguise));
        }
    }

    // ------------------------------------------------------------ Bliss gem detection
    private static String plain(Component c) { return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c).toLowerCase(Locale.ROOT); }

    /** True if the item looks like a Bliss gem. See bliss-gem-detect in config.yml; /rg debug shows what a real gem looks like. */
    public boolean isGemLike(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta() || isRedeemed(it)) return false;
        ItemMeta m = it.getItemMeta();
        var cfg = pl.getConfig();

        // 1) material + custom_model_data
        if (cfg.getStringList("bliss-gem-detect.materials").contains(it.getType().name()) && m.hasCustomModelDataComponent()) {
            List<Integer> ids = cfg.getIntegerList("bliss-gem-detect.model-data");
            for (Float f : m.getCustomModelDataComponent().getFloats()) if (ids.contains(Math.round(f))) return true;
        }
        // 2) text on the item
        StringBuilder text = new StringBuilder(plain(m.displayName())).append(' ');
        if (m.hasItemName()) text.append(plain(m.itemName())).append(' ');
        if (m.lore() != null) for (Component c : m.lore()) text.append(plain(c)).append(' ');
        String all = text.toString();
        String req = cfg.getString("bliss-gem-detect.required-word", "gem").toLowerCase(Locale.ROOT);
        if (req.isEmpty() || all.contains(req)) {
            for (String n : cfg.getStringList("bliss-gem-detect.names")) if (all.contains(n.toLowerCase(Locale.ROOT))) return true;
        }
        // 3) persistent-data keys from Bliss
        List<String> ns = cfg.getStringList("bliss-gem-detect.pdc-namespaces");
        for (NamespacedKey k : m.getPersistentDataContainer().getKeys()) {
            if (ns.contains(k.getNamespace().toLowerCase(Locale.ROOT)) && k.getKey().toLowerCase(Locale.ROOT).contains("gem")) return true;
        }
        return false;
    }

    /** Removes every Bliss gem item from the player (inventory, armor, offhand). Returns how many stacks were removed. */
    public int removeBlissGems(Player p) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isGemLike(it)) { inv.setItem(i, null); n++; }
        }
        ItemStack cursor = p.getItemOnCursor();
        if (isGemLike(cursor)) { p.setItemOnCursor(null); n++; }
        return n;
    }

    /** Describes an item for /rg debug. */
    public List<String> describe(ItemStack it) {
        List<String> out = new ArrayList<>();
        if (it == null || it.getType().isAir()) { out.add("Nothing in hand."); return out; }
        ItemMeta m = it.getItemMeta();
        out.add("Material: " + it.getType().name());
        if (m == null) return out;
        out.add("Name: " + plain(m.displayName()) + " | item name: " + (m.hasItemName() ? plain(m.itemName()) : "-"));
        out.add("Item model: " + (m.hasItemModel() ? m.getItemModel() : "-"));
        if (m.hasCustomModelDataComponent()) out.add("Custom model data: " + m.getCustomModelDataComponent().getFloats());
        else out.add("Custom model data: -");
        List<String> keys = new ArrayList<>();
        for (NamespacedKey k : m.getPersistentDataContainer().getKeys()) keys.add(k.toString());
        out.add("PDC keys: " + (keys.isEmpty() ? "-" : String.join(", ", keys)));
        out.add("Counted as Bliss gem: " + isGemLike(it));
        return out;
    }
}
