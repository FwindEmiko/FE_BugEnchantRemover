package top.miragedge.bugenchantremover;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;

public class BugEnchantRemover extends JavaPlugin {

    private final Set<String> enchantIdKeywords = new HashSet<>();
    private final Set<String> translationKeyKeywords = new HashSet<>();
    private long checkInterval;
    private boolean logRemovals;
    private boolean logKeywordMatches;

    // 消息配置
    private Component actionbarMessage;
    private String logPrefix;

    // 服务端类型检测
    private final boolean isFolia = detectFolia();
    private BukkitTask checkTask;

    /**
     * 检测是否为 Folia 服务端
     * Folia 特有的 ThreadedRegionizer 类只存在于 Folia 服务端中
     */
    private boolean detectFolia() {
        try {
            // ThreadedRegionizer 是 Folia 特有的调度器核心类
            Class.forName("io.papermc.paper.threadedregions.ThreadedRegionizer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onEnable() {
        getLogger().info("BugEnchantRemover 插件已启用");
        getLogger().info("作者: F.windEmiko");
        getLogger().info("版本: 1.2 - Paper/Folia 双兼容");

        // 加载配置
        loadConfig();

        // 启动检查任务
        startCheckTask();

        // 注册命令
        setupCommands();

        // 注册事件监听器
        setupEventListeners();

        getLogger().info("检测到服务端类型: " + (isFolia ? "Folia" : "Paper") +
                " | 调度模式: " + (isFolia ? "同步" : "异步"));
    }

    @Override
    public void onDisable() {
        // 取消检查任务
        if (checkTask != null) {
            checkTask.cancel();
        }
        getLogger().info("BugEnchantRemover 插件已禁用");
    }

    /**
     * 加载配置文件
     */
    private void loadConfig() {
        if (!new File(getDataFolder(), "config.yml").exists()) {
            saveDefaultConfig();
        }
        reloadConfig();

        enchantIdKeywords.clear();
        enchantIdKeywords.addAll(getConfig().getStringList("enchant-id-keywords"));

        translationKeyKeywords.clear();
        translationKeyKeywords.addAll(getConfig().getStringList("translation-key-keywords"));

        checkInterval = getConfig().getLong("check-interval", 21L);
        logRemovals = getConfig().getBoolean("log-removals", false);
        logKeywordMatches = getConfig().getBoolean("log-keyword-matches", false);

        // 加载消息配置
        String actionbarText = getConfig().getString("messages.actionbar", "已自动清除异常附魔");
        actionbarMessage = Component.text(actionbarText, NamedTextColor.RED);
        logPrefix = getConfig().getString("messages.log-prefix", "[BugEnchantRemover]");

        getLogger().info("已加载 " + enchantIdKeywords.size() + " 个附魔ID关键词");
        getLogger().info("已加载 " + translationKeyKeywords.size() + " 个翻译键关键词");
        getLogger().info("检查间隔: " + checkInterval + " tick");
    }

    /**
     * 启动检查任务
     * Paper: 使用异步调度，不阻塞主线程
     * Folia: 必须使用同步调度
     */
    private void startCheckTask() {
        if (isFolia) {
            startFoliaTask();
        } else {
            startPaperTask();
        }
    }

    /**
     * Paper 异步调度 - 高性能模式
     * 注意: 异步任务中只能访问世界数据，不能安全访问玩家对象
     * 因此检查任务本身在主线程执行，但使用异步调度来定期触发
     */
    private void startPaperTask() {
        // Paper 异步调度：在异步线程执行，但玩家检查会同步到主线程
        // 这是 Paper 推荐的做法，避免线程安全问题
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> {
                    // 获取玩家列表后在主线程执行检查
                    Player[] players = Bukkit.getOnlinePlayers().toArray(new Player[0]);
                    for (Player player : players) {
                        // 调度到主线程执行玩家相关操作
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (player.isOnline()) {
                                checkPlayerInventory(player);
                            }
                        });
                    }
                },
                0L,
                checkInterval
        );
        getLogger().info("异步调度任务已启动 (Paper高性能模式)，间隔: " + checkInterval + " tick");
    }

    /**
     * Folia 同步调度 - Folia 兼容模式
     * Folia 不支持异步调度，必须使用同步调度
     */
    private void startFoliaTask() {
        checkTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::performChecksFolia,
                0L,
                checkInterval
        );
        getLogger().info("同步检查任务已启动 (Folia同步模式)，间隔: " + checkInterval + " tick");
    }

    /**
     * Folia 执行检查逻辑 - 在主线程执行
     */
    private void performChecksFolia() {
        try {
            Player[] players = Bukkit.getOnlinePlayers().toArray(new Player[0]);
            for (Player player : players) {
                if (player != null && player.isOnline()) {
                    checkPlayerInventory(player);
                }
            }
        } catch (Exception e) {
            getLogger().warning("检查过程中发生错误: " + e.getMessage());
        }
    }

    /**
     * 检查玩家背包和打开的容器，移除异常附魔
     */
    private void checkPlayerInventory(Player player) {
        if (player == null || !player.isOnline()) return;

        String playerName = player.getName(); // 缓存玩家名
        boolean removed = false;

        // 检查背包所有格子
        Inventory inventory = player.getInventory();
        int size = inventory.getSize();
        for (int i = 0; i < size; i++) {
            if (removeBugEnchantments(inventory.getItem(i))) {
                removed = true;
                if (logRemovals) {
                    logRemoval(playerName + "的背包", i);
                }
            }
        }

        // 检查副手
        if (removeBugEnchantments(player.getInventory().getItemInOffHand())) {
            removed = true;
            if (logRemovals) {
                logRemoval(playerName + "的副手", -1);
            }
        }

        // 检查玩家打开的容器
        Inventory topInventory = player.getOpenInventory().getTopInventory();
        int topSize = topInventory.getSize();
        for (int i = 0; i < topSize; i++) {
            if (removeBugEnchantments(topInventory.getItem(i))) {
                removed = true;
                if (logRemovals) {
                    logRemoval(playerName + "打开的容器", i);
                }
            }
        }

        // 如果移除了附魔，发送提示
        if (removed) {
            sendActionBarSafe(player);
        }
    }

    /**
     * 安全发送 actionbar 消息
     */
    private void sendActionBarSafe(Player player) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                player.sendActionBar(actionbarMessage);
            }
        });
    }

    /**
     * 移除物品上的异常附魔
     */
    private boolean removeBugEnchantments(ItemStack item) {
        if (item == null) return false;

        Map<Enchantment, Integer> bugEnchantments = getBugEnchantments(item);
        if (bugEnchantments.isEmpty()) {
            return false;
        }

        if (item.getType() == Material.ENCHANTED_BOOK) {
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
            if (meta == null) return false;
            for (Enchantment enchant : bugEnchantments.keySet()) {
                meta.removeStoredEnchant(enchant);
            }
            item.setItemMeta(meta);
        } else {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return false;
            for (Enchantment enchant : bugEnchantments.keySet()) {
                meta.removeEnchant(enchant);
            }
            item.setItemMeta(meta);
        }

        return true;
    }

    /**
     * 获取物品中有问题的附魔列表
     */
    private Map<Enchantment, Integer> getBugEnchantments(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Collections.emptyMap();
        }

        if (item.getType() == Material.ENCHANTED_BOOK) {
            if (!(item.getItemMeta() instanceof EnchantmentStorageMeta meta)) {
                return Collections.emptyMap();
            }
            return findBugEnchantments(meta.getStoredEnchants());
        } else {
            ItemMeta itemMeta = item.getItemMeta();
            if (itemMeta == null) {
                return Collections.emptyMap();
            }
            return findBugEnchantments(itemMeta.getEnchants());
        }
    }

    /**
     * 从附魔Map中筛选出有问题的附魔
     */
    private Map<Enchantment, Integer> findBugEnchantments(Map<Enchantment, Integer> enchants) {
        if (enchants.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Enchantment, Integer> bugEnchantments = null;

        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            if (isBugEnchantment(entry.getKey())) {
                if (bugEnchantments == null) {
                    bugEnchantments = new HashMap<>();
                }
                bugEnchantments.put(entry.getKey(), entry.getValue());
            }
        }

        return bugEnchantments != null ? bugEnchantments : Collections.emptyMap();
    }

    /**
     * 检查附魔是否为异常附魔
     */
    private boolean isBugEnchantment(Enchantment enchant) {
        return matchesAnyKeyword(
                enchant.getKey().toString(),
                enchant.getKey().getNamespace(),
                enchant.getKey().getKey()
        );
    }

    /**
     * 检查附魔的ID和翻译键是否匹配任何关键词
     */
    private boolean matchesAnyKeyword(String enchantId, String enchantNamespace, String enchantKey) {
        // 构建翻译键
        String translationKey = "enchantment." + enchantNamespace + "." + enchantKey;

        // 检查附魔ID关键词
        for (String keyword : enchantIdKeywords) {
            if (keyword == null || keyword.isEmpty()) continue;

            String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
            String lowerEnchantId = enchantId.toLowerCase(Locale.ROOT);

            if (lowerEnchantId.contains(lowerKeyword) ||
                    enchantNamespace.toLowerCase(Locale.ROOT).contains(lowerKeyword) ||
                    enchantKey.toLowerCase(Locale.ROOT).contains(lowerKeyword)) {
                if (logKeywordMatches) {
                    getLogger().info(logPrefix + " 匹配附魔ID关键词: " + keyword + " -> " + enchantId);
                }
                return true;
            }
        }

        // 检查翻译键关键词
        String lowerTranslationKey = translationKey.toLowerCase(Locale.ROOT);
        for (String keyword : translationKeyKeywords) {
            if (keyword == null || keyword.isEmpty()) continue;

            if (lowerTranslationKey.contains(keyword.toLowerCase(Locale.ROOT))) {
                if (logKeywordMatches) {
                    getLogger().info(logPrefix + " 匹配翻译键关键词: " + keyword + " -> " + translationKey);
                }
                return true;
            }
        }

        return false;
    }

    /**
     * 设置命令
     */
    private void setupCommands() {
        var reloadCmd = this.getCommand("bugenchantreload");
        if (reloadCmd != null) {
            reloadCmd.setExecutor((sender, command, label, args) -> {
                if (sender.hasPermission("bugenchantremover.reload")) {
                    loadConfig();
                    sender.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.GREEN)
                            .append(Component.text("配置已重载", NamedTextColor.YELLOW)));
                    return true;
                }
                sender.sendMessage(Component.text("你没有执行此命令的权限", NamedTextColor.RED));
                return false;
            });
        }

        var scanCmd = this.getCommand("bugenchantscan");
        if (scanCmd != null) {
            scanCmd.setExecutor((sender, command, label, args) -> {
                if (sender.hasPermission("bugenchantremover.scan")) {
                    if (sender instanceof Player player) {
                        int removed = scanPlayerInventory(player);
                        sender.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.GREEN)
                                .append(Component.text("扫描完成，移除了 " + removed + " 个物品上的异常附魔", NamedTextColor.YELLOW)));
                    } else {
                        sender.sendMessage(Component.text("该命令只能由玩家执行", NamedTextColor.RED));
                    }
                    return true;
                }
                sender.sendMessage(Component.text("你没有执行此命令的权限", NamedTextColor.RED));
                return false;
            });
        }

        var testCmd = this.getCommand("bugenchanttest");
        if (testCmd != null) {
            testCmd.setExecutor((sender, command, label, args) -> {
                if (sender.hasPermission("bugenchantremover.test")) {
                    if (sender instanceof Player player) {
                        testPlayerEnchantments(player);
                    } else {
                        sender.sendMessage(Component.text("该命令只能由玩家执行", NamedTextColor.RED));
                    }
                    return true;
                }
                sender.sendMessage(Component.text("你没有执行此命令的权限", NamedTextColor.RED));
                return false;
            });
        }
    }

    /**
     * 测试玩家手中的附魔书
     */
    private void testPlayerEnchantments(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item.getType() != Material.ENCHANTED_BOOK) {
            player.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.RED)
                    .append(Component.text("请手持一本附魔书", NamedTextColor.YELLOW)));
            return;
        }

        if (!(item.getItemMeta() instanceof EnchantmentStorageMeta meta)) {
            player.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.RED)
                    .append(Component.text("这不是一本有效的附魔书", NamedTextColor.YELLOW)));
            return;
        }

        StringBuilder message = new StringBuilder("附魔书信息:\n");

        for (Map.Entry<Enchantment, Integer> entry : meta.getStoredEnchants().entrySet()) {
            Enchantment enchant = entry.getKey();
            int level = entry.getValue();

            String enchantId = enchant.getKey().toString();
            String enchantNamespace = enchant.getKey().getNamespace();
            String enchantKey = enchant.getKey().getKey();
            String translationKey = "enchantment." + enchantNamespace + "." + enchantKey;

            boolean isBug = isBugEnchantment(enchant);

            message.append(String.format("附魔ID: %s\n", enchantId));
            message.append(String.format("  命名空间: %s\n", enchantNamespace));
            message.append(String.format("  键名: %s\n", enchantKey));
            message.append(String.format("  翻译键: %s\n", translationKey));
            message.append(String.format("  等级: %d\n", level));
            message.append(String.format("  检测为Bug: %s\n\n", isBug ? "是" : "否"));
        }

        player.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.GREEN)
                .append(Component.text(message.toString(), NamedTextColor.YELLOW)));
    }

    /**
     * 设置事件监听器
     */
    private void setupEventListeners() {
        // 玩家拾取物品事件
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onEntityPickupItem(org.bukkit.event.entity.EntityPickupItemEvent event) {
                if (event.getEntity() instanceof Player player) {
                    if (removeBugEnchantments(event.getItem().getItemStack())) {
                        event.setCancelled(true);
                        event.getItem().remove();
                        sendActionBarSafe(player);
                    }
                }
            }
        }, this);

        // 玩家交互事件
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
                // removeBugEnchantments 内部已处理 null 情况
                if (removeBugEnchantments(event.getItem())) {
                    event.setCancelled(true);
                    sendActionBarSafe(event.getPlayer());
                }
            }
        }, this);

        // 玩家关闭容器事件
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
                if (event.getPlayer() instanceof Player player) {
                    Bukkit.getScheduler().runTaskLater(
                            BugEnchantRemover.this,
                            () -> checkPlayerInventory(player),
                            1L);
                }
            }
        }, this);
    }

    /**
     * 手动扫描玩家背包
     * 注意: 手动扫描只检查玩家背包和副手，不检查打开的容器
     */
    private int scanPlayerInventory(Player player) {
        int removed = 0;
        String playerName = player.getName(); // 缓存玩家名

        // 检查主背包
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (removeBugEnchantments(inventory.getItem(i))) {
                removed++;
                if (logRemovals) {
                    logRemoval(playerName + "的主背包", i);
                }
            }
        }

        // 检查副手
        if (removeBugEnchantments(player.getInventory().getItemInOffHand())) {
            removed++;
            if (logRemovals) {
                logRemoval(playerName + "的副手", -1);
            }
        }

        if (removed > 0) {
            sendActionBarSafe(player);
        }

        return removed;
    }

    /**
     * 记录移除日志
     */
    private void logRemoval(String location, int slot) {
        String slotStr = slot >= 0 ? "槽位 " + slot : "副手";
        getLogger().info(logPrefix + " 已清除异常附魔 - 位置: " + location + ", " + slotStr);
    }
}
