# TalkWith Session Guide / TalkWith 会话指南

## Overview / 概述

TalkWith supports two operating modes:

- **Client mode** — AI calls are made directly from the client using your local API settings. No server mod required for basic chat.
- **Server session mode** — A shared session runs on the server. Multiple players can participate, and the AI call is made server-side using the session owner's API settings.

TalkWith 支持两种运行模式：

- **客户端模式** — AI 请求从客户端直接发出，使用你本地的 API 配置。基本聊天无需服务端安装模组。
- **服务端会话模式** — 共享会话在服务端运行，多名玩家可以同时参与，AI 请求由服务端以会话所有者的 API 配置发起。

---

## Session State Machine / 会话状态机

```
[Client Mode] ──── session server create ────► [Server Session: Owner]
                                                       │
                         session invite / join ◄───────┤
                                                       │
[Client Mode] ◄──────────────────────────── [Server Session: Member]

[Server Session: Owner] ── leave (transfers ownership) ──► [Client Mode]
[Server Session: Owner] ── delete / close ──► closes session for all members
[Server Session: Member] ── leave ──► [Client Mode]
```

**Roles / 角色**

| Role / 角色 | Permissions / 权限 |
|---|---|
| Owner / 所有者 | All settings, kick, mute/unmute, cooldown, close, history clear |
| Member / 成员 | Send messages (if not muted), leave |

**Disconnect behavior / 断线行为**

- Owner disconnects with online members → ownership automatically transferred to the next online member.
- Owner disconnects with no online members → session removed from memory; **history saved in world data** (`<world>/data/talkwith_sessions.dat`) and restored on next server start.
- Member disconnects → silently removed from the session.

---

## Conversation History Persistence / 对话历史持久化

Server-side session history is automatically saved into the **world save** after each AI reply:

```
<world>/data/talkwith_sessions.dat
```

On server restart, all sessions are restored with their full conversation history. Players need to
manually rejoin after a restart via `/talkwith session join <name-or-id>` or `/talkwith session list`.

> **Upgrade note:** On first start after upgrading, existing sessions in `config/talkwith/sessions/`
> are automatically migrated to world save data. The old JSON files are kept but no longer updated.

To permanently wipe a session's history, use `/talkwith session history clear` (owner only).

服务端会话的对话历史在每次 AI 回复后自动保存到**存档**中：

```
<world>/data/talkwith_sessions.dat
```

服务端重启后，所有已保存的会话（含完整对话历史）会自动恢复。玩家重新加入后可继续之前的对话。
使用 `/talkwith session history clear`（仅所有者）可永久清空历史记录。

---

## The `>` Chat Shortcut / `>` 聊天快捷方式

Type `> <message>` in chat to send a message to AI without using a command.

- If you are in a **server session**, the message is sent to the session's AI on the server and broadcast to all session members. Mute and cooldown rules are enforced.
- If you are in **client mode**, the message is sent to your local AI and the reply appears in your own chat.

在聊天框输入 `> <消息>` 可以快速发送 AI 消息，无需命令。

- 若处于**服务端会话**中，消息将在服务端处理并广播给所有会话成员（受禁言/冷却规则约束）。
- 若处于**客户端模式**，消息由本地 AI 处理，回复仅显示在你的聊天框中。

---

## Command Reference / 命令参考

### Check Current Status / 查看当前状态

```
/talkwith status
```

Shows your current mode (client or server session) and relevant details without querying the server.

显示你当前的模式（客户端或服务端会话）及相关信息，无需查询服务端。

---

### Session Management / 会话管理

All session commands require the server to have TalkWith installed (or you are the host in singleplayer/LAN).

所有会话命令需要服务端安装 TalkWith（或你在单人/局域网中作为主机）。

#### Create a Server Session / 创建服务端会话

```
/talkwith session server create
```

Creates a new shared session and sets you as the owner. Your client automatically enters this session. Fails if you are already in a session.

创建一个新的共享会话，并将你设为所有者。你的客户端自动加入该会话。若你已在某会话中则返回错误。

#### Switch to Client Mode / 切换回客户端模式

```
/talkwith session client
```

Closes your owned session (and notifies all members) OR leaves a joined session. Returns you to local client mode.

关闭你拥有的会话（并通知所有成员）或退出你加入的会话，切换回本地客户端模式。

#### Leave a Session / 退出会话

```
/talkwith session leave
```

Leaves the current session without deleting it. If you are the owner, ownership is transferred to the next online member. If no other members are online, the session is closed but **history is preserved in world save data**.

退出当前会话而不删除它。若你是所有者，所有权将转移给下一个在线成员。若无其他在线成员，会话关闭但**历史记录保留在磁盘上**。

#### Delete Your Session / 删除会话

```
/talkwith session delete
```

Closes the session you own and notifies all members. Everyone returns to client mode. **Also deletes the history file.**

关闭你拥有的会话并通知所有成员，所有人切换回客户端模式。**同时删除历史文件。**

#### Join a Session / 加入会话

```
/talkwith session join <sessionId>
```

Joins an existing session by its ID (usually received via invite). On join, you receive the last 5 exchanges as history.

通过会话 ID 加入现有会话（通常通过邀请获得）。加入时会收到最近 5 条交互记录作为历史同步。

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

Shows all active server sessions (including restored sessions from disk).

显示所有活跃的服务端会话（包括从磁盘恢复的会话）。

#### Session Info / 会话信息

```
/talkwith session info
```

Shows information about the session you are currently in.

显示你当前所在会话的信息。

---

### Session Settings (Owner Only) / 会话设置（仅所有者）

Configure the AI endpoint and prompt used for the session via the unified `config server` tree.

配置该会话使用的 AI 接口和提示词，通过统一的 `config server` 命令树。

```
/talkwith config server model <modelName>
/talkwith config server baseurl <url>
/talkwith config server keyset <key>
/talkwith config server prompt_file <filename.json>
/talkwith config server list_prompts
```

**Examples / 示例:**
```
/talkwith config server model gpt-4o
/talkwith config server baseurl https://api.openai.com
/talkwith config server keyset sk-...
/talkwith config server prompt_file dungeon_master.json
/talkwith config server list_prompts
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

#### Clear History / 清空历史记录

```
/talkwith session history clear
```

Clears the session's full conversation history (in memory and on disk). The AI will have no memory of previous exchanges after this.

清空会话的完整对话历史（内存和磁盘同步清空）。执行后 AI 将不记得之前的任何对话。

#### Close Session / 关闭会话

```
/talkwith session close
```

Same as `delete` — closes the session, notifies all members, and deletes the history file.

与 `delete` 相同，关闭会话并通知所有成员，同时删除历史文件。

---

## Example Workflows / 使用示例

### Singleplayer / 单人游戏

1. Configure your API: `/talkwith config client baseurl <url>`, `/talkwith config client keyset <key>`, `/talkwith config client model <model>`
2. Type `> Hello!` in chat to talk to AI.

1. 配置 API：`/talkwith config client baseurl <地址>`，`/talkwith config client keyset <密钥>`，`/talkwith config client model <模型>`
2. 在聊天框输入 `> 你好！` 与 AI 对话。

### LAN / 局域网

Same as singleplayer. The `>` shortcut works. If you want friends to share the same AI context:

与单人游戏相同。如需与朋友共享同一 AI 上下文：

1. Host: `/talkwith session server create myteam`
2. Host: `/talkwith session invite <friend>`
3. Friend clicks the join link, or runs `/talkwith session join myteam`
4. Both players type `> message` to interact with the shared AI.

1. 主机：`/talkwith session server create myteam`
2. 主机：`/talkwith session invite <朋友名>`
3. 朋友点击聊天中的加入链接，或运行 `/talkwith session join myteam`
4. 双方输入 `> 消息` 与共享 AI 互动。

### Dedicated Server / 专用服务器

Same flow as LAN. Session history persists across restarts — after a server restart, the session is automatically restored. Players can rejoin and continue the conversation:

流程与局域网相同。**会话历史在服务端重启后自动恢复**，玩家重新加入即可继续之前的对话：

```
/talkwith session server create team1
/talkwith config server baseurl https://api.openai.com
/talkwith config server keyset sk-...
/talkwith config server model gpt-4o
/talkwith config server prompt_file dungeon_master.json
/talkwith session invite <player1>
/talkwith session invite <player2>
```

After restart / 重启后：
```
/talkwith session list          # see restored sessions / 查看已恢复的会话
/talkwith session join team1    # rejoin by name / 按名称重新加入
```

### Switching Modes / 切换模式

While in a shared session, use `/talkwith switch` to toggle between session AI and local AI without leaving:

在共享会话中，使用 `/talkwith switch` 在会话 AI 和本地 AI 之间切换而不退出会话：

```
/talkwith switch single   # ">" goes to your local AI
/talkwith switch multi    # ">" goes back to the shared session AI
/talkwith status          # shows current mode and session details
```
