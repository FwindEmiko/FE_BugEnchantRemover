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
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;

public class BugEnchantRemover extends JavaPlugin {

    /** 处理结果语义，用于区分「整本书删除」/「物品被修改」/「无变化」 */
    private enum RemovalResult {
        /** 无异常附魔或未触发处理 */
        NONE,
        /** 已移除部分异常附魔，物品仍存在（需回写调用方栈） */
        MODIFIED,
        /** 整本书被删除（setAmount(0)），调用方应清理对应槽位/实体 */
        REMOVED
    }

    /** 预计算的小写关键词，避免每次匹配时重复 toLowerCase */
    private final Set<String> lowerEnchantIdKeywords = new HashSet<>();
    private final Set<String> lowerTranslationKeyKeywords = new HashSet<>();
    private long checkInterval;
    private boolean logRemovals;
    private boolean logKeywordMatches;
    private boolean removeEnchantedBookWhenAllBug;
    private boolean removeEmptyEnchantedBook;
    private boolean protectCustomItems;

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

        // 预计算小写关键词，避免每次匹配时重复 toLowerCase
        lowerEnchantIdKeywords.clear();
        for (String kw : getConfig().getStringList("enchant-id-keywords")) {
            if (kw != null && !kw.isEmpty()) {
                lowerEnchantIdKeywords.add(kw.toLowerCase(Locale.ROOT));
            }
        }
        lowerTranslationKeyKeywords.clear();
        for (String kw : getConfig().getStringList("translation-key-keywords")) {
            if (kw != null && !kw.isEmpty()) {
                lowerTranslationKeyKeywords.add(kw.toLowerCase(Locale.ROOT));
            }
        }

        checkInterval = getConfig().getLong("check-interval", 21L);
        
        // 验证检查间隔，最小值为 1 tick
        if (checkInterval < 1) {
            getLogger().warning("检查间隔不能小于 1，已自动调整为 21 tick");
            checkInterval = 21L;
        }
        
        logRemovals = getConfig().getBoolean("log-removals", false);
        logKeywordMatches = getConfig().getBoolean("log-keyword-matches", false);
        // 兼容旧配置键 remove-enchanted-book-on-single-bug：优先读取新键，缺失时回退旧键，再缺失默认 true
        removeEnchantedBookWhenAllBug = getConfig().getBoolean("remove-enchanted-book-when-all-bug",
                getConfig().getBoolean("remove-enchanted-book-on-single-bug", true));
        removeEmptyEnchantedBook = getConfig().getBoolean("remove-empty-enchanted-book", true);
        protectCustomItems = getConfig().getBoolean("protect-custom-items", true);

        // 加载消息配置
        String actionbarText = getConfig().getString("messages.actionbar", "已自动清除异常附魔");
        actionbarMessage = Component.text(actionbarText, NamedTextColor.RED);
        logPrefix = getConfig().getString("messages.log-prefix", "[BugEnchantRemover]");

        getLogger().info("已加载 " + lowerEnchantIdKeywords.size() + " 个附魔ID关键词");
        getLogger().info("已加载 " + lowerTranslationKeyKeywords.size() + " 个翻译键关键词");
        getLogger().info("检查间隔: " + checkInterval + " tick");
        getLogger().info("全异常附魔时移除整本附魔书: " + (removeEnchantedBookWhenAllBug ? "开启" : "关闭"));
        getLogger().info("移除空附魔书: " + (removeEmptyEnchantedBook ? "开启" : "关闭"));
        getLogger().info("保护自定义物品(GUI道具): " + (protectCustomItems ? "开启" : "关闭"));
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
            getLogger().warning("检查过程中发生错误: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            if (logKeywordMatches) {
                getLogger().info("堆栈跟踪: " + e.getStackTrace()[0].toString());
            }
        }
    }

    /**
     * 检查玩家背包和打开的容器，移除异常附魔
     */
    private void checkPlayerInventory(Player player) {
        if (player == null || !player.isOnline()) return;

        String playerName = player.getName(); // 缓存玩家名
        boolean changed = false;

        // 检查背包所有格子
        Inventory inventory = player.getInventory();
        int size = inventory.getSize();
        for (int i = 0; i < size; i++) {
            RemovalResult result = removeBugEnchantments(inventory.getItem(i));
            if (result != RemovalResult.NONE) {
                changed = true;
                if (logRemovals) {
                    logRemoval(playerName + "的背包", i, result);
                }
            }
        }

        // 检查副手
        if (removeBugEnchantments(player.getInventory().getItemInOffHand()) != RemovalResult.NONE) {
            changed = true;
            if (logRemovals) {
                logRemoval(playerName + "的副手", -1, RemovalResult.MODIFIED);
            }
        }

        // 检查玩家打开的容器
        Inventory topInventory = player.getOpenInventory().getTopInventory();
        int topSize = topInventory.getSize();
        for (int i = 0; i < topSize; i++) {
            if (removeBugEnchantments(topInventory.getItem(i)) != RemovalResult.NONE) {
                changed = true;
                if (logRemovals) {
                    logRemoval(playerName + "打开的容器", i, RemovalResult.MODIFIED);
                }
            }
        }

        // 如果移除了附魔，发送提示
        if (changed) {
            sendActionBarSafe(player);
        }
    }

    /**
     * 安全发送 actionbar 消息
     * 所有调用点（定时检查、拾取事件、交互事件、容器关闭回调）均在主线程执行，
     * 因此直接发送即可，无需额外调度。
     */
    private void sendActionBarSafe(Player player) {
        if (player.isOnline()) {
            player.sendActionBar(actionbarMessage);
        }
    }

    /**
     * 移除物品上的异常附魔
     * 对于附魔书，会额外处理「全异常附魔导致空书」与「真正空附魔书」两种情况，
     * 同时通过 isCustomItem 保护作为 GUI 道具使用的附魔书。
     * 返回值语义：
     *   NONE     - 无异常附魔或未触发处理
     *   MODIFIED - 已移除部分异常附魔，物品仍存在（调用方需回写更新后的 ItemStack）
     *   REMOVED  - 整本书被删除（setAmount(0)），调用方应清理对应槽位/实体
     */
    private RemovalResult removeBugEnchantments(ItemStack item) {
        if (item == null) return RemovalResult.NONE;
        Material type = item.getType();
        if (type == Material.AIR) return RemovalResult.NONE;

        if (type == Material.ENCHANTED_BOOK) {
            if (!(item.getItemMeta() instanceof EnchantmentStorageMeta meta)) return RemovalResult.NONE;

            Map<Enchantment, Integer> storedEnchants = meta.getStoredEnchants();
            Map<Enchantment, Integer> bugEnchantments = findBugEnchantments(storedEnchants);

            int totalEnchants = storedEnchants.size();
            int bugCount = bugEnchantments.size();
            boolean isCustom = protectCustomItems && isCustomItem(meta);

            // 情况一：没有异常附魔
            if (bugCount == 0) {
                // 检查是否为真正的空附魔书（无任何存储附魔），且非自定义物品时才删除
                if (removeEmptyEnchantedBook && totalEnchants == 0 && !isCustom) {
                    item.setAmount(0);  // 彻底清空物品，而不是设置为AIR
                    return RemovalResult.REMOVED;
                }
                return RemovalResult.NONE;
            }

            // 情况二：存在异常附魔
            // 若所有附魔都是异常附魔（1个或多个），移除后将产生空附魔书
            if (bugCount == totalEnchants) {
                // 配置开启且非自定义物品时，直接删除整本书以避免空附魔书
                if (removeEnchantedBookWhenAllBug && !isCustom) {
                    item.setAmount(0);
                    return RemovalResult.REMOVED;
                }
                // 否则继续移除异常附魔（可能产生空附魔书，但这是配置选择 / 或为自定义物品需保留）
            }

            // 移除单个/多个异常附魔（保留正常附魔）
            for (Enchantment enchant : bugEnchantments.keySet()) {
                meta.removeStoredEnchant(enchant);
            }
            item.setItemMeta(meta);
            return RemovalResult.MODIFIED;
        } else {
            Map<Enchantment, Integer> bugEnchantments = getBugEnchantments(item);
            if (bugEnchantments.isEmpty()) return RemovalResult.NONE;

            ItemMeta meta = item.getItemMeta();
            if (meta == null) return RemovalResult.NONE;
            for (Enchantment enchant : bugEnchantments.keySet()) {
                meta.removeEnchant(enchant);
            }
            item.setItemMeta(meta);
            return RemovalResult.MODIFIED;
        }
    }

    /**
     * 检查物品是否为自定义物品 / GUI 道具
     * 很多 GUI 界面物品和道具是以「附魔书 + 自定义数据值」实现的，
     * 命中以下任意一项即视为自定义物品，避免被当作空书误删：
     *   - 自定义显示名称 / Lore 描述
     *   - CustomModelData 自定义模型数据
     *   - 修复成本(铁砧) / 不可破坏标记
     *   - ItemFlags（如 HIDE_ENCHANTS）/ 自定义属性修饰符
     *   - PersistentDataContainer 自定义NBT数据
     */
    private boolean isCustomItem(ItemMeta meta) {
        if (meta == null) return false;

        // 自定义显示名称
        if (meta.hasDisplayName()) return true;

        // Lore 描述
        if (meta.hasLore()) return true;

        // 自定义模型数据（GUI道具最常用）
        if (meta.hasCustomModelData()) return true;

        // 修复成本（铁砧修改痕迹）
        if (meta instanceof Repairable repairable && repairable.hasRepairCost()) return true;

        // 不可破坏标记
        if (meta.isUnbreakable()) return true;

        // ItemFlags（如 HIDE_ENCHANTS、HIDE_ATTRIBUTES 等，GUI道具常用）
        if (!meta.getItemFlags().isEmpty()) return true;

        // 自定义属性修饰符
        if (meta.hasAttributeModifiers()) return true;

        // PersistentDataContainer 自定义NBT数据（插件写入的数据）
        return !meta.getPersistentDataContainer().isEmpty();
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
     * 使用预计算的小写关键词集合，避免每次匹配重复 toLowerCase
     */
    private boolean matchesAnyKeyword(String enchantId, String enchantNamespace, String enchantKey) {
        // 构建翻译键
        String translationKey = "enchantment." + enchantNamespace + "." + enchantKey;

        // 预先转换为小写（单次调用只转换一次，而非每个关键词都转换）
        String lowerEnchantId = enchantId.toLowerCase(Locale.ROOT);
        String lowerNamespace = enchantNamespace.toLowerCase(Locale.ROOT);
        String lowerKey = enchantKey.toLowerCase(Locale.ROOT);
        String lowerTranslationKey = translationKey.toLowerCase(Locale.ROOT);

        // 检查附魔ID关键词（对 id / namespace / key 分别检查 contains）
        for (String lowerKeyword : lowerEnchantIdKeywords) {
            if (lowerEnchantId.contains(lowerKeyword) ||
                    lowerNamespace.contains(lowerKeyword) ||
                    lowerKey.contains(lowerKeyword)) {
                if (logKeywordMatches) {
                    getLogger().info(logPrefix + " 匹配附魔ID关键词: " + lowerKeyword + " -> " + enchantId);
                }
                return true;
            }
        }

        // 检查翻译键关键词
        for (String lowerKeyword : lowerTranslationKeyKeywords) {
            if (lowerTranslationKey.contains(lowerKeyword)) {
                if (logKeywordMatches) {
                    getLogger().info(logPrefix + " 匹配翻译键关键词: " + lowerKeyword + " -> " + translationKey);
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
     * 输出存储附魔详情、是否识别为自定义物品(GUI道具)，以及预测的处理结果，
     * 便于验证空书/全异常书/自定义物品保护逻辑是否生效。
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

        Map<Enchantment, Integer> storedEnchants = meta.getStoredEnchants();
        Map<Enchantment, Integer> bugEnchantments = findBugEnchantments(storedEnchants);
        boolean isCustom = protectCustomItems && isCustomItem(meta);

        int totalEnchants = storedEnchants.size();
        int bugCount = bugEnchantments.size();

        StringBuilder message = new StringBuilder("附魔书信息:\n");
        message.append(String.format("存储附魔总数: %d\n", totalEnchants));
        message.append(String.format("异常附魔数量: %d\n", bugCount));
        message.append(String.format("识别为自定义物品(GUI道具): %s\n", isCustom ? "是" : "否"));
        message.append('\n');

        for (Map.Entry<Enchantment, Integer> entry : storedEnchants.entrySet()) {
            Enchantment enchant = entry.getKey();
            int level = entry.getValue();

            String enchantId = enchant.getKey().toString();
            String enchantNamespace = enchant.getKey().getNamespace();
            String enchantKey = enchant.getKey().getKey();
            String translationKey = "enchantment." + enchantNamespace + "." + enchantKey;

            boolean isBug = bugEnchantments.containsKey(enchant);

            message.append(String.format("附魔ID: %s\n", enchantId));
            message.append(String.format("  命名空间: %s\n", enchantNamespace));
            message.append(String.format("  键名: %s\n", enchantKey));
            message.append(String.format("  翻译键: %s\n", translationKey));
            message.append(String.format("  等级: %d\n", level));
            message.append(String.format("  检测为Bug: %s\n\n", isBug ? "是" : "否"));
        }

        // 预测处理结果（与 removeBugEnchantments 逻辑保持一致）
        String prediction = predictEnchantedBookAction(bugCount, totalEnchants, isCustom);
        message.append(String.format("预测处理结果: %s\n", prediction));

        player.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.GREEN)
                .append(Component.text(message.toString(), NamedTextColor.YELLOW)));
    }

    /**
     * 预测附魔书的处理结果（与 removeBugEnchantments 逻辑保持一致）
     * 用于 /bugenchanttest 命令展示，便于验证配置与识别逻辑。
     */
    private String predictEnchantedBookAction(int bugCount, int totalEnchants, boolean isCustom) {
        if (bugCount == 0) {
            if (totalEnchants == 0 && removeEmptyEnchantedBook && !isCustom) {
                return "删除整本书（空附魔书）";
            }
            return "不处理";
        }
        if (bugCount == totalEnchants) {
            if (removeEnchantedBookWhenAllBug && !isCustom) {
                return "删除整本书（全为异常附魔）";
            }
            return "移除异常附魔（会产生空附魔书）";
        }
        return "移除异常附魔（保留正常附魔）";
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
                    ItemStack item = event.getItem().getItemStack();
                    RemovalResult result = removeBugEnchantments(item);
                    if (result == RemovalResult.REMOVED) {
                        // 整本书被删除：取消拾取并移除地面实体
                        event.setCancelled(true);
                        event.getItem().remove();
                        sendActionBarSafe(player);
                    } else if (result == RemovalResult.MODIFIED) {
                        // 部分异常附魔被移除：回写更新后的物品到地面实体，允许正常拾取
                        event.getItem().setItemStack(item);
                        sendActionBarSafe(player);
                    }
                }
            }
        }, this);

        // 玩家交互事件
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
                ItemStack item = event.getItem();
                if (item == null) return;
                // 复制一份进行处理，避免直接修改事件快照导致不一致
                ItemStack copy = item.clone();
                RemovalResult result = removeBugEnchantments(copy);
                if (result == RemovalResult.REMOVED) {
                    event.setCancelled(true);
                    event.getPlayer().getInventory().setItemInMainHand(null);
                    sendActionBarSafe(event.getPlayer());
                } else if (result == RemovalResult.MODIFIED) {
                    event.setCancelled(true);
                    event.getPlayer().getInventory().setItemInMainHand(copy);
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
            RemovalResult result = removeBugEnchantments(inventory.getItem(i));
            if (result != RemovalResult.NONE) {
                removed++;
                if (logRemovals) {
                    logRemoval(playerName + "的主背包", i, result);
                }
            }
        }

        // 检查副手
        RemovalResult offhandResult = removeBugEnchantments(player.getInventory().getItemInOffHand());
        if (offhandResult != RemovalResult.NONE) {
            removed++;
            if (logRemovals) {
                logRemoval(playerName + "的副手", -1, offhandResult);
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
    private void logRemoval(String location, int slot, RemovalResult result) {
        String slotStr = slot >= 0 ? "槽位 " + slot : "副手";
        String action = result == RemovalResult.REMOVED ? "删除物品" : "清除异常附魔";
        getLogger().info(logPrefix + " " + action + " - 位置: " + location + ", " + slotStr);
    }
}
