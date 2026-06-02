package com.clickereconomy;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Registers ClickerEconomy placeholders with PlaceholderAPI.
 *
 * <p>Available placeholders:
 * <ul>
 *   <li>{@code %clicker_clicks%}                   — total lifetime clicks</li>
 *   <li>{@code %clicker_rank%}                     — rank by clicks (1 = most clicks)</li>
 *   <li>{@code %clicker_balance%}                  — Vault economy balance</li>
 *   <li>{@code %clicker_multiplier%}               — effective multiplier (global × personal)</li>
 *   <li>{@code %clicker_personal_multiplier%}      — personal permission multiplier</li>
 *   <li>{@code %clicker_global_multiplier%}        — server-wide global multiplier</li>
 *   <li>{@code %clicker_multiplier_remaining%}     — seconds left on timed multiplier (-1 = permanent)</li>
 *   <li>{@code %clicker_multiplier_remaining_fmt%} — human-readable time left (e.g. "1h 30m 5s")</li>
 * </ul>
 */
public class ClickerPlaceholders extends PlaceholderExpansion {

    private final ClickerPlugin plugin;

    public ClickerPlaceholders(ClickerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "clicker"; }
    @Override public String getAuthor()     { return "ClickerEconomy"; }
    @Override public String getVersion()    { return plugin.getDescription().getVersion(); }

    /** Keep registration alive across PlaceholderAPI reloads. */
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (offlinePlayer == null) return "";
        Player player = offlinePlayer.getPlayer(); // null when offline

        switch (params.toLowerCase()) {

            case "clicks":
                return String.valueOf(plugin.getClickCount(offlinePlayer.getUniqueId()));

            case "rank":
                return String.valueOf(plugin.getClickRank(offlinePlayer.getUniqueId()));

            case "balance":
                if (!plugin.isVaultEnabled()) return "N/A";
                double bal = plugin.getEconomy().getBalance(offlinePlayer);
                return ClickerGUI.formatAmount(bal);

            case "multiplier":
                if (player == null) return "N/A";
                return ClickerGUI.formatAmount(plugin.getEffectiveMultiplier(player));

            case "personal_multiplier":
                if (player == null) return "N/A";
                return ClickerGUI.formatAmount(plugin.getPersonalMultiplier(player));

            case "global_multiplier":
                return ClickerGUI.formatAmount(plugin.getGlobalMultiplier());

            case "multiplier_remaining":
                return String.valueOf(plugin.getGlobalMultiplierRemaining());

            case "multiplier_remaining_fmt":
                return ClickerPlugin.formatDuration(plugin.getGlobalMultiplierRemaining());

            default:
                return null; // unknown placeholder — PAPI will leave it unresolved
        }
    }
}
