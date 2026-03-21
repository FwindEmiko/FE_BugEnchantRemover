# FE BugEnchantRemover

[![Build](https://github.com/FCelestial/FE_BugEnchantRemover/actions/workflows/build.yml/badge.svg)](https://github.com/FCelestial/FE_BugEnchantRemover/actions)

> 自动检测并移除 Minecraft 中异常/不存在的附魔书

## 功能特性

- 自动检测异常附魔
- 支持自定义检测关键词
- 异步检查，不影响服务器性能
- 详细的日志记录

## 前置要求

- Java 21
- Paper/Spigot 1.21+
- 无需其他依赖

## 安装

1. 下载最新版本的 JAR 文件
2. 放入服务器 `plugins` 文件夹
3. 重启服务器
4. 编辑 `plugins/BugEnchantRemover/config.yml` 进行配置

## 配置

```yaml
# 检测间隔 (tick)
check-interval: 21

# 是否记录移除日志
log-removals: true

# 附魔ID关键词
enchant-id-keywords:
  - "dnt"
  - "buggy"

# 翻译键关键词
translation-key-keywords:
  - "non_survival"
```

## 命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/bugenchantscan` | 手动扫描背包 | `bugenchantremover.scan` |
| `/bugenchanttest` | 检测手中附魔书 | `bugenchantremover.test` |
| `/bugenchantreload` | 重载配置 | `bugenchantremover.reload` |

## 权限

| 权限节点 | 描述 | 默认 |
|----------|------|------|
| `bugenchantremover.scan` | 扫描玩家背包 | OP |
| `bugenchantremover.test` | 测试附魔书 | OP |
| `bugenchantremover.reload` | 重载配置 | OP |

## 许可证

MIT License

## 作者

[F.windEmiko](https://github.com/fwindemiko)
