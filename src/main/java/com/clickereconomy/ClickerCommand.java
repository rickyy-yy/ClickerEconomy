package com.clickereconomy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ClickerCommand implements CommandExecutor, TabCompleter {

    private final ClickerPlugin plugin;

    public ClickerCommand(ClickerPlugin plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    // Command dispatch
    // =========================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        LangManager lang = plugin.getLang();

        if (args.length > 0) {
            switch (args[0].toLowerCase()) {

                case "help":
                    if (!sender.hasPermission("clicker.help")) {
                        sender.sendMessage(lang.get("general.no-permission"));
                        return true;
                    }
                    sendHelp(sender);
                    return true;

                case "reload":
                    if (!sender.hasPermission("clicker.admin")) {
                        sender.sendMessage(lang.get("general.no-permission"));
                        return true;
                    }
                    plugin.reloadPluginConfig();
                    sender.sendMessage(lang.get("general.config-reloaded"));
                    return true;

                case "top":
                    return handleTop(sender, args);

                case "stats":
                    return handleStats(sender);

                case "multiplier":
                    return handleMultiplier(sender, args);

                case "clicks":
                    return handleClicks(sender, args);

                case "placeholders":
                    return handlePlaceholders(sender);

                default:
                    sender.sendMessage(lang.get("general.unknown-subcommand"));
                    return true;
            }
        }

        // ── /clicker — open GUI ───────────────────────────────────────────────
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.get("general.players-only-gui"));
            return true;
        }
        Player player = (Player) sender;
        if (!plugin.isVaultEnabled()) {
            player.sendMessage(lang.get("general.vault-unavailable"));
            return true;
        }
        ClickerGUI.open(plugin, player);
        return true;
    }

    // =========================================================================
    // /clicker top [page]
    // =========================================================================

    private boolean handleTop(CommandSender sender, String[] args) {
        LangManager lang = plugin.getLang();

        int entriesPerPage = plugin.getConfig().getInt("leaderboard.entries-per-page", 10);
        List<Map.Entry<UUID, Long>> all = plugin.getTopPlayers(Integer.MAX_VALUE);

        int total   = all.size();
        int maxPage = Math.max(1, (int) Math.ceil(total / (double) entriesPerPage));

        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(lang.get("top.invalid-page"));
                return true;
            }
        }
        if (page < 1 || page > maxPage) {
            sender.sendMessage(lang.get("top.invalid-page"));
            return true;
        }

        sender.sendMessage(lang.get("top.header",
                "%clicker_page%",     String.valueOf(page),
                "%clicker_max_page%", String.valueOf(maxPage)));

        if (total == 0) {
            sender.sendMessage(lang.get("top.empty"));
            return true;
        }

        int start = (page - 1) * entriesPerPage;
        int end   = Math.min(start + entriesPerPage, total);
        for (int i = start; i < end; i++) {
            Map.Entry<UUID, Long> entry = all.get(i);
            @SuppressWarnings("deprecation")
            String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            if (name == null) name = entry.getKey().toString().substring(0, 8) + "...";
            sender.sendMessage(lang.get("top.entry",
                    "%clicker_rank%",   String.valueOf(i + 1),
                    "%clicker_player%", name,
                    "%clicker_clicks%", String.valueOf(entry.getValue())));
        }

        if (maxPage > 1) {
            sender.sendMessage(lang.get("top.footer",
                    "%clicker_page%",     String.valueOf(page),
                    "%clicker_max_page%", String.valueOf(maxPage)));
        }
        return true;
    }

    // =========================================================================
    // /clicker stats
    // =========================================================================

    private boolean handleStats(CommandSender sender) {
        LangManager lang = plugin.getLang();
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.get("general.players-only"));
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("clicker.stats")) {
            player.sendMessage(lang.get("general.no-permission"));
            return true;
        }

        long   clicks    = plugin.getClickCount(player.getUniqueId());
        int    rank      = plugin.getClickRank(player.getUniqueId());
        double personal  = plugin.getPersonalMultiplier(player);
        double global    = plugin.getGlobalMultiplier();
        double effective = plugin.getEffectiveMultiplier(player);
        String balance   = plugin.isVaultEnabled()
                ? ClickerGUI.formatAmount(plugin.getEconomy().getBalance(player))
                : "N/A";

        sender.sendMessage(lang.get("stats.header"));
        sender.sendMessage(lang.get("stats.clicks",
                "%clicker_clicks%", String.valueOf(clicks),
                "%clicker_rank%",   String.valueOf(rank)));
        sender.sendMessage(lang.get("stats.balance",
                "%clicker_balance%", balance));
        sender.sendMessage(lang.get("stats.multiplier",
                "%clicker_effective%", ClickerGUI.formatAmount(effective),
                "%clicker_personal%",  ClickerGUI.formatAmount(personal),
                "%clicker_global%",    ClickerGUI.formatAmount(global)));
        return true;
    }

    // =========================================================================
    // /clicker placeholders
    // =========================================================================

    private boolean handlePlaceholders(CommandSender sender) {
        LangManager lang = plugin.getLang();
        if (!sender.hasPermission("clicker.placeholders")) {
            sender.sendMessage(lang.get("general.no-permission"));
            return true;
        }
        sender.sendMessage(lang.get("placeholders.header"));
        sender.sendMessage(lang.get("placeholders.note"));
        for (String entry : lang.getList("placeholders.entries")) {
            sender.sendMessage(entry);
        }
        return true;
    }

    // =========================================================================
    // /clicker clicks give|set|reset <player> [amount]
    // =========================================================================

    private boolean handleClicks(CommandSender sender, String[] args) {
        LangManager lang = plugin.getLang();
        if (!sender.hasPermission("clicker.admin")) {
            sender.sendMessage(lang.get("general.no-permission"));
            return true;
        }

        // Must have at least: clicks <sub> <player>
        boolean validSub = args.length >= 2 &&
                (args[1].equalsIgnoreCase("give") ||
                 args[1].equalsIgnoreCase("set")  ||
                 args[1].equalsIgnoreCase("reset"));
        if (!validSub || args.length < 3) {
            sender.sendMessage(lang.get("clicks-cmd.usage"));
            return true;
        }

        String targetName = args[2];
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            sender.sendMessage(lang.get("clicks-cmd.player-not-found",
                    "%clicker_player%", targetName));
            return true;
        }

        UUID   uuid        = target.getUniqueId();
        String displayName = target.getName() != null ? target.getName() : targetName;
        String sub         = args[1].toLowerCase();

        if (sub.equals("reset")) {
            plugin.setClickCount(uuid, 0);
            plugin.saveData();
            sender.sendMessage(lang.get("clicks-cmd.reset-success",
                    "%clicker_player%", displayName));
            return true;
        }

        // give / set both require an <amount> argument
        if (args.length < 4) {
            sender.sendMessage(lang.get("clicks-cmd.usage"));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(lang.get("clicks-cmd.invalid-number",
                    "%clicker_input%", args[3]));
            return true;
        }
        if (amount <= 0) {
            sender.sendMessage(lang.get("clicks-cmd.invalid-amount"));
            return true;
        }

        if (sub.equals("give")) {
            long newTotal = plugin.getClickCount(uuid) + amount;
            plugin.setClickCount(uuid, newTotal);
            plugin.saveData();
            sender.sendMessage(lang.get("clicks-cmd.give-success",
                    "%clicker_player%", displayName,
                    "%clicker_amount%",  String.valueOf(amount),
                    "%clicker_clicks%",  String.valueOf(newTotal)));
        } else { // set
            plugin.setClickCount(uuid, amount);
            plugin.saveData();
            sender.sendMessage(lang.get("clicks-cmd.set-success",
                    "%clicker_player%", displayName,
                    "%clicker_clicks%",  String.valueOf(amount)));
        }
        return true;
    }

    // =========================================================================
    // /clicker multiplier [get | set <value> [duration] | reset]
    // =========================================================================

    private boolean handleMultiplier(CommandSender sender, String[] args) {
        LangManager lang = plugin.getLang();

        // ── get ───────────────────────────────────────────────────────────────
        if (args.length == 1 || args[1].equalsIgnoreCase("get")) {
            double global    = plugin.getGlobalMultiplier();
            long   remaining = plugin.getGlobalMultiplierRemaining();
            String timeTag   = remaining < 0
                    ? lang.get("multiplier-cmd.time-permanent")
                    : lang.get("multiplier-cmd.time-expires",
                               "%clicker_time%", ClickerPlugin.formatDuration(remaining));

            sender.sendMessage(lang.get("multiplier-cmd.get-global",
                    "%clicker_value%",    ClickerGUI.formatAmount(global),
                    "%clicker_time_tag%", timeTag));

            if (sender instanceof Player) {
                Player p = (Player) sender;
                sender.sendMessage(lang.get("multiplier-cmd.get-personal",
                        "%clicker_value%", ClickerGUI.formatAmount(plugin.getPersonalMultiplier(p))));
                sender.sendMessage(lang.get("multiplier-cmd.get-effective",
                        "%clicker_value%", ClickerGUI.formatAmount(plugin.getEffectiveMultiplier(p))));
            }
            return true;
        }

        // ── set <value> [duration] ────────────────────────────────────────────
        if (args[1].equalsIgnoreCase("set")) {
            if (!sender.hasPermission("clicker.admin")) {
                sender.sendMessage(lang.get("general.no-permission"));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(lang.get("multiplier-cmd.usage-set"));
                sender.sendMessage(lang.get("multiplier-cmd.set-hint"));
                return true;
            }

            double value;
            try {
                value = Double.parseDouble(args[2]);
                if (value <= 0) {
                    sender.sendMessage(lang.get("multiplier-cmd.value-too-low"));
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(lang.get("multiplier-cmd.invalid-number",
                        "%clicker_input%", args[2]));
                return true;
            }

            long duration = -1;
            if (args.length >= 4) {
                duration = parseDuration(args[3]);
                if (duration < 0) {
                    sender.sendMessage(lang.get("multiplier-cmd.invalid-duration",
                            "%clicker_input%", args[3]));
                    return true;
                }
            }

            plugin.setGlobalMultiplier(value, duration);

            String durationTag = duration < 0
                    ? lang.get("multiplier-cmd.duration-permanent")
                    : lang.get("multiplier-cmd.duration-timed",
                               "%clicker_time%", ClickerPlugin.formatDuration(duration));
            sender.sendMessage(lang.get("multiplier-cmd.set-success",
                    "%clicker_value%",        ClickerGUI.formatAmount(value),
                    "%clicker_duration_tag%", durationTag));
            return true;
        }

        // ── reset ─────────────────────────────────────────────────────────────
        if (args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("clear")) {
            if (!sender.hasPermission("clicker.admin")) {
                sender.sendMessage(lang.get("general.no-permission"));
                return true;
            }
            double base = plugin.getConfig().getDouble("global-multiplier", 1.0);
            plugin.setGlobalMultiplier(base, -1);
            sender.sendMessage(lang.get("multiplier-cmd.reset-success",
                    "%clicker_value%", ClickerGUI.formatAmount(base)));
            return true;
        }

        sender.sendMessage(lang.get("multiplier-cmd.usage"));
        return true;
    }

    // =========================================================================
    // /clicker help
    // =========================================================================

    private void sendHelp(CommandSender sender) {
        LangManager lang = plugin.getLang();
        boolean isAdmin  = sender.hasPermission("clicker.admin");

        sender.sendMessage(lang.get("help.header"));
        sender.sendMessage(lang.get("help.entry-open"));
        sender.sendMessage(lang.get("help.entry-top"));
        sender.sendMessage(lang.get("help.entry-help"));
        sender.sendMessage(lang.get("help.entry-stats"));
        sender.sendMessage(lang.get("help.entry-multiplier-get"));
        if (sender.hasPermission("clicker.placeholders")) {
            sender.sendMessage(lang.get("help.entry-placeholders"));
        }
        if (isAdmin) {
            sender.sendMessage(lang.get("help.entry-multiplier-set"));
            sender.sendMessage(lang.get("help.entry-multiplier-reset"));
            sender.sendMessage(lang.get("help.entry-clicks-give"));
            sender.sendMessage(lang.get("help.entry-clicks-set"));
            sender.sendMessage(lang.get("help.entry-clicks-reset"));
            sender.sendMessage(lang.get("help.entry-reload"));
        }
    }

    // =========================================================================
    // Tab completion
    // =========================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(Arrays.asList("help", "top", "stats", "multiplier"));
            if (sender.hasPermission("clicker.placeholders")) opts.add("placeholders");
            if (sender.hasPermission("clicker.admin")) {
                opts.add("clicks");
                opts.add("reload");
            }
            return filter(opts, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            return Collections.emptyList(); // page number — no tab hints needed
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("multiplier")) {
            List<String> sub = new ArrayList<>(Collections.singletonList("get"));
            if (sender.hasPermission("clicker.admin")) {
                sub.add("set");
                sub.add("reset");
            }
            return filter(sub, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("multiplier")
                && args[1].equalsIgnoreCase("set")) {
            return new ArrayList<>(Arrays.asList("1.0", "1.5", "2.0", "2.5", "3.0"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("multiplier")
                && args[1].equalsIgnoreCase("set")) {
            return new ArrayList<>(Arrays.asList("30s", "5m", "1h", "6h", "24h", "7d"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clicks")
                && sender.hasPermission("clicker.admin")) {
            return filter(new ArrayList<>(Arrays.asList("give", "set", "reset")), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("clicks")
                && sender.hasPermission("clicker.admin")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("clicks")
                && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("set"))
                && sender.hasPermission("clicker.admin")) {
            return new ArrayList<>(Arrays.asList("10", "100", "1000", "10000"));
        }
        return Collections.emptyList();
    }

    // =========================================================================
    // Duration helpers
    // =========================================================================

    private static long parseDuration(String input) {
        if (input == null || input.isEmpty()) return -1;
        input = input.toLowerCase().trim();
        try { long v = Long.parseLong(input); return v > 0 ? v : -1; }
        catch (NumberFormatException ignored) {}
        char unit  = input.charAt(input.length() - 1);
        String num = input.substring(0, input.length() - 1);
        try {
            long amount = Long.parseLong(num);
            if (amount <= 0) return -1;
            switch (unit) {
                case 's': return amount;
                case 'm': return amount > Long.MAX_VALUE / 60    ? -1 : amount * 60L;
                case 'h': return amount > Long.MAX_VALUE / 3600  ? -1 : amount * 3600L;
                case 'd': return amount > Long.MAX_VALUE / 86400 ? -1 : amount * 86400L;
            }
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    private static List<String> filter(List<String> options, String partial) {
        List<String> result = new ArrayList<>();
        String lc = partial.toLowerCase();
        for (String o : options) if (o.startsWith(lc)) result.add(o);
        return result;
    }
}
