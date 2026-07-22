# BugEnchantRemover — 异常附魔自动清理

> 自动检测并清除数据包/插件带来的异常附魔，支持 UberEnchant PDC 附魔兼容，多版本 | Folia 兼容 | 灵活配置

## 📌 简介

起因是我的服务器安装了包含附魔的数据包，而它官方提供的无附魔附加包不能完全阻止附魔自然生成，我又不想一个个改生成配置。

玩家在拿到这些异常附魔后会被踢出游戏，后台也会大量报错（提示找不到此附魔）。

本插件可自动检测并清除这类异常附魔书，同时支持清理 UberEnchant 8.12.10+ 改用 PDC 存储的药水效果附魔（传统插件完全读不到）。

也许还可以有其他用途？比如清除装备上某个本应删除的附魔，或批量清理 UberEnchant 中破坏平衡的药水附魔（失明、中毒、凋零等）。

## ✨ 特性

| 特性 | 说明 |
|------|------|
| 🔍 **智能检测** | 通过附魔 ID、命名空间、键名、翻译键四路匹配，支持关键词列表 |
| ⚡ **高性能** | Paper 端异步调度，不阻塞游戏主线程；Folia 自动切换同步模式 |
| 🔄 **双端兼容** | 同时支持 Paper 1.18.2+ 与 Folia 1.20.1+，运行时自动识别 |
| 🎮 **事件响应** | 监听玩家拾取、交互、关闭容器等事件，实时清除异常附魔 |
| 🧹 **UberEnchant 兼容** | 直接读取 UberEnchant 的 PDC 数据，无需将其作为依赖，兼容两种 PDC 结构 |
| 🛡️ **GUI 道具保护** | 自动识别含 CustomModelData / 自定义显示名 / 属性修饰符的 GUI 道具，避免误删 |
| 📝 **灵活配置** | 关键词列表、清理策略、日志级别、消息文案全部可配置 |
| 📖 **详细日志** | 可记录每次清理操作与关键词匹配详情，便于排查 |
| 🗑️ **智能移除** | 全异常附魔书可直接删除整本，避免产生空书残留 |

## 📋 兼容性

| 服务端 | 版本支持 | 调度模式 | UberEnchant 发光清理 |
|--------|----------|----------|----------------------|
| **Paper** | 1.18.2 - 1.21.x | 异步（高性能） | 1.20.5+ 支持 |
| **Folia** | 1.20.1+ | 同步（兼容） | 1.20.5+ 支持 |

> ⚠️ **限制说明**
> - AdvancedEnchantments 这类纯模拟实现的附魔（不写入 ItemMeta）不能清除
> - UberEnchant 的 Lore 残留（附魔名+罗马数字）在 v1.3 暂不主动清理，需用铁砧手动移除

## ⚙️ 前置要求

- **Java**：JDK 21+
- **服务端**：Paper 1.18.2+ 或 Folia 1.20.1+
- **其他依赖**：无（UberEnchant 为可选依赖，仅在需要清理其 PDC 附魔时安装）

## 📥 安装

1. 下载最新版本的 `BugEnchantRemover.jar`
2. 将 JAR 文件放入服务器 `plugins` 文件夹
3. 重启服务器，插件自动生成 `plugins/BugEnchantRemover/config.yml`
4. 手持可疑附魔书执行 `/bugenchanttest` 查看附魔 ID、命名空间、翻译键
5. 根据查到的特征编辑 `config.yml` 的关键词列表
6. 执行 `/bugenchantreload` 重载配置即可生效

## 🔧 配置示例

```yaml
# BugEnchantRemover 配置文件
# 检测附魔ID或翻译键中包含以下关键词的附魔

# 附魔ID关键词列表（不区分大小写）
# 检查完整ID、命名空间或键名是否包含这些关键词
enchant-id-keywords:
  - "nova_structures"
  - "uberenchant:"           # 清理所有 UberEnchant 药水效果附魔

# 翻译键关键词列表（不区分大小写）
# 检查翻译键是否包含这些关键词
translation-key-keywords:
  - "enchantment.dnt"

# 检查间隔（单位：tick，20tick=1秒）
# 修改后需重启服务器生效，/bugenchantreload 不会重启定时任务
check-interval: 21

# 是否在控制台记录清除日志
log-removals: true

# 是否记录匹配的关键词详情（排查问题时开启）
log-keyword-matches: true

# 当附魔书的所有附魔都是异常附魔时，直接删除整本书
remove-enchanted-book-when-all-bug: true

# 清理没有任何附魔的空附魔书（数据包残留）
remove-empty-enchanted-book: true

# 保护 GUI 道具不被误删（识别 CustomModelData、自定义显示名等）
protect-custom-items: true

# 清理 UberEnchant 添加的发光效果残留（需 Paper 1.20.5+，旧版本静默跳过）
clean-uber-enchant-glint: true

# 提示消息配置（支持 MiniMessage 格式）
messages:
  actionbar: "已自动清除异常附魔"
  log-prefix: "[BugEnchantRemover]"
```

## 📌 使用指令

| 指令 | 描述 | 权限 | 默认 |
|------|------|------|------|
| `/bugenchantreload` | 重载配置并显示加载摘要 | `bugenchantremover.reload` | OP |
| `/bugenchantscan` | 手动扫描背包并清理（含统计反馈） | `bugenchantremover.scan` | OP |
| `/bugenchanttest` | 检测手持物品的附魔详情与预测处理结果 | `bugenchantremover.test` | OP |

### `/bugenchanttest` 输出示例

```
=== 物品信息 ===
类型: ENCHANTED_BOOK (附魔书)
附魔总数: 2（标准 1 + UberEnchant PDC 1）
异常附魔数: 2（标准 1 + UberEnchant 1）
识别为自定义物品(GUI道具): 否

--- 标准附魔 ---
附魔ID: nova_structures:bug_enchant
  命名空间: nova_structures | 键名: bug_enchant
  翻译键: enchantment.nova_structures.bug_enchant | 等级: 1
  检测为Bug: 是

--- UberEnchant PDC 附魔 ---
附魔ID: uberenchant:blindness
  命名空间: uberenchant | 键名: blindness
  翻译键: enchantment.uberenchant.blindness | 等级: 3
  检测为Bug: 是

预测处理结果: 删除整本书（全为异常附魔）
```

## 🔑 权限节点

| 权限节点 | 描述 | 默认 |
|----------|------|------|
| `bugenchantremover.scan` | 允许手动扫描背包 | OP |
| `bugenchantremover.test` | 允许测试物品附魔信息 | OP |
| `bugenchantremover.reload` | 允许重载配置 | OP |

## 📂 工作原理

1. **定时检查**：每 `check-interval` tick 检查所有在线玩家的背包、副手、打开的容器
2. **事件响应**：监听玩家拾取物品、交互、关闭容器等事件
3. **关键词匹配**：通过附魔 ID、命名空间、键名、翻译键四路匹配异常附魔
4. **PDC 直读**：直接读取 UberEnchant 的 `uberenchant:uberenchantment` 容器，兼容直接 INTEGER 与嵌套 TAG_CONTAINER 两种结构
5. **GUI 道具保护**：检测 CustomModelData / 自定义显示名 / 属性修饰符等特征，避免误删 GUI 道具
6. **自动清除**：检测到异常附魔后自动移除并发送 actionbar 提示

## 🎯 典型场景

### 场景一：数据包残留附魔书

```yaml
enchant-id-keywords:
  - "nova_structures:"
translation-key-keywords:
  - "enchantment.nova_structures."
```

### 场景二：清理所有 UberEnchant 药水效果附魔

```yaml
enchant-id-keywords:
  - "uberenchant:"
clean-uber-enchant-glint: true
```

### 场景三：仅清理破坏平衡的 UberEnchant 附魔

```yaml
enchant-id-keywords:
  - "uberenchant:blindness"
  - "uberenchant:poison"
  - "uberenchant:wither"
  - "uberenchant:harm"
  - "uberenchant:weakness"
clean-uber-enchant-glint: true
```

## 🐛 常见问题

**Q: 为什么异常附魔书会出现？**
> A: 通常是由于服务器安装了包含自定义附魔的数据包，但数据包已被移除或附魔定义丢失导致的。部分数据包的"无附魔附加包"不能完全阻止附魔自然生成。

**Q: 如何确定要屏蔽的关键词？**
> A: 使用 `/bugenchanttest` 手持物品查看详细信息，包括附魔 ID、命名空间、键名和翻译键。任一字段包含配置的关键词即视为异常。

**Q: 为什么 UberEnchant 的附魔用 `/bugenchanttest` 能看到，但其他插件看不到？**
> A: UberEnchant 8.12.10+ 改用 PDC（PersistentDataContainer）存储附魔，不再走 Bukkit 注册表。本插件 v1.3 直接读取其 PDC 数据结构，无需将 UberEnchant 作为依赖。

**Q: 物品被识别为自定义物品未被清理怎么办？**
> A: 物品可能触发了 GUI 道具保护（有 CustomModelData、自定义显示名等）。如确认需要清理，临时设置 `protect-custom-items: false` 后重载。

**Q: 清理后物品仍发光？**
> A: 服务端为 Paper 1.18.2 ~ 1.20.4 时，`setEnchantmentGlintOverride` API 不存在，无法清理发光（PDC 数据仍正常清理）。升级到 1.20.5+ 即可。

**Q: 修改 `check-interval` 后未生效？**
> A: `/bugenchantreload` 不会重启定时任务，修改检查间隔需要重启服务器。

## 📦 下载

- **GitHub**: [fwindemiko/FE_BugEnchantRemover](https://github.com/fwindemiko/FE_BugEnchantRemover)
- **适用版本**: v1.3+
- **协议**: GPL-3.0

---

# 标题与概述（单独复制使用）

## 标题

```
BugEnchantRemover - 自动清除数据包/UberEnchant 带来的异常附魔 | Paper/Folia 双端兼容
```

## 完整概述

```
轻量级异常附魔清理插件。自动检测并移除数据包残留附魔书、UberEnchant PDC 附魔，避免玩家拾取后踢出服务器。支持 Paper 1.18.2+ 与 Folia 双端，关键词灵活配置，内置 GUI 道具保护避免误删。装上即用，无需额外依赖。
```

## 简短版概述（字数受限场景）

```
自动清除数据包与 UberEnchant 带来的异常附魔，Paper/Folia 双端兼容，关键词灵活配置。
```

## 标签关键词

```
异常附魔清理 | UberEnchant 兼容 | 数据包修复 | Folia 支持 | Paper 插件 | 附魔书清理 | PDC 直读 | GUI 道具保护
```
