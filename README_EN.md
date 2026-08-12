# TalkWith

A mod that lets you chat with an AI assistant inside Minecraft 1.7.10.

Use it on your own, or share a single AI session with your teammates. A custom chat interface is provided, and the vanilla chat bar mode is also supported.

## Features

### AI Chat
- Talk to the AI through a custom interface or the vanilla chat bar.
- Single-player mode: AI requests are made directly by the client; conversation data never passes through the server.
- Multi-player sessions: several players join the same session and talk to one shared AI; everyone sees each other's messages and the AI replies.

### Session Management
- Create, join, leave, disband and rename sessions, and transfer ownership to another member.
- Sessions can be public or private; private sessions support invitations and a join-request review flow.
- Moderator role to help manage members and requests.
- Mute members, kick players, and adjust the AI request cooldown per session.

### Chat Interface
- Custom AI chat screen with chat history, a message summary, and one-click history clearing.
- Settings screen for the model, API URL, API key and prompt.
- Switch back to the vanilla chat bar at any time; all features keep working.

### Prompt Management
- Every session has its own independent prompt files.
- View, edit, create, save and activate prompts from inside the game; changes take effect immediately.
- Switch the active prompt among multiple saved files.

### Team System
- Create teams with three roles: owner, officer and member.
- Invite, kick, promote and demote members; rename and disband teams.
- Merge teams: after both owners agree, members and data are merged.
- Admin commands are available to manage any team.

### Security & Configuration
- The API key is stored encrypted in the world save; the encryption passphrase is set by the user (AES).
- Session data and team data are stored in the world folder.

## Requirements
- Minecraft 1.7.10 with Forge
- A compatible AI service URL and API key

## Quick Start
1. Put the mod into the `mods` folder.
2. In game, use the `/talkwith config` commands or the settings screen to set the AI service URL and API key.
3. Run `/talkwith open` to open the AI chat screen and start chatting.
4. To share with teammates, create or join a multi-player session.
