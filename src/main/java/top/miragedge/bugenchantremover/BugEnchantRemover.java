package top.miragedge.bugenchantremover;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
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

    /** UberEnchant PDC 顶级容器 key（普通物品） */
    private static final NamespacedKey UE_ITEM_KEY = new NamespacedKey("uberenchant", "uberenchantment");
    /** UberEnchant PDC 顶级容器 key（附魔书存储） */
    private static final NamespacedKey UE_BOOK_KEY = new NamespacedKey("uberenchant", "storeduberenchantment");
    /** UberEnchant PDC 嵌套结构中的 level 子 key */
    private static final NamespacedKey UE_LEVEL_KEY = new NamespacedKey("uberenchant", "level");
    /** UberEnchant 顶级容器集合，用于 isCustomItem 排除 */
    private static final Set<NamespacedKey> UE_TOP_KEYS = Set.of(UE_ITEM_KEY, UE_BOOK_KEY);

    /** 预计算的小写关键词，避免每次匹配时重复 toLowerCase */
    private final Set<String> lowerEnchantIdKeywords = new HashSet<>();
    private final Set<String> lowerTranslationKeyKeywords = new HashSet<>();
    private long checkInterval;
    private boolean logRemovals;
    private boolean logKeywordMatches;
    private boolean removeEnchantedBookWhenAllBug;
    private boolean removeEmptyEnchantedBook;
    private boolean protectCustomItems;
    /** 清空 UberEnchant PDC 时同步清理其添加的发光残留 */
    private boolean cleanUberEnchantGlint;
    /** 清理异常附魔时同步清理匹配关键词的 Lore 行 */
    private boolean cleanLore;
    /** Lore 清理关键词（小写预计算） */
    private final Set<String> cleanLoreKeywords = new HashSet<>();

    // 消息配置
    private Component actionbarMessage;
    private String logPrefix;

    // 服务端类型检测
    private final boolean isFolia = detectFolia();
    private BukkitTask checkTask;

    // ItemMeta.setEnchantmentGlintOverride 反射缓存（Paper 1.20.5+ API，旧版本不存在）
    private volatile Method glintOverrideMethod;
    private volatile boolean glintOverrideChecked = false;

    /**
     * 检测是否为 Folia 服务端
     * Folia 特有的 ThreadedRegionizer 类只存在于 Folia 服务端中
     */
    private boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.ThreadedRegionizer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onEnable() {
        getLogger().info("BugEnchantRemover 插件已启用");

        loadConfig();
        startCheckTask();
        setupCommands();
        setupEventListeners();

        getLogger().info("检测到服务端类型: " + (isFolia ? "Folia" : "Paper") +
                " | 调度模式: " + (isFolia ? "同步" : "异步"));
    }

    @Override
    public void onDisable() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
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
        cleanUberEnchantGlint = getConfig().getBoolean("clean-uber-enchant-glint", true);
        cleanLore = getConfig().getBoolean("clean-lore", false);
        cleanLoreKeywords.clear();
        for (String kw : getConfig().getStringList("clean-lore-keywords")) {
            if (kw != null && !kw.isEmpty()) {
                cleanLoreKeywords.add(kw.toLowerCase(Locale.ROOT));
            }
        }

        // 加载消息配置
        String actionbarText = getConfig().getString("messages.actionbar", "已自动清除异常附魔");
        actionbarMessage = Component.text(actionbarText, NamedTextColor.RED);
        logPrefix = getConfig().getString("messages.log-prefix", "[BugEnchantRemover]");

        getLogger().info("已加载 " + lowerEnchantIdKeywords.size() + " 个附魔ID关键词");
        getLogger().info("已加载 " + lowerTranslationKeyKeywords.size() + " 个翻译键关键词");
        getLogger().info("检查间隔: " + checkInterval + " tick");
        getLogger().info("全异常附魔时移除整本附魔书: " + boolStr(removeEnchantedBookWhenAllBug));
        getLogger().info("移除空附魔书: " + boolStr(removeEmptyEnchantedBook));
        getLogger().info("保护自定义物品(GUI道具): " + boolStr(protectCustomItems));
        getLogger().info("清理 UberEnchant 发光残留: " + boolStr(cleanUberEnchantGlint));
        getLogger().info("清理 Lore 残留: " + boolStr(cleanLore));
        if (cleanLore) {
            getLogger().info("已加载 " + cleanLoreKeywords.size() + " 个 Lore 清理关键词");
        }
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
     * 异步任务中只能访问世界数据，不能安全访问玩家对象
     * 因此检查任务本身在主线程执行，但使用异步调度来定期触发
     */
    private void startPaperTask() {
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> {
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
                StackTraceElement[] trace = e.getStackTrace();
                if (trace != null && trace.length > 0) {
                    getLogger().info("堆栈跟踪: " + trace[0].toString());
                }
            }
        }
    }

    /**
     * 检查玩家背包和打开的容器，移除异常附魔
     * 注意：当玩家未打开任何容器时，getTopInventory 返回 CRAFTING 类型，已跳过避免重复扫描
     */
    private void checkPlayerInventory(Player player) {
        if (player == null || !player.isOnline()) return;

        String playerName = player.getName();
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

        // 检查玩家打开的容器（仅在确实打开了外部容器时才扫描，避免重复扫描玩家合成表）
        Inventory topInventory = player.getOpenInventory().getTopInventory();
        if (topInventory != null && topInventory.getType() != InventoryType.CRAFTING) {
            int topSize = topInventory.getSize();
            for (int i = 0; i < topSize; i++) {
                if (removeBugEnchantments(topInventory.getItem(i)) != RemovalResult.NONE) {
                    changed = true;
                    if (logRemovals) {
                        logRemoval(playerName + "打开的容器", i, RemovalResult.MODIFIED);
                    }
                }
            }
        }

        if (changed) {
            sendActionBarSafe(player);
        }
    }

    /**
     * 安全发送 actionbar 消息
     */
    private void sendActionBarSafe(Player player) {
        if (player.isOnline()) {
            player.sendActionBar(actionbarMessage);
        }
    }

    /**
     * 移除物品上的异常附魔（标准 Bukkit 附魔 + UberEnchant PDC 附魔）
     * 返回值语义：
     *   NONE     - 无异常附魔或未触发处理
     *   MODIFIED - 已移除部分异常附魔，物品仍存在（调用方需回写更新后的 ItemStack）
     *   REMOVED  - 整本书被删除（setAmount(0)），调用方应清理对应槽位/实体
     */
    private RemovalResult removeBugEnchantments(ItemStack item) {
        if (item == null) return RemovalResult.NONE;
        Material type = item.getType();
        if (type == Material.AIR) return RemovalResult.NONE;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return RemovalResult.NONE;

        boolean isBook = type == Material.ENCHANTED_BOOK;
        boolean isBookMeta = meta instanceof EnchantmentStorageMeta;

        BugEnchantResult bugs = findAllBugEnchantments(item);

        // 标准附魔总数（用于"全异常书"判定）
        int standardCount;
        if (isBookMeta) {
            standardCount = ((EnchantmentStorageMeta) meta).getStoredEnchants().size();
        } else {
            standardCount = meta.getEnchants().size();
        }
        int totalCount = standardCount + bugs.uberBugs.size();
        int bugCount = bugs.totalBugCount();

        boolean isCustom = protectCustomItems && isCustomItem(meta);

        if (isBook) {
            // === 附魔书逻辑 ===
            if (bugCount == 0) {
                // 没有异常附魔
                if (removeEmptyEnchantedBook && totalCount == 0 && !isCustom) {
                    item.setAmount(0);
                    return RemovalResult.REMOVED;
                }
                return RemovalResult.NONE;
            }

            // 存在异常附魔
            if (bugCount == totalCount) {
                // 全为异常附魔
                if (removeEnchantedBookWhenAllBug && !isCustom) {
                    item.setAmount(0);
                    return RemovalResult.REMOVED;
                }
            }

            // 自定义物品保护：不修改 meta，避免破坏 GUI 道具数据
            if (protectCustomItems && isCustom) {
                return RemovalResult.NONE;
            }

            // 移除异常附魔（标准 + UberEnchant PDC）
            boolean modified = false;
            if (isBookMeta) {
                EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) meta;
                for (Enchantment enchant : bugs.standardBugs.keySet()) {
                    if (bookMeta.removeStoredEnchant(enchant)) {
                        modified = true;
                    }
                }
            } else {
                for (Enchantment enchant : bugs.standardBugs.keySet()) {
                    if (meta.removeEnchant(enchant)) {
                        modified = true;
                    }
                }
            }

            NamespacedKey ueContainerKey = isBookMeta ? UE_BOOK_KEY : UE_ITEM_KEY;
            for (NamespacedKey key : bugs.uberBugs.keySet()) {
                if (removeUberEnchantment(meta, key, ueContainerKey)) {
                    modified = true;
                }
            }

            // 清理匹配关键词的 Lore 行（仅在被修改时触发）
            if (modified) {
                cleanLoreByKeywords(meta);
                item.setItemMeta(meta);
                return RemovalResult.MODIFIED;
            }
            return RemovalResult.NONE;
        } else {
            // === 普通物品逻辑 ===
            if (bugCount == 0) return RemovalResult.NONE;

            // 自定义物品保护：不修改 meta
            if (protectCustomItems && isCustom) {
                return RemovalResult.NONE;
            }

            boolean modified = false;
            for (Enchantment enchant : bugs.standardBugs.keySet()) {
                if (meta.removeEnchant(enchant)) {
                    modified = true;
                }
            }

            for (NamespacedKey key : bugs.uberBugs.keySet()) {
                if (removeUberEnchantment(meta, key, UE_ITEM_KEY)) {
                    modified = true;
                }
            }

            // 清理匹配关键词的 Lore 行（仅在被修改时触发）
            if (modified) {
                cleanLoreByKeywords(meta);
                item.setItemMeta(meta);
                return RemovalResult.MODIFIED;
            }
            return RemovalResult.NONE;
        }
    }

    /**
     * 检测物品上的所有异常附魔（标准 Bukkit + UberEnchant PDC）
     */
    private BugEnchantResult findAllBugEnchantments(ItemStack item) {
        BugEnchantResult result = new BugEnchantResult();
        if (item == null || !item.hasItemMeta()) return result;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return result;

        NamespacedKey ueContainerKey;
        Map<Enchantment, Integer> standardEnchants;
        if (meta instanceof EnchantmentStorageMeta bookMeta) {
            standardEnchants = bookMeta.getStoredEnchants();
            ueContainerKey = UE_BOOK_KEY;
        } else {
            standardEnchants = meta.getEnchants();
            ueContainerKey = UE_ITEM_KEY;
        }

        // 1. 标准附魔
        for (Map.Entry<Enchantment, Integer> entry : standardEnchants.entrySet()) {
            if (isBugEnchantment(entry.getKey())) {
                result.standardBugs.put(entry.getKey(), entry.getValue());
            }
        }

        // 2. UberEnchant PDC 附魔
        Map<NamespacedKey, Integer> ueEnchants = getUberEnchantments(meta, ueContainerKey);
        for (Map.Entry<NamespacedKey, Integer> entry : ueEnchants.entrySet()) {
            NamespacedKey key = entry.getKey();
            if (matchesAnyKeyword(key.toString(), key.getNamespace(), key.getKey())) {
                result.uberBugs.put(key, entry.getValue());
            }
        }

        return result;
    }

    /**
     * 从 PDC 读取 UberEnchant 附魔
     * 兼容性：UberEnchant 1.20.4+ 同时支持两种数据结构
     *   结构 A（直接 INTEGER）：data.get(enchantKey, INTEGER) == level
     *   结构 B（嵌套 TAG_CONTAINER）：data.get(enchantKey, TAG_CONTAINER).get(level, INTEGER)
     * 来源验证：UberUtils.getCustomMap (master 分支)
     *
     * @param meta 物品 meta
     * @param containerKey 顶级容器 key（UE_ITEM_KEY 或 UE_BOOK_KEY）
     */
    private Map<NamespacedKey, Integer> getUberEnchantments(ItemMeta meta, NamespacedKey containerKey) {
        Map<NamespacedKey, Integer> result = new HashMap<>();
        if (meta == null) return result;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(containerKey, PersistentDataType.TAG_CONTAINER)) return result;

        PersistentDataContainer ueData = pdc.get(containerKey, PersistentDataType.TAG_CONTAINER);
        if (ueData == null) return result;

        for (NamespacedKey key : ueData.getKeys()) {
            Integer level = readUberEnchantLevel(ueData, key);
            if (level != null) {
                result.put(key, level);
            }
        }
        return result;
    }

    /**
     * 读取单个 UberEnchant 附魔的等级，兼容两种 PDC 结构
     */
    private Integer readUberEnchantLevel(PersistentDataContainer ueData, NamespacedKey enchantKey) {
        // 结构 A：直接 INTEGER
        if (ueData.has(enchantKey, PersistentDataType.INTEGER)) {
            return ueData.get(enchantKey, PersistentDataType.INTEGER);
        }
        // 结构 B：嵌套 TAG_CONTAINER，内部 level 子键
        if (ueData.has(enchantKey, PersistentDataType.TAG_CONTAINER)) {
            PersistentDataContainer enchData = ueData.get(enchantKey, PersistentDataType.TAG_CONTAINER);
            if (enchData != null && enchData.has(UE_LEVEL_KEY, PersistentDataType.INTEGER)) {
                return enchData.get(UE_LEVEL_KEY, PersistentDataType.INTEGER);
            }
        }
        return null;
    }

    /**
     * 从 PDC 移除单个 UberEnchant 附魔
     *
     * @param meta 物品 meta
     * @param enchantKey 附魔的 NamespacedKey（如 uberenchant:blindness）
     * @param containerKey 顶级容器 key（UE_ITEM_KEY 或 UE_BOOK_KEY）
     * @return true 如果有修改
     */
    private boolean removeUberEnchantment(ItemMeta meta, NamespacedKey enchantKey, NamespacedKey containerKey) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(containerKey, PersistentDataType.TAG_CONTAINER)) return false;

        PersistentDataContainer ueData = pdc.get(containerKey, PersistentDataType.TAG_CONTAINER);
        if (ueData == null) return false;

        // 检查是否存在该附魔（兼容两种结构）
        boolean hasDirect = ueData.has(enchantKey, PersistentDataType.INTEGER);
        boolean hasNested = ueData.has(enchantKey, PersistentDataType.TAG_CONTAINER);
        if (!hasDirect && !hasNested) return false;

        ueData.remove(enchantKey);
        // 回写以确保修改生效（部分实现可能是只读视图）
        pdc.set(containerKey, PersistentDataType.TAG_CONTAINER, ueData);

        // 如果整个 UberEnchant 容器空了，移除顶级 key 并清理发光
        if (ueData.getKeys().isEmpty()) {
            pdc.remove(containerKey);
            if (cleanUberEnchantGlint) {
                setEnchantmentGlintSafe(meta, null);
            }
        }
        return true;
    }

    /**
     * 清空 UberEnchant 的所有附魔（同时清空普通物品容器与附魔书容器）
     * 通常用于强制清理场景
     */
    @SuppressWarnings("unused")
    private boolean clearAllUberEnchantments(ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        boolean changed = false;
        if (pdc.has(UE_ITEM_KEY, PersistentDataType.TAG_CONTAINER)) {
            pdc.remove(UE_ITEM_KEY);
            changed = true;
        }
        if (pdc.has(UE_BOOK_KEY, PersistentDataType.TAG_CONTAINER)) {
            pdc.remove(UE_BOOK_KEY);
            changed = true;
        }
        if (changed && cleanUberEnchantGlint) {
            setEnchantmentGlintSafe(meta, null);
        }
        return changed;
    }

    /**
     * 兼容性封装：调用 ItemMeta.setEnchantmentGlintOverride（Paper 1.20.5+ API）
     * 旧版本 Paper 服务端不支持此方法，通过反射查找，失败则静默跳过。
     * 首次调用查找方法并缓存，后续直接复用。
     */
    private void setEnchantmentGlintSafe(ItemMeta meta, Boolean value) {
        if (!glintOverrideChecked) {
            synchronized (this) {
                if (!glintOverrideChecked) {
                    try {
                        glintOverrideMethod = ItemMeta.class.getMethod("setEnchantmentGlintOverride", Boolean.class);
                    } catch (NoSuchMethodException ignored) {
                        glintOverrideMethod = null;
                    }
                    glintOverrideChecked = true;
                }
            }
        }
        if (glintOverrideMethod == null) return;
        try {
            glintOverrideMethod.invoke(meta, value);
        } catch (Exception ignored) {
            // 反射调用失败（罕见，通常为安全限制），静默忽略
        }
    }

    /**
     * 按关键词清理 Lore 行
     * 遍历 Lore 每一行，将纯文本包含任意关键词的行移除。
     * 清理后若 Lore 为空则清除 Lore，否则回写过滤后的 Lore。
     *
     * @param meta 物品 meta（将被直接修改）
     */
    private void cleanLoreByKeywords(ItemMeta meta) {
        if (!cleanLore || cleanLoreKeywords.isEmpty()) return;
        if (!meta.hasLore()) return;

        List<Component> lore = meta.lore();
        if (lore == null || lore.isEmpty()) return;

        List<Component> newLore = new ArrayList<>(lore.size());
        boolean modified = false;

        for (Component line : lore) {
            String plainText = PlainTextComponentSerializer.plainText().serialize(line);
            String lowerText = plainText.toLowerCase(Locale.ROOT);

            boolean shouldRemove = false;
            for (String keyword : cleanLoreKeywords) {
                if (lowerText.contains(keyword)) {
                    shouldRemove = true;
                    if (logKeywordMatches) {
                        getLogger().info(logPrefix + " 匹配Lore关键词: " + keyword + " -> " + plainText);
                    }
                    break;
                }
            }

            if (shouldRemove) {
                modified = true;
            } else {
                newLore.add(line);
            }
        }

        if (modified) {
            if (newLore.isEmpty()) {
                meta.lore(null);
            } else {
                meta.lore(newLore);
            }
        }
    }

    /**
     * 统计物品 Lore 中将被清理的行数
     * 用于 /bugenchanttest 命令展示 Lore 清理预测
     */
    private int countLoreLinesToRemove(ItemMeta meta) {
        if (!cleanLore || cleanLoreKeywords.isEmpty() || !meta.hasLore()) return 0;

        List<Component> lore = meta.lore();
        if (lore == null || lore.isEmpty()) return 0;

        int count = 0;
        for (Component line : lore) {
            String lowerText = PlainTextComponentSerializer.plainText().serialize(line).toLowerCase(Locale.ROOT);
            for (String keyword : cleanLoreKeywords) {
                if (lowerText.contains(keyword)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }



    /**
     * 检查物品是否为自定义物品 / GUI 道具
     * 命中以下任意一项即视为自定义物品，避免被当作空书误删或被修改 meta：
     *   - 自定义显示名称
     *   - Lore 描述（仅当 PDC 不只是 UberEnchant 数据时）
     *   - CustomModelData 自定义模型数据
     *   - 修复成本(铁砧) / 不可破坏标记
     *   - ItemFlags / 自定义属性修饰符
     *   - PersistentDataContainer 中除 UberEnchant 外的自定义数据
     *
     * UberEnchant 物品优化：当 PDC 中只有 UberEnchant 数据时，忽略 Lore 和 ItemFlags 检查
     * （这些通常由 UberEnchant 自动添加，不代表物品本身是 GUI 道具）
     */
    private boolean isCustomItem(ItemMeta meta) {
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        // 判断是否为"纯 UberEnchant 物品"（PDC 中只有 UberEnchant 顶级 key）
        boolean onlyUberEnchantPdc = false;
        if (!pdc.isEmpty()) {
            onlyUberEnchantPdc = true;
            for (NamespacedKey key : pdc.getKeys()) {
                if (!UE_TOP_KEYS.contains(key)) {
                    onlyUberEnchantPdc = false;
                    break;
                }
            }
        }

        // 自定义显示名（始终检查，UberEnchant 不会修改 displayName）
        if (meta.hasDisplayName()) return true;

        if (!onlyUberEnchantPdc) {
            // 普通物品：Lore 和 ItemFlags 视为 GUI 道具特征
            if (meta.hasLore()) return true;
            if (!meta.getItemFlags().isEmpty()) return true;
        }
        // 纯 UberEnchant 物品：跳过 Lore/ItemFlags 检查（UberEnchant 自动添加的）

        // 自定义模型数据（GUI道具最常用）
        if (meta.hasCustomModelData()) return true;

        // 修复成本（铁砧修改痕迹）
        if (meta instanceof Repairable repairable && repairable.hasRepairCost()) return true;

        // 不可破坏标记
        if (meta.isUnbreakable()) return true;

        // 自定义属性修饰符
        if (meta.hasAttributeModifiers()) return true;

        // PDC：排除 UberEnchant 数据后，剩余有数据则视为自定义物品
        if (!pdc.isEmpty()) {
            for (NamespacedKey key : pdc.getKeys()) {
                if (!UE_TOP_KEYS.contains(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从附魔Map中筛选出有问题的附魔（仅标准 Bukkit 附魔，不含 UberEnchant PDC）
     * 保留供 /bugenchanttest 等场景使用，主流程已由 findAllBugEnchantments 统一处理
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

        // 预先转换为小写
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
                if (!sender.hasPermission("bugenchantremover.reload")) {
                    sender.sendMessage(Component.text("你没有执行此命令的权限", NamedTextColor.RED));
                    return false;
                }
                try {
                    loadConfig();
                } catch (Exception e) {
                    sender.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.RED)
                            .append(Component.text("重载失败: " + e.getMessage(), NamedTextColor.RED)));
                    getLogger().warning("配置重载失败: " + e);
                    return false;
                }
                sender.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.GREEN)
                        .append(Component.text("配置已重载", NamedTextColor.YELLOW)));
                sender.sendMessage(Component.text("  关键词: ", NamedTextColor.GRAY)
                        .append(Component.text(lowerEnchantIdKeywords.size() + " 个ID / ", NamedTextColor.AQUA))
                        .append(Component.text(lowerTranslationKeyKeywords.size() + " 个翻译键", NamedTextColor.AQUA)));
                sender.sendMessage(Component.text("  间隔: ", NamedTextColor.GRAY)
                        .append(Component.text(checkInterval + " tick", NamedTextColor.AQUA)));
                sender.sendMessage(Component.text("  选项: ", NamedTextColor.GRAY)
                        .append(Component.text(String.format(
                                "删全异常书=%s 删空书=%s 保护自定义=%s 清发光=%s 清Lore=%s",
                                boolStr(removeEnchantedBookWhenAllBug),
                                boolStr(removeEmptyEnchantedBook),
                                boolStr(protectCustomItems),
                                boolStr(cleanUberEnchantGlint),
                                boolStr(cleanLore)),
                                NamedTextColor.AQUA)));
                if (cleanLore) {
                    sender.sendMessage(Component.text("  Lore关键词: ", NamedTextColor.GRAY)
                            .append(Component.text(cleanLoreKeywords.size() + " 个", NamedTextColor.AQUA)));
                }
                return true;
            });
        }

        var scanCmd = this.getCommand("bugenchantscan");
        if (scanCmd != null) {
            scanCmd.setExecutor((sender, command, label, args) -> {
                if (!sender.hasPermission("bugenchantremover.scan")) {
                    sender.sendMessage(Component.text("你没有执行此命令的权限", NamedTextColor.RED));
                    return false;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("该命令只能由玩家执行", NamedTextColor.RED));
                    return false;
                }
                ScanStats stats = scanPlayerInventoryDetailed(player);
                sender.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.GREEN)
                        .append(Component.text("扫描完成", NamedTextColor.YELLOW)));
                sender.sendMessage(Component.text("  扫描槽位: ", NamedTextColor.GRAY)
                        .append(Component.text(stats.scannedSlots + " 个", NamedTextColor.AQUA)));
                sender.sendMessage(Component.text("  修改物品: ", NamedTextColor.GRAY)
                        .append(Component.text(stats.modifiedItems + " 个", NamedTextColor.AQUA)));
                sender.sendMessage(Component.text("  删除物品: ", NamedTextColor.GRAY)
                        .append(Component.text(stats.removedItems + " 个", NamedTextColor.AQUA)));
                if (stats.modifiedItems == 0 && stats.removedItems == 0) {
                    sender.sendMessage(Component.text("  未发现异常附魔", NamedTextColor.GRAY));
                }
                return true;
            });
        }

        var testCmd = this.getCommand("bugenchanttest");
        if (testCmd != null) {
            testCmd.setExecutor((sender, command, label, args) -> {
                if (!sender.hasPermission("bugenchantremover.test")) {
                    sender.sendMessage(Component.text("你没有执行此命令的权限", NamedTextColor.RED));
                    return false;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("该命令只能由玩家执行", NamedTextColor.RED));
                    return false;
                }
                testPlayerEnchantments(player);
                return true;
            });
        }
    }

    /**
     * 测试玩家手持物品的附魔详情
     * 支持附魔书与普通物品，输出标准附魔 + UberEnchant PDC 附魔信息，
     * 以及预测的处理结果，便于验证配置与识别逻辑。
     */
    private void testPlayerEnchantments(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item.getType() == Material.AIR) {
            player.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.RED)
                    .append(Component.text("请手持一个物品", NamedTextColor.YELLOW)));
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            player.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.RED)
                    .append(Component.text("物品无 ItemMeta", NamedTextColor.YELLOW)));
            return;
        }

        boolean isBook = item.getType() == Material.ENCHANTED_BOOK;
        boolean isBookMeta = meta instanceof EnchantmentStorageMeta;

        BugEnchantResult bugs = findAllBugEnchantments(item);

        Map<Enchantment, Integer> standardEnchants;
        NamespacedKey ueContainerKey;
        if (isBookMeta) {
            standardEnchants = ((EnchantmentStorageMeta) meta).getStoredEnchants();
            ueContainerKey = UE_BOOK_KEY;
        } else {
            standardEnchants = meta.getEnchants();
            ueContainerKey = UE_ITEM_KEY;
        }
        int standardCount = standardEnchants.size();
        int ueCount = getUberEnchantments(meta, ueContainerKey).size();
        int totalCount = standardCount + ueCount;
        int bugCount = bugs.totalBugCount();

        boolean isCustom = protectCustomItems && isCustomItem(meta);

        StringBuilder message = new StringBuilder();
        message.append("=== 物品信息 ===\n");
        message.append(String.format("类型: %s%s\n", item.getType(), isBook ? " (附魔书)" : ""));
        message.append(String.format("附魔总数: %d（标准 %d + UberEnchant PDC %d）\n", totalCount, standardCount, ueCount));
        message.append(String.format("异常附魔数: %d（标准 %d + UberEnchant %d）\n",
                bugCount, bugs.standardBugs.size(), bugs.uberBugs.size()));
        message.append(String.format("识别为自定义物品(GUI道具): %s\n", isCustom ? "是" : "否"));
        message.append('\n');

        if (!standardEnchants.isEmpty()) {
            message.append("--- 标准附魔 ---\n");
            for (Map.Entry<Enchantment, Integer> entry : standardEnchants.entrySet()) {
                Enchantment enchant = entry.getKey();
                int level = entry.getValue();
                String enchantId = enchant.getKey().toString();
                String enchantNamespace = enchant.getKey().getNamespace();
                String enchantKey = enchant.getKey().getKey();
                String translationKey = "enchantment." + enchantNamespace + "." + enchantKey;
                boolean isBug = bugs.standardBugs.containsKey(enchant);

                message.append(String.format("附魔ID: %s\n", enchantId));
                message.append(String.format("  命名空间: %s | 键名: %s\n", enchantNamespace, enchantKey));
                message.append(String.format("  翻译键: %s | 等级: %d\n", translationKey, level));
                message.append(String.format("  检测为Bug: %s\n\n", isBug ? "是" : "否"));
            }
        }

        Map<NamespacedKey, Integer> ueEnchants = getUberEnchantments(meta, ueContainerKey);
        if (!ueEnchants.isEmpty()) {
            message.append("--- UberEnchant PDC 附魔 ---\n");
            for (Map.Entry<NamespacedKey, Integer> entry : ueEnchants.entrySet()) {
                NamespacedKey key = entry.getKey();
                int level = entry.getValue();
                String enchantId = key.toString();
                String translationKey = "enchantment." + key.getNamespace() + "." + key.getKey();
                boolean isBug = bugs.uberBugs.containsKey(key);

                message.append(String.format("附魔ID: %s\n", enchantId));
                message.append(String.format("  命名空间: %s | 键名: %s\n", key.getNamespace(), key.getKey()));
                message.append(String.format("  翻译键: %s | 等级: %d\n", translationKey, level));
                message.append(String.format("  检测为Bug: %s\n\n", isBug ? "是" : "否"));
            }
        } else if (isBook) {
            message.append("--- UberEnchant PDC 附魔 ---\n（无）\n\n");
        }

        // Lore 信息与清理预测
        int loreLinesToRemove = countLoreLinesToRemove(meta);
        if (meta.hasLore()) {
            List<Component> lore = meta.lore();
            message.append("--- Lore ---\n");
            if (lore != null && !lore.isEmpty()) {
                message.append(String.format("Lore 总行数: %d\n", lore.size()));
                if (cleanLore) {
                    message.append(String.format("将被清理: %d 行（关键词 %d 个）\n", loreLinesToRemove, cleanLoreKeywords.size()));
                } else {
                    message.append("Lore 清理: 未启用\n");
                }
            } else {
                message.append("（无）\n");
            }
            message.append('\n');
        }

        // 预测处理结果
        String prediction;
        if (isBook) {
            prediction = predictEnchantedBookAction(bugCount, totalCount, isCustom);
        } else {
            if (bugCount == 0) {
                prediction = "不处理";
            } else if (protectCustomItems && isCustom) {
                prediction = "保护自定义物品（不修改）";
            } else {
                prediction = "移除异常附魔";
            }
        }
        // 附加 Lore 清理提示
        if (cleanLore && loreLinesToRemove > 0 && !prediction.contains("不处理") && !prediction.contains("保护自定义")) {
            prediction += " + 清理 Lore " + loreLinesToRemove + " 行";
        }
        message.append(String.format("预测处理结果: %s\n", prediction));

        player.sendMessage(Component.text("[BugEnchantRemover] ", NamedTextColor.GREEN)
                .append(Component.text(message.toString(), NamedTextColor.YELLOW)));
    }

    /**
     * 预测附魔书的处理结果（与 removeBugEnchantments 逻辑保持一致）
     * 用于 /bugenchanttest 命令展示
     */
    private String predictEnchantedBookAction(int bugCount, int totalEnchants, boolean isCustom) {
        if (bugCount == 0) {
            if (totalEnchants == 0 && removeEmptyEnchantedBook && !isCustom) {
                return "删除整本书（空附魔书）";
            }
            return "不处理";
        }
        if (protectCustomItems && isCustom) {
            return "保护自定义物品（不修改）";
        }
        if (bugCount == totalEnchants) {
            if (removeEnchantedBookWhenAllBug) {
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
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onEntityPickupItem(EntityPickupItemEvent event) {
                if (!(event.getEntity() instanceof Player player)) return;
                ItemStack item = event.getItem().getItemStack();
                RemovalResult result = removeBugEnchantments(item);
                if (result == RemovalResult.REMOVED) {
                    event.setCancelled(true);
                    event.getItem().remove();
                    sendActionBarSafe(player);
                } else if (result == RemovalResult.MODIFIED) {
                    event.getItem().setItemStack(item);
                    sendActionBarSafe(player);
                }
            }
        }, this);

        // 玩家交互事件
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerInteract(PlayerInteractEvent event) {
                ItemStack item = event.getItem();
                if (item == null) return;
                // 物理交互（踩踏压力板等）无明确手，跳过避免误改主手
                EquipmentSlot hand = event.getHand();
                if (hand == null) return;

                ItemStack copy = item.clone();
                RemovalResult result = removeBugEnchantments(copy);
                if (result == RemovalResult.NONE) return;

                event.setCancelled(true);
                Player player = event.getPlayer();
                ItemStack resultItem = (result == RemovalResult.REMOVED) ? null : copy;
                if (hand == EquipmentSlot.HAND) {
                    player.getInventory().setItemInMainHand(resultItem);
                } else {
                    player.getInventory().setItemInOffHand(resultItem);
                }
                sendActionBarSafe(player);
            }
        }, this);

        // 玩家关闭容器事件
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onInventoryClose(InventoryCloseEvent event) {
                if (!(event.getPlayer() instanceof Player player)) return;
                schedulePlayerCheck(player, 1L);
            }
        }, this);
    }

    /**
     * 调度玩家库存检查（兼容 Folia）
     * Paper: runTaskLater 延迟 1 tick
     * Folia: 直接在当前区域线程同步执行（事件已在玩家区域线程）
     */
    private void schedulePlayerCheck(Player player, long delayTicks) {
        if (isFolia) {
            try {
                if (player.isOnline()) {
                    checkPlayerInventory(player);
                }
            } catch (Throwable t) {
                getLogger().warning("Folia 容器关闭检查失败: " + t.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) {
                    checkPlayerInventory(player);
                }
            }, delayTicks);
        }
    }

    /**
     * 手动扫描玩家背包
     * 注意: 手动扫描只检查玩家背包和副手，不检查打开的容器
     */
    private ScanStats scanPlayerInventoryDetailed(Player player) {
        ScanStats stats = new ScanStats();
        String playerName = player.getName();

        // 检查主背包
        Inventory inventory = player.getInventory();
        int size = inventory.getSize();
        stats.scannedSlots = size + 1;  // 主背包 + 副手

        for (int i = 0; i < size; i++) {
            RemovalResult result = removeBugEnchantments(inventory.getItem(i));
            if (result == RemovalResult.MODIFIED) {
                stats.modifiedItems++;
                if (logRemovals) logRemoval(playerName + "的主背包", i, result);
            } else if (result == RemovalResult.REMOVED) {
                stats.removedItems++;
                if (logRemovals) logRemoval(playerName + "的主背包", i, result);
            }
        }

        // 检查副手
        RemovalResult offhandResult = removeBugEnchantments(player.getInventory().getItemInOffHand());
        if (offhandResult == RemovalResult.MODIFIED) {
            stats.modifiedItems++;
            if (logRemovals) logRemoval(playerName + "的副手", -1, offhandResult);
        } else if (offhandResult == RemovalResult.REMOVED) {
            stats.removedItems++;
            if (logRemovals) logRemoval(playerName + "的副手", -1, offhandResult);
        }

        if (stats.modifiedItems > 0 || stats.removedItems > 0) {
            sendActionBarSafe(player);
        }

        return stats;
    }

    /**
     * 记录移除日志
     */
    private void logRemoval(String location, int slot, RemovalResult result) {
        String slotStr = slot >= 0 ? "槽位 " + slot : "副手";
        String action = result == RemovalResult.REMOVED ? "删除物品" : "清除异常附魔";
        getLogger().info(logPrefix + " " + action + " - 位置: " + location + ", " + slotStr);
    }

    private String boolStr(boolean v) {
        return v ? "开启" : "关闭";
    }

    /** 扫描结果统计 */
    private static final class ScanStats {
        int scannedSlots = 0;
        int modifiedItems = 0;
        int removedItems = 0;
    }

    /** 附魔检测结果：标准 Bukkit 附魔 + UberEnchant PDC 附魔 */
    private static final class BugEnchantResult {
        final Map<Enchantment, Integer> standardBugs = new HashMap<>();
        final Map<NamespacedKey, Integer> uberBugs = new HashMap<>();

        boolean isEmpty() {
            return standardBugs.isEmpty() && uberBugs.isEmpty();
        }

        int totalBugCount() {
            return standardBugs.size() + uberBugs.size();
        }
    }
}
