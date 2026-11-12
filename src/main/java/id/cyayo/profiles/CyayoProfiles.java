package id.cyayo.profiles;

import id.cyayo.profiles.command.ProfileAdminCommand;
import id.cyayo.profiles.command.ProfileCommand;
import id.cyayo.profiles.command.ProfileTabCompleter;
import id.cyayo.profiles.gui.GUIListener;
import id.cyayo.profiles.gui.ProfileGUIManager;
import id.cyayo.profiles.hook.MMOItemsHook;
import id.cyayo.profiles.hook.PlaceholderAPIHook;
import id.cyayo.profiles.hook.VaultHook;
import id.cyayo.profiles.listener.CombatTagListener;
import id.cyayo.profiles.listener.GamemodeListener;
import id.cyayo.profiles.listener.ItemClassRestrictionListener;
import id.cyayo.profiles.listener.PlayerConnectionListener;
import id.cyayo.profiles.listener.ProfileSwitchFreezeListener;
import id.cyayo.profiles.manager.ClassManager;
import id.cyayo.profiles.manager.GamemodeManager;
import id.cyayo.profiles.manager.LevelManager;
import id.cyayo.profiles.manager.ProfileManager;
import id.cyayo.profiles.storage.DatabaseManager;
import id.cyayo.profiles.storage.ProfileStorage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

public class CyayoProfiles extends JavaPlugin {

    private static CyayoProfiles instance;

    private DatabaseManager databaseManager;
    private ProfileStorage profileStorage;
    private ClassManager classManager;
    private GamemodeManager gamemodeManager;
    private LevelManager levelManager;
    private ProfileManager profileManager;
    private ProfileGUIManager guiManager;
    private GamemodeListener gamemodeListener;
    private MMOItemsHook mmoItemsHook;
    private VaultHook vaultHook;

    private File messagesFile;
    private FileConfiguration messagesConfig;
    private BukkitTask autoSaveTask;
    private BukkitTask autoSaveBatchTask;
    private final Queue<UUID> autoSaveQueue = new ArrayDeque<>();

    @Override
    public void onEnable() {
        instance = this;

        // 1. Save default configs
        saveDefaultConfig();
        saveDefaultPlaceholders();
        loadMessagesConfig();

        // 2. Initialize Database & Storage
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();
        this.profileStorage = new ProfileStorage(this, databaseManager);

        // 3. Initialize Core Managers
        this.classManager = new ClassManager(this);
        this.gamemodeManager = new GamemodeManager(this);
        this.levelManager = new LevelManager(this);
        this.profileManager = new ProfileManager(this, profileStorage);
        this.guiManager = new ProfileGUIManager(this);

        // 4. Initialize External Hooks
        this.vaultHook = new VaultHook();
        this.mmoItemsHook = new MMOItemsHook(this);
        this.mmoItemsHook.registerHook();

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PlaceholderAPIHook(this).register();
            getLogger().info("Successfully hooked into PlaceholderAPI!");
        }

        // 5. Register Event Listeners
        this.gamemodeListener = new GamemodeListener(this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new ProfileSwitchFreezeListener(this), this);
        getServer().getPluginManager().registerEvents(new ItemClassRestrictionListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatTagListener(this), this);
        getServer().getPluginManager().registerEvents(this.gamemodeListener, this);
        getServer().getPluginManager().registerEvents(new GUIListener(this), this);

        // 6. Register Commands
        ProfileCommand profileCmd = new ProfileCommand(this);
        ProfileAdminCommand adminCmd = new ProfileAdminCommand(this);
        ProfileTabCompleter tabCompleter = new ProfileTabCompleter(this);

        if (getCommand("profile") != null) {
            getCommand("profile").setExecutor(profileCmd);
            getCommand("profile").setTabCompleter(tabCompleter);
        }
        if (getCommand("cyayoprofiles") != null) {
            getCommand("cyayoprofiles").setExecutor(adminCmd);
            getCommand("cyayoprofiles").setTabCompleter(tabCompleter);
        }

        // 7. Load data for players already online (e.g. during reload)
        for (Player online : Bukkit.getOnlinePlayers()) {
            profileManager.loadPlayerData(online);
        }

        // 8. Start Auto-Save Task
        startAutoSaveTask();

        getLogger().info("CyayoProfiles v" + getDescription().getVersion() + " has been successfully enabled!");
    }

    @Override
    public void onDisable() {
        stopAutoSaveTasks();

        // Save all online players synchronously before shutdown
        if (profileManager != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                profileManager.savePlayerDataSync(online);
            }
        }

        if (profileStorage != null) {
            profileStorage.flushPendingWritesSync(5000L);
        }

        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        getLogger().info("CyayoProfiles has been safely disabled!");
    }

    /** Salin placeholder/*.yml bawaan agar ikut dibaca SeriaCore Placeholder Engine. */
    private void saveDefaultPlaceholders() {
        File target = new File(getDataFolder(), "placeholder/cyayoprofiles.yml");
        if (!target.exists()) {
            saveResource("placeholder/cyayoprofiles.yml", false);
        }
    }

    public void loadMessagesConfig() {
        String language = getConfig().getString("settings.language", "messages_en.yml");
        messagesFile = new File(getDataFolder(), language);
        // Migrasi struktur lama: messages.yml -> messages_en.yml
        File legacyMessages = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists() && legacyMessages.exists() && language.equals("messages_en.yml")) {
            if (legacyMessages.renameTo(messagesFile)) {
                getLogger().info("Migrated messages.yml to messages_en.yml");
            }
        }
        if (!messagesFile.exists()) {
            if (getResource(language) != null) {
                saveResource(language, false);
            } else {
                getLogger().warning("Language file " + language + " not found, falling back to messages_en.yml");
                messagesFile = new File(getDataFolder(), "messages_en.yml");
                if (!messagesFile.exists()) saveResource("messages_en.yml", false);
            }
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public void reloadAll() {
        reloadConfig();
        loadMessagesConfig();
        if (classManager != null) classManager.loadClasses();
        if (gamemodeManager != null) gamemodeManager.loadConfig();
        if (mmoItemsHook != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                var holder = profileManager.getHolder(online);
                if (holder != null && holder.hasActiveProfile()) {
                    mmoItemsHook.applyGamemodeModifiers(online, holder.getActiveProfile());
                }
            }
        }
        if (levelManager != null) levelManager.loadConfig();
        if (guiManager != null) guiManager.loadConfigs();
        startAutoSaveTask();
    }

    private void stopAutoSaveTasks() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (autoSaveBatchTask != null) {
            autoSaveBatchTask.cancel();
            autoSaveBatchTask = null;
        }
        autoSaveQueue.clear();
    }

    private void startAutoSaveTask() {
        stopAutoSaveTasks();
        if (!getConfig().getBoolean("auto_save.enabled", true)) {
            return;
        }

        long intervalTicks = Math.max(20L, getConfig().getLong("auto_save.interval_seconds", 300) * 20L);
        int batchSize = Math.max(1, getConfig().getInt("auto_save.batch_size", 5));
        long batchIntervalTicks = Math.max(1L, getConfig().getLong("auto_save.batch_interval_ticks", 10));

        // Ambil daftar pemain di main thread lalu cicil per batch (snapshot main thread -> serialisasi & SQL async)
        autoSaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (profileManager == null) return;
            if (!autoSaveQueue.isEmpty()) return;

            for (Player online : Bukkit.getOnlinePlayers()) {
                autoSaveQueue.add(online.getUniqueId());
            }
            if (autoSaveQueue.isEmpty()) return;

            if (autoSaveBatchTask == null || autoSaveBatchTask.isCancelled()) {
                autoSaveBatchTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                    int processed = 0;
                    while (processed < batchSize && !autoSaveQueue.isEmpty()) {
                        UUID nextUuid = autoSaveQueue.poll();
                        Player online = Bukkit.getPlayer(nextUuid);
                        if (online != null && online.isOnline()) {
                            profileManager.savePlayerData(online);
                            processed++;
                        }
                    }
                    if (autoSaveQueue.isEmpty() && autoSaveBatchTask != null) {
                        autoSaveBatchTask.cancel();
                        autoSaveBatchTask = null;
                    }
                }, 1L, batchIntervalTicks);
            }
        }, intervalTicks, intervalTicks);
    }

    public static CyayoProfiles getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ProfileStorage getProfileStorage() {
        return profileStorage;
    }

    public ClassManager getClassManager() {
        return classManager;
    }

    public GamemodeManager getGamemodeManager() {
        return gamemodeManager;
    }

    public GamemodeListener getGamemodeListener() {
        return gamemodeListener;
    }

    public LevelManager getLevelManager() {
        return levelManager;
    }

    public ProfileManager getProfileManager() {
        return profileManager;
    }

    public ProfileGUIManager getGuiManager() {
        return guiManager;
    }

    public MMOItemsHook getMmoItemsHook() {
        return mmoItemsHook;
    }

    public VaultHook getVaultHook() {
        return vaultHook;
    }

    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }
}
