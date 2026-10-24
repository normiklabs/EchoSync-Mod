<div align="center">

![EchoSync](https://cdn.modrinth.com/data/cached_images/50c97c42764e44ca8e7831250a8579f152cbaa89.png)

[![Source](https://img.shields.io/badge/Source-FFD84D?style=for-the-badge&logo=github&logoColor=111111)](https://github.com/normiklabs/EchoSync-Mod)
[![API SDK](https://img.shields.io/badge/Developer_API-9B7FE0?style=for-the-badge&logo=java&logoColor=white)](https://github.com/normiklabs/EchoSync-API)
[![Support](https://img.shields.io/badge/Support-FFD84D?style=for-the-badge&logo=ko-fi&logoColor=111111)](https://ko-fi.com/normiklabs)

</div>

---

## 📡 What is EchoSync?

EchoSync is a real-time data sync network for Minecraft mods that need to exchange data between players in real time. Normally, developers need to write their own data sync logic, handle their own authentication, and host the infrastructure behind it. EchoSync solves all of that.

---

## 📦 Do I Need to Download This?

**For Players:**

Install EchoSync if another mod lists it as a required or optional dependency. Installed on its own, EchoSync stays idle and does nothing.

**For Mod Developers:**

EchoSync is currently in a selected testing state. If you are interested, reach out at [contact@normik.org](mailto:contact@normik.org).

---

## 🔒 Privacy Disclosure

When an installed mod calls EchoSync, your client connects to our servers to sync data. Installed on its own, EchoSync stays idle.

**What we see:**
- Your IP address (required for any internet connection, never shared with other players)
- Your Minecraft username and UUID (to prevent players from impersonating you)
- The mod's synced data (example below)

**What other players using the same mod can see:**
- Your Minecraft UUID / username (so their client knows which player the data belongs to)
- The synced mod data

**Example of synced data ([Descriptive](https://modrinth.com/plugin/descriptive)):**
- Current server channel/topic (so only players on the same server see your styled nametag)
- Name color & gradient colors (`color`, `gradientColors`)
- Text formatting flags (`bold`, `italic`, `underlined`, `strikethrough`)
- Active animations & speed (`animationEnabled`, `animationTypes` like `wave` or `rainbow`, `animationSpeed`)

We never write any data to disk, so your data only exists as long as you are connected and syncing.

---

## 🧑‍💻 Why developers (should) love it

As a developer, you won't need to:

- Host any infrastructure & maintain it
- Hassle with player verification and spoofing
- Think about any logic behind the syncing

A full developer guide is coming when we are out of selected testing.

---

## 📥 Getting started

### 🎮 Minecraft Client

1. Download **EchoSync** for your loader (**Fabric** or **NeoForge**).
2. Drop the `.jar` into your mods folder alongside any mod that uses EchoSync.
3. Boot up Minecraft. EchoSync works automatically.

### 🖥️ Minecraft Dedicated Server

1. Download **EchoSync** and drop it into your server's mods folder.
2. Start the server. Server-side mods can broadcast packets over `srv:*` topics.

> ⚠️ Only download from [Modrinth](https://modrinth.com/mod/echosync). Don't trust random sites!

---

## 🐛 Something broken?

Open an issue on [GitHub](https://github.com/normiklabs/EchoSync-Mod/issues). Describe your issue and include which version and loader you are using, what other mods you have installed, and any logs.

---

## 📩 Contact

Reach out to us at [contact@normik.org](mailto:contact@normik.org).

---

<div align="center">

*Enjoyed it? A ❤️ on Modrinth or a ⭐ on GitHub genuinely makes our day.*

</div>