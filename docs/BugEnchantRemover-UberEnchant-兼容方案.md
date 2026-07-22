# BugEnchantRemover × UberEnchant 兼容方案

## 一、背景

UberEnchant 8.12.10（`coltonj96/UberEnchant`，GPL-3.0）自 1.20.4 起不再使用 Bukkit 的 `Enchantment.registerEnchantment()` 注册自定义附魔，而是改用 **PDC（PersistentDataContainer）** 存储附魔数据。其 37 个「药水效果附魔」（`EffectEnchantment`）全部走 PDC 路径。

BugEnchantRemover 目前只用 `meta.getEnchants()` / `meta.getStoredEnchants()`（标准 Bukkit NBT API）检测附魔——**完全读不到 UberEnchant 的 PDC 附魔**。

**目标：** 为 BugEnchantRemover 增加 PDC 读取/移除能力，兼容清除 UberEnchant 物品上的附魔，并让 `/bugenchanttest` 命令能显示其附魔信息。

---

## 二、UberEnchant PDC 数据结构（核心）

来源：`api/utils/UberUtils.java`（[GitHub 源码](https://github.com/coltonj96/UberEnchant/blob/master/src/me/sciguymjm/uberenchant/api/utils/UberUtils.java)）

### 2.1 普通物品（非附魔书）

```
物品的 PersistentDataContainer
  └─ key: NamespacedKey("uberenchant", "uberenchantment")
       └─ type: PersistentDataType.TAG_CONTAINER
            └─ key: NamespacedKey("uberenchant", "<ENCHANT_KEY>")  ← 如 "BLINDNESS"
                 └─ type: PersistentDataType.TAG_CONTAINER
                      └─ key: NamespacedKey("uberenchant", "level")
                           └─ type: PersistentDataType.INTEGER
                           └─ value: 附魔等级
```

### 2.2 附魔书（存储附魔）

```
物品的 PersistentDataContainer
  └─ key: NamespacedKey("uberenchant", "storeduberenchantment")
       └─ type: PersistentDataType.TAG_CONTAINER
            └─ 结构同上（key → level）
```

### 2.3 关键常量（取自 UberUtils.java）

```java
// PDC 顶级 key
public static final NamespacedKey uberEnchantment = 
    new NamespacedKey("uberenchant", "uberenchantment");        // 普通物品
public static final NamespacedKey storedUberEnchantment = 
    new NamespacedKey("uberenchant", "storeduberenchantment");  // 附魔书

// PDC 子 key（在各附魔的 TAG_CONTAINER 内）
public static final NamespacedKey LEVEL_KEY = 
    new NamespacedKey("uberenchant", "level");  // 存储等级
```

### 2.4 读取方法参考

```java
// UberEnchant 自身读取附魔列表的方法（源码参考，不需要直接调用）
// api/utils/UberUtils.java
public static Map<UberEnchantment, Integer> getMap(ItemStack item) {
    return getCustomMap(item, uberEnchantment);
}
public static Map<UberEnchantment, Integer> getStoredMap(ItemStack item) {
    return getCustomMap(item, storedUberEnchantment);
}
```

**注意：** `UberEnchantment` 是 `Enchantment` 的子类，其 `getKey()` 返回 `NamespacedKey`。`UberEnchantment.getByKey(NamespacedKey)` 可以从其内部 `HashMap` 查找到注册的实例。

但 BugEnchantRemover **不需要依赖 UberEnchant API**——可以直接从 PDC 读取 key 信息来匹配关键词。

---

## 三、需要修改的文件

### 文件 1：`BugEnchantRemover.java`（主逻辑）

位于：`src/main/java/top/miragedge/bugenchantremover/BugEnchantRemover.java`

#### 修改 A：新增 import

```java
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
```

#### 修改 B：新增 PDC 常量

```java
// === UberEnchant PDC Keys ===
private static final NamespacedKey UE_NAMESPACE = NamespacedKey.fromString("uberenchant");
private static final NamespacedKey UE_ITEM_KEY = new NamespacedKey("uberenchant", "uberenchantment");
private static final NamespacedKey UE_BOOK_KEY = new NamespacedKey("uberenchant", "storeduberenchantment");
private static final NamespacedKey UE_LEVEL_KEY = new NamespacedKey("uberenchant", "level");
```

#### 修改 C：新增从 PDC 读取 UberEnchant 附魔的方法

```java
/**
 * 从 PDC 读取 UberEnchant 的附魔（普通物品版）
 * 对应 UberUtils.getMap(item)
 */
private Map<NamespacedKey, Integer> getUberEnchantments(ItemMeta meta) {
    Map<NamespacedKey, Integer> result = new HashMap<>();
    if (meta == null) return result;
    
    PersistentDataContainer pdc = meta.getPersistentDataContainer();
    if (!pdc.has(UE_ITEM_KEY, PersistentDataType.TAG_CONTAINER)) return result;
    
    PersistentDataContainer ueData = pdc.get(UE_ITEM_KEY, PersistentDataType.TAG_CONTAINER);
    if (ueData == null) return result;
    
    for (NamespacedKey key : ueData.getKeys()) {
        if (ueData.has(key, PersistentDataType.TAG_CONTAINER)) {
            PersistentDataContainer enchData = ueData.get(key, PersistentDataType.TAG_CONTAINER);
            if (enchData != null && enchData.has(UE_LEVEL_KEY, PersistentDataType.INTEGER)) {
                int level = enchData.get(UE_LEVEL_KEY, PersistentDataType.INTEGER);
                result.put(key, level);
            }
        }
    }
    return result;
}

/**
 * 从 PDC 读取 UberEnchant 的存储附魔（附魔书版）
 * 对应 UberUtils.getStoredMap(item)
 */
private Map<NamespacedKey, Integer> getStoredUberEnchantments(ItemMeta meta) {
    Map<NamespacedKey, Integer> result = new HashMap<>();
    if (meta == null) return result;
    
    PersistentDataContainer pdc = meta.getPersistentDataContainer();
    if (!pdc.has(UE_BOOK_KEY, PersistentDataType.TAG_CONTAINER)) return result;
    
    PersistentDataContainer ueData = pdc.get(UE_BOOK_KEY, PersistentDataType.TAG_CONTAINER);
    if (ueData == null) return result;
    
    for (NamespacedKey key : ueData.getKeys()) {
        if (ueData.has(key, PersistentDataType.TAG_CONTAINER)) {
            PersistentDataContainer enchData = ueData.get(key, PersistentDataType.TAG_CONTAINER);
            if (enchData != null && enchData.has(UE_LEVEL_KEY, PersistentDataType.INTEGER)) {
                int level = enchData.get(UE_LEVEL_KEY, PersistentDataType.INTEGER);
                result.put(key, level);
            }
        }
    }
    return result;
}

/**
 * 从 PDC 移除单个 UberEnchant 附魔
 */
private void removeUberEnchantment(ItemMeta meta, NamespacedKey enchantKey, boolean isBook) {
    NamespacedPDCKey containerKey = isBook ? UE_BOOK_KEY : UE_ITEM_KEY;
    PersistentDataContainer pdc = meta.getPersistentDataContainer();
    if (!pdc.has(containerKey, PersistentDataType.TAG_CONTAINER)) return;
    
    PersistentDataContainer ueData = pdc.get(containerKey, PersistentDataType.TAG_CONTAINER);
    if (ueData != null) {
        ueData.remove(enchantKey);
        // 回写
        pdc.set(containerKey, PersistentDataType.TAG_CONTAINER, ueData);
    }
}

/**
 * 清除 UberEnchant 所有附魔（整个 PDC 节点清空）
 */
private void clearAllUberEnchantments(ItemMeta meta) {
    PersistentDataContainer pdc = meta.getPersistentDataContainer();
    pdc.remove(UE_ITEM_KEY);
    pdc.remove(UE_BOOK_KEY);
}
```

#### 修改 D：修改 `findBugEnchantments()` 方法

现有方法只检测 `meta.getEnchants()` 和 `meta.getStoredEnchants()`。需要**追加 PDC 检测**：

```java
// 在现有 logic 之后，追加 UberEnchant PDC 检测
// 读取 UberEnchant 的 PDC 附魔
Map<NamespacedKey, Integer> ueEnchants;
if (isBook(meta)) {
    ueEnchants = getStoredUberEnchantments(meta);
} else {
    ueEnchants = getUberEnchantments(meta);
}

for (Map.Entry<NamespacedKey, Integer> entry : ueEnchants.entrySet()) {
    NamespacedKey key = entry.getKey();
    // 用 key.toString() 做关键词匹配
    String enchantId = key.toString();  // 例如 "uberenchant:blindness"
    String namespace = key.getNamespace();  // "uberenchant"
    String enchKey = key.getKey();  // "blindness"
    
    if (matchesAnyKeyword(enchantId, namespace, enchKey)) {
        // 记录为 bug 附魔，按 PDC key 存储
        // 注意：返回类型需要扩展，或者新增一个 PDC 附魔的检测结果集
    }
}
```

**关键改动：** `findBugEnchantments()` 原先返回 `Map<Enchantment, Integer>`。对于 UberEnchant 附魔，我们拿不到 `Enchantment` 实例（未注册到 Bukkit），需要用 `NamespacedKey` 来标识。建议：

- 新加一个 `Map<NamespacedKey, Integer> ueBugEnchantments` 字段或并行方法
- 或者把检测逻辑拆成两个阶段：先检测标准附魔，再检测 PDC 附魔

**推荐方案：** 新增一个内部类：

```java
/**
 * 存储两类附魔检测结果：标准 Bukkit Enchantment 和 UberEnchant PDC NamespacedKey
 */
private static class BugEnchantResult {
    final Map<Enchantment, Integer> standardBugs;    // 标准 Bukkit 附魔
    final Map<NamespacedKey, Integer> uberBugs;      // UberEnchant PDC 附魔
    boolean isEmpty() {
        return standardBugs.isEmpty() && uberBugs.isEmpty();
    }
}
```

然后把 `removeBugEnchantments()` 拆成：

1. 先处理标准附魔（现有逻辑）
2. 再处理 PDC 附魔：遍历 `uberBugs`，调用 `removeUberEnchantment(meta, key, isBook)`

#### 修改 E：修改 `predicateEnchantedBookAction()` 的判定

需要额外考虑 UberEnchant PDC 附魔的存在。当标准附魔为空但有 PDC 附魔时，不应判为「空附魔书」。

#### 修改 F：修改 `testPlayerEnchantments()` 命令输出

添加 PDC 附魔信息输出：

```java
// 在 testPlayerEnchantments() 方法中追加
// 输出 UberEnchant PDC 附魔信息
Map<NamespacedKey, Integer> ueStored = getStoredUberEnchantments(meta);
if (!ueStored.isEmpty()) {
    message.append("=== UberEnchant PDC 附魔 ===\n");
    for (Map.Entry<NamespacedKey, Integer> entry : ueStored.entrySet()) {
        NamespacedKey key = entry.getKey();
        int level = entry.getValue();
        String enchantId = key.toString();
        String translationKey = "enchantment." + key.getNamespace() + "." + key.getKey();
        String matched = matchesAnyKeyword(key.toString(), key.getNamespace(), key.getKey()) 
            ? "是" : "否";
        
        message.append(String.format("附魔ID: %s\n", enchantId));
        message.append(String.format("  命名空间: %s\n", key.getNamespace()));
        message.append(String.format("  键名: %s\n", key.getKey()));
        message.append(String.format("  翻译键: %s\n", translationKey));
        message.append(String.format("  等级: %d\n", level));
        message.append(String.format("  检测为Bug: %s\n\n", matched));
    }
}
```

---

## 四、UberEnchant 所有药水效果附魔清单（供 config.yml 配置）

来源：[EffectEnchantment.init()](https://github.com/coltonj96/UberEnchant/blob/master/src/me/sciguymjm/uberenchant/enchantments/abstraction/EffectEnchantment.java) + [vanilla_effects.yml](https://github.com/coltonj96/UberEnchant/blob/master/enchantments/default/vanilla_effects.yml)

PDC 中对应的 `NamespacedKey` 为 `new NamespacedKey("uberenchant", "<ENCHANT_KEY>")`，`ENCHANT_KEY` 列在下表中。

| 类名 | PDC Key（大写） | 命名空间 | 完整 Key |
|------|-----------------|---------|----------|
| SpeedEnchantment | `SPEED` | `uberenchant` | `uberenchant:speed` |
| SlowEnchantment | `SLOW` | `uberenchant` | `uberenchant:slow` |
| FastDiggingEnchantment | `FAST_DIGGING` | `uberenchant` | `uberenchant:fast_digging` |
| SlowDiggingEnchantment | `SLOW_DIGGING` | `uberenchant` | `uberenchant:slow_digging` |
| IncreaseDamageEnchantment | `INCREASE_DAMAGE` | `uberenchant` | `uberenchant:increase_damage` |
| HealEnchantment | `HEAL` | `uberenchant` | `uberenchant:heal` |
| HarmEnchantment | `HARM` | `uberenchant` | `uberenchant:harm` |
| JumpEnchantment | `JUMP` | `uberenchant` | `uberenchant:jump` |
| ConfusionEnchantment | `CONFUSION` | `uberenchant` | `uberenchant:confusion` |
| RegenerationEnchantment | `REGENERATION` | `uberenchant` | `uberenchant:regeneration` |
| DamageResistanceEnchantment | `DAMAGE_RESISTANCE` | `uberenchant` | `uberenchant:damage_resistance` |
| FireResistanceEnchantment | `FIRE_RESISTANCE` | `uberenchant` | `uberenchant:fire_resistance` |
| WaterBreathingEnchantment | `WATER_BREATHING` | `uberenchant` | `uberenchant:water_breathing` |
| InvisibilityEnchantment | `INVISIBILITY` | `uberenchant` | `uberenchant:invisibility` |
| BlindnessEnchantment | `BLINDNESS` | `uberenchant` | `uberenchant:blindness` |
| NightVisionEnchantment | `NIGHT_VISION` | `uberenchant` | `uberenchant:night_vision` |
| HungerEnchantment | `HUNGER` | `uberenchant` | `uberenchant:hunger` |
| WeaknessEnchantment | `WEAKNESS` | `uberenchant` | `uberenchant:weakness` |
| PoisonEnchantment | `POISON` | `uberenchant` | `uberenchant:poison` |
| WitherEnchantment | `WITHER` | `uberenchant` | `uberenchant:wither` |
| HealthBoostEnchantment | `HEALTH_BOOST` | `uberenchant` | `uberenchant:health_boost` |
| AbsorptionEnchantment | `ABSORPTION` | `uberenchant` | `uberenchant:absorption` |
| SaturationEnchantment | `SATURATION` | `uberenchant` | `uberenchant:saturation` |
| GlowingEnchantment | `GLOWING` | `uberenchant` | `uberenchant:glowing` |
| LevitationEnchantment | `LEVITATION` | `uberenchant` | `uberenchant:levitation` |
| LuckEnchantment | `LUCK` | `uberenchant` | `uberenchant:luck` |
| UnLuckEnchantment | `UNLUCK` | `uberenchant` | `uberenchant:unluck` |
| SlowFallingEnchantment | `SLOW_FALLING` | `uberenchant` | `uberenchant:slow_falling` |
| ConduitPowerEnchantment | `CONDUIT_POWER` | `uberenchant` | `uberenchant:conduit_power` |
| DolphinsGraceEnchantment | `DOLPHINS_GRACE` | `uberenchant` | `uberenchant:dolphins_grace` |
| BadOmenEnchantment | `BAD_OMEN` | `uberenchant` | `uberenchant:bad_omen` |
| HeroOfTheVillageEnchantment | `HERO_OF_THE_VILLAGE` | `uberenchant` | `uberenchant:hero_of_the_village` |
| DarknessEnchantment | `DARKNESS` | `uberenchant` | `uberenchant:darkness` (1.19+) |
| TrialOmenEnchantment | `TRIAL_OMEN` | `uberenchant` | `uberenchant:trial_omen` (1.20.5+) |
| RaidOmenEnchantment | `RAID_OMEN` | `uberenchant` | `uberenchant:raid_omen` (1.20.5+) |
| WindChargedEnchantment | `WIND_CHARGED` | `uberenchant` | `uberenchant:wind_charged` (1.20.5+) |
| WeavingEnchantment | `WEAVING` | `uberenchant` | `uberenchant:weaving` (1.20.5+) |
| OozingEnchantment | `OOZING` | `uberenchant` | `uberenchant:oozing` (1.20.5+) |
| InfestedEnchantment | `INFESTED` | `uberenchant` | `uberenchant:infested` (1.20.5+) |
| BreathOfTheNautilusEnchantment | `BREATH_OF_THE_NAUTILUS` | `uberenchant` | `uberenchant:breath_of_the_nautilus` (1.21.11+) |

> **注意：** PDC 中存储的 key 是构造时传入的大写字符串（如 `"BLINDNESS"`），但从 PDC 读出时 Java 的 `NamespacedKey` 会自动转为小写（Bukkit 规范），所以读取时 key 为小写。

---

## 五、config.yml 配置示例

在 BugEnchantRemover 的 `config.yml` 添加关键词：

```yaml
# 匹配所有 UberEnchant 药水效果附魔（命名空间匹配）
enchant-id-keywords:
  - "uberenchant:"

# 或者逐一指定具体附魔
# enchant-id-keywords:
#   - "uberenchant:blindness"
#   - "uberenchant:poison"
#   - "uberenchant:wither"
#   - ...
```

---

## 六、涉及到的源码链接引用

| 文件 | GitHub URL |
|------|-----------|
| UberEnchant 主类 | https://github.com/coltonj96/UberEnchant/blob/master/src/me/sciguymjm/uberenchant/UberEnchant.java |
| UberEnchantment 基类 | https://github.com/coltonj96/UberEnchant/blob/master/src/me/sciguymjm/uberenchant/api/UberEnchantment.java |
| EffectEnchantment 抽象类（含 init() 注册） | https://github.com/coltonj96/UberEnchant/blob/master/src/me/sciguymjm/uberenchant/enchantments/abstraction/EffectEnchantment.java |
| UberUtils（PDC 所有操作 + 常量） | https://github.com/coltonj96/UberEnchant/blob/master/src/me/sciguymjm/uberenchant/api/utils/UberUtils.java |
| vanilla_effects.yml（37 个效果附魔配置） | https://github.com/coltonj96/UberEnchant/blob/master/enchantments/default/vanilla_effects.yml |
| BugEnchantRemover 源码 | F:\Java_project\BugEnchantRemover\src\main\java\top\miragedge\bugenchantremover\BugEnchantRemover.java |

---

## 七、实现优先级

### P0（必须做）
- [ ] `findBugEnchantments()` 增加 PDC 附魔检测路径
- [ ] `removeBugEnchantments()` 增加 PDC 附魔移除逻辑
- [ ] 关键词匹配系统兼容 `NamespacedKey.toString()` 格式

### P1（推荐做）
- [ ] `testPlayerEnchantments()` 命令增加 PDC 附魔信息输出
- [ ] `isCustomItem()` 保护逻辑也作用于 PDC 附魔物品
- [ ] 空附魔书判定考虑 PDC 存储附魔

### P2（可选）
- [ ] 事件监听器（拾取/交互）中的 `clone()` 副本同步处理 PDC 数据

---

## 八、注意事项

1. **无需添加 UberEnchant 作为依赖**——直接操作 PDC 即可，无需导入 UberEnchant 的任何类。
2. **PDC 的 `NamespacedKey` 在 Java 中会统一为小写**——虽然 UberEnchant 用大写字符串 `"BLINDNESS"` 构造，但 Bukkit 的 `NamespacedKey` 内部转小写，读取时 key 为 `"blindness"`。关键词匹配时用 `"uberenchant:"` 做 namespace 前缀匹配即可通吃。
3. **`EnchantmentStorageMeta` 的 `getStoredEnchants()` 仍为空**——UberEnchant 附魔书的数据完全在 `storeduberenchantment` 节点。修改后 `isCustomItem()` 的 PDC 判空逻辑会认为有数据（因为 `storeduberenchantment` 节点存在），这就是为什么 `isCustomItem()` 需要加一个排除：PDC 中只有 `uberenchantment`/`storeduberenchantment` 节点的不算自定义物品。
4. **UberEnchant 本身会加上发光效果和 Lore**——移除 PDC 附魔后需要额外处理 Lore 和发光。如果 PDC 附魔全部清空，需要：
   - 清除 PDC 的 `uberenchant:uberenchantment` / `uberenchant:storeduberenchantment` 节点
   - 移除 UberEnchant 添加的 Lore（通过检查 lore 是否以附魔名+罗马数字开头）
   - 如果原物品没有任何标准附魔，移除发光（`setEnchantmentGlintOverride(false)`）
5. **`isCustomItem()` 的 PDC 排除逻辑**——当前 `isCustomItem()` 中包含 `!meta.getPersistentDataContainer().isEmpty()`。UberEnchant 物品即使没有自定义数据，PDC 中也会有 `uberenchantment` 节点，会被误判为自定义物品。需要加特判：
   ```java
   // 排除 UberEnchant 的 PDC 数据
   PersistentDataContainer pdc = meta.getPersistentDataContainer();
   if (pdc.getKeys().size() == 1 && 
       (pdc.has(UE_ITEM_KEY, PersistentDataType.TAG_CONTAINER) ||
        pdc.has(UE_BOOK_KEY, PersistentDataType.TAG_CONTAINER))) {
       // 只有 UberEnchant 数据，不算自定义物品
       // 继续检查其他自定义数据...
   }
   ```

---

## 九、测试方法

1. 安装 UberEnchant，用 `/ue give <player> <enchant>` 获得一个带药水效果附魔的物品
2. 手持该物品运行 `/bugenchanttest` —— 应输出 PDC 附魔信息
3. 如果配置了相关关键词，定时检查应自动移除该附魔
4. `/bugenchantreload` 重载配置后测试关键词变更
