# Rhythm Axe Mod

节奏地图的必备前置模组，**并非数据包的一部分**
> waring：100% AI generate

/rhythm_axe_mod 游戏内帮助指令

### 功能

#### 增强 /tick rate 指令

原版 `/tick rate` 支持多种灵活的速率输入格式：

| 格式 | 示例 | 说明 | 范围 |
| --- | --- | --- | --- |
| 纯数字 | `/tick rate 20` | 每秒刻数 | 1 ~ 1000 |
| `数字t` | `/tick rate 30t` | 每秒刻数，带单位 | 1 ~ 1000 |
| `数字ms` | `/tick rate 50ms` | 每刻毫秒数 | 1 ~ 1000 |
| `数字bpm 数字tpb` | `/tick rate 150bpm 8` | 拍每分钟 × 每拍刻数（节奏地图专用） | 最终速率 ≤ 1000 |

##### 示例详解

| 输入 | 实际设置 | 说明 |
| --- | --- | --- |
| `/tick rate 30` | 30 tps | 每秒 30 刻，比原版快 50% |
| `/tick rate 50ms` | 20 tps | 每刻 50 毫秒（原版默认值） |
| `/tick rate 1ms` | 1000 tps | 最快速度 |
| `/tick rate 150bpm 8` | 20 tps | 适合音乐同步的常用节奏 |

##### 权限降级

原版 `/tick` 指令需要 OP 权限等级 **4**，本模组将其降为等级 **2**，使之**可被命令方块执行**。

##### 可选同步控制

在指令末尾添加 `true` 或 `false`，控制客户端是否同步服务端速率（默认为 `true`）：

| 指令 | 效果 |
| --- | --- |
| `/tick rate 30t` | 默认：客户端同步 |
| `/tick rate 30t true` | 客户端同步 |
| `/tick rate 30t false` | **客户端不同步**（仅服务端加速，客户端重置为20tps） |

##### 客户端速率同步

服务端速率超过 **20 tps** 时自动加速渲染，切换世界/离开服务器时自动重置为 20 tps。

#### Gamerule 控制 run_command 确认弹窗

自 Minecraft 25w20a 起，点击聊天/书本中的 `run_command` 会弹出确认屏幕。本模组添加了自定义 gamerule `rhythm_axe_mod:confirm_command`：

| 值 | 效果 |
| --- | --- |
| `true`（默认） | 原版行为，弹出确认屏幕 |
| `false` | 跳过确认，直接执行命令 |

```plain
/gamerule rhythm_axe_mod:confirm_command false   # 禁用确认弹窗
```

#### 音频播放控件

> /playmusic <音频路径> [起始时间] [播放速率] [目标] [音量]

|     参数     |      类型      |                 介绍                 |
|    ---      |     ---       |                 ---                  |
| `<音频路径>` |   `string`    | 和 `playsound` 一样，使用 `sounds.json` 文件内定义的声音事件 |
| `[起始时间]` | `int` @ 0..   | 从第 `<起始时间>` 刻开始播放，**单位 tick**；模组按当前服务器 tick 速率自动换算成毫秒（未编辑谱面时 20tps → 1tick=50ms），默认 0 |
| `[播放速率]` | `float` @ 0.1..8 | 音频的播放速率，**不更改音高**（保调重采样），默认 1 |
| `[目标]` |  `目标选择器`  | 要播放给的玩家，默认 `@s`（执行者） |
| `[音量]`     | `float` @ 0..1 | 音量，默认 1 |

- 如果目标对象已经有一个通过 playmusic 播放的音乐，那么新的播放任务会覆盖旧的播放任务
- 可被数据包函数直接调用（如 `playmusic rhythm_axe:rhythm_axe.audio 100 1 @s 1`）
- 执行指令时，如果没有正在编辑的谱面或者虽然有正在编辑的谱面但是谱面的BPMlist不存在，那么读取当前的服务端的mspt再换算起始时间;
- 如果有正在编辑的谱面并且谱面至少存在一个时间点，那么通过谱面的时间点信息来换算tick→起始时间
- 实现契约：模组读取 `storage rhythm_axe:maps.editor`（`{active:1b, mapid:"xx"}`）与 `storage rhythm_axe:maps.<mapid>` 的 `timing_points`（`{time,bpm,tpb,...}`）；未就绪/无时间点/数据异常时自动回退 mspt 换算

---

> /preloadmusic <音频路径>

|     参数     |      类型      |                 介绍                 |
|    ---      |     ---       |                 ---                  |
| `<音频路径>` |   `string`    | 和 `playsound` 一样，使用 `sounds.json` 文件内定义的声音事件 |

> 后台预解码整首音频并缓存（消除 playmusic 的首播解码延迟）

- 把音频**全量解码**进客户端内存缓存，之后的 `/playmusic` 直接用缓存，启动零延迟
- 发给**所有在线玩家**；不开始播放、不打断当前播放；重复预加载刷新缓存
- 编辑器打开谱面时自动执行（预热该谱面的音乐）；也可被数据包函数直接调用（如 `preloadmusic rhythm_axe:rhythm_axe.audio`）

---

> /pausemusic <目标>
> /resumemusic <目标>
> /stopmusic <目标>

| `<目标>` |  `目标选择器`  | 要播放给的玩家 |

> 暂停播放音乐
> 继续播放音乐
> 取消播放音乐

- 如果目标对象没有通过`/playmusic`播放的音乐，那么什么也不会发生
- playmusic 的音乐归属 **record 通道**：原版 `/stopsound <目标>`（全停）或 `/stopsound <目标> record [音效id]` 也会把它一并停掉

---

#### 💬 帮助指令

在游戏中输入 `/rhythm_axe_mod` 可查看详细帮助。

#### 🎮 可视化时间轴（编辑器 HUD 覆盖层）

编辑器打开时，屏幕顶部居中会叠加一个**黑色半透明矩形时间轴**（由 mod 的客户端 HUD 渲染，非世界内实体）。纯显示、无交互，用于直观查看谱面节奏与物件分布。

- **开关**：`options` 计分板 `editor_timeline_gui`（进编辑器时 `init_state` 置 1、退出时清 0）。1=显示，0=隐藏。
- **渲染**：`rhythm_axe_mod` 客户端 `TimelineGui`（HudElement `extractRenderState`）。横向占屏幕 70%、居中；顶部信息行 + 下方 7 条轨道行（时间点 / 事件 / 音符盒 / 木板 / 唱片机 / 混凝土 / 染色玻璃）。
- **状态栏（顶部信息行）**：一行内左/中/右三段展示，供快速查看当前曲目与节奏/速度状态：
  - **左**：`曲名 - 歌手`（`title` - `artist`）
  - **中**：`xx.xbpm 拍/每拍`（播放头所在时间点参数 `bpm`/`bpb`/`tpb`）
  - **右**：`播放速度 x 流速  播放头/结尾`（`playSpeed` + `noteSpeed`（音符流速 `note_speed`）+ `playhead/endTime`），如 `1.00x 16  500/1000`
- **格子**：固定每格 8px（不随界面尺寸变化），窗口格数 `len = 面板宽 / 8`（最小 8）。
- **播放头**：固定于窗口前 1/3 处，播放头不动、内容随谱面滚动（剪辑软件风格）。
- **平滑运动**：客户端对播放头做插值（`displayPlayhead += (playhead - displayPlayhead) * 0.15`），切换/暂停也平滑过渡，不跳变。
- **闲置淡出**：10 秒无数据变化 → 整体透明度逐渐降到 0.5（缓慢过渡）。
- **节奏刻度（节奏染色）**：按播放头所在时间点参数（`bpm`/`tpb`/`bpb`/`offset`）在上部画刻度线。染色逻辑在 `TimelineGui.rhythmColor`，**重拍优先、从粗到细**：
  - **每小节**（`(t-offset) % (tpb×bpb) == 0`）：**最粗**（`fill` 3px 宽）纯白 `0xFFFFFFFF`（小节加粗）
  - **每拍头**（拍内偏移 `r == 0`）：1px 白 `0xE0FFFFFF`
  - **细分刻**（按拍内偏移 `r = ((t-offset) % tpb + tpb) % tpb` 与 `tpb` 匹配）：

    | 段 | tpb 条件 | 命中条件 | 颜色 |
    | --- | --- | --- | --- |
    | 半拍 | `tpb % 2 == 0` | `r == tpb/2` | 红 `0xFFFF4D4D` |
    | 三连 | `tpb % 3 == 0` | `r == tpb/3` 或 `2*tpb/3` | 品红 `0xFFFF66CC` |
    | 四连 | `tpb % 4 == 0` | `r == tpb/4` 或 `3*tpb/4` | 蓝 `0xFF4DA6FF` |
    | 六连 | `tpb % 6 == 0` | `r % (tpb/6) == 0` | 紫 `0xFFB36BFF` |
    | 八连 | `tpb % 8 == 0` | `r % (tpb/8) == 0` | 黄 `0xFFFFD33D` |
    | 十二连 | `tpb % 12 == 0` | `r % (tpb/12) == 0` | 淡灰 `0xFF9E9E9E` |
    | 默认刻 | — | 其余 | 更亮灰 `0xFF8A8A8A` |

  - 匹配按 **2→3→4→6→8→12** 顺序**先命中优先**（如 `tpb=6` 时半拍的红优先于三连/六连）；所有线条随整体透明度 `alpha` 缩放（`withAlpha`），配合闲置淡出。
- **物件的显示**（来自服务端推送窗口）：
  - 时间点：红/绿混凝土（首个恒红、之后 bpm 变化为红）
  - 事件：淡蓝混凝土；**指令数 ≥2 时在格子右下角显示数量**（物品栏小数字样式）
  - 音符：按类型显示对应方块；**同一行同一刻覆盖数 ≥2 时在格子右下角显示数量**；混凝土按 `[time, time+duration]` 覆盖每格并分别统计数量
- **数据流**：服务端 `TimelineSync` 每 tick 读取工作副本 `maps.editor.history[history_cursor]`，裁剪出**窗口** `[播放头 - windowLen/3, 播放头 + 2windowLen/3]` 打包 `ShowPayload`；客户端上报当前显示长度 `ClientWindowPayload(len)`（`setWindowLen`），服务端据此设 `windowLen`。开关关闭/退出时发 `HidePayload` 清客户端。
- **逻辑判断**：播放中每 tick 推（playhead 变化）；暂停时仅在快进/快退/编辑（playhead/history_cursor 变化）时推。

### 📦 需求

1. Minecraft 26.1.2
2. 安装 [Fabric Loader](https://fabricmc.net/use/) ≥ 0.19.2
3. 需要 [Fabric API](https://modrinth.com/mod/fabric-api) ≥ 0.149.0
4. 需要 Java 25 或更高版本