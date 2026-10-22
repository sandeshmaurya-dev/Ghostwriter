# Ghostwriter Cloud — deployment guide (FREE)

The app ships with a **Ghostwriter Cloud (Free)** provider preset. A user picks it
in Settings and everything works — no API key needed. This guide sets up the two
free pieces that power it.

## Architecture

```
Phone app ──(POST /gw/<token>/chat/completions, SSE ok)──▶ Cloudflare Worker (free)
                                                            │ adds Authorization
                                                            ▼
                                                   api.groq.com (owner's free key)
```

The Worker is a dumb reverse proxy to **Groq** (OpenAI-compatible). Streaming,
error codes and the model catalog all pass through unchanged — that's why Groq
was chosen over Gemini for the cloud path: no format conversion anywhere.

## Step 1 — Get free Groq keys

1. Sign in at https://console.groq.com (Google/GitHub login works).
2. API Keys → **Create API Key** → copy it (starts with `gsk_`).
3. RECOMMENDED: create 2–4 keys (they can belong to one account if quota
   allows, or multiple accounts). The Worker spread-rotates across all of them,
   multiplying the free-tier rate limits.

## Step 2 — Deploy the Worker (Cloudflare free plan)

1. Sign up at https://dash.cloudflare.com (no credit card needed).
2. Left menu → **Workers & Pages** → **Create application** → **Create Worker**.
3. Name it, e.g. `ghostwriter` → Deploy (default code is fine for now).
4. Click **Edit code** → delete everything → paste the full contents of
   `cloud/worker.js` (next to this file) → **Deploy**.
5. Worker → **Settings → Variables & Secrets** (may be under "Bindings"):

   | Type   | Name       | Value                                          |
   |--------|------------|------------------------------------------------|
   | Secret | `GROQ_KEYS`| your Groq keys, comma-separated: `gsk_...,gsk_...` |
   | Secret | `GW_TOKEN` | a random string, e.g. `openssl rand -hex 16` output or any long gibberish |

6. Note the Worker's URL: `https://ghostwriter.<yoursub>.workers.dev`

## Step 3 — Point the app at it (developer step)

In `provider/ProviderConfig.kt` change:

```kotlin
const val DEFAULT_ENDPOINT =
    "https://ghostwriter.<yoursub>.workers.dev/gw/<GW_TOKEN>"
```

This is the only line that carries deployment-specific values. It is baked into
the APK the user installs (the `/gw/<token>` part is the multi-tenant access
control; it is intentionally NOT a secret against a determined reverse-engineer —
it's a rate-limit gate, not DRM).

For quick testing WITHOUT rebuilding:
```bash
adb shell am broadcast -a ... # not needed; easier:
adb shell settings ...        # no — the app reads prefs:
# Simply call the app's hidden dev override (Debug builds):
adb shell run-as ...          # skip; rebuild with the constant is the normal flow.
```
(The `cloud_endpoint` pref override exists for debug tooling; normal flow = rebuild.)

## Step 4 — Test the worker from a PC (before shipping an APK)

```bash
curl https://ghostwriter.<yoursub>.workers.dev/gw/<GW_TOKEN>/models

curl -X POST https://ghostwriter.<yoursub>.workers.dev/gw/<GW_TOKEN>/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"llama-3.1-8b-instant","messages":[{"role":"user","content":"say hi"}]}'
```

Expect a JSON model list and a chat completion respectively. SSE: add
`"stream": true` to the POST body and you should get `data: {...}` lines.

## Limits / honest notes

| Thing | Value |
|---|---|
| Cloudflare free | 100k requests/day (the whole category barely ticks this) |
| Groq free per key | ~30 req/min + daily cap (varies per model) — hence multi-key rotation |
| The `/gw/<token>` gate | Stops drive-by scanner traffic; NOT protection against a curious old user |
| Hard rate limiting | In-memory throttle here is soft. Next step if user count grows: Durable Object counter bound |
| ToS | Proxying your own key is allowed; abuse on it is your responsibility. Keep the user count sane and watch the Worker dashboard (metrics tab shows every request) |
