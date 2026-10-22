<div align="center">

# ✍️ Ghostwriter

**The invisible AI that lives in your text boxes.**

Type `?`, say what you want, watch your text transform. Any app. No sign-up. No API keys. No keyboard to learn.

![License: MIT](https://img.shields.io/badge/License-MIT-22d3ee?style=flat-square&labelColor=0b1120)
![Android 6.0+](https://img.shields.io/badge/Android-6.0%2B-3ddc84?style=flat-square&labelColor=0b1120)
![Zero config](https://img.shields.io/badge/Sign--up-none-ff5e7a?style=flat-square&labelColor=0b1120)
![No tracking](https://img.shields.io/badge/Analytics-zero-7f5af0?style=flat-square&labelColor=0b1120)

---

<code>Original&nbsp;:&nbsp;hlo sir i want meet 2mora</code>

| | Type | Result |
|---|---|---|
| 🩹 | `?fix` | Hello sir, I want to meet tomorrow. |
| 💼 | `?formal` | Good afternoon. I would like to schedule a meeting tomorrow. |
| 🧊 | `?shorten` | Meet tomorrow? |
| 🇪🇸 | `?translate:es` | Hola señor, quiero vernos mañana. |

*(Every command streams in live, right inside the box you're typing in.)*

</div>

---

## ⚡ Why Ghostwriter

Other AI-writing tools ask you to sign in, paste text on their website, or replace your entire keyboard. Ghostwriter does none of that.

- **Zero configuration** — install it, switch the accessibility service on, done. No accounts, no keys, no setup wizard.
- **Works in *every* app** — WhatsApp, Gmail, Telegram, Chrome, Notes, your weird bank app. If it's a text box, Ghostwriter works there.
- **It reads the screen, not just your text** — `?reply` doesn't transform a sentence; it drafts the message *you* would send in the chat that's open in front of you.
- **Tiny and clean** — no analytics, no telemetry, no Firebase, no tracking SDKs. Networking is done with plain `HttpURLConnection`; the whole app stays lean.

## 🚀 Installation

1. **Download** the APK from [Releases](https://github.com/sandeshmaurya-dev/Ghostwriter/releases) and install it (sideload: allow "install unknown apps").
2. **Open Settings → Accessibility → Ghostwriter** and toggle it on. One toggle, once.
3. **Type `?fix`** in any text box with some text in it. That's the whole tutorial.

> Android's accessibility framework doesn't grant this toggle programmatically — by design, and Ghostwriter respects that. It's the only manual step on any device.

## 🪄 Commands

The default trigger prefix is `?` (changeable in Settings).

### AI commands — edit any text box

| Command | What happens to your text |
|---|---|
| `?fix` | Grammar, spelling and punctuation, fixed |
| `?improve` | Clears it up and makes it flow |
| `?shorten` | Same meaning, fewer words |
| `?expand` | More detail, nothing invented |
| `?formal` / `?casual` | Business polish / friendly looseness |
| `?human` | Natural, human-sounding prose — drops AI clichés |
| `?emoji` | Adds fitting emojis throughout |
| `?translate:xx` | Translate — any language code: `?translate:fr`, `?translate:hi`, `?translate:de`, … |
| `?summary` | Summarizes what's on the screen |
| `?reply` | **Drafts your next message from the open conversation** |

### Built-in system commands — instant, fully offline

| Command | Action |
|---|---|
| `?undo` | Revert to the text you had before the last command |
| `?copy` / `?cut` | Clipboard + erase |
| `?paste` | Paste clipboard content |
| `?replace` | Swap your text with the clipboard's |
| `?clear` | Empty the box completely |

### Make your own

Settings → **create any command**: pick a trigger (any word you like) and write the prompt → `?improve`, `?email my manager a sick note`, whatever you want. Your commands are seeded with sensible defaults on day one and stay fully editable.

## 👻 Screen-aware commands (the party trick)

Open any chat, scroll to the conversation, tap the empty reply box, and type:

```
?reply
```

Ghostwriter reads the visible chat, understands context, tone and language — then writes a reply *you* would actually send. Slang stays slang. Emojis stay emojis. Long threads get short answers.

Same superpower for reading: `?summary` condenses whatever's in view — articles, email chains, group-chat arguments — into a few clean sentences.

## 🔩 Keep-alive — built like a service, not a toy

Android OEMs (Xiaomi, Realme, Vivo, Oppo…) love to kill background services. Ghostwriter fights back and stays honest about it:

- **Persistent status notification** — the shade always shows the truth: *active* or *stopped*, one tap to fix
- **Quick Settings tile** — glance at your shade, know Ghostwriter is alive
- **Battery-exemption & autostart guidance** — the app walks you through each OEM's screens (own dashboard card, only shown when something actually needs doing)
- **Crash detection** — survives the "toggle says on but service is dead" limbo

## 🔒 Privacy

- **You never touch an API key.** Ghostwriter ships with a hosted backend ("Ghostwriter Cloud"); the credentials live in the cloud worker and never in the app or on your device.
- **Text goes one place** — the command you typed is sent to that backend, transformed, and returned. Nothing is stored. No logs of your writing are kept.
- **The app phones nowhere else.** No analytics, no ads, no SDKs, no trackers. The APK is small enough that you can audit it yourself — [the source is right here](https://github.com/sandeshmaurya-dev/Ghostwriter).
- The system commands (`?copy`, `?paste`, `?clear`…) and `?translate`'s language routing are resolved **entirely on-device**.

## 🔧 How it works

```
┌─────────────────┐   your keystrokes?   ┌──────────────────┐  command  ┌───────────────┐
│  any app's      │ ───────────────────► │  Accessibility   │ ────────► │ Ghostwriter   │
│  text box       │                      │  Service         │           │ Cloud         │
└─────────────────┘                      └──────────────────┘ ◄──────── └───────────────┘
                                               │
                                               ▼
                                    streams the replacement
                                    directly into the box
```

- **Zero-config by design** — the app, the hosted backend and the accessibility service form one pipeline that just works on install
- On-device: command parsing, system commands, trigger matching, screen-reading
- AI transformations execute on the hosted worker and stream back token by token
- **No external dependencies** for networking/JSON — `HttpURLConnection` and `org.json` only
- Build it yourself below — every feature is in this repository

## 🛠️ Building from source

```bash
git clone https://github.com/sandeshmaurya-dev/Ghostwriter.git
cd Ghostwriter
./gradlew :app:assemblePreview    # debuggable "Preview" flavor
# → app/build/outputs/apk/preview/app-preview.apk
```

- **Android Studio** Electric Eel or newer opens it natively
- CI builds release APKs automatically via GitHub Actions on every push to `master`

## 📸 Screenshots

> Live-build screenshots are being finalized — this space will be filled in shortly.

<!--
| Dashboard | Reading a chat | Keep-alive card |
|---|---|---|
| ![Dashboard](screenshots/dashboard.png) | ![In-app control](screenshots/chat.png) | ![Keep-alive](screenshots/keepalive.png) |
-->

## 🗺️ Roadmap

- [x] Zero-config hosted AI backend
- [x] Screen-aware `?reply` and `?summary`
- [x] `?translate:<language>` in-line translator
- [x] Real `?clear` system command
- [x] Case-insensitive, autocorrect-tolerant trigger matching
- [x] Keep-alive armor: status notification + QS tile + OEM guidance
- [ ] First-run interactive demo
- [ ] Translation language picker UI
- [ ] Per-device usage stats dashboard

## 🤝 Contributing

Contributions are welcome — bug fixes, new tricks, better copy. Read [CONTRIBUTING.md](CONTRIBUTING.md), open an issue, and keep the codebase small and dependency-free the way it is.

Report vulnerabilities privately via [SECURITY.md](SECURITY.md).

## 🧾 Credits & lineage

Ghostwriter is built on the shoulders of [SwiftSlate](https://github.com/Musheer360/SwiftSlate) by **Musheer Alam** — a beautifully minimal command engine for Android text boxes. Ghostwriter's accessibility architecture, command system and service design continue to honor that foundation, extended with the hosted Cloud backend, screen-aware commands and survival armor.

Maintained and expanded by **Sandesh Maurya** ([@sandeshmaurya-dev](https://github.com/sandeshmaurya-dev)).

## 📜 License

[MIT](LICENSE) — © 2026 Sandesh Maurya, © 2026 Musheer Alam. Free to use, modify and ship; keep the notice.
