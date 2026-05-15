# TalkWith Session Guide / TalkWith 会话指南

## Overview / 概述

TalkWith supports two operating modes:

- **Client mode** — AI calls are made directly from the client using your local API settings. No server mod required for basic chat.
- **Server session mode** — A shared session runs on the server. Multiple players can participate, and the AI call is made server-side using the session owner's API settings.

TalkWith 支持两种运行模式：

- **客户端模式** — AI 请求从客户端直接发出，使用你本地的 API 配置。基本聊天无需服务端安装模组。
- **服务端会话模式** — 共享会话在服务端运行，多名玩家可以同时参与，AI 请求由服务端以会话所有者的 API 配置发起。

---

## The `>` Chat Shortcut / `>` 聊天快捷方式

Type `> <message>` in chat to send a message to AI without using a command.

- If you are in a **server session**, the message is sent to the session's AI on the server and broadcast to all session members.
- If you are in **client mode**, the message is sent to your local AI and the reply appears in your own chat.

在聊天框输入 `> <消息>` 可以快速发送 AI 消息，无需命令。

- 若处于**服务端会话**中，消息将在服务端处理并广播给所有会话成员。
- 若处于**客户端模式**，消息由本地 AI 处理，回复仅显示在你的聊天框中。

---

## Command Reference / 命令参考

### Session Management / 会话管理

All session commands require the server to have TalkWith installed (or you are the host in singleplayer/LAN).

所有会话命令需要服务端安装 TalkWith（或你在单人/局域网中作为主机）。

#### Create a Server Session / 创建服务端会话

```
/talkwith session server create
```

Creates a new shared session and sets you as the owner. Your client automatically enters this session.

创建一个新的共享会话，并将你设为所有者。你的客户端自动加入该会话。

#### Switch to Client Mode / 切换回客户端模式

```
/talkwith session client
```

Leaves the current server session (if any) and returns to local client mode.

退出当前服务端会话（如有），切换回本地客户端模式。

#### Delete Your Session / 删除会话

```
/talkwith session delete
```

Closes the session you own and notifies all members. Everyone returns to client mode.

关闭你拥有的会话并通知所有成员，所有人切换回客户端模式。

#### Join a Session / 加入会话

```
/talkwith session join <sessionId>
```

Joins an existing session by its ID (usually received via invite).

通过会话 ID 加入现有会话（通常通过邀请获得）。

#### Invite a Player / 邀请玩家

```
/talkwith session invite <playerName>
```

Sends an invite to another online player. They will see a clickable message to join.

向另一名在线玩家发送邀请，对方会看到一条可点击的加入消息。

#### List Sessions / 列出会话

```
/talkwith session list
```

Shows all active server sessions.

显示所有活跃的服务端会话。

#### Session Info / 会话信息

```
/talkwith session info
```

Shows information about the session you are currently in.

显示你当前所在会话的信息。

---

### Session Settings (Owner Only) / 会话设置（仅所有者）

Configure the AI endpoint used for the session. These override the server's global config for this session only.

配置该会话使用的 AI 接口。这些设置仅覆盖本会话的全局配置。

```
/talkwith session server setting model <modelName>
/talkwith session server setting baseurl <url>
/talkwith session server setting apikey <key>
```

**Examples / 示例:**
```
/talkwith session server setting model gpt-4o
/talkwith session server setting baseurl https://api.openai.com
/talkwith session server setting apikey sk-...
```

---

### Member Management (Owner Only) / 成员管理（仅所有者）

#### Kick / 踢出

```
/talkwith session kick <playerName>
```

Removes a player from the session and returns them to client mode.

将玩家踢出会话，该玩家切换回客户端模式。

#### Mute / 禁言

```
/talkwith session mute <playerName>
```

Prevents a player from triggering AI replies in this session.

禁止玩家在该会话中触发 AI 回复。

#### Unmute / 解除禁言

```
/talkwith session unmute <playerName>
```

Restores a player's ability to trigger AI replies.

恢复玩家触发 AI 回复的权限。

#### Set Cooldown / 设置冷却时间

```
/talkwith session cooldown <seconds>
```

Sets the minimum time between AI replies (default from global config).

设置 AI 回复之间的最小间隔（默认使用全局配置）。

#### Close Session / 关闭会话

```
/talkwith session close
```

Same as `delete` — closes the session and notifies all members.

与 `delete` 相同，关闭会话并通知所有成员。

---

## Example Workflows / 使用示例

### Singleplayer / 单人游戏

1. Configure your API: `/talkwith baseurl <url>`, `/talkwith keyset <key>`, `/talkwith model <model>`
2. Type `> Hello!` in chat to talk to AI.

1. 配置 API：`/talkwith baseurl <地址>`，`/talkwith keyset <密钥>`，`/talkwith model <模型>`
2. 在聊天框输入 `> 你好！` 与 AI 对话。

### LAN / 局域网

Same as singleplayer. The `>` shortcut works. If you want friends to share the same AI context:

与单人游戏相同。如需与朋友共享同一 AI 上下文：

1. Host: `/talkwith session server create`
2. Host: `/talkwith session invite <friend>`
3. Friend clicks the join link in chat.
4. Both players type `> message` to interact with the shared AI.

1. 主机：`/talkwith session server create`
2. 主机：`/talkwith session invite <朋友名>`
3. 朋友点击聊天中的加入链接。
4. 双方输入 `> 消息` 与共享 AI 互动。

### Dedicated Server / 专用服务器

Same flow as LAN, but TalkWith must be installed on the server. The server owner sets their API key on the session:

流程与局域网相同，但服务端需安装 TalkWith。会话所有者需为该会话设置 API 密钥：

```
/talkwith session server create
/talkwith session server setting baseurl https://api.openai.com
/talkwith session server setting apikey sk-...
/talkwith session server setting model gpt-4o
/talkwith session invite <player1>
/talkwith session invite <player2>
```

---

## Legacy Commands / 旧版命令（向后兼容）

The following commands from older versions still work:

以下旧版命令仍然可用：

| Old / 旧命令 | New equivalent / 新命令 |
|---|---|
| `/talkwith share <player>` | `/talkwith session invite <player>` |
| `/talkwith join <id>` | `/talkwith session join <id>` |
| `/talkwith single` | `/talkwith session client` |
| `/talkwith kick <player>` | `/talkwith session kick <player>` |
| `/talkwith mute <player>` | `/talkwith session mute <player>` |
| `/talkwith unmute <player>` | `/talkwith session unmute <player>` |
| `/talkwith cooldown <sec>` | `/talkwith session cooldown <sec>` |
| `/talkwith close` | `/talkwith session close` |
