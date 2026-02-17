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
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

public class BugEnchantRemover extends JavaPlugin {

    private static final Component ACTIONBAR_MESSAGE =
            Component.text("已自动清除异常附魔书", NamedTextColor.RED);

    private final Set<String> enchantIdKeywords = new HashSet<>();
    private final Set<String> translationKeyKeywords = new HashSet<>();
    private long checkInterval;
    private boolean logRemovals;

    @Override
    public void onEnable() {
        getLogger().info("BugEnchantRemover 插件已启用");
        getLogger().info("作者: F.windEmiko");
        getLogger().info("版本: 1.0.2 - 附魔ID/翻译键检测模式");

        // 加载配置
        loadConfig();

        // 启动异步检查任务
        startCheckTask();

        // 注册命令
        setupCommands();

        // 注册事件监听器
        setupEventListeners();
    }

    @Override
    public void onDisable() {
        getLogger().info("BugEnchantRemover 插件已禁用");
    }

    /**
     * 加载配置文件
     */
    private void loadConfig() {
        // 确保配置文件存在
        if (!new File(getDataFolder(), "config.yml").exists()) {
            saveDefaultConfig();
        }

        // 重新加载配置
        reloadConfig();

        // 读取配置
        enchantIdKeywords.clear();
        enchantIdKeywords.addAll(getConfig().getStringList("enchant-id-keywords"));

        translationKeyKeywords.clear();
        translationKeyKeywords.addAll(getConfig().getStringList("translation-key-keywords"));

        checkInterval = getConfig().getLong("check-interval", 21L);
        logRemovals = getConfig().getBoolean("log-removals", false);

        getLogger().info("已加载 " + enchantIdKeywords.size() + " 个附魔ID关键词: " + String.join(", ", enchantIdKeywords));
        getLogger().info("已加载 " + translationKeyKeywords.size() + " 个翻译键关键词: " + String.join(", ", translationKeyKeywords));
        getLogger().info("检查间隔: " + checkInterval + " tick");
    }

    /**
     * 启动异步检查任务
     */
    private void startCheckTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                this::performChecks,
                0L, // 初始延迟
                checkInterval // 检查间隔
        );
        getLogger().info("异步检查任务已启动，间隔: " + checkInterval + " tick");
    }

    /**
     * 执行检查逻辑
     */
    private void performChecks() {
        try {
            // 检查在线玩家
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player != null && player.isOnline()) {
                    checkPlayerInventory(player);
                }
            }
        } catch (Exception e) {
            getLogger().warning("检查过程中发生错误: " + e.getMessage());
        }
    }

    /**
     * 检查玩家背包和打开的容器
     */
    private void checkPlayerInventory(Player player) {
        if (player == null || !player.isOnline()) return;

        boolean removed = false;

        // 检查背包所有格子
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (isBugEnchantedBook(item)) {
                inventory.setItem(i, null);
                removed = true;
                if (logRemovals) {
                    logRemoval(player.getName() + "的背包", i);
                }
            }
        }

        // 检查副手
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (isBugEnchantedBook(offhand)) {
            player.getInventory().setItemInOffHand(null);
            removed = true;
            if (logRemovals) {
                logRemoval(player.getName() + "的副手", -1);
            }
        }

        // 检查玩家打开的容器
        Inventory openInventory = player.getOpenInventory().getTopInventory();
        if (openInventory != null) {
            for (int i = 0; i < openInventory.getSize(); i++) {
                ItemStack item = openInventory.getItem(i);
                if (isBugEnchantedBook(item)) {
                    openInventory.setItem(i, null);
                    removed = true;
                    if (logRemovals) {
                        logRemoval(player.getName() + "打开的容器", i);
                    }
                }
            }
        }

        // 如果移除了物品，发送提示
        if (removed) {
            // 在主线程发送actionbar
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) {
                    player.sendActionBar(ACTIONBAR_MESSAGE);
                }
            });
        }
    }

    /**
     * 判断物品是否为异常的附魔书
     */
    private boolean isBugEnchantedBook(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK) {
            return false;
        }

        if (!item.hasItemMeta()) {
            return false;
        }

        if (!(item.getItemMeta() instanceof EnchantmentStorageMeta)) {
            return false;
        }

        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();

        // 检查存储的附魔的ID和翻译键
        for (Map.Entry<Enchantment, Integer> entry : meta.getStoredEnchants().entrySet()) {
            Enchantment enchant = entry.getKey();

            // 获取附魔的ID和翻译键
            String enchantId = enchant.getKey().toString(); // 完整ID，如 "nova_structures:jockey/spawn_bogged_horseman"
            String enchantNamespace = enchant.getKey().getNamespace(); // 命名空间，如 "nova_structures"
            String enchantKey = enchant.getKey().getKey(); // 键名，如 "jockey/spawn_bogged_horseman"
            String translationKey = enchant.translationKey(); // 翻译键，如 "enchantment.dnt.non_survival_enchant"

            // 检查是否匹配任何关键词
            if (matchesAnyKeyword(enchantId, enchantNamespace, enchantKey, translationKey)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查附魔的ID和翻译键是否匹配任何关键词
     */
    private boolean matchesAnyKeyword(String enchantId, String enchantNamespace, String enchantKey, String translationKey) {
        // 将参数转换为小写用于不区分大小写的比较
        String lowerEnchantId = enchantId.toLowerCase(Locale.ROOT);
        String lowerEnchantNamespace = enchantNamespace.toLowerCase(Locale.ROOT);
        String lowerEnchantKey = enchantKey.toLowerCase(Locale.ROOT);
        String lowerTranslationKey = translationKey.toLowerCase(Locale.ROOT);

        // 检查附魔ID关键词
        for (String keyword : enchantIdKeywords) {
            if (keyword == null || keyword.isEmpty()) continue;

            String lowerKeyword = keyword.toLowerCase(Locale.ROOT);

            // 检查完整ID、命名空间或键名是否包含关键词
            if (lowerEnchantId.contains(lowerKeyword) ||
                    lowerEnchantNamespace.contains(lowerKeyword) ||
                    lowerEnchantKey.contains(lowerKeyword)) {
                if (logRemovals) {
                    getLogger().info("匹配附魔ID关键词: " + keyword + " -> " + enchantId);
                }
                return true;
            }
        }

        // 检查翻译键关键词
        for (String keyword : translationKeyKeywords) {
            if (keyword == null || keyword.isEmpty()) continue;

            String lowerKeyword = keyword.toLowerCase(Locale.ROOT);

            // 检查翻译键是否包含关键词
            if (lowerTranslationKey.contains(lowerKeyword)) {
                if (logRemovals) {
                    getLogger().info("匹配翻译键关键词: " + keyword + " -> " + translationKey);
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
        // 重新加载配置的命令
        this.getCommand("bugenchantreload").setExecutor((sender, command, label, args) -> {
            if (sender.hasPermission("bugenchantremover.reload")) {
                loadConfig();

                sender.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.GREEN)
                        .append(Component.text("配置已重载", NamedTextColor.YELLOW)));
                return true;
            }
            sender.sendMessage(Component.text("你没有执行此命令的权限", NamedTextColor.RED));
            return false;
        });

        // 手动扫描命令
        this.getCommand("bugenchantscan").setExecutor((sender, command, label, args) -> {
            if (sender.hasPermission("bugenchantremover.scan")) {
                if (sender instanceof Player player) {
                    int removed = scanPlayerInventory(player);
                    sender.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.GREEN)
                            .append(Component.text("扫描完成，移除了 " + removed + " 本异常附魔书", NamedTextColor.YELLOW)));
                } else {
                    sender.sendMessage(Component.text("该命令只能由玩家执行", NamedTextColor.RED));
                }
                return true;
            }
            sender.sendMessage(Component.text("你没有执行此命令的权限", NamedTextColor.RED));
            return false;
        });

        // 检测附魔信息命令
        this.getCommand("bugenchanttest").setExecutor((sender, command, label, args) -> {
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

    /**
     * 测试玩家手中的附魔书
     */
    private void testPlayerEnchantments(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item == null || item.getType() != Material.ENCHANTED_BOOK) {
            player.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.RED)
                    .append(Component.text("请手持一本附魔书", NamedTextColor.YELLOW)));
            return;
        }

        if (!(item.getItemMeta() instanceof EnchantmentStorageMeta)) {
            player.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.RED)
                    .append(Component.text("这不是一本有效的附魔书", NamedTextColor.YELLOW)));
            return;
        }

        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
        StringBuilder message = new StringBuilder("附魔书信息:\n");

        for (Map.Entry<Enchantment, Integer> entry : meta.getStoredEnchants().entrySet()) {
            Enchantment enchant = entry.getKey();
            int level = entry.getValue();

            // 获取附魔的各个ID部分
            String enchantId = enchant.getKey().toString();
            String enchantNamespace = enchant.getKey().getNamespace();
            String enchantKey = enchant.getKey().getKey();
            String translationKey = enchant.translationKey();

            // 检查是否匹配
            boolean isBug = matchesAnyKeyword(enchantId, enchantNamespace, enchantKey, translationKey);

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
        // 玩家拾取物品事件 - 防止拾取异常附魔书
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onPlayerPickupItem(org.bukkit.event.player.PlayerPickupItemEvent event) {
                if (isBugEnchantedBook(event.getItem().getItemStack())) {
                    event.setCancelled(true);
                    event.getItem().remove();
                    event.getPlayer().sendActionBar(ACTIONBAR_MESSAGE);
                }
            }
        }, this);

        // 玩家交互事件 - 防止使用异常附魔书
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
                ItemStack item = event.getItem();
                if (item != null && isBugEnchantedBook(item)) {
                    event.setCancelled(true);
                    event.getPlayer().getInventory().remove(item);
                    event.getPlayer().sendActionBar(ACTIONBAR_MESSAGE);
                }
            }
        }, this);

        // 玩家关闭容器事件 - 确保容器关闭时检查容器内物品
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
                if (event.getPlayer() instanceof Player player) {
                    // 延迟1tick检查，确保所有操作已完成
                    Bukkit.getScheduler().runTaskLater(BugEnchantRemover.this, () -> {
                        checkPlayerInventory(player);
                    }, 1L);
                }
            }
        }, this);
    }

    /**
     * 手动扫描玩家背包
     */
    private int scanPlayerInventory(Player player) {
        int removed = 0;
        Inventory inventory = player.getInventory();

        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (isBugEnchantedBook(item)) {
                inventory.setItem(i, null);
                removed++;
                if (logRemovals) {
                    logRemoval(player.getName() + "的手动扫描", i);
                }
            }
        }

        if (removed > 0) {
            player.sendActionBar(ACTIONBAR_MESSAGE);
        }

        return removed;
    }

    /**
     * 记录移除日志
     */
    private void logRemoval(String location, int slot) {
        String slotStr = slot >= 0 ? "槽位 " + slot : "副手";
        getLogger().info("已清除异常附魔书 - 位置: " + location + ", " + slotStr);
    }
}