# Diagnosis prompt: WhatsApp test message not reaching Cloud API webhook
<!-- Copy everything below the line into any agent (including Meta support / Meta AI). No secrets inside. -->

## 1. Who we are

MiloBroker prototype. Backend: Java 25, Spring Boot 4.1.2, three services on one
EC2 instance (region ap-southeast-2), all running as healthy Docker containers:
concierge :8081 (WhatsApp webhook + Flows + AI replies), marketplace :8082,
atlas :8083. Public API via Nginx at `https://api.milobroker.com` with
prefix-strip (`/concierge/*` -> `127.0.0.1:8081`). Direct Meta integration, no
BSP/provider. AI replies use a Meta chat model only (`muse-spark-1.3` via an
OpenAI-compatible `POST {base}/chat/completions`, Bearer key) — verified
returning HTTP 200. No Llama/open models anywhere.

## 2. Integration config (IDs only, no secrets)

- Test messaging account ID: `1057188817349490` (direct integration, 0 partners).
- Test number: `+1 555-678-0198`, phone-number ID `1415608411630431`.
- Webhook callback URL: `https://api.milobroker.com/concierge/api/v1/whatsapp/webhook`.
- Flows data-exchange URL: `.../concierge/api/v1/flows/onboarding` (Flow not yet
  published; onboarding Flow ID blank — plain-text welcome fallback is expected,
  not a failure).
- Verify endpoint: `GET` with `hub.mode=subscribe`, `hub.verify_token`,
  `hub.challenge` echoes `hub.challenge` as `text/plain` 200 on exact token
  match, else 403.
- Receive endpoint: `POST` verifies `X-Hub-Signature-256` (HMAC-SHA256 over the
  raw body with the app secret), parses `entry -> changes -> value`, returns 200
  whenever the envelope parses. Unknown sender gets a welcome text; completed
  Flow replies (`interactive.nfm_reply`) complete onboarding; onboarded users get
  guardrail-checked model replies.

## 3. Symptom

A test WhatsApp message was sent to the configured test number. No webhook POST
appears in the concierge container logs (`docker logs infra-milo-concierge-1`).
Manager-side subscription state (Verify status, `messages` field, app-to-account
subscription) is unconfirmed — this is the prime suspect, not our code.

## 4. Verified working (evidence from today, same stack)

1. All three EC2 containers report healthy; the services answer their
   `/actuator/health` endpoints with 200.
2. The public webhook URL is reachable from the internet: the same GET-verify
   with a wrong token returns 403, proving the public path reaches live code
   and the guard rejects mismatches.
3. `conciergedb` tables exist (`conversation_sessions`, `inbound_events`,
   `model_executions`) — the write path for inbound messages is ready.
4. Atlas geo lookups, marketplace search (seeded ACTIVE listings return
   `total:2`), and Meta model chat all return 200 end to end, so the app, DB,
   and model stack are healthy. The failure is isolated to Meta -> webhook
   delivery or Manager configuration.
5. DB connectivity was flapping earlier (SSM tunnel) but is stable at test time.

## 5. What we need from you

1. An ordered checklist to confirm from the Meta side: (a) the app is
   subscribed to the WhatsApp Business Account (`POST /{WABA_ID}/subscribed_apps`
   state), (b) the test number is registered under the app, (c) the `messages`
   webhook field is subscribed, (d) the callback URL + verify token are saved
   and Verify shows success, (e) no quality-rating, ban, or restriction flags
   on the account/number.
2. How to confirm in Meta tooling that a specific inbound message (sender WA ID
   + timestamp supplied on request) was routed to the callback: which
   dashboards/logs show delivery attempts and the HTTP response codes?
3. Test-number constraints: are there direction/recipient limits on test phone
   numbers receiving inbound messages from real WhatsApp users that would
   silently drop them?
4. If Verify passes but POSTs never arrive: the top causes beyond a missing
   `messages` subscription (e.g. app mode live vs development, business
   verification state for test accounts, certificate/port requirements — our
   port 443 serves a valid hostname cert).
5. The exact expected POST JSON shape for a plain inbound text message
   (`entry/changes/value/messages/statuses` arrays) so we can diff it against
   our parser.

## 6. Constraints on your answer

- Keep all AI/model suggestions Meta-only; the model stack is settled.
- Do not ask for secrets (tokens, keys, app secret). The config answers needed
  are IDs, subscription states, and dashboard statuses only.
- Prefer answers checkable from the Meta App Dashboard / WhatsApp Manager /
  Graph API over code changes.
