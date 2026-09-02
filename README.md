# Rhythm Axe Mod

节奏地图数据包的伴侣 Fabric 模组。

## 功能

### 1️⃣ 增强 /tick rate 指令

原版 `/tick rate` 支持多种灵活的速率输入格式：

| 格式 | 示例 | 说明 | 范围 |
|------|------|------|------|
| 纯数字 | `/tick rate 20` | 每秒刻数 | 1 ~ 1000 |
| `数字t` | `/tick rate 30t` | 每秒刻数，带单位 | 1 ~ 1000 |
| `数字ms` | `/tick rate 50ms` | 每刻毫秒数 | 1 ~ 1000 |
| `数字bpm 数字tpb` | `/tick rate 150bpm 8` | 拍每分钟 × 每拍刻数（节奏地图专用） | 最终速率 ≤ 1000 |

#### 示例详解

| 输入 | 实际设置 | 说明 |
|------|---------|------|
| `/tick rate 30` | 30 tps | 每秒 30 刻，比原版快 50% |
| `/tick rate 50ms` | 20 tps | 每刻 50 毫秒（原版默认值） |
| `/tick rate 1ms` | 1000 tps | 最快速度 |
| `/tick rate 150bpm 8` | =20 tps | 适合音乐同步的常用节奏 |

### 2️⃣ 权限降级

原版 `/tick` 指令需要 OP 权限等级 **4**（管理员），本模组将其降为等级 **2**（游戏管理员）。

### 3️⃣ 可选同步控制

在指令末尾添加 `true` 或 `false`，控制客户端是否同步服务端速率（默认为 `true`）：

| 指令 | 效果 |
|------|------|
| `/tick rate 30t` | 默认：客户端同步 |
| `/tick rate 30t true` | 客户端同步 |
| `/tick rate 30t false` | **客户端不同步**（仅服务端加速，客户端重置为20tps） |

### 4️⃣ 客户端速率同步

服务端速率超过 **20 tps** 时自动加速渲染，切换世界/离开服务器时自动重置为 20 tps。

### 5️⃣ Gamerule 控制 run_command 确认弹窗

自 Minecraft 25w20a 起，点击聊天/书本中的 `run_command` 会弹出确认屏幕。本模组添加了自定义 gamerule `rhythm_axe_mod:confirm_command`：

| 值 | 效果 |
|----|------|
| `true`（默认） | 原版行为，弹出确认屏幕 |
| `false` | 跳过确认，直接执行命令 |

```
/gamerule rhythm_axe_mod:confirm_command false   # 禁用确认弹窗
```

---

### 💬 帮助指令

在游戏中输入 `/btc` 可查看详细帮助。

## 📦 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/) ≥ 0.19.2
2. 将本模组放入 `mods` 文件夹
3. 需要 [Fabric API](https://modrinth.com/mod/fabric-api) ≥ 0.149.0
4. 需要 Java 25 或更高版本
