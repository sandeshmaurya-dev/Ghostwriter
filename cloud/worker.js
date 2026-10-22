/**
 * Ghostwriter Cloud — the free hosted AI proxy for the Ghostwriter Android app.
 *
 * WHAT IT DOES
 *   The app talks to this Worker as if it were an OpenAI-compatible server
 *   (GET /models, POST /chat/completions). The Worker validates a baked-in
 *   access token, injects the OWNER'S Groq API key(s), and streams the response
 *   straight through. The user never sees or enters a key.
 *
 *   Real upstream: https://api.groq.com/openai/  (OpenAI-compatible, free tier)
 *
 * SECURITY / ABUSE MODEL
 *   - gwToken (below) is a path-scoped secret shared only with the app build.
 *     Requests without it get 401. It is friction, not absolute protection —
 *     anyone who unzips the APK can read it. Acceptable for a starter service.
 *   - Round-robin across MULTIPLE Groq keys in the GROQ_KEYS text: spreads the
 *     free-tier per-key limits (add one key per line in a Cloudflare "Secret").
 *   - Soft IP throttle in isolate memory: 1 request / 3s and 120 requests/hour
 *     per IP. Imperfect (Workers isolate memory resets), but it stops the most
 *     obvious single-machine spam. For harder guarantees add a Durable Object.
 *
 * DEPLOY (free)
 *   1. dash.cloudflare.com → Workers & Pages → Create Worker → name it
 *      (e.g. ghostwriter) → Deploy (any code) → Edit code → paste this file → Deploy.
 *   2. Worker → Settings → Variables & Secrets:
 *        - Add Secret: Name = GROQ_KEYS,   Value = gsk_key1,gsk_key2,... (your Groq keys)
 *        - Add Secret: Name = GW_TOKEN,    Value = a long random string (make one up,
 *                  e.g. openssl rand -hex 16). The app build's CloudConfig.DEFAULT_ENDPOINT
 *                  must end with /gw/<this same token>.
 *   3. Note your worker URL: https://<name>.<subdomain>.workers.dev
 *      The app endpoint becomes: https://<name>.<subdomain>.workers.dev/gw/<GW_TOKEN>
 *      (If it 404s on /gw/<token>/models, add "Compatibility date >= 2024-09-23"
 *      in Worker settings → the router here only needs the standard runtime.)
 *
 * TEST (with GW_TOKEN=abc and GROQ_KEYS set):
 *   curl https://<name>.<subdomain>.workers.dev/gw/abc/models
 *   curl -X POST https://<name>.<subdomain>.workers.dev/gw/abc/chat/completions \
 *        -H "Content-Type: application/json" -d '{"model":"llama-3.1-8b-instant",
 *          "messages":[{"role":"user","content":"hi"}]}'
 */

const UPSTREAM = "https://api.groq.com/openai/v1";

// Read from Cloudflare env (Variables & Secrets). Fallbacks are empty → 401 until set.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const token = (env.GW_TOKEN || "").trim();

    if (!token) {
      return json({ error: "Worker not configured: GW_TOKEN secret is missing" }, 500);
    }
    if (!(url.pathname === "/gw/" + token || url.pathname.startsWith("/gw/" + token + "/"))) {
      return json({ error: "Unauthorized" }, 401);
    }
    // Path after the token decides what we proxy; empty path → 404 (like upstream).
    const rest = url.pathname.slice(("/gw/" + token).length);
    if (rest !== "" && rest !== "/") {
      // explicit subpath form /gw/<token>/models
      url.pathname = rest;
    } else {
      return json({ error: "Not found" }, 404);
    }

    const keys = (env.GROQ_KEYS || "")
      .split(",")
      .map(k => k.trim())
      .filter(k => k.startsWith("gsk_"));
    if (keys.length === 0) {
      return json({ error: "Worker not configured: GROQ_KEYS secret is missing/invalid" }, 500);
    }

    // Soft per-IP throttle (see note above).
    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    const gate = throttle(ip);
    if (!gate.ok) {
      return json({
        error: "Rate limit exceeded, retry after " + gate.retryAfter + "s",
      }, 429);
    }

    const target = UPSTREAM + url.pathname + url.search;
    const upstreamKey = keys[Math.floor(Math.random() * keys.length)];

    const upstreamInit = {
      method: request.method,
      headers: {
        "Authorization": "Bearer " + upstreamKey,
        "Content-Type": "application/json",
      },
      // Streaming requests pass through untouched; the body was already consumed
      // only for methods with one.
      body: (request.method === "GET" || request.method === "HEAD") ? undefined : request.body,
      // double-ended streaming requires cf duplex
      duplex: (request.method === "GET") ? undefined : "half",
    };

    const upstream = await fetch(target, upstreamInit);

    if (!upstream.ok) {
      // Upstream rate-limited THIS provider key — surface the original body so the
      // app's error mapping sees the real code/message.
      return new Response(upstream.body, upstream);
    }

    // Straight pass-through: SSE / JSON / anything. Headers mostly kept.
    const headers = new Headers(upstream.headers);
    headers.delete("set-cookie");
    headers.delete("content-encoding"); // body already decompressed by fetch()
    return new Response(upstream.body, { status: upstream.status, headers });
  },
};

function json(obj, status) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

// ── isolate-memory throttle ────────────────────────────────────────────────────
const hits = new Map(); // ip -> [timestamps ms]
const WINDOW = 3600_000; // 1 hour
const MAX_PER_HOUR = 120;
const MIN_GAP = 3000; // 3s between requests

function throttle(ip) {
  const now = Date.now();
  const list = (hits.get(ip) || []).filter(t => now - t < WINDOW);

  if (list.length) {
    const gap = now - list[list.length - 1];
    if (gap < MIN_GAP) {
      return { ok: false, retryAfter: Math.ceil((MIN_GAP - gap) / 1000) };
    }
  }
  if (list.length >= MAX_PER_HOUR) {
    const oldest = list[0];
    return { ok: false, retryAfter: Math.ceil((WINDOW - (now - oldest)) / 1000) };
  }

  list.push(now);
  hits.set(ip, list);
  if (hits.size > 10000) hits.clear(); // crude memory cap; a restart is fine
  return { ok: true };
}
