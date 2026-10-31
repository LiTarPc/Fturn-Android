# RAW Android verification IPC v1

Opt in with FREETURN_RAW_CAPTCHA_TOKEN (64 hex chars / 256 bits) and
FREETURN_RAW_CAPTCHA_GENERATION (8..128 ASCII alphanumeric, underscore, hyphen).
FREETURN_RAW_BROWSER_CAPTCHA=1 selects the browser-first solver unless the
profile explicitly requests manual-only. No IPC environment means legacy CLI.

Go listens only at 127.0.0.1:ephemeral. Every request requires:
Authorization: Bearer <token>
X-Fturn-Generation: <generation>
Host must match 127.0.0.1:<port>. Origin and URL queries are rejected.

stdout lines begin exactly FTURN_EVENT followed by space and one JSON object:
{v:1,type:"captcha",generation,request_id,mode:"browser"|"manual",
 phase:"pending"|"resolved"|"expired"|"cancelled"|"unavailable",
 expires_at_ms,ipc_port}
No private URL, token or solution appears in the event.

GET /v1/captcha/<request_id> returns JSON:
{generation,request_id,mode,expires_at_ms,url}
URL is an http://localhost:8765 or http://127.0.0.1:8765 verification-proxy URL.
The host validates generation, ID, TTL, scheme, port and origin before loading.

POST /v1/captcha/<request_id>/host, JSON:
{generation,status:"available"|"unavailable"|"manual_requested",reason}
reason is empty or host_unavailable|renderer_gone|load_failed|challenge_expired|
user_requested. Body is capped at 2048 bytes; unknown keys/trailing JSON rejected.
Unavailable or manual_requested promotes the SAME challenge/URL/ID to manual.
After 45s without a result, browser mode also becomes manual. The original
3-minute overall deadline is not extended. Same-ID manual pending must expose
the existing WebView rather than silently reload the page.
Results go to the existing local verification proxy, not through IPC.

A failure/timeout or a rejected browser result pauses new authorization. Event:
{v:1,type:"auth",phase:"waiting_user"|"resuming"|"authorized",generation,ipc_port}
POST /v1/auth/resume with the same auth headers resumes only on explicit user
request (no body required), idempotently. Existing cached credential groups and
active allocations keep working. Intentional user wait must not trigger an
Android watchdog restart even when no workers are ready.

HTTP: 200 successful GET; 204 successful host/resume; 400 malformed fields;
401 missing/wrong bearer; 403 Origin/Host forbidden; 409 stale generation;
410 nonexistent/finished/expired challenge. A process restart invalidates all
previous IDs, secrets and generation values. Never put these headers/private
responses in general logs or diagnostic metadata.

After an explicit resume, fresh credential success emits auth/authorized even
when no new captcha is required. This clears the RESUMING presentation, not the
ready-worker count. Cached credential hits never emit it. An existing pending
challenge or a new WAITING_USER pause cannot be cleared by this notification.
