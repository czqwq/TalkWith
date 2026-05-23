# TalkWith — Developer & User Guide

> GTNH Forge 1.7.10 mod that integrates OpenAI-compatible LLMs into Minecraft.  
> Supports single-player (client-only) AI chat and multi-player shared sessions with per-session prompts.

---

## Table of Contents

1. [Project Structure](#1-project-structure)
2. [Build](#2-build)
3. [Configuration System](#3-configuration-system)
4. [Prompt File System](#4-prompt-file-system)
5. [Client-Side AI Chat](#5-client-side-ai-chat)
6. [Shared Session System](#6-shared-session-system)
7. [Session Persistence — World Save Data](#7-session-persistence--world-save-data)
8. [Switch Mechanism](#8-switch-mechanism)
9. [Network Packets](#9-network-packets)
10. [Commands Reference](#10-commands-reference)
11. [Localization](#11-localization)
12. [AI Backend](#12-ai-backend)
13. [Data Flow Diagrams](#13-data-flow-diagrams)

---

## 1. Project Structure

```
src/main/java/com/czqwq/talkwith/
├── TalkWith.java                  — Mod entry point (@Mod), registers proxies
├── ClientProxy.java               — Client-side lifecycle, tick queue, client state
├── CommonProxy.java               — Shared lifecycle (preInit → init → serverStarting)
├── ServerEventHandler.java        — Server-side events (login, logout, chat, world load)
├── Config.java                    — Config read/write + prompt-file helpers
│
├── ai/
│   ├── AIClient.java              — HTTP client for OpenAI-compatible API (async)
│   ├── ChatMessage.java           — Simple record: role + content
│   ├── ChatSession.java           — In-memory conversation history with windowed getMessages()
│   ├── SharedSession.java         — A multi-player AI session (owner, members, settings, history)
│   ├── SessionPersistence.java    — JSON (de)serializer helpers; legacy file-based migration
│   └── SessionWorldData.java      — WorldSavedData — saves all sessions to world save (.dat)
│
├── command/
│   └── TalkWithCommand.java       — /talkwith command (client-side, all sub-commands)
│
├── gui/
│   └── GuiAIChat.java             — Minimal in-game AI chat GUI (no pause, scrolling history)
│
├── network/
│   ├── PacketHandler.java         — SimpleNetworkWrapper channel registration
│   ├── PacketHandshake.java       — S→C: server has mod (sets ClientProxy.serverHasMod)
│   ├── PacketOpenGui.java         — S→C: set/clear client's currentSessionId
│   ├── PacketJoinSession.java     — C→S: join session by name or UUID
│   ├── PacketSessionControl.java  — C→S: all session management actions
│   ├── PacketSessionMessage.java  — C→S: send a chat message to session AI
│   ├── PacketSessionBroadcast.java— S→C: broadcast AI reply to all session members
│   ├── PacketShareInvite.java     — S→C: show session invitation to target player
│   └── PacketClientAIRequest.java — S→C: trigger local AI processing for "> " shortcut
│
└── util/
    ├── TextUtils.java             — Client-side chat output helpers (info / error / addChat)
    └── ApiPinger.java             — Async API connectivity test
```

---

## 2. Build

```bash
# Fix formatting + compile only (fast, ~10–15 s on warm cache)
./gradlew spotlessApply compileJava

# Full build (JAR output in build/libs/)
./gradlew spotlessApply build
```

The project targets **Forge 1.7.10-10.13.4.1614** (GTNH flavour).  
Java 8 is required; `readAllBytes()`, `String.repeat()` and lambda consumers from Java 11+ are **not available** in the Forge environment's bundled JVM — use manual loops instead.

---

## 3. Configuration System

**File:** `Config.java`  
**Config location:** `config/talkwith/talkwith.cfg` (Forge config format)

### Fields

| Field | Default | Section | Description |
|---|---|---|---|
| `baseUrl` | `https://api.openai.com` | `api` | OpenAI-compatible API root |
| `model` | `gpt-3.5-turbo` | `api` | Default model name |
| `timeout` | `30` | `api` | HTTP timeout in seconds |
| `apiKey` | _(empty)_ | `auth` | API key (stored in plain text) |
| `maxHistory` | `20` | `chat` | Max message pairs kept in window |
| `replyCooldown` | `10` | `chat` | Seconds between replies in shared sessions |
| `clientPromptFile` | `system_prompt.json` | `chat` | Active prompt file for client mode |
| `saveHistory` | `false` | `session` | Unused placeholder for future use |

### Static helpers

```java
Config.init(File cfgFile)          // called in CommonProxy.preInit — sets configDir + loads
Config.load()                       // (re)reads .cfg, migrates legacy .txt, loads prompt
Config.save()                       // writes all dirty fields back to .cfg
```

### `configDir`

`Config.configDir` points to `config/talkwith/`.  Used by:
- Prompt file loading/writing
- `SessionPersistence` migration (sub-dir `sessions/`)
- `SessionWorldData` (reads NBT from world, not from here)

---

## 4. Prompt File System

Every prompt lives in a **standalone JSON file** inside `config/talkwith/`:

```json
{ "prompt": "You are a helpful Minecraft assistant." }
```

### Key APIs

```java
Config.loadPromptFromFile(String filename)
    // Reads <configDir>/<filename>.
    // If the file does not exist, creates it with the default prompt and returns that.
    // Sanitizes path separators (no directory traversal).

Config.writePromptFile(File file, String prompt)
    // Writes {"prompt":"..."} JSON to the given File.

Config.listPromptFiles()
    // Returns List<String> of all *.json filenames in configDir (shallow scan).

Config.sanitizePromptFilename(String filename)
    // Strips path separators; appends .json if missing.
```

### Client prompt

`Config.systemPrompt` (in-memory) always holds the currently loaded client prompt.  
It is updated by `Config.load()` and by `/talkwith config client prompt_file <file>`.

### Server session prompt

Each `SharedSession` has its own `sessionPromptFile` (filename, default `"system_prompt.json"`).  
When an AI call is made for a session, the prompt is loaded fresh from disk:

```java
String prompt = Config.loadPromptFromFile(session.sessionPromptFile);
session.session.getMessages(prompt);   // prepends as the "system" role message
```

This means each session can use a completely different persona without a server restart.

### Migration from older versions

If `system_prompt.txt` exists and `system_prompt.json` does not, `Config.load()` performs a
one-time migration: reads the `.txt`, writes the `.json`, and deletes nothing.

---

## 5. Client-Side AI Chat

### Local `ChatSession`

`ClientProxy.clientSession` is a per-client `ChatSession` instance.  
It accumulates history in memory for the duration of the game session (not persisted).

### `> message` shortcut

When a player types `> hello world` in the Minecraft chat box:

1. `ServerChatEvent` fires in `ServerEventHandler.onServerChat` on the server.
2. If the player is **in a shared session** and **not in single-mode override** → handled by session AI (see §6).
3. Otherwise → server sends `PacketClientAIRequest(playerName, text)` back to the player's client.
4. The client handler runs `AIClient.sendAsync(clientSession.getMessages(Config.systemPrompt), ...)`.
5. Reply is displayed in chat via `TextUtils.addChatMessage`.

### `GuiAIChat`

A lightweight `GuiScreen` opened when the server sends `PacketOpenGui(sessionId)`.

- If `sessionId` is non-empty: sends messages via `PacketSessionMessage` (server session mode).
- If `sessionId` is empty or null: talks directly to the client-local AI.
- Shows a scrolling history with a `> …` thinking indicator.
- Unicode flag is force-enabled via reflection on `FontRenderer.unicodeFlag` so Chinese and other
  non-ASCII text renders correctly.

---

## 6. Shared Session System

### `SharedSession`

Represents one active multi-player AI conversation:

| Field | Type | Description |
|---|---|---|
| `sessionId` | `String` | UUID (immutable key in `sessions` map) |
| `sessionName` | `String` | Optional human-readable name (for join-by-name) |
| `ownerUuid` | `UUID` | Transferred on disconnect/explicit leave |
| `ownerName` | `String` | Display name of current owner |
| `ownerBaseUrl` | `String` | Session-specific API base URL (overrides client's) |
| `ownerApiKey` | `String` | Session-specific API key |
| `sessionModel` | `String` | Session-specific model |
| `sessionPromptFile` | `String` | Filename of the JSON prompt file in `config/talkwith/` |
| `cooldown` | `int` | Seconds between AI replies |
| `players` | `Set<UUID>` | All current members (owner included) |
| `mutedPlayers` | `Set<UUID>` | Players whose messages are silently dropped |
| `session` | `ChatSession` | Full conversation history |
| `recentMessages` | `List<String[]>` | Last 5 `[playerName, userMsg, aiReply]` — sent to late joiners |

All `SharedSession` instances live in `SharedSession.sessions` (static `ConcurrentHashMap<String, SharedSession>`).

### Session lifecycle

```
/talkwith session server create [name]
  → PacketSessionControl("server_create", name)
  → new SharedSession(ownerUuid, ...) with optional name
  → sessions.put(id, session)
  → SessionWorldData.save()
  → PacketOpenGui(sessionId) sent to owner

/talkwith session join <name-or-id>
  → PacketJoinSession(nameOrId)
  → server resolves by sessionName (case-insensitive) first, then by exact UUID
  → session.players.add(uuid)
  → PacketOpenGui(sessionId) + recent history broadcast sent to joiner

/talkwith session leave
  → PacketSessionControl("leave", "")
  → owner: transfers ownership to next online member, or removes session
  → non-owner: removed from players set
  → PacketOpenGui("") sent to leaver

/talkwith session delete
  → PacketSessionControl("delete", "")
  → owner-only; removes session + notifies all members

> hello world   (chat shortcut while in session)
  → ServerChatEvent handled by ServerEventHandler
  → AIClient.sendAsync with session's prompt/model/key
  → PacketSessionBroadcast to all members
  → SessionWorldData.save()
```

### Ownership transfer

When the owner disconnects (`PlayerLoggedOutEvent`) or explicitly leaves:
1. The next **online** member becomes the new owner.
2. Session is saved to world data.
3. If no online members remain, the session is removed from memory (but world data still contains it and will be restored on next load).

---

## 7. Session Persistence — World Save Data

### Architecture

Session state is saved into the **world save** file:

```
<world>/data/talkwith_sessions.dat   ← NBT compound
```

This means session history travels with the world (not with the server config folder), which is the
correct behaviour for world-specific AI conversations.

### `SessionWorldData`

Extends Forge `WorldSavedData`.  Accessed via:

```java
SessionWorldData.get()      // gets or creates the instance from overworld MapStorage
SessionWorldData.save()     // marks dirty → auto-flushed on next world save tick
SessionWorldData.restore()  // called on WorldEvent.Load (dim 0); also triggers JSON migration
```

### NBT format

```
NBTTagCompound (root)
  └─ sessions: NBTTagList[NBTTagCompound]
       ├─ {data: "<JSON string>"}    ← full session as toJson()
       ├─ {data: "<JSON string>"}
       └─ ...
```

The JSON strings are produced and consumed by `SessionPersistence.toJson()` / `fromJson()`.
Using JSON-inside-NBT means we reuse the existing serializer without duplicating logic.

### `SessionPersistence`

Now a **serializer/migration helper** only:

- `toJson(SharedSession)` — serializes to JSON string (includes `sessionName`, `sessionPromptFile`, full history).
- `fromJson(String)` — deserializes; backward-compatible (missing fields default gracefully).
- `save(SharedSession)` / `delete(String)` — write/delete files in `config/talkwith/sessions/` (manual use / migration source).
- `loadAll()` — scans `config/talkwith/sessions/*.json` and populates `SharedSession.sessions`; called by `SessionWorldData.restore()` when world data is empty (one-time migration).

### Save triggers

`SessionWorldData.save()` is called after every mutation:
- AI reply received
- Session created / deleted / closed
- Session settings changed (model, baseurl, apikey, promptFile, cooldown)
- Ownership transferred
- Player joins or is kicked

---

## 8. Switch Mechanism

Players can freely toggle between **local-AI mode** and **session-AI mode** without leaving the session.

### `/talkwith switch single`

- Client: sets `ClientProxy.isSingleOverride = true` immediately (for local status display).
- Server: `PacketSessionControl("switch_single", "")` adds the player's UUID to `ServerEventHandler.singleModeOverride`.
- Effect: `> messages` are routed to `PacketClientAIRequest` (client-local AI) even while the player remains a session member.

### `/talkwith switch multi`

- Client: sets `ClientProxy.isSingleOverride = false`.
- Server: `PacketSessionControl("switch_multi", "")` removes from `singleModeOverride`.
- Effect: `> messages` resume going to the shared session AI.

### Override lifecycle

`singleModeOverride` is cleared:
- On logout (`PlayerLoggedOutEvent`)
- On session leave/delete/kick
- On `switch multi`
- On `session client` (return to fully client mode)

### Status display

`/talkwith status` (while in session) sends `PacketSessionControl("status_info", "")` to the server.  
The server replies with a formatted message showing:

```
[TalkWith] Mode: <Single-override / Single-player / Multi-player> (<owner / member>)
           | Session: <name or id> | Members: N | Prompt: <file.json>
```

---

## 9. Network Packets

All packets are registered in `PacketHandler.init()` with `SimpleNetworkWrapper` on channel `talkwith`.

| Packet | Direction | Purpose |
|---|---|---|
| `PacketHandshake` | S→C | Tells client the server has the mod; client sets `serverHasMod = true` |
| `PacketOpenGui` | S→C | Sets `ClientProxy.currentSessionId`; empty string clears it |
| `PacketSessionControl` | C→S | Multiplex for all session management actions (see below) |
| `PacketJoinSession` | C→S | Join a session by name or UUID |
| `PacketSessionMessage` | C→S | Send a message to the session's AI (from GuiAIChat) |
| `PacketSessionBroadcast` | S→C | Deliver `[playerName, userMsg, aiReply]` to all session members |
| `PacketShareInvite` | S→C | Show an invitation to the target player (invite flow) |
| `PacketClientAIRequest` | S→C | Request client to process a message with local AI (no session) |

### `PacketSessionControl` actions

| Action | Target | Description |
|---|---|---|
| `server_create` | optional name | Create a new shared session |
| `client_create` | — | Disband/leave all sessions; return to client mode |
| `list` | — | List all active sessions |
| `info` | — | Show info for player's current session |
| `status_info` | — | Detailed status for `/talkwith status` |
| `join` / `invite` | playerName | Create/invite to session (legacy) |
| `leave` | — | Leave current session (with ownership transfer) |
| `delete` | — | Owner-only: delete session + notify members |
| `close` | — | Alias for delete |
| `single` | — | Legacy leave action |
| `kick` | playerName | Owner-only: remove a member |
| `mute` / `unmute` | playerName | Owner-only: mute/unmute a member |
| `cooldown` | seconds | Owner-only: set cooldown |
| `setting_model` | model | Owner-only: change session model |
| `setting_baseurl` | url | Owner-only: change session base URL |
| `setting_apikey` | key | Owner-only: change session API key |
| `cfg_prompt_file` | filename | Owner-only: set session prompt file (auto-creates if missing) |
| `cfg_list_prompts` | — | List available prompt JSON files on server |
| `switch_single` | — | Enter single-mode override |
| `switch_multi` | — | Exit single-mode override |
| `history_clear` | — | Owner-only: clear session conversation history |

---

## 10. Commands Reference

All commands are client-side (`/talkwith` registered via `ClientCommandHandler`).  
Server-side actions are forwarded via packets.

### `/talkwith config`

```
/talkwith config reload
    Reload client config (talkwith.cfg + active prompt file).

/talkwith config client baseurl [url]
    Show or set the local API base URL.

/talkwith config client keyset <key>
    Set the local API key.

/talkwith config client model [name]
    Show or set the local model.

/talkwith config client prompt_file [filename.json]
    Show or set the client's active prompt file.
    If the file doesn't exist it is auto-created with the default prompt.

/talkwith config client list_prompts
    List all *.json files found in config/talkwith/.

/talkwith config server baseurl <url>
    Set the current session's base URL (owner only).

/talkwith config server keyset <key>
    Set the current session's API key (owner only).

/talkwith config server model <name>
    Set the current session's model (owner only).

/talkwith config server prompt_file <filename.json>
    Set the current session's prompt file (owner only).
    File is auto-created with default content if it doesn't exist.

/talkwith config server list_prompts
    List available prompt files on the server.
```

### `/talkwith session`

```
/talkwith session server create [name]
    Create a new shared session with an optional human-readable name.

/talkwith session client
    Leave all sessions and return to client-local AI mode.

/talkwith session join <name-or-id>
    Join a session by its name (case-insensitive) or UUID.

/talkwith session leave
    Leave the current session (ownership is transferred if you're the owner).

/talkwith session delete
    Owner only: delete the session and notify all members.

/talkwith session list
    List all active sessions.

/talkwith session info
    Show details about your current session.

/talkwith session invite <player>
    Invite a player to your session (or create one first).

/talkwith session kick <player>       (owner only)
/talkwith session mute <player>       (owner only)
/talkwith session unmute <player>     (owner only)
/talkwith session cooldown <seconds>  (owner only)
/talkwith session history clear       (owner only)
```

### `/talkwith switch`

```
/talkwith switch single
    Stay in session but route ">" messages to local AI.

/talkwith switch multi
    Resume routing ">" messages to the shared session AI.
```

### `/talkwith status`

- **Client mode:** shows base URL, model, and active prompt file (local, instant).
- **Session mode:** queries server for live session info (mode, role, name, member count, prompt file).

### `/talkwith history`

```
/talkwith history clear     Clear local client chat history.
/talkwith history show      Show number of messages in local history.
```

---

## 11. Localization

Language files are in `src/main/resources/assets/talkwith/lang/`:

| File | Language |
|---|---|
| `en_US.lang` | English |
| `zh_CN.lang` | Simplified Chinese |

All user-facing strings use translation keys of the form `talkwith.*`.  
Server-side messages use `ChatComponentTranslation` so they are rendered in the **client's** language.  
Client-side command output uses `StatCollector.translateToLocal(key)`.

To add a new language, copy `en_US.lang` and translate the values.

---

## 12. AI Backend

### `AIClient`

Sends requests to the OpenAI chat completions endpoint:

```
POST <baseUrl>/v1/chat/completions
Authorization: Bearer <apiKey>
Content-Type: application/json

{
  "model": "<model>",
  "messages": [ {"role":"system","content":"..."}, {"role":"user","content":"..."}, ... ]
}
```

All calls are asynchronous (dedicated `ExecutorService`).  
Callbacks `onSuccess(String reply)` / `onError(String message)` are called on the executor thread;
callers must schedule GUI/world updates back to the main thread (via `ClientProxy.scheduleOnMainThread`
or the server main thread for session broadcasts).

### `ChatSession`

In-memory list of `ChatMessage(role, content)` objects.  
`getMessages(systemPrompt)` returns the system message + the last `Config.maxHistory * 2` messages
(sliding window to stay within token limits).

### `ApiPinger`

Called by `config client baseurl` and `config client keyset` to verify connectivity.
Sends a minimal request and logs success/failure.

---

## 13. Data Flow Diagrams

### Client-only AI chat (no server session)

```
Player types "> hello"
  └─► ServerChatEvent (server)
        └─► No session found (or singleModeOverride set)
              └─► PacketClientAIRequest(playerName, "hello") → client
                    └─► ClientProxy.scheduleOnMainThread
                          └─► ClientProxy.clientSession.getMessages(Config.systemPrompt)
                                └─► AIClient.sendAsync(...)
                                      └─► HTTP POST /v1/chat/completions
                                            └─► reply → chat message in client
```

### Shared session AI chat

```
Player types "> hello"
  └─► ServerChatEvent (server)
        └─► Session found, not singleModeOverride
              └─► cooldown / mute checks
                    └─► session.session.getMessages(Config.loadPromptFromFile(session.sessionPromptFile))
                          └─► AIClient.sendAsync(...)
                                └─► HTTP POST /v1/chat/completions
                                      └─► reply
                                            ├─► session history updated
                                            ├─► SessionWorldData.save()
                                            └─► PacketSessionBroadcast → all members
```

### Session save on world save

```
Any session mutation
  └─► SessionWorldData.save()  (= markDirty())
        └─► Minecraft world auto-save tick
              └─► SessionWorldData.writeToNBT(nbt)
                    └─► for each session: SessionPersistence.toJson(session)
                          └─► stored as NBT string in <world>/data/talkwith_sessions.dat
```

### Session restore on world load

```
WorldEvent.Load (dim 0)
  └─► ServerEventHandler.onWorldLoad
        └─► SessionWorldData.restore()
              └─► world.mapStorage.loadData(SessionWorldData.class, "talkwith_sessions")
                    ├─► file exists → readFromNBT → sessions populated
                    └─► file missing → SessionPersistence.loadAll() (JSON migration)
                              └─► markDirty() so it's saved as world data next time
```
