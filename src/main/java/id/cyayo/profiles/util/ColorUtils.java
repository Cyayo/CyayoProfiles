package id.cyayo.profiles.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import id.seria.core.gui.menu.BaseSeriaMenu;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorUtils {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer SECTION_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final LegacyComponentSerializer AMPERSAND_SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static final Pattern HEX_TAG_PATTERN = Pattern.compile("&#([a-fA-F0-9]{6})");
    private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("[§&]x[§&]([0-9a-fA-F])[§&]([0-9a-fA-F])[§&]([0-9a-fA-F])[§&]([0-9a-fA-F])[§&]([0-9a-fA-F])[§&]([0-9a-fA-F])");

    /**
     * Resolve tag semantik Seria (<sc:prefix>, <sc:...>, %sc_*%) via SeriaCore/SeriaPrefix menjadi MiniMessage.
     * Tanpa ini tag <sc:...> tidak dikenali MiniMessage dan hilang/tampil mentah.
     */
    private static String resolveSeriaTags(Player viewer, String text) {
        if (text.indexOf('<') == -1 && text.indexOf('%') == -1) return text;
        try {
            return BaseSeriaMenu.formatSemanticColorTags(viewer, text);
        } catch (Throwable t) {
            return text;
        }
    }

    public static Component parse(String text) {
        return parse(null, text);
    }

    public static Component parse(Player viewer, String text) {
        if (text == null || text.isEmpty()) return Component.empty();

        text = resolveSeriaTags(viewer, text);
        String processed = text;

        Matcher legacyHexMatcher = LEGACY_HEX_PATTERN.matcher(processed);
        StringBuffer sbHex = new StringBuffer();
        while (legacyHexMatcher.find()) {
            String hex = "#" + legacyHexMatcher.group(1) + legacyHexMatcher.group(2) + legacyHexMatcher.group(3)
                    + legacyHexMatcher.group(4) + legacyHexMatcher.group(5) + legacyHexMatcher.group(6);
            legacyHexMatcher.appendReplacement(sbHex, "<color:" + hex + ">");
        }
        legacyHexMatcher.appendTail(sbHex);
        processed = sbHex.toString();

        Matcher hexTagMatcher = HEX_TAG_PATTERN.matcher(processed);
        StringBuffer sbTag = new StringBuffer();
        while (hexTagMatcher.find()) {
            hexTagMatcher.appendReplacement(sbTag, "<color:#" + hexTagMatcher.group(1) + ">");
        }
        hexTagMatcher.appendTail(sbTag);
        processed = sbTag.toString();

        processed = processed
                .replace("&0", "<reset><black>").replace("§0", "<reset><black>")
                .replace("&1", "<reset><dark_blue>").replace("§1", "<reset><dark_blue>")
                .replace("&2", "<reset><dark_green>").replace("§2", "<reset><dark_green>")
                .replace("&3", "<reset><dark_aqua>").replace("§3", "<reset><dark_aqua>")
                .replace("&4", "<reset><dark_red>").replace("§4", "<reset><dark_red>")
                .replace("&5", "<reset><dark_purple>").replace("§5", "<reset><dark_purple>")
                .replace("&6", "<reset><gold>").replace("§6", "<reset><gold>")
                .replace("&7", "<reset><gray>").replace("§7", "<reset><gray>")
                .replace("&8", "<reset><dark_gray>").replace("§8", "<reset><dark_gray>")
                .replace("&9", "<reset><blue>").replace("§9", "<reset><blue>")
                .replace("&a", "<reset><green>").replace("§a", "<reset><green>")
                .replace("&b", "<reset><aqua>").replace("§b", "<reset><aqua>")
                .replace("&c", "<reset><red>").replace("§c", "<reset><red>")
                .replace("&d", "<reset><light_purple>").replace("§d", "<reset><light_purple>")
                .replace("&e", "<reset><yellow>").replace("§e", "<reset><yellow>")
                .replace("&f", "<reset><white>").replace("§f", "<reset><white>")
                .replace("&l", "<bold>").replace("§l", "<bold>")
                .replace("&m", "<strikethrough>").replace("§m", "<strikethrough>")
                .replace("&n", "<underlined>").replace("§n", "<underlined>")
                .replace("&o", "<italic>").replace("§o", "<italic>")
                .replace("&k", "<obfuscated>").replace("§k", "<obfuscated>")
                .replace("&r", "<reset>").replace("§r", "<reset>");

        try {
            return MINI_MESSAGE.deserialize(processed);
        } catch (Exception e) {
            try {
                return SECTION_SERIALIZER.deserialize(text);
            } catch (Exception ex) {
                return AMPERSAND_SERIALIZER.deserialize(text);
            }
        }
    }

    public static String color(String text) {
        return color(null, text);
    }

    public static String color(Player viewer, String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.contains("\n")) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                lines[i] = color(viewer, lines[i]);
            }
            return String.join("\n", lines);
        }
        try {
            return SECTION_SERIALIZER.serialize(parse(viewer, text));
        } catch (Exception e) {
            return ChatColor.translateAlternateColorCodes('&', text);
        }
    }

    public static List<String> color(List<String> list) {
        if (list == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String s : list) {
            result.add(color(s));
        }
        return result;
    }

    public static boolean isNone(String text) {
        if (text == null) return true;
        String clean = text.trim();
        if ((clean.startsWith("\"") && clean.endsWith("\"")) || (clean.startsWith("'") && clean.endsWith("'"))) {
            if (clean.length() >= 2) {
                clean = clean.substring(1, clean.length() - 1).trim();
            }
        }
        return clean.equalsIgnoreCase("none");
    }

    public static boolean isNoneOrEmpty(String text) {
        if (text == null) return true;
        if (text.trim().isEmpty()) return true;
        return isNone(text);
    }

    public static void sendMessage(CommandSender sender, String message) {
        if (sender == null || isNone(message)) return;
        String colored = color(sender instanceof Player p ? p : null, message);
        sender.sendMessage(colored.trim().isEmpty() ? " " : colored);
    }

    public static List<String> getMessageLines(ConfigurationSection section, String path) {
        if (section == null || !section.contains(path)) return Collections.emptyList();
        if (section.isList(path)) {
            List<String> raw = section.getStringList(path);
            List<String> result = new ArrayList<>();
            for (String line : raw) {
                if (line != null && !isNone(line)) {
                    result.add(line);
                }
            }
            return result;
        }
        String single = section.getString(path);
        if (single == null || isNone(single)) return Collections.emptyList();
        return List.of(single);
    }

    public static void sendActionBar(Player player, String message) {
        if (player == null || isNoneOrEmpty(message)) return;
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(color(player, message)));
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    public static void sendTitle(Player player, String titleText, String subtitleText, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        if (player == null) return;
        if (isNoneOrEmpty(titleText) && isNoneOrEmpty(subtitleText)) return;

        String title = isNoneOrEmpty(titleText) ? "" : color(player, titleText);
        String subtitle = isNoneOrEmpty(subtitleText) ? "" : color(player, subtitleText);

        player.sendTitle(title, subtitle, fadeInTicks, stayTicks, fadeOutTicks);
    }

    public static String stripColor(String text) {
        if (text == null || text.isEmpty()) return "";
        String stripped = ChatColor.stripColor(color(text));
        return stripped != null ? stripped : "";
    }

    public static String replaceBoth(String text, String key, String value) {
        if (text == null || value == null) return text;
        return text.replace("{" + key + "}", value).replace("%" + key + "%", value);
    }

    public static void playSound(Player player, String soundName) {
        if (player == null || isNoneOrEmpty(soundName)) return;
        try {
            String[] parts = soundName.trim().split(":");
            Sound sound = Sound.valueOf(parts[0].trim().toUpperCase());
            float volume = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 1.0f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 1.0f;
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception ignored) {
        }
    }
}
