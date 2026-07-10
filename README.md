# BugEnchantRemover

<div align="center">

![Version](https://img.shields.io/badge/Version-1.2-blue)
![Java](https://img.shields.io/badge/Java-21-red)
![Paper](https://img.shields.io/badge/Paper-1.18%2B-blue)
![Folia](https://img.shields.io/badge/Folia-1.20%2B-blue)
[![Build](https://github.com/fwindemiko/FE_BugEnchantRemover/actions/workflows/build.yml/badge.svg)](https://github.com/fewindemiko/FE_BugEnchantRemover/actions)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

**自动检测并移除 Minecraft 数据包带来的异常/不存在附魔**

</div>

---

## 📌 简介

当服务器安装包含自定义附魔的数据包时，这些附魔可能在玩家背包中出现异常显示（显示为附魔 ID 而非名称），严重影响游戏体验。本插件可自动检测并清除这类异常附魔书。

## ✨ 特性

| 特性 | 说明 |
|------|------|
| 🔍 **智能检测** | 支持通过附魔 ID、命名空间、键名、翻译键等多种方式检测 |
| ⚡ **高性能** | Paper 端使用异步调度，不阻塞游戏主线程 |
| 🔄 **双端兼容** | 同时支持 Paper 和 Folia 服务端 |
| 🎮 **事件响应** | 监听玩家拾取、交互等事件，实时清除异常附魔 |
| 📝 **灵活配置** | 支持自定义检测关键词 |
| 📖 **详细日志** | 可配置的日志记录，便于排查问题 |
| 🗑️ **智能移除** | 当附魔书所有附魔均为异常时直接移除整本书，避免产生空附魔书 |
| 🧹 **空书清理** | 自动清理数据包残留的真正空附魔书 |
| 🛡️ **道具保护** | 识别自定义物品(GUI道具)，避免误删以附魔书实现的GUI界面物品 |

## 📋 兼容性

| 服务端 | 版本支持 | 调度模式 |
|--------|----------|----------|
| **Paper** | 1.18.2 - 1.21.x | 异步 (高性能) |
| **Folia** | 1.20.1+ | 同步 (兼容) |

## ⚙️ 前置要求

- Java 21
- Paper / Folia 服务端
- 无其他依赖

## 📥 安装

1. 下载最新版本的 `BugEnchantRemover.jar`
2. 将 JAR 文件放入服务器 `plugins` 文件夹
3. 重启服务器
4. 编辑 `plugins/BugEnchantRemover/config.yml` 配置文件

## 🔧 配置

```yaml
# BugEnchantRemover 配置文件
# 检测附魔ID或翻译键中包含以下关键词的附魔书

# 附魔ID关键词列表（不区分大小写）
# 检查完整ID、命名空间或键名是否包含这些关键词
enchant-id-keywords:
  - "nova_structures"

# 翻译键关键词列表（不区分大小写）
# 检查翻译键是否包含这些关键词
translation-key-keyswords:
  - "enchantment.dnt"

# 检查间隔（单位：tick，20tick=1秒）
check-interval: 21

# 是否在控制台记录清除日志
log-removals: true

# 是否记录匹配的关键词详情
log-keyword-matches: true

# 当附魔书的【所有】附魔都是异常附魔时，直接删除整本书以避免产生空附魔书
remove-enchanted-book-when-all-bug: true

# 是否移除真正的空附魔书（没有任何存储附魔的附魔书）
remove-empty-enchanted-book: true

# 自定义物品(GUI道具)保护：检测到自定义显示名/CustomModelData/Lore/
# PersistentData 等数据的附魔书不会被当作空书删除，避免误删GUI道具
protect-custom-items: true

# 提示消息配置
messages:
  actionbar: "已自动清除异常附魔"
  log-prefix: "[BugEnchantRemover]"
```

## 📌 使用指令

| 指令 | 描述 | 权限 | 默认 |
|------|------|------|------|
| `/bugenchantscan` | 手动扫描背包中的异常附魔书 | `bugenchantremover.scan` | OP |
| `/bugenchanttest` | 检测手中附魔书的详细信息 | `bugenchantremover.test` | OP |
| `/bugenchantreload` | 重载插件配置 | `bugenchantremover.reload` | OP |

## 🔑 权限节点

| 权限节点 | 描述 | 默认 |
|----------|------|------|
| `bugenchantremover.scan` | 允许手动扫描背包 | OP |
| `bugenchantremover.test` | 允许测试附魔书信息 | OP |
| `bugenchantremover.reload` | 允许重载配置 | OP |
| `bugenchantremover.notify` | 接收清除通知 | true |

## 📂 工作原理

1. **定时检查**：每 `check-interval` tick 检查所有在线玩家的背包、副手、打开的容器
2. **事件响应**：监听玩家拾取物品、交互、关闭容器等事件
3. **关键词匹配**：通过附魔 ID、命名空间、键名、翻译键匹配异常附魔
4. **自动清除**：检测到异常附魔后自动移除并发送 actionbar 提示

## 🐛 常见问题

**Q: 为什么异常附魔书会出现？**
> A: 通常是由于服务器安装了包含自定义附魔的数据包，但数据包已被移除或附魔定义丢失导致的。

**Q: 如何确定要屏蔽的关键词？**
> A: 使用 `/bugenchanttest` 手持附魔书查看详细信息，包括附魔 ID、命名空间、键名和翻译键。

## 📄 许可证

本插件采用 MIT 许可证开源。

## 👤 作者

**F.windEmiko** - [GitHub](https://github.com/fwindemiko)

---

<div align="center">

**如果你觉得这个插件对你有帮助，欢迎 Star ⭐**

</div>
