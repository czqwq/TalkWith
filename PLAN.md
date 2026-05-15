# TalkWith Mod 实现计划

## 概述

TalkWith 是一个 Minecraft 1.7.10 Forge Mod，目标是在游戏内接入 AI 聊天功能（兼容 OpenAI API 协议）。  
**核心设计：客户端 Only 即可运行**；若服务端也安装了 TalkWith，则自动启用玩家间会话共享（联动）系统。

---

## 关于客户端 Only 可行性

**完全可行。** 实现方案：
- 使用 `ClientCommandHandler.instance.registerCommand()` 注册客户端侧命令（不需要服务端）
- 使用 `GuiScreen` 自定义聊天 GUI，与原版聊天栏完全独立
- AI HTTP 请求在独立 Java 线程中执行，不阻塞游戏主线程
- 配置（baseurl、api-key、system_prompt）存储于本地 Forge Configuration 文件

服务端联动（share/join）需双端均安装 mod，通过 `SimpleNetworkWrapper` 自定义数据包实现。

---

## 模块划分

```
com.czqwq.talkwith
├── TalkWith.java              # 主 Mod 类
├── Tags.java                  # 版本 token（自动生成）
├── Config.java                # 配置管理（baseurl/apikey/system_prompt/cooldown 等）
├── ClientProxy.java           # 客户端代理（注册客户端命令、GUI、事件）
├── CommonProxy.java           # 通用代理
├── command/
│   ├── TalkWithCommand.java   # 客户端命令 /talkwith（及 > 前缀触发）
│   └── TalkWithServerCommand.java  # 服务端命令（仅服务端存在时注册）
├── gui/
│   ├── GuiAIChat.java         # 独立 AI 聊天界面
│   └── GuiAIChatInput.java    # 聊天输入框组件
├── ai/
│   ├── AIClient.java          # OpenAI 兼容 HTTP 客户端（线程安全）
│   ├── ChatSession.java       # 单人会话（消息历史、system_prompt）
│   └── SharedSession.java     # 多人共享会话（Owner/Player 模型）
├── network/
│   ├── PacketHandler.java     # SimpleNetworkWrapper 注册
│   ├── PacketShareInvite.java # 服务端→客户端：收到 share 邀请
│   ├── PacketJoinSession.java # 客户端→服务端：加入会话
│   ├── PacketSessionMessage.java  # 双向：会话内消息转发
│   └── PacketSessionControl.java # Owner 控制包（禁言/踢出/冷却设置）
└── util/
    ├── ApiPinger.java         # 异步 ping API 可用性
    └── TextUtils.java         # 聊天文字格式工具
```

---

## 功能详细设计

### 1. 独立 AI 聊天栏激活

**触发方式（客户端拦截）：**
- 监听 `ClientChatEvent`，若消息以 `>` 开头，取消原版发送，打开 `GuiAIChat` 并传入内容
- `/talkwith` 命令不带子命令时，直接打开 `GuiAIChat`

**`GuiAIChat` 界面特性：**
- 独立于原版聊天，使用 `GuiScreen` 实现
- 滚动显示消息记录（用户消息 / AI 回复区分颜色）
- 底部输入框，按 Enter 发送，按 ESC 关闭
- AI 响应时显示打字动画（"AI 正在思考..."）
- 支持 Markdown 基本格式（粗体、斜体通过 Minecraft 格式码渲染）
- 消息时间戳显示

---

### 2. 配置命令 `/talkwith`

所有子命令同时注册为客户端命令（`ClientCommandHandler`）和服务端命令（`FMLServerStartingEvent`）：

| 命令 | 功能 |
|------|------|
| `/talkwith baseurl <url>` | 设置 API base URL，设置后自动异步 ping 验证可用性 |
| `/talkwith keyset <api-key>` | 设置 API Key，设置后自动 ping 验证 |
| `/talkwith system_prompt <prompt>` | 设置系统提示词（可多词，空格分隔） |
| `/talkwith reload` | 从配置文件热重载所有配置（客户端/服务端均支持） |
| `/talkwith model <model-name>` | 设置使用的模型（默认 `gpt-3.5-turbo`） |
| `/talkwith history clear` | 清空当前会话历史 |
| `/talkwith history show` | 在聊天栏显示当前历史摘要 |

**自动 Ping 机制（`ApiPinger`）：**
- 设置 baseurl 或 keyset 后，在独立线程向 `{baseurl}/v1/models` 发起 GET 请求
- 成功（HTTP 200）：客户端聊天栏显示 `§a[TalkWith] API 可用 ✓`
- 失败：显示 `§c[TalkWith] API 不可用: <错误信息>`
- 超时设置 5 秒

---

### 3. AI 客户端（`AIClient`）

**兼容 OpenAI API 协议：**
```
POST {baseurl}/v1/chat/completions
Authorization: Bearer {api-key}
Content-Type: application/json

{
  "model": "{model}",
  "messages": [
    {"role": "system", "content": "{system_prompt}"},
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "..."},
    ...
  ],
  "stream": false
}
```

**上下文（对话历史）管理（`ChatSession`）：**
- 维护 `List<Message>` 存储完整对话历史
- 每次发送时携带完整历史（实现连续上下文）
- 可配置最大历史条数（`maxHistory`，默认 20 轮）
- 超出时自动截断旧消息（保留 system_prompt）
- 历史可持久化到本地 JSON 文件（可选，配置项控制）

**线程模型：**
- 发送请求时开启新线程，回调通过 `Minecraft.getMinecraft().addScheduledTask()` 回到主线程更新 GUI

---

### 4. 配置文件（`config/talkwith.cfg`）

使用 Forge `Configuration` 管理：

```ini
# TalkWith Mod 配置

[api]
    # API 请求基础 URL（兼容 OpenAI 协议）
    baseurl = https://api.openai.com
    # 使用的模型
    model = gpt-3.5-turbo
    # 最大历史轮数（0 = 无限制）
    maxHistory = 20
    # 请求超时（秒）
    timeout = 30

[auth]
    # API Key（留空则从命令设置）
    apikey = 

[chat]
    # 系统提示词
    system_prompt = You are a helpful assistant in Minecraft.
    # 是否保存历史到文件
    saveHistory = false

[session]
    # 多人共享会话 AI 回复冷却时间（秒）
    replyCooldown = 10
```

`/talkwith reload` 重新读取此文件并应用所有配置。

---

### 5. 服务端联动系统（双端均安装时启用）

**检测机制：** 客户端通过握手包检测服务端是否安装 TalkWith，决定是否显示联动功能。

#### 5.1 分享会话 `/talkwith share <ToID>`

- Owner（执行命令者）创建 `SharedSession`，生成唯一 `sessionID`（UUID）
- 服务端发送 `PacketShareInvite` 给 ToID 玩家
- ToID 玩家客户端收到包后，在聊天栏显示可点击链接：  
  `§a[TalkWith] §eczqwq §f邀请你加入 AI 会话！§b[点击加入]`  
  （点击自动执行 `/talkwith join <sessionID>`）
- **只有 ToID 玩家可见此消息**（服务端精准投递）

#### 5.2 加入会话 `/talkwith join <sessionID>`

- 客户端发送 `PacketJoinSession` 到服务端
- 服务端验证 sessionID 有效，将该玩家加入 `SharedSession.players`
- 加入后，该玩家发送的消息通过 `PacketSessionMessage` 广播给所有会话成员
- 使用 Owner 的 baseurl 和 api-key 进行 AI 请求（API 凭证不暴露给 Player，仅服务端持有）

#### 5.3 Owner 控制功能

| 命令 | 说明 |
|------|------|
| `/talkwith mute <PlayerName>` | 禁止该玩家在会话中发言 |
| `/talkwith unmute <PlayerName>` | 解除禁言 |
| `/talkwith kick <PlayerName>` | 将玩家移出会话 |
| `/talkwith cooldown <秒>` | 设置 AI 回复冷却时间（默认 10s，防刷屏） |
| `/talkwith close` | 关闭共享会话，所有 Player 退出 |

#### 5.4 切换单人模式 `/talkwith single`

- 暂时退出共享会话，切换回个人 `ChatSession`
- 个人 AI 使用本地配置的 baseurl/api-key
- 再次 `/talkwith join <sessionID>` 可重新加入

---

### 6. `>` 快捷前缀

在客户端的 `ClientChatEvent` 中拦截：
- 以 `>` 开头：取消原版发送 → 提取后续文本 → 直接发送给 AI（若 `GuiAIChat` 未打开则后台处理，回复显示在聊天栏）
- 以 `> ` 开头（有空格）：打开 `GuiAIChat` 并预填文本

---

## 实现顺序（优先级）

- [x] 项目骨架（已存在）
- [ ] **Phase 1 - 核心 AI 功能（客户端 Only）**
  - [ ] `Config.java` 完善（baseurl/apikey/system_prompt/model/maxHistory）
  - [ ] `AIClient.java`（OpenAI 兼容 HTTP，异步线程）
  - [ ] `ChatSession.java`（上下文管理）
  - [ ] `TalkWithCommand.java`（客户端命令注册）
  - [ ] `ClientProxy.java`（注册命令、事件监听）
  - [ ] `ApiPinger.java`（异步 ping）
  - [ ] `>` 前缀聊天拦截
- [ ] **Phase 2 - 独立聊天 GUI**
  - [ ] `GuiAIChat.java`（滚动消息列表 + 输入框）
  - [ ] `GuiAIChatInput.java`（输入框组件）
  - [ ] 打字动画、颜色区分、时间戳
- [ ] **Phase 3 - 服务端联动**
  - [ ] `PacketHandler.java` + 各 Packet 类
  - [ ] `SharedSession.java`（多人会话模型）
  - [ ] `TalkWithServerCommand.java`（服务端命令）
  - [ ] 可点击链接实现（`ChatComponentText` + `ChatStyle` + `ClickEvent`）
  - [ ] 冷却、禁言、权限控制
- [ ] **Phase 4 - 完善**
  - [ ] 配置文件热重载（`/talkwith reload`）
  - [ ] 历史持久化（可选 JSON）
  - [ ] 错误处理优化（网络超时、API 错误码解析）
  - [ ] 本地化（`lang/zh_CN.lang`、`lang/en_US.lang`）

---

## 技术要点（1.7.10 Forge 特定）

1. **客户端命令注册：** `ClientCommandHandler.instance.registerCommand(new TalkWithCommand())` — 无需服务端
2. **聊天拦截：** `@ForgeSubscribe` 监听 `ClientChatEvent`，`event.setCanceled(true)` 取消发送
3. **可点击文字：** `ChatComponentText` + `ChatStyle.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/talkwith join xxx"))`
4. **精准私聊消息：** 服务端 `player.addChatMessage()` 只给特定玩家发送
5. **网络包：** `SimpleNetworkWrapper` + `IMessage` / `IMessageHandler`
6. **HTTP 请求：** 使用 Java 原生 `HttpURLConnection`（无需额外依赖），在 `ExecutorService` 线程中执行
7. **回到主线程：** `Minecraft.getMinecraft().addScheduledTask(Runnable)` 或 `IThreadListener`

---

## 注意事项

- **API Key 安全：** 客户端模式下 key 存储在本地 config，不上传服务器；联动模式下 Owner 的 key 只在服务端持有，不通过网络包暴露给其他玩家
- **线程安全：** AI 请求全部异步，回调必须通过 scheduled task 回主线程操作 GUI/聊天
- **冷却机制：** 服务端联动模式下，多人会话中 AI 回复有全局冷却计时器，防止多玩家同时触发
- **历史截断：** 避免超长 context 导致 token 超限，需实现滑动窗口截断
- **兼容性：** baseurl 支持任意 OpenAI 协议兼容服务（如 ollama、one-api、new-api 等本地/第三方中转）
