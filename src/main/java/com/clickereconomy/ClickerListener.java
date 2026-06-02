package com.clickereconomy;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class ClickerListener implements Listener {

    private final ClickerPlugin plugin;

    /** Hard floor — always silent, always enforced. Stops event-burst duplicates. */
    private static final long MIN_CLICK_MS = 200L;

    public ClickerListener(ClickerPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Remove cooldown entry when a player disconnects ───────────────────────

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.removeCooldown(event.getPlayer().getUniqueId());
    }

    // ── Block all drag actions inside our GUI ─────────────────────────────────

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof ClickerGUI)) return;
        event.setCancelled(true);
    }

    // ── Main click handler ────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!(event.getInventory().getHolder() instanceof ClickerGUI)) return;

        event.setCancelled(true); // cancel everything — prevents item theft

        // ── 1. Reward only intentional single clicks ──────────────────────────
        switch (event.getClick()) {
            case LEFT: case RIGHT: break;
            default: return;
        }

        if (event.getRawSlot() != plugin.getClickerSlot()) return;

        Player player = (Player) event.getWhoClicked();
        UUID   uuid   = player.getUniqueId();
        long   now    = System.currentTimeMillis();

        if (!player.hasPermission("clicker.use")) return;

        // ── 2. Anti-spam + configurable cooldown ──────────────────────────────
        Long lastClick = plugin.getCooldown(uuid);
        if (lastClick != null) {
            long elapsedMs = now - lastClick;
            if (elapsedMs < MIN_CLICK_MS) return; // hard floor, always silent

            int cooldownSec = plugin.getConfig().getInt("click-cooldown", 0);
            if (cooldownSec > 0 && elapsedMs < cooldownSec * 1000L) {
                if (!plugin.getConfig().getBoolean("silent-cooldown", false)) {
                    long remaining = (cooldownSec * 1000L - elapsedMs + 999L) / 1000L;
                    player.sendMessage(plugin.getLang().get("cooldown.message",
                            "%clicker_seconds%", String.valueOf(remaining)));
                }
                return;
            }
        }
        plugin.setCooldown(uuid, now);

        // ── 3. Calculate reward (fixed or random) × multiplier ────────────────
        boolean randomEnabled = plugin.getConfig().getBoolean("random-reward.enabled", false);
        double  baseAmount;
        boolean isJackpot = false;

        if (randomEnabled) {
            if (plugin.getConfig().getBoolean("random-reward.jackpot.enabled", false)) {
                double chance = plugin.getConfig().getDouble("random-reward.jackpot.chance", 0.01);
                if (ThreadLocalRandom.current().nextDouble() < chance) {
                    baseAmount = randomInRange("random-reward.jackpot.min",
                                              "random-reward.jackpot.max", 50.0, 100.0);
                    isJackpot = true;
                } else {
                    baseAmount = randomInRange("random-reward.min", "random-reward.max", 0.5, 5.0);
                }
            } else {
                baseAmount = randomInRange("random-reward.min", "random-reward.max", 0.5, 5.0);
            }
        } else {
            baseAmount = plugin.getConfig().getDouble("reward-amount", 1.0);
        }

        double multiplier   = plugin.getEffectiveMultiplier(player);
        double finalAmount  = baseAmount * multiplier;

        // ── 4. Deposit ────────────────────────────────────────────────────────
        if (!plugin.isVaultEnabled()) {
            player.sendMessage(plugin.getLang().get("general.vault-unavailable"));
            return;
        }
        EconomyResponse response = plugin.getEconomy().depositPlayer(player, finalAmount);
        if (!response.transactionSuccess()) {
            player.sendMessage(plugin.getLang().get("reward.deposit-failed"));
            plugin.getLogger().warning("Vault deposit failed for " + player.getName()
                    + ": " + response.errorMessage);
            return;
        }

        // ── 5. Increment click count ──────────────────────────────────────────
        plugin.incrementClickCount(uuid);

        // ── 6. Send message ───────────────────────────────────────────────────
        String prefix = plugin.getLang().getPrefix();
        if (isJackpot) {
            player.sendMessage(prefix + ClickerGUI.resolvePlaceholders(
                    plugin.getLang().getRaw("reward.jackpot"), plugin, player, finalAmount));
            String jackpotSound = plugin.getConfig().getString("random-reward.jackpot.sound", "");
            if (!jackpotSound.isEmpty()) playSound(player, jackpotSound, 1f, 1f);
        } else {
            player.sendMessage(prefix + ClickerGUI.resolvePlaceholders(
                    plugin.getLang().getRaw("reward.earned"), plugin, player, finalAmount));
            playRewardSound(player);
        }

        // ── 7. Refresh GUI items live ─────────────────────────────────────────
        event.getInventory().setItem(plugin.getClickerSlot(),
                ClickerGUI.buildClickerItem(plugin, player));

        int balSlot = plugin.getConfig().getInt("balance-display.slot", 4);
        if (balSlot >= 0 && balSlot < 27 && balSlot != plugin.getClickerSlot()) {
            event.getInventory().setItem(balSlot,
                    ClickerGUI.buildBalanceDisplay(plugin, player));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private double randomInRange(String minKey, String maxKey,
                                  double defMin, double defMax) {
        double min = plugin.getConfig().getDouble(minKey, defMin);
        double max = plugin.getConfig().getDouble(maxKey, defMax);
        return min >= max ? min : min + ThreadLocalRandom.current().nextDouble() * (max - min);
    }

    private void playRewardSound(Player player) {
        if (!plugin.getConfig().getBoolean("reward-sound.enabled", true)) return;
        playSound(player,
                plugin.getConfig().getString("reward-sound.sound", "ENTITY_PLAYER_LEVELUP"),
                (float) plugin.getConfig().getDouble("reward-sound.volume", 1.0),
                (float) plugin.getConfig().getDouble("reward-sound.pitch",  1.0));
    }

    private void playSound(Player player, String soundName, float volume, float pitch) {
        try {
            player.playSound(player.getLocation(), Sound.valueOf(soundName.toUpperCase()),
                    volume, pitch);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid sound '" + soundName + "' in config.yml.");
        }
    }
}
