<div align="center">

![EchoSync](https://cdn.modrinth.com/data/cached_images/50c97c42764e44ca8e7831250a8579f152cbaa89.png)

[![Source](https://img.shields.io/badge/Source-FFD84D?style=for-the-badge&logo=github&logoColor=111111)](https://github.com/normiklabs/EchoSync-Mod)
[![Website](https://img.shields.io/badge/Website-9B7FE0?style=for-the-badge&logo=googlechrome&logoColor=white)](https://echosync.normik.org)
[![Support](https://img.shields.io/badge/Support-FFD84D?style=for-the-badge&logo=ko-fi&logoColor=111111)](https://ko-fi.com/normiklabs)

</div>

---

## 📡 What is EchoSync?

EchoSync is a shared networking layer for Minecraft mods that need to exchange data between players in real time, such as nametags, waypoints, party HUDs, or custom animations.

Normally, each of these mods opens its own connection, handles its own authentication, and often needs a plugin on the server. EchoSync replaces all of that with one background connection shared by every mod that uses it. Data travels through EchoSync relays instead of the game server, so it works on vanilla servers too.

Players install one runtime mod. Developers add one small API.

---

## ⚡ Why developers (should) love it

🛠️ **No hosting infrastructure**: You don't need to design the server logic or even host it yourself.

🛡️ **Verified identities**: Players are authenticated through Mojang's session servers, so the UUID on every packet is real and can't be spoofed.

🌍 **Regional relays with automatic failover**: You connect to the closest region (`EU`, `NA`, `AS`, `SA`, `AF`, `OC`). If a relay goes down, EchoSync switches to a backup without interrupting the game.

🪶 **Off the game thread**: All networking runs on background virtual threads, so it doesn't affect FPS or tick time.

---
## 🛠️ Quick developer start guide

Add `echosync-api` to your mod. It has no external dependencies, and if a player doesn't have the EchoSync runtime installed, every call quietly does nothing instead of crashing the game.

```groovy
dependencies {
    implementation "org.normik.echosync:echosync-api:1.0.0"
    include "org.normik.echosync:echosync-api:1.0.0"
}
```

```java
// 1. Open a channel for your mod
EchoSyncChannel channel = EchoSync.channel("com.you.yourmod", "epk_live_...");

// 2. Receive packets from other players
channel.subscribe("pub:nametags", packet -> {
    UUID sender = packet.senderUuid();
    byte[] data = packet.payload();
});

// 3. Send data to everyone subscribed to the topic
channel.send("pub:nametags", myBytes);
```

Topics are grouped by prefix, which decides who can send and who can receive:

| Prefix | Who can send | Who can receive | Good for |
|---|---|---|---|
| 📢 `pub:<topic>` | Verified players and servers | Everyone | Nametags, emotes, waypoints, proximity data |
| 🖥️ `srv:<topic>` | Dedicated servers only | Everyone | Announcements, boss timers, server rules |
| 🔒 `priv:<uuid>:<topic>` | Matching player or server | That player only | Private HUD alerts, personal messages |

👉 Read the full developer docs and wire spec at **[~~echosync.normik.org/docs~~](https://echosync.normik.org/docs)** (Temporarily unavailable).

---

## 📥 Getting started

### 🎮 Minecraft Client

1. Download **EchoSync** for your loader (**Fabric** or **NeoForge**).
2. Drop the `.jar` into your `.minecraft/mods/` folder alongside any mod that uses EchoSync (such as **Descriptive**).
3. Boot up Minecraft. EchoSync works automatically in the background with no configuration needed!

### 🖥️ Minecraft Dedicated Server

1. Download **EchoSync** and drop it into your server's `mods/` folder.
2. Start the server. Server-side mods using Secret Keys (`esk_live_...`) can broadcast authoritative packets over `srv:*` topics.

> ⚠️ Only download from [Modrinth](https://modrinth.com/mod/echosync). Don't trust random sites!

---

## 🐛 Something broken?

Open an issue on [GitHub](https://github.com/normiklabs/EchoSync-Mod/issues). Describe your issue, and include which version and loader you're using, what other mods you have installed, and any logs. We'll sort it out.

---

## 📩 Contact

Reach out to us at [contact@normik.org](mailto:contact@normik.org).

---

<div align="center">

*Enjoyed it? A ❤️ on Modrinth or a ⭐ on GitHub genuinely makes our day.*

</div>