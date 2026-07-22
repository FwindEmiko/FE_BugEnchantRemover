# BugEnchantRemover v1.3 — UberEnchant 兼容 + 命令系统优化报告

> 模型：GLM-5.2 · 审查日期：2026-07-21 · 编译验证：BUILD SUCCESS

---

## 一、执行摘要

本次任务包含三部分：
1. **审查 UberEnchant 兼容方案**（去 GitHub 核对 UberUtils / PDCItemUtils / PDCUtils / UberMeta 源码）
2. **修正方案错误并实现兼容**
3. **优化命令系统使用体验**
4. **全项目潜在问题审查与修复**

最终编译通过，未引入新依赖，保持 paper-api 1.18.2 兼容（高版本 API 通过反射访问）。

---

## 二、方案审查发现的问题（原方案书错漏）

经核对 UberEnchant master 分支源码后，发现原方案存在以下错误/疏漏：

| # | 问题 | 严重度 | 处理 |
|---|------|--------|------|
| 1 | 方案只描述了 PDC 嵌套结构（TAG_CONTAINER），但 `UberUtils.getCustomMap` 同时支持两种结构：直接 INTEGER 与嵌套 TAG_CONTAINER | **高** | 已实现双结构兼容（`readUberEnchantLevel`） |
| 2 | `NamespacedPDCKey` 类型不存在（方案拼写错误） | 中 | 改用 `NamespacedKey` |
| 3 | `UE_NAMESPACE = NamespacedKey.fromString("uberenchant")` 写法错误（会生成 `minecraft:uberenchant`） | 中 | 删除冗余常量 |
| 4 | `isBook(meta)` 方法在项目中不存在 | 中 | 改用 `meta instanceof EnchantmentStorageMeta` |
| 5 | `testPlayerEnchantments()` 方案只输出 stored，未处理普通物品 | 中 | 已支持普通物品 |
| 6 | `setEnchantmentGlintOverride` 是 Paper 1.20.5+ API，paper-api 1.18.2 jar 不存在此方法，直接调用会导致**编译失败** | **高** | 已用反射 + 双重检查缓存兼容 |
| 7 | 方案未考虑 `onInventoryClose` 在 Folia 上的兼容性（`Bukkit.getScheduler().runTaskLater` 在 Folia 会抛异常） | **高** | 已抽出 `schedulePlayerCheck`，Folia 同步执行 |
| 8 | 方案未考虑 `onPlayerInteract` 的副手问题（原代码硬编码 setItemInMainHand） | 中 | 已用 `event.getHand()` 判断 |
| 9 | 方案对 `isCustomItem()` 的 PDC 排除只考虑了"只有 1 个 key"，未处理"PDC 同时含 UberEnchant + 其他 key" | 中 | 已改为遍历所有 key，排除 UberEnchant 后再判空 |
| 10 | 方案未提及：UberEnchant 物品因 `setEnchantmentGlintOverride(true)` + Lore 添加，会被 `isCustomItem` 误判为 GUI 道具 | **高** | 已加 "纯 UberEnchant 物品" 判定，跳过 Lore/ItemFlags 检查 |
| 11 | 方案未提供"自定义物品保护"对 MODIFIED 状态的处理（原代码仅保护 REMOVED，会修改 meta 破坏 GUI 道具） | **高** | 已扩展保护：自定义物品完全不修改 meta |
| 12 | `performChecksFolia` 中 `e.getStackTrace()[0]` 可能数组越界（空堆栈场景） | 低 | 已加 `length > 0` 判断 |
| 13 | `checkPlayerInventory` 中 `getTopInventory` 在玩家未打开容器时返回 CRAFTING 类型，会重复扫描玩家自身合成表 | 中 | 已加 `InventoryType.CRAFTING` 跳过 |
| 14 | `onDisable()` 未将 `checkTask` 置 null | 低 | 已修复 |

---

## 三、修改文件清单

### 1. `src/main/java/top/miragedge/bugenchantremover/BugEnchantRemover.java`（主代码）

#### 新增常量
- `UE_ITEM_KEY` = `NamespacedKey("uberenchant", "uberenchantment")`
- `UE_BOOK_KEY` = `NamespacedKey("uberenchant", "storeduberenchantment")`
- `UE_LEVEL_KEY` = `NamespacedKey("uberenchant", "level")`
- `UE_TOP_KEYS` = `Set.of(UE_ITEM_KEY, UE_BOOK_KEY)`

#### 新增字段
- `cleanUberEnchantGlint`（配置项 `clean-uber-enchant-glint`）
- `glintOverrideMethod` / `glintOverrideChecked`（反射缓存，双重检查锁）

#### 新增方法
- `findAllBugEnchantments(ItemStack)` — 统一检测标准 + UberEnchant PDC 异常附魔
- `getUberEnchantments(ItemMeta, NamespacedKey)` — 从 PDC 读取 UberEnchant 附魔
- `readUberEnchantLevel(PersistentDataContainer, NamespacedKey)` — 兼容两种 PDC 结构
- `removeUberEnchantment(ItemMeta, NamespacedKey, NamespacedKey)` — 移除单个 PDC 附魔
- `clearAllUberEnchantments(ItemMeta)` — 清空所有 UberEnchant 数据
- `setEnchantmentGlintSafe(ItemMeta, Boolean)` — 反射调用 1.20.5+ API
- `schedulePlayerCheck(Player, long)` — Folia 兼容的延迟调度
- `boolStr(boolean)` — 中文布尔值辅助
- `scanPlayerInventoryDetailed(Player)` — 增强版扫描（返回 ScanStats）

#### 重构方法
- `removeBugEnchantments(ItemStack)` — 重构为统一处理标准 + PDC 两类附魔
- `isCustomItem(ItemMeta)` — 增加 "纯 UberEnchant 物品" 判定，排除 UberEnchant PDC
- `testPlayerEnchantments(Player)` — 支持普通物品，输出 PDC 附魔
- `predictEnchantedBookAction(...)` — 增加 "保护自定义物品" 预测
- `performChecksFolia()` — 修复堆栈数组越界
- `checkPlayerInventory(Player)` — 跳过 CRAFTING 类型容器
- `setupEventListeners` 中 `onPlayerInteract` — 修复副手问题
- `setupEventListeners` 中 `onInventoryClose` — 走 `schedulePlayerCheck`

#### 新增内部类
- `BugEnchantResult` — 双类附魔检测结果（standardBugs + uberBugs）
- `ScanStats` — 扫描结果统计

#### 删除方法
- `getBugEnchantments(ItemStack)` — 已被 `findAllBugEnchantments` 替代

### 2. `src/main/resources/config.yml`

新增配置项：
```yaml
# 清理 UberEnchant 发光残留
clean-uber-enchant-glint: true
```

更新 `protect-custom-items` 注释，说明 UberEnchant 排除规则。

### 3. `src/main/resources/plugin.yml`

- 版本号 `1.2` → `1.3`
- 描述追加 "（含 UberEnchant PDC 兼容）"
- 三个命令的 description 更新为更准确的描述

### 4. `pom.xml`

- 版本号 `1.2` → `1.3`

---

## 四、关键改动详解

### 4.1 PDC 双结构兼容（核心修正）

经核对 [UberUtils.java](https://github.com/coltonj96/UberEnchant/blob/master/src/me/sciguymjm/uberenchant/api/utils/UberUtils.java) `getCustomMap` 方法，发现 UberEnchant 同时支持两种数据结构：

```java
// UberUtils.getCustomMap 的实际逻辑（源码核对结果）
data.getKeys().forEach(key -> {
    if (UberEnchantment.containsKey(key))
        if (has(data, key, PersistentDataType.INTEGER))       // 结构 A：直接 INTEGER
            map.put(..., get(data, key, PersistentDataType.INTEGER));
        else {
            PersistentDataContainer meta = getPDC(data, key);  // 结构 B：嵌套 TAG_CONTAINER
            UberMeta<Integer> level = UberMeta.LEVEL;
            if (has(meta, level.getKey()))
                map.put(..., get(meta, level.getKey(), level.getType()));
        }
});
```

原方案只描述了结构 B。本次实现通过 `readUberEnchantLevel` 同时兼容两种：

```java
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
```

### 4.2 setEnchantmentGlintOverride 跨版本兼容

原方案直接调用 `meta.setEnchantmentGlintOverride(null)`，但该方法在 paper-api 1.18.2 jar 中不存在，**会导致编译失败**。

实现采用反射 + 双重检查锁缓存：

```java
private void setEnchantmentGlintSafe(ItemMeta meta, Boolean value) {
    if (!glintOverrideChecked) {
        synchronized (this) {
            if (!glintOverrideChecked) {
                try {
                    glintOverrideMethod = ItemMeta.class.getMethod(
                        "setEnchantmentGlintOverride", Boolean.class);
                } catch (NoSuchMethodException ignored) {
                    glintOverrideMethod = null;  // 旧版本静默跳过
                }
                glintOverrideChecked = true;
            }
        }
    }
    if (glintOverrideMethod == null) return;
    try {
        glintOverrideMethod.invoke(meta, value);
    } catch (Exception ignored) {}
}
```

行为：
- 编译时：1.18.2 paper-api 无此方法 → 用反射查找
- 运行时（1.20.5+ 服务端）：能找到方法，正常清理发光
- 运行时（1.18.x ~ 1.20.4 服务端）：找不到方法，静默跳过（PDC 仍正常清理）

### 4.3 isCustomItem 的 UberEnchant 排除

原方案的 `isCustomItem` 改造逻辑过于简单（仅判断 PDC 是否只有 1 个 key），未处理：
- PDC 同时含 UberEnchant + 其他 key 的场景
- UberEnchant 物品有 Lore（由插件自动添加）会触发 `meta.hasLore()` 误判

改进：
1. 遍历所有 PDC key，只要存在非 UberEnchant key 就视为自定义
2. 增加 "纯 UberEnchant 物品" 判定，跳过 Lore 和 ItemFlags 检查

```java
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

if (meta.hasDisplayName()) return true;
if (!onlyUberEnchantPdc) {
    if (meta.hasLore()) return true;
    if (!meta.getItemFlags().isEmpty()) return true;
}
// ...
```

### 4.4 自定义物品保护扩展到 MODIFIED 状态

原代码仅在"全异常书"场景下保护自定义物品（REMOVED 状态），但允许移除异常附魔（MODIFIED）。这会破坏 GUI 道具数据。

修正后：自定义物品无论哪种情况都不修改 meta：

```java
// 在 removeBugEnchantments 的"开始移除"分支前
if (protectCustomItems && isCustom) {
    return RemovalResult.NONE;
}
```

### 4.5 onPlayerInteract 副手修复

原代码：
```java
event.getPlayer().getInventory().setItemInMainHand(copy);
```
**Bug**：玩家用副手交互时会错误修改主手。

修正：
```java
EquipmentSlot hand = event.getHand();
if (hand == null) return;  // 物理交互无明确手
// ...
if (hand == EquipmentSlot.HAND) {
    player.getInventory().setItemInMainHand(resultItem);
} else {
    player.getInventory().setItemInOffHand(resultItem);
}
```

### 4.6 Folia 兼容的延迟调度

原代码在 `onInventoryClose` 中：
```java
Bukkit.getScheduler().runTaskLater(this, () -> ..., 1L);
```
**Bug**：Folia 不支持此 API，会抛异常。

抽出统一方法 `schedulePlayerCheck`，Folia 上同步执行（事件已在玩家区域线程）：

```java
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
```

---

## 五、命令系统优化

### 5.1 `/bugenchantreload` 增强

重载后显示加载摘要：
```
[BugEnchantRemover] 配置已重载
  关键词: 2 个ID / 1 个翻译键
  间隔: 21 tick
  选项: 删全异常书=开启 删空书=开启 保护自定义=开启 清发光=开启
```

异常时显示具体错误，避免静默失败。

### 5.2 `/bugenchantscan` 增强

扫描后分项统计：
```
[BugEnchantRemover] 扫描完成
  扫描槽位: 42 个
  修改物品: 3 个
  删除物品: 1 个
```

未发现时显示 "未发现异常附魔"。

### 5.3 `/bugenchanttest` 增强

- 支持普通物品（不再仅限附魔书）
- 输出标准附魔 + UberEnchant PDC 附魔
- 显示分项计数（标准 / PDC / bug）
- 预测处理结果（含"保护自定义物品"分支）

---

## 六、编译验证

```bash
mvn -DskipTests compile
```

结果：`BUILD SUCCESS`（5.9s）

警告仅为 Java 21 source/target 与系统模块位置的系统级警告，与本次改动无关。

---

## 七、回归测试建议

| 场景 | 预期行为 |
|------|----------|
| 不装 UberEnchant，正常游戏 | 与 v1.2 行为完全一致（无 PDC 数据，新代码路径不触发） |
| 装 UberEnchant，手持带效果附魔的剑运行 `/bugenchanttest` | 输出标准 + PDC 附魔列表 |
| 配置 `enchant-id-keywords: ["uberenchant:"]`，定时扫描 | 自动移除 UberEnchant PDC 附魔 + 清理发光 |
| GUI 道具（有 CustomModelData） + UberEnchant 数据 | 跳过处理，不破坏道具 |
| 玩家用副手右键使用带 bug 附魔物品 | 正确修改副手，不影响主手 |
| Folia 服务端关闭容器 | 不抛异常，正常延迟检查 |
| 1.18.2 服务端运行 | 反射查找 setEnchantmentGlintOverride 返回 null，静默跳过发光清理 |
| 1.20.5+ 服务端运行 | 反射查找成功，正常清理 UberEnchant 发光残留 |

---

## 八、未来优化方向（可选）

- P2: 处理 UberEnchant 添加的 Lore（移除附魔后清理对应行）
- 性能：`removeBugEnchantments` 中可缓存 `item.getItemMeta()` 结果（当前调用 2 次）
- 命令 Tab 补全：为 `/bugenchanttest` 增加 `[detail]` 参数显示 PDC 原始字节
- 配置热重载通知：在玩家在线时广播重载事件

---

## 九、附：原方案核对参考源码

| 文件 | URL |
|------|-----|
| UberUtils.java | https://github.com/coltonj96/UberEnchant/blob/master/src/me/sciguymjm/uberenchant/api/utils/UberUtils.java |
| PDCItemUtils.java | https://github.com/coltonj96/UberEnchant/blob/master/src/me/sciguymjm/uberenchant/api/utils/persistence/PDCItemUtils.java |
| PDCUtils.java | https://github.com/coltonj96/UberEnchant/blob/master/src/me/sciguymjm/uberenchant/api/utils/persistence/PDCUtils.java |
| UberMeta.java | https://github.com/coltonj96/UberEnchant/blob/master/src/me/sciguymjm/uberenchant/api/utils/persistence/UberMeta.java |
