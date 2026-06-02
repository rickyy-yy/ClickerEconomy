package com.clickereconomy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates &#RRGGBB hex colour codes and standard &amp;X chat codes.
 *
 * <p>Hex colours are rendered only on Minecraft 1.16+ servers; on older servers
 * the hex token is silently stripped so the text remains readable without
 * square-bracket artefacts.
 */
public final class ColorUtil {

    /** Matches &#RRGGBB (case-insensitive) anywhere in a string. */
    private static final Pattern HEX_PATTERN =
            Pattern.compile("&#([A-Fa-f0-9]{6})");

    /**
     * True when the server's BungeeCord ChatColor supports hex (1.16+).
     * Determined once at class-load time so there is no per-call try/catch overhead.
     */
    private static final boolean HEX_SUPPORTED;

    static {
        boolean ok;
        try {
            net.md_5.bungee.api.ChatColor.of("#FFFFFF");
            ok = true;
        } catch (Throwable e) {
            ok = false;
        }
        HEX_SUPPORTED = ok;
    }

    private ColorUtil() {}

    /**
     * Translates {@code &#RRGGBB} hex tokens and {@code &X} colour codes in
     * {@code input} into Bukkit chat-colour sequences.
     *
     * @param input raw string, may be {@code null}
     * @return coloured string, never {@code null}
     */
    public static String colorize(String input) {
        if (input == null) return "";

        if (HEX_SUPPORTED) {
            Matcher m = HEX_PATTERN.matcher(input);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String code = net.md_5.bungee.api.ChatColor.of("#" + m.group(1)).toString();
                m.appendReplacement(sb, Matcher.quoteReplacement(code));
            }
            m.appendTail(sb);
            input = sb.toString();
        } else {
            // Strip hex tokens — keeps text legible on pre-1.16 servers
            input = HEX_PATTERN.matcher(input).replaceAll("");
        }

        return org.bukkit.ChatColor.translateAlternateColorCodes('&', input);
    }
}
