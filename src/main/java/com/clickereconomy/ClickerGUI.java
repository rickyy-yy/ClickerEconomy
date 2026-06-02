package com.clickereconomy;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Implements {@link InventoryHolder} so the listener can identify this GUI by
 * {@code event.getInventory().getHolder() instanceof ClickerGUI} — no fragile
 * title-string comparison required.
 *
 * <p>All player-facing text (title, item names, lore) is read from
 * {@link LangManager} so admins can fully customise them in {@code lang.yml}.
 */
public class ClickerGUI implements InventoryHolder {

    private static final int GUI_SIZE = 27;

    private final Inventory inventory;

    private ClickerGUI(ClickerPlugin plugin, Player player) {
        String title = plugin.getLang().get("gui.title");
        inventory = Bukkit.createInventory(this, GUI_SIZE, title);
        populate(plugin, player);
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public static void open(ClickerPlugin plugin, Player player) {
        player.openInventory(new ClickerGUI(plugin, player).getInventory());
        playOpenSound(plugin, player);
    }

    private static void playOpenSound(ClickerPlugin plugin, Player player) {
        if (!plugin.getConfig().getBoolean("gui-open-sound.enabled", true)) return;
        String soundName = plugin.getConfig().getString("gui-open-sound.sound", "BLOCK_CHEST_OPEN");
        float volume = (float) plugin.getConfig().getDouble("gui-open-sound.volume", 1.0);
        float pitch  = (float) plugin.getConfig().getDouble("gui-open-sound.pitch",  1.2);
        try {
            player.playSound(player.getLocation(), Sound.valueOf(soundName.toUpperCase()),
                    volume, pitch);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid gui-open-sound '" + soundName + "' in config.yml.");
        }
    }

    // =========================================================================
    // GUI population
    // =========================================================================

    private void populate(ClickerPlugin plugin, Player player) {
        ItemStack filler = buildFiller(plugin);
        for (int i = 0; i < GUI_SIZE; i++) inventory.setItem(i, filler);

        int clickerSlot = plugin.getClickerSlot();
        int balSlot = plugin.getConfig().getInt("balance-display.slot", 4);
        if (balSlot >= 0 && balSlot < GUI_SIZE && balSlot != clickerSlot) {
            inventory.setItem(balSlot, buildBalanceDisplay(plugin, player));
        }

        inventory.setItem(clickerSlot, buildClickerItem(plugin, player));
    }

    // =========================================================================
    // Clicker item  (public — ClickerListener refreshes it after each click)
    // =========================================================================

    public static ItemStack buildClickerItem(ClickerPlugin plugin, Player player) {
        LangManager lang = plugin.getLang();

        String matName    = plugin.getConfig().getString("clicker-item.material", "DIAMOND");
        String skullTex   = plugin.getConfig().getString("clicker-item.skull-texture", "");
        boolean wantsSkull = matName.equalsIgnoreCase("PLAYER_HEAD")
                          || matName.equalsIgnoreCase("SKULL_ITEM");

        // ── Name: animation frames (lang) or static name (lang) ───────────────
        List<String> frames = lang.getRawList("gui.clicker-item.animation");
        String name;
        if (!frames.isEmpty()) {
            long clicks = plugin.getClickCount(player.getUniqueId());
            name = ColorUtil.colorize(frames.get((int) (clicks % frames.size())));
        } else {
            name = lang.get("gui.clicker-item.name");
        }

        // ── Lore with full placeholder support (raw from lang) ────────────────
        List<String> lore = new ArrayList<>();
        for (String line : lang.getRawList("gui.clicker-item.lore")) {
            lore.add(resolvePlaceholders(line, plugin, player, null));
        }

        // ── Build item ────────────────────────────────────────────────────────
        if (wantsSkull && skullTex != null && !skullTex.isEmpty()) {
            return buildSkull(plugin, skullTex, name, lore);
        }
        Material mat = parseMaterial(matName, Material.DIAMOND, plugin);
        ItemStack item = new ItemStack(mat);
        applyMeta(item, name, lore);
        return item;
    }

    // =========================================================================
    // Balance display  (public — ClickerListener refreshes it after each click)
    // =========================================================================

    public static ItemStack buildBalanceDisplay(ClickerPlugin plugin, Player player) {
        LangManager lang = plugin.getLang();
        String matName = plugin.getConfig().getString("balance-display.material", "GOLD_INGOT");
        Material mat   = parseMaterial(matName, Material.GOLD_INGOT, plugin);
        ItemStack item = new ItemStack(mat);

        String name = lang.get("gui.balance-display.name");
        List<String> lore = new ArrayList<>();
        for (String line : lang.getRawList("gui.balance-display.lore")) {
            lore.add(resolvePlaceholders(line, plugin, player, null));
        }

        applyMeta(item, name, lore);
        return item;
    }

    // =========================================================================
    // Placeholder resolution
    // =========================================================================

    /**
     * Replaces all {@code %clicker_*%} tokens in {@code text}, then passes the
     * result through PlaceholderAPI (if installed) to resolve any third-party
     * placeholders (e.g. {@code %player_name%}), then applies colour codes.
     *
     * <p>Pass {@code actualAmount} as the real reward for reward / jackpot messages;
     * pass {@code null} to derive the expected amount from config (used in lore).
     */
    static String resolvePlaceholders(String text, ClickerPlugin plugin,
                                       Player player, Double actualAmount) {
        boolean random = plugin.getConfig().getBoolean("random-reward.enabled", false);
        double base = random
                ? (plugin.getConfig().getDouble("random-reward.min", 0.5)
                   + plugin.getConfig().getDouble("random-reward.max", 5.0)) / 2.0
                : plugin.getConfig().getDouble("reward-amount", 1.0);

        double multiplier = plugin.getEffectiveMultiplier(player);
        double shown      = actualAmount != null ? actualAmount : base * multiplier;
        double minAmt     = plugin.getConfig().getDouble(
                random ? "random-reward.min" : "reward-amount", base) * multiplier;
        double maxAmt     = plugin.getConfig().getDouble(
                random ? "random-reward.max" : "reward-amount", base) * multiplier;

        long clicks = plugin.getClickCount(player.getUniqueId());

        // 1. Replace internal %clicker_*% tokens
        String result = text
                .replace("%clicker_amount%",              formatAmount(shown))
                .replace("%clicker_min_amount%",          formatAmount(minAmt))
                .replace("%clicker_max_amount%",          formatAmount(maxAmt))
                .replace("%clicker_clicks%",              String.valueOf(clicks))
                .replace("%clicker_multiplier%",          formatAmount(multiplier))
                .replace("%clicker_personal_multiplier%", formatAmount(plugin.getPersonalMultiplier(player)))
                .replace("%clicker_global_multiplier%",   formatAmount(plugin.getGlobalMultiplier()));
        // fetch balance only when the placeholder is actually present
        if (result.contains("%clicker_balance%")) {
            double balance = plugin.isVaultEnabled() ? plugin.getEconomy().getBalance(player) : 0.0;
            result = result.replace("%clicker_balance%", formatAmount(balance));
        }
        // O(n) rank scan — only compute when the placeholder is actually present
        if (result.contains("%clicker_rank%")) {
            result = result.replace("%clicker_rank%", String.valueOf(plugin.getClickRank(player.getUniqueId())));
        }

        if (result.contains("%clicker_jackpot_min_amount%") || result.contains("%clicker_jackpot_max_amount%")) {
            boolean jpEnabled = plugin.getConfig().getBoolean("random-reward.jackpot.enabled", false);
            if (jpEnabled) {
                double jpMin = plugin.getConfig().getDouble("random-reward.jackpot.min", 50.0) * multiplier;
                double jpMax = plugin.getConfig().getDouble("random-reward.jackpot.max", 100.0) * multiplier;
                result = result
                        .replace("%clicker_jackpot_min_amount%", formatAmount(jpMin))
                        .replace("%clicker_jackpot_max_amount%", formatAmount(jpMax));
            } else {
                result = result
                        .replace("%clicker_jackpot_min_amount%", "N/A")
                        .replace("%clicker_jackpot_max_amount%", "N/A");
            }
        }

        // 2. Resolve any third-party PAPI placeholders (e.g. %player_name%)
        if (plugin.isPapiEnabled()) {
            result = PlaceholderAPI.setPlaceholders(player, result);
        }

        // 3. Apply colour codes (handles both & codes and &#RRGGBB hex)
        return ColorUtil.colorize(result);
    }

    // =========================================================================
    // Skull building
    // =========================================================================

    @SuppressWarnings("deprecation")
    private static ItemStack buildSkull(ClickerPlugin plugin, String base64,
                                        String displayName, List<String> lore) {
        Material mat;
        boolean legacy;
        try { mat = Material.valueOf("PLAYER_HEAD"); legacy = false; }
        catch (IllegalArgumentException e) {
            try { mat = Material.valueOf("SKULL_ITEM"); legacy = true; }
            catch (IllegalArgumentException e2) { mat = Material.DIRT; legacy = false; }
        }

        ItemStack item = legacy ? new ItemStack(mat, 1, (short) 3) : new ItemStack(mat);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) return item;

        if (!applyViaPlayerProfile(meta, base64, plugin)) applyViaGameProfile(meta, base64, plugin);

        meta.setDisplayName(displayName);
        if (!lore.isEmpty()) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean applyViaPlayerProfile(SkullMeta meta, String base64, ClickerPlugin plugin) {
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            int idx = json.indexOf("\"url\":\"");
            if (idx == -1) return false;
            int s = idx + 7, e = json.indexOf('"', s);
            if (e == -1) return false;
            URL skinUrl = new URL(json.substring(s, e));
            UUID id = UUID.nameUUIDFromBytes(base64.getBytes(StandardCharsets.UTF_8));

            Object profile = Bukkit.class.getMethod("createPlayerProfile", UUID.class).invoke(null, id);

            Method getTextures = profile.getClass().getMethod("getTextures");
            getTextures.setAccessible(true);
            Object textures = getTextures.invoke(profile);

            // setSkin accepts URL on ≤1.21.4; URI on ≥1.21.5
            try {
                Method setSkin = textures.getClass().getMethod("setSkin", URL.class);
                setSkin.setAccessible(true);
                setSkin.invoke(textures, skinUrl);
            } catch (NoSuchMethodException ex) {
                Method setSkin = textures.getClass().getMethod("setSkin", java.net.URI.class);
                setSkin.setAccessible(true);
                setSkin.invoke(textures, skinUrl.toURI());
            }

            for (Method m : profile.getClass().getMethods()) {
                if (m.getName().equals("setTextures") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    m.invoke(profile, textures);
                    break;
                }
            }
            for (Method m : meta.getClass().getMethods()) {
                if (m.getName().equals("setOwnerProfile") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    m.invoke(meta, profile);
                    return true;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("PlayerProfile skull path failed (" + e.getClass().getSimpleName() + "): " + e.getMessage() + " — trying GameProfile fallback.");
        }
        return false;
    }

    private static void applyViaGameProfile(SkullMeta meta, String base64, ClickerPlugin plugin) {
        try {
            Class<?> gpClass   = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propClass = Class.forName("com.mojang.authlib.properties.Property");
            UUID id = UUID.nameUUIDFromBytes(base64.getBytes(StandardCharsets.UTF_8));
            Object gp = gpClass.getConstructor(UUID.class, String.class).newInstance(id, "CE");
            Object prop;
            try { prop = propClass.getConstructor(String.class, String.class).newInstance("textures", base64); }
            catch (NoSuchMethodException ex) { prop = propClass.getConstructor(String.class, String.class, String.class).newInstance("textures", base64, null); }

            // Resolve the property map: "getProperties" (≤1.21.4) or "properties" (≥1.21.5 record accessor).
            // Walk the full class hierarchy in case the method is declared on a supertype.
            Method getPropMethod = null;
            for (String candidate : new String[]{"getProperties", "properties"}) {
                for (Class<?> c = gpClass; c != null && getPropMethod == null; c = c.getSuperclass()) {
                    try {
                        Method m = c.getDeclaredMethod(candidate);
                        m.setAccessible(true);
                        getPropMethod = m;
                    } catch (NoSuchMethodException ignored) {}
                }
                if (getPropMethod != null) break;
            }
            if (getPropMethod == null) {
                plugin.getLogger().warning("Could not apply skull texture: no getProperties/properties method found on GameProfile.");
                return;
            }
            Object propMap = getPropMethod.invoke(gp);
            for (Method m : propMap.getClass().getMethods())
                if (m.getName().equals("put") && m.getParameterCount() == 2) { m.invoke(propMap, "textures", prop); break; }

            Field f = meta.getClass().getDeclaredField("profile");
            f.setAccessible(true);
            f.set(meta, gp);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not apply skull texture (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
    }

    // =========================================================================
    // Filler item
    // =========================================================================

    @SuppressWarnings("deprecation")
    private static ItemStack buildFiller(ClickerPlugin plugin) {
        String matName    = plugin.getConfig().getString("filler.material", "GRAY_STAINED_GLASS_PANE");
        String displayName = plugin.getLang().get("gui.filler.name");

        Material mat = null; boolean useLegacyDamage = false;
        try { mat = Material.valueOf(matName.toUpperCase()); }
        catch (IllegalArgumentException e) { plugin.getLogger().warning("Invalid filler material '" + matName + "', using default."); }
        if (mat == null) { try { mat = Material.valueOf("GRAY_STAINED_GLASS_PANE"); } catch (IllegalArgumentException ignored) {} }
        if (mat == null) { try { mat = Material.valueOf("STAINED_GLASS_PANE"); useLegacyDamage = true; } catch (IllegalArgumentException ignored) {} }
        if (mat == null) mat = Material.GLASS;

        ItemStack item = useLegacyDamage ? new ItemStack(mat, 1, (short) 7) : new ItemStack(mat);
        applyMeta(item, displayName, null);
        return item;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void applyMeta(ItemStack item, String name, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setDisplayName(name);
        if (lore != null && !lore.isEmpty()) meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private static Material parseMaterial(String name, Material fallback, ClickerPlugin plugin) {
        try { return Material.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid material '" + name + "', falling back to " + fallback.name() + ".");
            return fallback;
        }
    }

    public static String formatAmount(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) return "N/A";
        if (amount == Math.floor(amount))
            return String.valueOf((long) amount);
        return String.format("%.2f", amount);
    }
}
