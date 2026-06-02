package com.clickereconomy;

import com.clickereconomy.storage.ClickerStorage;
import com.clickereconomy.storage.SqlClickerStorage;
import com.clickereconomy.storage.YamlClickerStorage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ClickerPlugin extends JavaPlugin {

    /**
     * Version history:
     *   1–8 — original releases
     *   9   — added storage section (yaml/h2/mysql/mariadb/postgresql)
     */
    static final int CONFIG_VERSION = 10;

    // ── Economy ───────────────────────────────────────────────────────────────
    private Economy economy;
    private boolean vaultEnabled = false;
    private boolean papiEnabled  = false;

    // ── Click cooldown map ────────────────────────────────────────────────────
    private final HashMap<UUID, Long> cooldowns = new HashMap<>();

    // ── Click-count in-memory cache ───────────────────────────────────────────
    private final HashMap<UUID, Long> clickCounts = new HashMap<>();

    // ── Global multiplier ─────────────────────────────────────────────────────
    private double activeGlobalMultiplier          = 1.0;
    private long   globalMultiplierRemainingSeconds = -1;
    private BukkitTask countdownTask = null;
    private BukkitTask autoSaveTask  = null;

    // ── Storage backend ───────────────────────────────────────────────────────
    private ClickerStorage storage;

    // ── Language ──────────────────────────────────────────────────────────────
    private LangManager lang;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        checkAndMigrateConfig();
        validateConfig();
        lang = new LangManager(this);
        lang.load();
        initStorage();
        loadData();
        setupVault();
        setupPlaceholders();

        ClickerCommand cmd = new ClickerCommand(this);
        PluginCommand command = getCommand("clicker");
        if (command == null) {
            getLogger().severe("Command 'clicker' not registered in plugin.yml — disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(cmd);
        command.setTabCompleter(cmd);
        getServer().getPluginManager().registerEvents(new ClickerListener(this), this);

        autoSaveTask = new BukkitRunnable() {
            @Override public void run() { saveData(); }
        }.runTaskTimer(this, 6000L, 6000L);

        getLogger().info("ClickerEconomy enabled!");
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) { autoSaveTask.cancel(); autoSaveTask = null; }
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        saveData();
        if (storage != null) storage.close();
        cooldowns.clear();
        getLogger().info("ClickerEconomy disabled.");
    }

    // =========================================================================
    // Storage initialisation
    // =========================================================================

    private void initStorage() {
        String backend = getConfig().getString("storage.backend", "yaml").toLowerCase();
        boolean isSql = backend.equals("h2") || backend.equals("mysql")
                || backend.equals("mariadb") || backend.equals("postgresql");

        if (isSql) {
            storage = new SqlClickerStorage(this);
        } else {
            storage = new YamlClickerStorage(this);
            backend = "yaml";
        }

        try {
            storage.init();
            getLogger().info("Storage backend: " + backend.toUpperCase());
        } catch (Exception e) {
            getLogger().severe("Storage backend '" + backend + "' failed to initialise: " + e.getMessage());
            getLogger().severe("Falling back to YAML storage.");
            storage = new YamlClickerStorage(this);
            storage.init();
        }
    }

    // =========================================================================
    // Data persistence
    // =========================================================================

    private void loadData() {
        // Click counts
        Map<UUID, Long> loaded = storage.loadClicks();
        clickCounts.putAll(loaded);
        getLogger().info("Loaded click data for " + clickCounts.size() + " player(s).");

        // Global multiplier
        ClickerStorage.MultiplierRecord rec = storage.loadMultiplier();
        if (rec != null) {
            long savedRemaining = rec.remaining;
            if (savedRemaining == 0) {
                getLogger().info("Timed global multiplier expired while offline — reverting to base.");
                activeGlobalMultiplier          = getConfig().getDouble("global-multiplier", 1.0);
                globalMultiplierRemainingSeconds = -1;
                storage.clearMultiplier();
            } else {
                activeGlobalMultiplier          = rec.value;
                globalMultiplierRemainingSeconds = savedRemaining;
                if (savedRemaining > 0) {
                    startCountdown();
                    getLogger().info(String.format(
                            "Resuming global multiplier %.2fx with %s remaining.",
                            rec.value, formatDuration(savedRemaining)));
                } else {
                    getLogger().info(String.format("Loaded permanent global multiplier %.2fx.", rec.value));
                }
            }
        } else {
            activeGlobalMultiplier = getConfig().getDouble("global-multiplier", 1.0);
        }
    }

    public void saveData() {
        if (storage == null) return;

        storage.saveClicks(clickCounts);

        double  baseMultiplier = getConfig().getDouble("global-multiplier", 1.0);
        boolean isDefault      = Math.abs(activeGlobalMultiplier - baseMultiplier) < 1e-9
                && globalMultiplierRemainingSeconds == -1;
        if (isDefault) {
            storage.clearMultiplier();
        } else {
            storage.saveMultiplier(activeGlobalMultiplier, globalMultiplierRemainingSeconds);
        }
    }

    // =========================================================================
    // Click-count API
    // =========================================================================

    public long getClickCount(UUID uuid)         { return clickCounts.getOrDefault(uuid, 0L); }
    public void incrementClickCount(UUID uuid)   { clickCounts.merge(uuid, 1L, Long::sum); }

    public void setClickCount(UUID uuid, long count) {
        if (count <= 0) {
            clickCounts.remove(uuid);
        } else {
            clickCounts.put(uuid, count);
        }
    }

    /** Returns all entries sorted descending by click count. Pass {@link Integer#MAX_VALUE} for all. */
    public List<Map.Entry<UUID, Long>> getTopPlayers(int limit) {
        List<Map.Entry<UUID, Long>> sorted = new ArrayList<>(clickCounts.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<UUID, Long>>() {
            @Override
            public int compare(Map.Entry<UUID, Long> a, Map.Entry<UUID, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    public int getClickRank(UUID uuid) {
        long mine = clickCounts.getOrDefault(uuid, 0L);
        int rank = 1;
        for (Map.Entry<UUID, Long> e : clickCounts.entrySet()) {
            if (!e.getKey().equals(uuid) && e.getValue() > mine) rank++;
        }
        return rank;
    }

    // =========================================================================
    // Multiplier API
    // =========================================================================

    public double getPersonalMultiplier(Player player) {
        double highest = 1.0;
        for (PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
            if (!pai.getValue()) continue;
            String perm = pai.getPermission().toLowerCase();
            if (!perm.startsWith("clicker.multiplier.")) continue;
            try {
                double val = Double.parseDouble(perm.substring("clicker.multiplier.".length()));
                if (val > highest) highest = val;
            } catch (NumberFormatException ignored) {}
        }
        return highest;
    }

    public double getGlobalMultiplier()            { return activeGlobalMultiplier; }
    public long   getGlobalMultiplierRemaining()   { return globalMultiplierRemainingSeconds; }
    public double getEffectiveMultiplier(Player p) { return getGlobalMultiplier() * getPersonalMultiplier(p); }

    public void setGlobalMultiplier(double value, long durationSeconds) {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        activeGlobalMultiplier          = value;
        globalMultiplierRemainingSeconds = durationSeconds;
        if (durationSeconds > 0) startCountdown();
        saveData();
    }

    private void startCountdown() {
        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                globalMultiplierRemainingSeconds--;
                if (globalMultiplierRemainingSeconds <= 0) {
                    cancel();
                    countdownTask = null;
                    expireGlobalMultiplier();
                    return;
                }
                if (globalMultiplierRemainingSeconds % 60 == 0) saveData();
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void expireGlobalMultiplier() {
        double expiredValue             = activeGlobalMultiplier;
        activeGlobalMultiplier          = getConfig().getDouble("global-multiplier", 1.0);
        globalMultiplierRemainingSeconds = -1;
        saveData();
        if (lang.isEnabled("multiplier-events.expired")) {
            Bukkit.broadcastMessage(lang.get("multiplier-events.expired",
                    "%clicker_multiplier%", ClickerGUI.formatAmount(expiredValue)));
        }
    }

    // =========================================================================
    // Duration formatting
    // =========================================================================

    public static String formatDuration(long totalSeconds) {
        if (totalSeconds < 0) return "permanent";
        if (totalSeconds == 0) return "expired";
        long days    = totalSeconds / 86400;
        long hours   = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days    > 0) sb.append(days).append("d ");
        if (hours   > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.length() == 0) sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    // =========================================================================
    // Config management
    // =========================================================================

    public void reloadPluginConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
            getLogger().info("config.yml was missing and has been regenerated from defaults.");
        }
        reloadConfig();
        checkAndMigrateConfig();
        validateConfig();
        lang.reload();
        vaultEnabled = false;
        economy = null;
        setupVault();
        if (!papiEnabled) setupPlaceholders();
    }

    private void checkAndMigrateConfig() {
        int existing = getConfig().getInt("config-version", 0);
        if (existing >= CONFIG_VERSION) return;

        getLogger().info(existing == 0
                ? "No config-version found — treating as legacy and migrating to v" + CONFIG_VERSION + "."
                : "Config is v" + existing + ", updating to v" + CONFIG_VERSION + "...");

        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) {
            String backupName = "config_v" + (existing == 0 ? "legacy" : existing) + ".bak";
            try {
                Files.copy(configFile.toPath(),
                        new File(getDataFolder(), backupName).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                getLogger().info("Previous config backed up as " + backupName + ".");
            } catch (IOException e) {
                getLogger().warning("Could not back up config.yml: " + e.getMessage());
            }
        }

        FileConfiguration defaults = loadBundledConfig();
        if (defaults == null) return;

        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (getConfig().contains(key)) continue;
            Object value = defaults.get(key);
            if (value instanceof org.bukkit.configuration.ConfigurationSection) continue;
            getConfig().set(key, value);
            added.add(key);
        }

        if (existing < 6) {
            String[] obsolete = {
                "gui-title", "reward-message", "cooldown-message", "multiplier-expired-message",
                "clicker-item.name", "clicker-item.animation", "clicker-item.lore",
                "balance-display.name", "balance-display.lore", "filler.name",
                "random-reward.jackpot.message"
            };
            List<String> removed = new ArrayList<>();
            for (String key : obsolete) {
                if (getConfig().contains(key)) { getConfig().set(key, null); removed.add(key); }
            }
            if (!removed.isEmpty()) {
                getLogger().info("  Removed " + removed.size() + " obsolete locale key(s).");
            }
        }

        getConfig().set("config-version", CONFIG_VERSION);
        saveConfig();

        getLogger().info("Config migrated to v" + CONFIG_VERSION + ".");
        if (!added.isEmpty()) {
            getLogger().info("  Added " + added.size() + " new key(s): " + String.join(", ", added));
        }
    }

    private FileConfiguration loadBundledConfig() {
        java.io.InputStream stream = getResource("config.yml");
        if (stream == null) { getLogger().severe("Bundled config.yml not found — cannot migrate."); return null; }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            getLogger().warning("Could not read bundled config.yml: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Vault & PlaceholderAPI
    // =========================================================================

    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found — economy features disabled."); return;
        }
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("No economy provider registered — economy features disabled."); return;
        }
        economy = rsp.getProvider();
        vaultEnabled = (economy != null);
        if (!vaultEnabled) getLogger().warning("Economy provider returned null — economy features disabled.");
    }

    private void setupPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ClickerPlaceholders(this).register();
            papiEnabled = true;
            getLogger().info("PlaceholderAPI found — placeholders registered.");
        }
    }

    // =========================================================================
    // Config validation
    // =========================================================================

    private void validateConfig() {
        int clickerSlot = getConfig().getInt("clicker-item.slot", 13);
        int balSlot     = getConfig().getInt("balance-display.slot", 4);
        if (clickerSlot < 0 || clickerSlot >= 27)
            getLogger().warning("clicker-item.slot (" + clickerSlot + ") is out of range [0-26]; defaulting to 13.");
        if (balSlot >= 0 && balSlot < 27 && balSlot == getClickerSlot())
            getLogger().warning("balance-display.slot conflicts with clicker-item.slot; balance display will not be shown.");
        double jackpotChance = getConfig().getDouble("random-reward.jackpot.chance", 0.01);
        if (jackpotChance < 0 || jackpotChance > 1)
            getLogger().warning("random-reward.jackpot.chance (" + jackpotChance + ") is outside [0, 1].");
        if (getConfig().getBoolean("random-reward.enabled", false)) {
            double rMin = getConfig().getDouble("random-reward.min", 0.5);
            double rMax = getConfig().getDouble("random-reward.max", 5.0);
            if (rMin > rMax)
                getLogger().warning("random-reward.min > random-reward.max; all rewards will equal min.");
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public Economy getEconomy()     { return economy; }
    public boolean isVaultEnabled() { return vaultEnabled; }
    public boolean isPapiEnabled()  { return papiEnabled; }
    public LangManager getLang()    { return lang; }

    public Long getCooldown(UUID uuid)          { return cooldowns.get(uuid); }
    public void setCooldown(UUID uuid, long ms) { cooldowns.put(uuid, ms); }
    public void removeCooldown(UUID uuid)       { cooldowns.remove(uuid); }

    public int getClickerSlot() {
        int slot = getConfig().getInt("clicker-item.slot", 13);
        return (slot >= 0 && slot < 27) ? slot : 13;
    }
}
