package id.cyayo.profiles.gui;

import id.cyayo.profiles.CyayoProfiles;
import id.cyayo.profiles.manager.GamemodeManager;
import id.cyayo.profiles.model.ClassData;
import id.cyayo.profiles.model.GamemodeData;
import id.cyayo.profiles.model.PlayerProfileHolder;
import id.cyayo.profiles.model.Profile;
import id.cyayo.profiles.util.ColorUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class ProfileGUIManager {

    private final CyayoProfiles plugin;
    private FileConfiguration profilesGuiConfig;
    private FileConfiguration classSelectGuiConfig;
    private FileConfiguration gamemodeSelectGuiConfig;
    private FileConfiguration confirmDeleteGuiConfig;

    private static final DecimalFormat XP_FORMAT = new DecimalFormat("#,###.##");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm");

    public ProfileGUIManager(CyayoProfiles plugin) {
        this.plugin = plugin;
        loadConfigs();
    }

    public void loadConfigs() {
        profilesGuiConfig = loadGuiFile("menus/profiles.yml");
        classSelectGuiConfig = loadGuiFile("menus/class_select.yml");
        gamemodeSelectGuiConfig = loadGuiFile("menus/gamemode_select.yml");
        confirmDeleteGuiConfig = loadGuiFile("menus/confirm_delete.yml");
    }

    private FileConfiguration loadGuiFile(String path) {
        File file = new File(plugin.getDataFolder(), path);
        // Migrasi struktur lama: gui/<nama>.yml -> menus/<nama>.yml
        File legacy = new File(plugin.getDataFolder(), path.replaceFirst("^menus/", "gui/"));
        if (!file.exists() && legacy.exists()) {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            if (legacy.renameTo(file)) {
                plugin.getLogger().info("Migrated " + legacy.getName() + " from gui/ to menus/");
            }
        }
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    public FileConfiguration getProfilesGuiConfig() {
        return profilesGuiConfig;
    }

    public FileConfiguration getClassSelectGuiConfig() {
        return classSelectGuiConfig;
    }

    public FileConfiguration getGamemodeSelectGuiConfig() {
        return gamemodeSelectGuiConfig;
    }

    public FileConfiguration getConfirmDeleteGuiConfig() {
        return confirmDeleteGuiConfig;
    }

    public void openProfilesMenu(Player player) {
        PlayerProfileHolder holder = plugin.getProfileManager().getHolder(player);
        if (holder == null) return;

        String title = ColorUtils.color(replaceAllPlaceholders(
                profilesGuiConfig.getString("title", "Character Profiles"),
                player, holder.getActiveProfile(), holder.getActiveSlot()));
        int size = profilesGuiConfig.getInt("size", 45);

        ProfileHolder invHolder = new ProfileHolder("PROFILES", 0);
        Inventory inv = Bukkit.createInventory(invHolder, size, title);
        invHolder.setInventory(inv);

        // 1. Fillers with array slots [0, 1, 2, ...]
        loadFillers(inv, profilesGuiConfig, size);

        // 2. Custom Decorative Items with array slots or single slot
        loadCustomItems(inv, profilesGuiConfig, player, holder.getActiveProfile(), holder.getActiveSlot(), size);

        // 3. Render Profile Slots
        int maxSlots = plugin.getProfileManager().getMaxSlots(player);

        ConfigurationSection slotLocs = profilesGuiConfig.getConfigurationSection("profile_slot_locations");
        if (slotLocs != null) {
            for (String key : slotLocs.getKeys(false)) {
                try {
                    int profileSlot = Integer.parseInt(key);
                    int guiSlot = slotLocs.getInt(key);
                    if (guiSlot < 0 || guiSlot >= size) continue;

                    ItemStack item;
                    if (profileSlot > maxSlots) {
                        item = buildSimpleItem(profilesGuiConfig.getConfigurationSection("items.locked_slot"), player, null, profileSlot);
                    } else if (holder.hasProfile(profileSlot)) {
                        Profile profile = holder.getProfile(profileSlot);
                        boolean isActive = (holder.getActiveSlot() == profileSlot);
                        if (isActive && !profile.isDead()) {
                            plugin.getProfileManager().savePlayerStateToProfile(player, profile);
                        }
                        String stateKey;
                        if (profile.isDead()) {
                            stateKey = "items.fallen_profile";
                        } else {
                            stateKey = isActive ? "items.active_profile" : "items.saved_profile";
                        }
                        item = buildProfileItem(profilesGuiConfig.getConfigurationSection(stateKey), player, profile);
                    } else {
                        item = buildSimpleItem(profilesGuiConfig.getConfigurationSection("items.empty_slot"), player, null, profileSlot);
                    }

                    inv.setItem(guiSlot, item);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // 4. Close button
        ConfigurationSection closeSec = profilesGuiConfig.getConfigurationSection("items.close_button");
        if (closeSec != null) {
            List<Integer> closeSlots = getSlotsList(closeSec);
            if (closeSlots.isEmpty() && closeSec.contains("slot")) {
                closeSlots.add(closeSec.getInt("slot", 40));
            }
            for (int closeSlot : closeSlots) {
                if (closeSlot >= 0 && closeSlot < size) {
                    inv.setItem(closeSlot, buildSimpleItem(closeSec, player, holder.getActiveProfile(), 0));
                }
            }
        }

        // 5. Fallback fill empty if enabled
        fillFallback(inv, profilesGuiConfig);

        player.openInventory(inv);
    }

    public void openClassSelectMenu(Player player, int targetSlot) {
        String title = ColorUtils.color(replaceAllPlaceholders(
                classSelectGuiConfig.getString("title", "Select Class"),
                player, null, targetSlot));
        int size = classSelectGuiConfig.getInt("size", 36);

        ProfileHolder invHolder = new ProfileHolder("CLASS_SELECT", targetSlot);
        Inventory inv = Bukkit.createInventory(invHolder, size, title);
        invHolder.setInventory(inv);

        loadFillers(inv, classSelectGuiConfig, size);
        loadCustomItems(inv, classSelectGuiConfig, player, null, targetSlot, size);

        // Render class items dari section 'classes' di gui/class_select.yml (atau fallback ke class_slots)
        ConfigurationSection classSection = classSelectGuiConfig.getConfigurationSection("classes");
        if (classSection != null) {
            for (String classId : classSection.getKeys(false)) {
                ConfigurationSection itemSec = classSection.getConfigurationSection(classId);
                if (itemSec == null) continue;

                ClassData classData = plugin.getClassManager().getClass(classId);
                if (classData == null) continue;
                if (!classData.hasPermission(player) && classData.isHideWithoutPermission()) continue;

                ItemStack classItem = buildClassSelectItem(classData, itemSec, player, targetSlot);
                for (int guiSlot : getSlotsList(itemSec)) {
                    if (guiSlot >= 0 && guiSlot < size) {
                        inv.setItem(guiSlot, classItem);
                    }
                }
            }
        }

        ConfigurationSection classSlots = classSelectGuiConfig.getConfigurationSection("class_slots");
        if (classSlots != null) {
            for (String classId : classSlots.getKeys(false)) {
                int guiSlot = classSlots.getInt(classId);
                if (guiSlot < 0 || guiSlot >= size) continue;

                ClassData classData = plugin.getClassManager().getClass(classId);
                if (classData == null) continue;
                if (!classData.hasPermission(player) && classData.isHideWithoutPermission()) continue;

                inv.setItem(guiSlot, buildClassSelectItem(classData, null, player, targetSlot));
            }
        }

        // Back button
        ConfigurationSection backSec = classSelectGuiConfig.getConfigurationSection("back_button");
        if (backSec != null) {
            List<Integer> backSlots = getSlotsList(backSec);
            if (backSlots.isEmpty() && backSec.contains("slot")) {
                backSlots.add(backSec.getInt("slot", 31));
            }
            for (int backSlot : backSlots) {
                if (backSlot >= 0 && backSlot < size) {
                    inv.setItem(backSlot, buildSimpleItem(backSec, player, null, targetSlot));
                }
            }
        }

        fillFallback(inv, classSelectGuiConfig);
        player.openInventory(inv);
    }

    public void openGamemodeSelectMenu(Player player, GamemodeManager.CreationSession session) {
        if (player == null || session == null) return;
        GamemodeManager gm = plugin.getGamemodeManager();
        if (gm == null) return;

        String titleRaw = replaceSessionPlaceholders(
                gamemodeSelectGuiConfig.getString("title", "Select Challenge Modes ({selected_count}/{max_modes})"),
                player, session);
        String title = ColorUtils.color(titleRaw);
        int size = gamemodeSelectGuiConfig.getInt("size", 45);

        ProfileHolder invHolder = new ProfileHolder("GAMEMODE_SELECT", session.getTargetSlot());
        Inventory inv = Bukkit.createInventory(invHolder, size, title);
        invHolder.setInventory(inv);

        loadFillers(inv, gamemodeSelectGuiConfig, size);

        // Custom decorative / summary items using live session placeholders
        ConfigurationSection customSec = gamemodeSelectGuiConfig.getConfigurationSection("custom_items");
        if (customSec != null) {
            for (String key : customSec.getKeys(false)) {
                ConfigurationSection sec = customSec.getConfigurationSection(key);
                if (sec == null) continue;
                ItemStack item = buildSessionItem(sec, player, session);
                for (int slot : getSlotsList(sec)) {
                    if (slot >= 0 && slot < size) {
                        inv.setItem(slot, item);
                    }
                }
            }
        }

        // Toggleable Challenge Modes
        ConfigurationSection modesSec = gamemodeSelectGuiConfig.getConfigurationSection("modes");
        if (modesSec != null) {
            for (String modeId : modesSec.getKeys(false)) {
                GamemodeData modeData = gm.getMode(modeId);
                if (modeData == null || !modeData.isEnabled()) continue;

                ConfigurationSection modeGuiSec = modesSec.getConfigurationSection(modeId);
                if (modeGuiSec == null) continue;

                boolean selected = session.getSelectedModes().contains(modeData.getId());
                ConfigurationSection stateSec = modeGuiSec.getConfigurationSection(selected ? "enabled" : "disabled");
                if (stateSec == null) continue;

                ItemStack modeItem = buildSessionItem(stateSec, player, session);
                for (int slot : getSlotsList(modeGuiSec)) {
                    if (slot >= 0 && slot < size) {
                        inv.setItem(slot, modeItem);
                    }
                }
            }
        }

        // Hardcore lives controls (only shown when Hardcore is active & configurable)
        if (session.getSelectedModes().contains("hardcore") && gm.isHardcorePlayerSelectableLives()) {
            ConfigurationSection hcSec = gamemodeSelectGuiConfig.getConfigurationSection("hardcore_lives_controls");
            if (hcSec != null) {
                for (String key : List.of("decrease_lives", "lives_display", "increase_lives")) {
                    ConfigurationSection btnSec = hcSec.getConfigurationSection(key);
                    if (btnSec == null) continue;
                    ItemStack btnItem = buildSessionItem(btnSec, player, session);
                    for (int slot : getSlotsList(btnSec)) {
                        if (slot >= 0 && slot < size) {
                            inv.setItem(slot, btnItem);
                        }
                    }
                }
            }
        }

        // Back button
        ConfigurationSection backSec = gamemodeSelectGuiConfig.getConfigurationSection("back_button");
        if (backSec != null) {
            for (int slot : getSlotsListOrSingle(backSec, 36)) {
                if (slot >= 0 && slot < size) {
                    inv.setItem(slot, buildSessionItem(backSec, player, session));
                }
            }
        }

        // Confirm creation button
        ConfigurationSection confirmSec = gamemodeSelectGuiConfig.getConfigurationSection("confirm_button");
        if (confirmSec != null) {
            for (int slot : getSlotsListOrSingle(confirmSec, 40)) {
                if (slot >= 0 && slot < size) {
                    inv.setItem(slot, buildSessionItem(confirmSec, player, session));
                }
            }
        }

        fillFallback(inv, gamemodeSelectGuiConfig);
        player.openInventory(inv);
    }

    private ItemStack buildSessionItem(ConfigurationSection section, Player player, GamemodeManager.CreationSession session) {
        if (section == null) return new ItemStack(Material.STONE);
        String matStr = section.getString("material", "STONE");
        Material mat = Material.matchMaterial(matStr);
        if (mat == null) mat = Material.STONE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skullMeta && mat == Material.PLAYER_HEAD && player != null) {
            skullMeta.setOwningPlayer(player);
        }
        if (meta != null) {
            int cmd = section.getInt("custom_model_data", 0);
            if (cmd > 0) meta.setCustomModelData(cmd);

            if (section.getBoolean("glow", false)) {
                meta.setEnchantmentGlintOverride(true);
            }

            String name = replaceSessionPlaceholders(section.getString("name", " "), player, session);
            meta.setDisplayName(ColorUtils.color(name));

            List<String> lore = new ArrayList<>();
            for (String line : section.getStringList("lore")) {
                String parsed = replaceSessionPlaceholders(line, player, session);
                if (parsed.contains("\n")) {
                    for (String sub : parsed.split("\n")) {
                        lore.add(ColorUtils.color(sub));
                    }
                } else {
                    lore.add(ColorUtils.color(parsed));
                }
            }
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String replaceSessionPlaceholders(String text, Player player, GamemodeManager.CreationSession session) {
        if (text == null) return "";
        GamemodeManager gm = plugin.getGamemodeManager();
        ClassData classData = plugin.getClassManager().getClass(session.getClassId());
        String classDisplay = classData != null ? classData.getDisplayName() : session.getClassId();
        String classPlain = classData != null ? classData.getPlainDisplayName() : session.getClassId();

        String res = text;
        res = replaceBoth(res, "player", player != null ? player.getName() : "Unknown");
        res = replaceBoth(res, "slot", String.valueOf(session.getTargetSlot()));
        res = replaceBoth(res, "class", session.getClassId());
        res = replaceBoth(res, "class_display", classDisplay);
        res = replaceBoth(res, "class_plain", classPlain);
        res = replaceBoth(res, "selected_count", String.valueOf(session.getSelectedModes().size()));
        res = replaceBoth(res, "max_modes", String.valueOf(gm.getMaxSelectableModes()));
        res = replaceBoth(res, "gamemode_names", gm.getNamesRaw(session.getSelectedModes()));
        res = replaceBoth(res, "gamemode_badges", gm.getBadgesRaw(session.getSelectedModes()));
        res = replaceBoth(res, "hardcore_lives", String.valueOf(session.getHardcoreLives()));
        res = replaceBoth(res, "min_lives", String.valueOf(gm.getHardcoreMinLives()));
        res = replaceBoth(res, "max_lives", String.valueOf(gm.getHardcoreMaxLives()));

        String totalClassXp = XP_FORMAT.format(gm.getClassXpBonusFraction(session.getSelectedModes()) * 100.0);
        String totalGatherXp = XP_FORMAT.format(gm.getGatheringXpBonusFraction(session.getSelectedModes()) * 100.0);
        String totalGatherLuck = XP_FORMAT.format(gm.getGatheringLuckBonus(session.getSelectedModes()));

        res = replaceBoth(res, "total_class_xp", totalClassXp);
        res = replaceBoth(res, "total_gathering_xp", totalGatherXp);
        res = replaceBoth(res, "total_gathering_luck", totalGatherLuck);
        return res;
    }

    private ItemStack buildClassSelectItem(ClassData classData, ConfigurationSection guiSec, Player player, int targetSlot) {
        List<ConfigurationSection> overrides = new ArrayList<>();
        Material mat;
        int cmd;
        String rawName;
        List<String> rawLore;

        if (!classData.hasPermission(player)) {
            mat = Material.BARRIER;
            cmd = 0;
            rawName = "<red><bold>🔒 {class_display} <dark_gray>[LOCKED]</bold>";
            rawLore = Collections.emptyList();

            ConfigurationSection defaultLocked = classSelectGuiConfig.getConfigurationSection("locked_class_default");
            if (defaultLocked != null) overrides.add(defaultLocked);

            ConfigurationSection perClassLocked = guiSec != null ? guiSec.getConfigurationSection("locked") : null;
            if (perClassLocked != null) overrides.add(perClassLocked);
        } else {
            mat = classData.getIconMaterial();
            cmd = classData.getCustomModelData();
            rawName = classData.getIconName();
            rawLore = classData.getIconLore();
            if (guiSec != null) overrides.add(guiSec);
        }

        for (ConfigurationSection sec : overrides) {
            if (sec.contains("material")) {
                Material m = Material.matchMaterial(sec.getString("material", ""));
                if (m != null) mat = m;
            }
            if (sec.contains("custom_model_data")) {
                cmd = sec.getInt("custom_model_data", cmd);
            }
            if (sec.contains("name")) {
                rawName = sec.getString("name");
            }
            if (sec.isList("lore")) {
                rawLore = sec.getStringList("lore");
            }
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (cmd > 0) meta.setCustomModelData(cmd);

            String name = replaceClassPlaceholders(rawName, classData, player, targetSlot);
            meta.setDisplayName(ColorUtils.color(name));

            List<String> lore = new ArrayList<>();
            if (rawLore != null) {
                boolean hasHint = classData.getLockedHint() != null && !classData.getLockedHint().isEmpty();
                for (String l : rawLore) {
                    if (!hasHint && (l.trim().equals("{locked_hint}") || l.trim().equals("%locked_hint%"))) {
                        continue;
                    }
                    lore.add(ColorUtils.color(replaceClassPlaceholders(l, classData, player, targetSlot)));
                }
            }
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String replaceClassPlaceholders(String text, ClassData classData, Player player, int targetSlot) {
        if (text == null) return "";
        String res = replaceAllPlaceholders(text, player, null, targetSlot);
        res = replaceBoth(res, "class", classData.getId());
        res = replaceBoth(res, "class_display", classData.getDisplayName());
        res = replaceBoth(res, "class_plain", classData.getPlainDisplayName());
        res = replaceBoth(res, "max_health", String.format(Locale.US, "%.1f", classData.getBaseHealth()));
        res = replaceBoth(res, "walk_speed", String.format(Locale.US, "%.2f", classData.getWalkSpeed()));
        res = replaceBoth(res, "allowed_gear", String.join(", ", classData.getAllowedGear()));
        res = replaceBoth(res, "permission", classData.getPermission());
        res = replaceBoth(res, "locked_hint", classData.getLockedHint());
        return res;
    }

    public void openConfirmDeleteMenu(Player player, int targetSlot) {
        PlayerProfileHolder holder = plugin.getProfileManager().getHolder(player);
        if (holder == null) return;
        Profile profile = holder.getProfile(targetSlot);
        if (profile == null) return;

        String title = ColorUtils.color(replaceAllPlaceholders(
                confirmDeleteGuiConfig.getString("title", "Delete Profile?"),
                player, profile, targetSlot));
        int size = confirmDeleteGuiConfig.getInt("size", 27);

        ProfileHolder invHolder = new ProfileHolder("CONFIRM_DELETE", targetSlot);
        Inventory inv = Bukkit.createInventory(invHolder, size, title);
        invHolder.setInventory(inv);

        loadFillers(inv, confirmDeleteGuiConfig, size);
        loadCustomItems(inv, confirmDeleteGuiConfig, player, profile, targetSlot, size);

        // Confirm button
        ConfigurationSection confirmSec = confirmDeleteGuiConfig.getConfigurationSection("confirm_button");
        if (confirmSec != null) {
            for (int slot : getSlotsListOrSingle(confirmSec, 11)) {
                if (slot >= 0 && slot < size) {
                    inv.setItem(slot, buildProfileItem(confirmSec, player, profile));
                }
            }
        }

        // Info item
        ConfigurationSection infoSec = confirmDeleteGuiConfig.getConfigurationSection("info_item");
        if (infoSec != null) {
            for (int slot : getSlotsListOrSingle(infoSec, 13)) {
                if (slot >= 0 && slot < size) {
                    inv.setItem(slot, buildProfileItem(infoSec, player, profile));
                }
            }
        }

        // Cancel button
        ConfigurationSection cancelSec = confirmDeleteGuiConfig.getConfigurationSection("cancel_button");
        if (cancelSec != null) {
            for (int slot : getSlotsListOrSingle(cancelSec, 15)) {
                if (slot >= 0 && slot < size) {
                    inv.setItem(slot, buildProfileItem(cancelSec, player, profile));
                }
            }
        }

        fillFallback(inv, confirmDeleteGuiConfig);
        player.openInventory(inv);
    }

    private void loadFillers(Inventory inv, FileConfiguration guiConfig, int size) {
        ConfigurationSection fillersSec = guiConfig.getConfigurationSection("filler_items");
        if (fillersSec == null) return;

        for (String key : fillersSec.getKeys(false)) {
            ConfigurationSection sec = fillersSec.getConfigurationSection(key);
            if (sec == null) continue;

            String matStr = sec.getString("material", "BLACK_STAINED_GLASS_PANE");
            Material mat = Material.matchMaterial(matStr);
            if (mat == null) mat = Material.BLACK_STAINED_GLASS_PANE;

            int cmd = sec.getInt("custom_model_data", 0);
            String name = sec.getString("name", " ");
            List<String> rawLore = sec.getStringList("lore");

            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ColorUtils.color(name));
                if (cmd > 0) meta.setCustomModelData(cmd);
                if (rawLore != null && !rawLore.isEmpty()) {
                    List<String> lore = new ArrayList<>();
                    for (String l : rawLore) lore.add(ColorUtils.color(l));
                    meta.setLore(lore);
                }
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);
            }

            for (int slot : getSlotsList(sec)) {
                if (slot >= 0 && slot < size) {
                    inv.setItem(slot, item);
                }
            }
        }
    }

    private void loadCustomItems(Inventory inv, FileConfiguration guiConfig, Player player, Profile profile, int targetSlot, int size) {
        ConfigurationSection customSec = guiConfig.getConfigurationSection("custom_items");
        if (customSec == null) return;

        for (String key : customSec.getKeys(false)) {
            ConfigurationSection sec = customSec.getConfigurationSection(key);
            if (sec == null) continue;

            ItemStack item = (profile != null) ? buildProfileItem(sec, player, profile) : buildSimpleItem(sec, player, null, targetSlot);
            for (int slot : getSlotsList(sec)) {
                if (slot >= 0 && slot < size) {
                    inv.setItem(slot, item);
                }
            }
        }
    }

    private void fillFallback(Inventory inv, FileConfiguration guiConfig) {
        if (!guiConfig.getBoolean("fill_empty.enabled", false)) return;
        String matStr = guiConfig.getString("fill_empty.material", "BLACK_STAINED_GLASS_PANE");
        Material mat = Material.matchMaterial(matStr);
        if (mat == null) mat = Material.BLACK_STAINED_GLASS_PANE;

        ItemStack filler = new ItemStack(mat);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtils.color(guiConfig.getString("fill_empty.name", " ")));
            filler.setItemMeta(meta);
        }

        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) {
                inv.setItem(i, filler);
            }
        }
    }

    public List<Integer> getSlotsList(ConfigurationSection section) {
        List<Integer> slots = new ArrayList<>();
        if (section == null) return slots;

        if (section.isList("slots")) {
            for (int s : section.getIntegerList("slots")) {
                slots.add(s);
            }
        } else if (section.contains("slot")) {
            slots.add(section.getInt("slot"));
        }
        return slots;
    }

    private List<Integer> getSlotsListOrSingle(ConfigurationSection section, int defaultSlot) {
        List<Integer> list = getSlotsList(section);
        if (list.isEmpty()) {
            list.add(section.getInt("slot", defaultSlot));
        }
        return list;
    }

    private ItemStack buildSimpleItem(ConfigurationSection section, Player player, Profile profile, int slot) {
        if (section == null) return new ItemStack(Material.STONE);
        String matStr = section.getString("material", "STONE");
        Material mat = Material.matchMaterial(matStr);
        if (mat == null) mat = Material.STONE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skullMeta && mat == Material.PLAYER_HEAD && player != null) {
            skullMeta.setOwningPlayer(player);
        }
        if (meta != null) {
            int cmd = section.getInt("custom_model_data", 0);
            if (cmd > 0) meta.setCustomModelData(cmd);

            String name = replaceAllPlaceholders(section.getString("name", " "), player, profile, slot);
            meta.setDisplayName(ColorUtils.color(name));

            List<String> lore = new ArrayList<>();
            for (String line : section.getStringList("lore")) {
                String parsed = replaceAllPlaceholders(line, player, profile, slot);
                if (parsed.contains("\n")) {
                    for (String sub : parsed.split("\n")) {
                        lore.add(ColorUtils.color(sub));
                    }
                } else {
                    lore.add(ColorUtils.color(parsed));
                }
            }
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildProfileItem(ConfigurationSection section, Player player, Profile profile) {
        if (section == null || profile == null) return new ItemStack(Material.STONE);

        ClassData classData = plugin.getClassManager().getClass(profile.getClassId());
        String matStr = section.getString("material", "PLAYER_HEAD");
        Material mat;
        int cmd = section.getInt("custom_model_data", 0);

        if ("CLASS_ICON".equalsIgnoreCase(matStr) && classData != null) {
            mat = classData.getIconMaterial();
            cmd = classData.getCustomModelData();
        } else {
            mat = Material.matchMaterial(matStr);
            if (mat == null) {
                mat = Material.matchMaterial(section.getString("fallback_material", "PLAYER_HEAD"));
                if (mat == null) mat = Material.PLAYER_HEAD;
            }
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        if (meta instanceof SkullMeta skullMeta && mat == Material.PLAYER_HEAD && player != null) {
            skullMeta.setOwningPlayer(player);
        }

        if (meta != null) {
            if (cmd > 0) meta.setCustomModelData(cmd);

            String name = replaceAllPlaceholders(section.getString("name", " "), player, profile, profile.getSlot());
            meta.setDisplayName(ColorUtils.color(name));

            List<String> lore = new ArrayList<>();
            for (String line : section.getStringList("lore")) {
                String parsed = replaceAllPlaceholders(line, player, profile, profile.getSlot());
                if (parsed.contains("\n")) {
                    for (String sub : parsed.split("\n")) {
                        lore.add(ColorUtils.color(sub));
                    }
                } else {
                    lore.add(ColorUtils.color(parsed));
                }
            }
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String replaceBoth(String text, String key, String value) {
        if (text == null || value == null) return text;
        return text.replace("{" + key + "}", value).replace("%" + key + "%", value);
    }

    private String replaceAllPlaceholders(String text, Player player, Profile profile, int slot) {
        if (text == null) return "";

        String pName = player != null ? player.getName() : "Unknown";
        String pDisplay = player != null ? player.getDisplayName() : pName;
        int maxSlots = player != null ? plugin.getProfileManager().getMaxSlots(player) : 1;
        String slotStr = String.valueOf(slot > 0 ? slot : (profile != null ? profile.getSlot() : 1));

        String res = text;
        res = replaceBoth(res, "player", pName);
        res = replaceBoth(res, "player_display", pDisplay);
        res = replaceBoth(res, "slot", slotStr);
        res = replaceBoth(res, "max_slots", String.valueOf(maxSlots));

        if (profile != null) {
            ClassData classData = plugin.getClassManager().getClass(profile.getClassId());
            String classId = profile.getClassId();
            String classDisplay = classData != null ? classData.getDisplayName() : classId;
            String classPlain = classData != null ? classData.getPlainDisplayName() : classId;
            String classDesc = classData != null ? String.join("\n", classData.getDescription()) : "";
            String allowedGear = classData != null ? String.join(", ", classData.getAllowedGear()) : "ALL";

            int level = profile.getLevel();
            int maxLevel = plugin.getLevelManager().getMaxLevel();
            double reqXp = plugin.getLevelManager().getRequiredXp(level);
            double currentXp = profile.getXp();

            String xpStr = level >= maxLevel ? plugin.getLevelManager().getMaxXpText() : XP_FORMAT.format(currentXp);
            String reqXpStr = level >= maxLevel ? plugin.getLevelManager().getMaxXpText() : XP_FORMAT.format(reqXp);
            double remainingXpVal = Math.max(0.0, reqXp - currentXp);
            String remXpStr = level >= maxLevel ? "0" : XP_FORMAT.format(remainingXpVal);

            String percentStr = (level >= maxLevel || reqXp <= 0) ? "100.0" : String.format(Locale.US, "%.1f", (currentXp / reqXp) * 100.0);
            String progressBar = plugin.getLevelManager().getProgressBar(currentXp, reqXp, 10, "§a", "§7");

            double baseHp = classData != null ? classData.getBaseHealth() : 20.0;
            String healthStr = String.format(Locale.US, "%.1f", profile.getHealth());
            String maxHealthStr = String.format(Locale.US, "%.1f", baseHp);
            String hungerStr = String.valueOf(profile.getHunger());
            String speedStr = String.format(Locale.US, "%.2f", classData != null ? classData.getWalkSpeed() : 0.2);

            Location loc = profile.getLastLocation();
            String worldName = (loc != null && loc.getWorld() != null) ? loc.getWorld().getName() : "world";
            String xStr = loc != null ? String.valueOf(loc.getBlockX()) : "0";
            String yStr = loc != null ? String.valueOf(loc.getBlockY()) : "64";
            String zStr = loc != null ? String.valueOf(loc.getBlockZ()) : "0";

            String createdDate = profile.getCreatedAt() > 0 ? DATE_FORMAT.format(new Date(profile.getCreatedAt())) : "-";
            String updatedDate = profile.getUpdatedAt() > 0 ? DATE_FORMAT.format(new Date(profile.getUpdatedAt())) : "-";

            PlayerProfileHolder holder = player != null ? plugin.getProfileManager().getHolder(player) : null;
            boolean isActive = holder != null && holder.getActiveSlot() == profile.getSlot();
            String status = profile.isDead() ? "Fallen" : (isActive ? "Playing" : "Saved");
            String statusColored = profile.isDead() ? "<dark_red>Permanently Fallen"
                    : (isActive ? "<green>Currently Playing" : "<yellow>Saved");

            GamemodeManager gm = plugin.getGamemodeManager();
            String gmBadges = gm != null ? gm.getBadgesRaw(profile.getGamemodes()) : "";
            String gmNames = gm != null ? gm.getNamesRaw(profile.getGamemodes()) : "Normal";
            String livesInfo = profile.isHardcore()
                    ? "<gray>(<red>" + profile.getCurrentLives() + "/" + profile.getMaxLives() + " ❤<gray>)"
                    : "";
            String fallenDate = profile.getFallenAt() > 0 ? DATE_FORMAT.format(new Date(profile.getFallenAt())) : "-";
            String fallenReason = !profile.getFallenReason().isEmpty() ? profile.getFallenReason() : "-";

            res = replaceBoth(res, "class", classId);
            res = replaceBoth(res, "class_display", classDisplay);
            res = replaceBoth(res, "class_plain", classPlain);
            res = replaceBoth(res, "class_desc", classDesc);
            res = replaceBoth(res, "allowed_gear", allowedGear);
            res = replaceBoth(res, "gamemode_badges", gmBadges);
            res = replaceBoth(res, "gamemode_names", gmNames);
            res = replaceBoth(res, "lives", String.valueOf(profile.getCurrentLives()));
            res = replaceBoth(res, "max_lives", String.valueOf(profile.getMaxLives()));
            res = replaceBoth(res, "lives_info", livesInfo);
            res = replaceBoth(res, "is_dead", String.valueOf(profile.isDead()));
            res = replaceBoth(res, "fallen_at", fallenDate);
            res = replaceBoth(res, "fallen_reason", fallenReason);
            res = replaceBoth(res, "level", String.valueOf(level));
            res = replaceBoth(res, "max_level", String.valueOf(maxLevel));
            res = replaceBoth(res, "xp", xpStr);
            res = replaceBoth(res, "current_xp", xpStr);
            res = replaceBoth(res, "xp_raw", String.valueOf(currentXp));
            res = replaceBoth(res, "req_xp", reqXpStr);
            res = replaceBoth(res, "req_xp_raw", String.valueOf(reqXp));
            res = replaceBoth(res, "remaining_xp", remXpStr);
            res = replaceBoth(res, "progress_percent", percentStr);
            res = replaceBoth(res, "percent", percentStr);
            res = replaceBoth(res, "progress_bar", progressBar);
            res = replaceBoth(res, "health", healthStr);
            res = replaceBoth(res, "max_health", maxHealthStr);
            res = replaceBoth(res, "hunger", hungerStr);
            res = replaceBoth(res, "walk_speed", speedStr);
            res = replaceBoth(res, "world", worldName);
            res = replaceBoth(res, "x", xStr);
            res = replaceBoth(res, "y", yStr);
            res = replaceBoth(res, "z", zStr);
            res = replaceBoth(res, "created_at", createdDate);
            res = replaceBoth(res, "last_played", updatedDate);
            res = replaceBoth(res, "status", status);
            res = replaceBoth(res, "status_colored", statusColored);
        } else {
            String worldName = player != null ? player.getWorld().getName() : "world";
            String xStr = player != null ? String.valueOf(player.getLocation().getBlockX()) : "0";
            String yStr = player != null ? String.valueOf(player.getLocation().getBlockY()) : "64";
            String zStr = player != null ? String.valueOf(player.getLocation().getBlockZ()) : "0";
            res = replaceBoth(res, "class", "none");
            res = replaceBoth(res, "class_display", "<gray>None");
            res = replaceBoth(res, "class_plain", "None");
            res = replaceBoth(res, "class_desc", "");
            res = replaceBoth(res, "allowed_gear", "-");
            GamemodeManager gmNull = plugin.getGamemodeManager();
            res = replaceBoth(res, "gamemode_badges", gmNull != null ? gmNull.getNormalSymbol() : "");
            res = replaceBoth(res, "gamemode_names", gmNull != null ? gmNull.getNormalDisplayName() : "Normal");
            res = replaceBoth(res, "lives", "1");
            res = replaceBoth(res, "max_lives", "1");
            res = replaceBoth(res, "lives_info", "");
            res = replaceBoth(res, "is_dead", "false");
            res = replaceBoth(res, "fallen_at", "-");
            res = replaceBoth(res, "fallen_reason", "-");
            res = replaceBoth(res, "level", "0");
            res = replaceBoth(res, "max_level", String.valueOf(plugin.getLevelManager().getMaxLevel()));
            res = replaceBoth(res, "xp", "0");
            res = replaceBoth(res, "current_xp", "0");
            res = replaceBoth(res, "req_xp", "100");
            res = replaceBoth(res, "remaining_xp", "100");
            res = replaceBoth(res, "progress_percent", "0.0");
            res = replaceBoth(res, "percent", "0.0");
            res = replaceBoth(res, "health", "20.0");
            res = replaceBoth(res, "max_health", "20.0");
            res = replaceBoth(res, "hunger", "20");
            res = replaceBoth(res, "walk_speed", "0.20");
            res = replaceBoth(res, "world", worldName);
            res = replaceBoth(res, "x", xStr);
            res = replaceBoth(res, "y", yStr);
            res = replaceBoth(res, "z", zStr);
            res = replaceBoth(res, "created_at", "-");
            res = replaceBoth(res, "last_played", "-");
            res = replaceBoth(res, "status", "Not Created");
            res = replaceBoth(res, "status_colored", "<gray>Not Created");
        }

        return res;
    }
}
