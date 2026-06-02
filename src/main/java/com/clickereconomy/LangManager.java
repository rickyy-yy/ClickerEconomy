package com.clickereconomy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and manages {@code lang.yml}, providing all player-facing text.
 *
 * <h3>Key design rules</h3>
 * <ul>
 *   <li>All internal placeholders use the {@code %clicker_<name>%} format to avoid
 *       conflicts with PlaceholderAPI and other plugins.</li>
 *   <li>{@link #get} replaces placeholders then applies colour codes — use for simple
 *       chat messages with a small number of runtime values.</li>
 *   <li>{@link #getRaw} / {@link #getRawList} return the unprocessed string(s) — pass
 *       these into {@code ClickerGUI.resolvePlaceholders} when many values need filling.</li>
 *   <li>Missing keys return a visible error token so admins can spot misconfigured files.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 *   <li>1 — initial release</li>
 *   <li>2 — all internal {@code %placeholder%} tokens renamed to
 *            {@code %clicker_placeholder%} to prevent PAPI conflicts</li>
 * </ul>
 */
public class LangManager {

    static final int LANG_VERSION = 6;

    private static final String MISSING_PREFIX = "§c[MISSING LANG: ";

    /**
     * Keys whose values are GUI display text or inline message fragments.
     * These are never prefixed with the lang.yml {@code prefix} value.
     */
    private static final java.util.Set<String> NO_PREFIX_KEYS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                "multiplier-cmd.time-permanent",
                "multiplier-cmd.time-expires",
                "multiplier-cmd.duration-permanent",
                "multiplier-cmd.duration-timed",
                "help.header",
                "stats.header",
                "placeholders.header"
            ));

    /**
     * Rename table applied during the v1 → v2 migration.
     * Order matters for safety: longer/more-specific tokens are listed first so that
     * a short pattern cannot match inside a longer one before the longer one is renamed.
     * (e.g. {@code %personal_multiplier%} must be renamed before {@code %multiplier%})
     */
    private static final String[][] PLACEHOLDER_RENAMES = {
        {"%personal_multiplier%", "%clicker_personal_multiplier%"},
        {"%global_multiplier%",   "%clicker_global_multiplier%"},
        {"%min_amount%",          "%clicker_min_amount%"},
        {"%max_amount%",          "%clicker_max_amount%"},
        {"%time_tag%",            "%clicker_time_tag%"},
        {"%duration_tag%",        "%clicker_duration_tag%"},
        {"%amount%",              "%clicker_amount%"},
        {"%balance%",             "%clicker_balance%"},
        {"%clicks%",              "%clicker_clicks%"},
        {"%rank%",                "%clicker_rank%"},
        {"%multiplier%",          "%clicker_multiplier%"},
        {"%seconds%",             "%clicker_seconds%"},
        {"%effective%",           "%clicker_effective%"},
        {"%personal%",            "%clicker_personal%"},
        {"%global%",              "%clicker_global%"},
        {"%value%",               "%clicker_value%"},
        {"%time%",                "%clicker_time%"},
        {"%input%",               "%clicker_input%"},
    };

    private final ClickerPlugin plugin;
    private FileConfiguration lang;
    private File langFile;
    private String cachedPrefix = null;

    public LangManager(ClickerPlugin plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void load() {
        cachedPrefix = null;
        langFile = new File(plugin.getDataFolder(), "lang.yml");
        if (!langFile.exists()) plugin.saveResource("lang.yml", false);
        lang = YamlConfiguration.loadConfiguration(langFile);
        migrate();
    }

    public void reload() {
        cachedPrefix = null;
        if (!langFile.exists()) {
            plugin.saveResource("lang.yml", false);
            plugin.getLogger().info("lang.yml was missing and has been regenerated.");
        }
        lang = YamlConfiguration.loadConfiguration(langFile);
        migrate();
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the message at {@code key} with placeholder pairs replaced, then
     * colour-translated.  Pass alternating placeholder/value pairs:
     * {@code get("key", "%clicker_amount%", "5.00", "%clicker_clicks%", "42")}.
     *
     * <p>The {@code prefix} value from lang.yml is automatically prepended for
     * chat messages.  GUI item text (keys starting with {@code "gui."}) and
     * inline fragment keys are never prefixed.
     */
    public String get(String key, String... placeholders) {
        String raw = getRaw(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        String result = ColorUtil.colorize(raw);
        if (!key.startsWith("gui.") && !NO_PREFIX_KEYS.contains(key) && !raw.isEmpty()) {
            result = getPrefix() + result;
        }
        return result;
    }

    /** Returns the colourised prefix string, or {@code ""} if not set. Cached between reloads. */
    public String getPrefix() {
        if (cachedPrefix == null) {
            String p = lang.getString("prefix", "");
            cachedPrefix = (p == null || p.isEmpty()) ? "" : ColorUtil.colorize(p);
        }
        return cachedPrefix;
    }

    /**
     * Raw (un-colourised, un-replaced) string — pass to
     * {@link ClickerGUI#resolvePlaceholders} which handles both.
     */
    public String getRaw(String key) {
        String value = lang.getString(key);
        return value != null ? value : MISSING_PREFIX + key + "]";
    }

    /** Raw list — pass to {@link ClickerGUI#resolvePlaceholders} for each entry. */
    public List<String> getRawList(String key) {
        return lang.getStringList(key);
    }

    /** Colourised list with placeholders replaced. */
    public List<String> getList(String key, String... placeholders) {
        List<String> out = new ArrayList<>();
        for (String line : getRawList(key)) {
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                line = line.replace(placeholders[i], placeholders[i + 1]);
            }
            out.add(ColorUtil.colorize(line));
        }
        return out;
    }

    /**
     * {@code true} when the key exists and its string value is non-empty.
     * Useful for optional broadcasts that admins silence by setting {@code ""}.
     */
    public boolean isEnabled(String key) {
        String value = lang.getString(key);
        return value != null && !value.isEmpty();
    }

    // =========================================================================
    // Migration
    // =========================================================================

    private void migrate() {
        int existing = lang.getInt("lang-version", 0);
        if (existing >= LANG_VERSION) return;

        plugin.getLogger().info(existing == 0
                ? "No lang-version found — migrating lang.yml to v" + LANG_VERSION + "."
                : "lang.yml is v" + existing + ", updating to v" + LANG_VERSION + "...");

        // Backup before any changes
        if (langFile.exists()) {
            String bak = "lang_v" + (existing == 0 ? "legacy" : existing) + ".bak";
            try {
                Files.copy(langFile.toPath(),
                        new File(plugin.getDataFolder(), bak).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Previous lang.yml backed up as " + bak + ".");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not back up lang.yml: " + e.getMessage());
            }
        }

        // ── v2: rename %placeholder% → %clicker_placeholder% in all values ──
        if (existing < 2) {
            renamePlaceholders();
        }

        // v3: structural-only bump — new keys are added via the missing-keys loop below;
        //     no active migration step is required.

        // ── v4: strip hardcoded "[ClickerEconomy] " from multiplier-events.expired ──
        if (existing < 4) {
            String key = "multiplier-events.expired";
            String val = lang.getString(key, "");
            String old = "&6[ClickerEconomy] &e";
            if (val.startsWith(old)) {
                lang.set(key, "&e" + val.substring(old.length()));
                plugin.getLogger().info("  Removed hardcoded prefix from " + key + ".");
            }
        }

        // ── Add any keys present in the bundled default but missing here ──────
        java.io.InputStream stream = plugin.getResource("lang.yml");
        if (stream == null) {
            plugin.getLogger().severe("Bundled lang.yml not found — cannot complete migration.");
            return;
        }
        FileConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read bundled lang.yml: " + e.getMessage());
            return;
        }

        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (lang.contains(key)) continue;
            Object value = defaults.get(key);
            if (value instanceof ConfigurationSection) continue;
            lang.set(key, value);
            added.add(key);
        }

        lang.set("lang-version", LANG_VERSION);
        try {
            lang.save(langFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save lang.yml: " + e.getMessage());
        }

        plugin.getLogger().info("lang.yml migrated to v" + LANG_VERSION + ".");
        if (!added.isEmpty()) {
            plugin.getLogger().info("  Added " + added.size() + " key(s): "
                    + String.join(", ", added));
        }
    }

    /**
     * Walks every string value and every string-list entry in the live config and
     * applies {@link #PLACEHOLDER_RENAMES}.  Section nodes and non-string scalars
     * (integers, booleans) are skipped safely.
     */
    @SuppressWarnings("unchecked")
    private void renamePlaceholders() {
        int changed = 0;
        for (String key : lang.getKeys(true)) {
            Object raw = lang.get(key);
            if (raw instanceof String) {
                String updated = applyRenames((String) raw);
                if (!updated.equals(raw)) { lang.set(key, updated); changed++; }
            } else if (raw instanceof List) {
                List<?> rawList = (List<?>) raw;
                List<String> updated = new ArrayList<>();
                boolean listChanged = false;
                for (Object entry : rawList) {
                    if (entry instanceof String) {
                        String upd = applyRenames((String) entry);
                        if (!upd.equals(entry)) listChanged = true;
                        updated.add(upd);
                    } else {
                        updated.add(entry != null ? entry.toString() : "");
                    }
                }
                if (listChanged) { lang.set(key, updated); changed++; }
            }
            // integers, booleans, sections — skip
        }
        if (changed > 0) {
            plugin.getLogger().info("  Renamed %placeholder% → %clicker_placeholder% in "
                    + changed + " value(s).");
        }
    }

    private static String applyRenames(String text) {
        for (String[] pair : PLACEHOLDER_RENAMES) {
            text = text.replace(pair[0], pair[1]);
        }
        return text;
    }
}
