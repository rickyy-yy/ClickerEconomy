package com.clickereconomy.storage;

import com.clickereconomy.ClickerPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class YamlClickerStorage implements ClickerStorage {

    private final ClickerPlugin plugin;
    private File              dataFile;
    private FileConfiguration dataConfig;

    public YamlClickerStorage(ClickerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void init() {
        plugin.getDataFolder().mkdirs();
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); }
            catch (IOException e) {
                plugin.getLogger().warning("Could not create data.yml: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    @Override
    public void close() { /* nothing to close for YAML */ }

    // ── Clicks ───────────────────────────────────────────────────────────────

    @Override
    public Map<UUID, Long> loadClicks() {
        Map<UUID, Long> result = new HashMap<>();
        ConfigurationSection sec = dataConfig.getConfigurationSection("clicks");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try { result.put(UUID.fromString(key), dataConfig.getLong("clicks." + key)); }
                catch (IllegalArgumentException ignored) {}
            }
        }
        return result;
    }

    @Override
    public void saveClicks(Map<UUID, Long> clickCounts) {
        // Wipe section so removed entries are actually deleted
        dataConfig.set("clicks", null);
        for (Map.Entry<UUID, Long> e : clickCounts.entrySet()) {
            if (e.getValue() > 0) {
                dataConfig.set("clicks." + e.getKey(), e.getValue());
            }
        }
        flush();
    }

    // ── Multiplier ────────────────────────────────────────────────────────────

    @Override
    public MultiplierRecord loadMultiplier() {
        if (!dataConfig.contains("global-multiplier.value")) return null;
        double value     = dataConfig.getDouble("global-multiplier.value", 1.0);
        long   remaining = dataConfig.getLong("global-multiplier.remaining", -1);
        return new MultiplierRecord(value, remaining);
    }

    @Override
    public void saveMultiplier(double value, long remainingSeconds) {
        dataConfig.set("global-multiplier.value",     value);
        dataConfig.set("global-multiplier.remaining", remainingSeconds);
        flush();
    }

    @Override
    public void clearMultiplier() {
        dataConfig.set("global-multiplier", null);
        flush();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void flush() {
        try { dataConfig.save(dataFile); }
        catch (IOException e) {
            plugin.getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }
}
