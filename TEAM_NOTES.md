# Milobroker Prototype — Team Notes (EOD Delivery Log)

> Owner: Senior Team Lead + sub-agent squad (docs/env scout, services/webhook scout, prototype planner, synthesis lead)
> Goal: Phenomenal EOD prototype. Meta models only. No Llama / open models. Human in loop.
> Last updated: 2026-11-04 — after scout sweep + lead verification

## 1. Status snapshot

- Repo is backend-only: 3 Spring Boot Java 25 services (Maven multi-module `pom.xml`).
  - `milo-concierge` :8081 — WhatsApp webhook + Flows + guardrails + routing + audit — DB `conciergedb`
  - `milo-marketplace` :8082 — users/listings/moderation/search/leads — DB `bizdb`
  - `milo-atlas` :8083 — geo + contact lookup — DB `staticdb`
- Build: `mvn clean verify`. Run per-service: `mvn -pl <svc> spring-boot:run -Dspring-boot.run.profiles=local`
- **Live probe just now (lead verification): all three `/actuator/health` unreachable** (`Unable to connect to remote server` on 8081/8082/8083). Same-day logs claimed Tomcat started 2026-11-04T08:17Z, but that is not live proof. User said "all services ready and running" — discrepancy to resolve: please start services or share correct ports/logs.
- DB: `.env.local` points at EC2 tunnel `127.0.0.1:5433`. Alternative is `compose.yaml` postgres:18-alpine on 5432 (`milobroker/milobroker-local`). Decide target before P0.

## 2. Docs map

- [docs/index.md](docs/index.md) — catalog + canonical SQL order (wins over README) + symptom index.
- [docs/environment-configuration.md](docs/environment-configuration.md) — profile→file table (local/.env.local, dev, stage, prod); `application.yaml` defaults + `.env.${profile}` import; ports 8081/8082/8083.
- [docs/api-domain-deploy.md](docs/api-domain-deploy.md) — target `https://api.milobroker.com` Nginx `/atlas|/marketplace|/concierge` prefix-strip; actuator + onboarding + concierge blocked (404); prototype auto-approve ACTIVE; search cap 50; promotion-site `VITE_*` config.
- [docs/ec2-access.md](docs/ec2-access.md) — region ap-southeast-2, ubuntu, SSM + SSH key.
- [docs/whatsapp-setup.md](docs/whatsapp-setup.md), [docs/whatsapp-flows.md](docs/whatsapp-flows.md), [docs/whatsapp-onboarding-flow.json](docs/whatsapp-onboarding-flow.json) — test messaging account 1057188817349490, test phone +1 555-678-0198 ID 1415608411630431; per-number PKCS#8 keypair; Flow fields name/role/state/city (roles RENTAL/BUY/LIST/AGENT); INIT→data_exchange→COMPLETE; flow_token = WA ID.
- Infra: `infra/nginx`, `infra/postgres`, `infra/s3`, `middleware.compose.yaml`, `middleware.production.compose.yaml`.
- SQL (canonical order from index): V1_staticdb → V2_staticdb_whatsapp_contact → V2_bizdb → V3 listings → V4 moderation → V5 leads (bizdb) → V1_conciergedb.

## 3. Env gaps (secrets masked — never paste values)

`.env.local.example` = 34 keys. `.env.local` = 25 keys set, verified just now.

- Set in `.env.local`: APP_ENV, 3× DB_URL (→5433 tunnel), 3× DB_PASSWORD, 3× ports, 3× DB usernames, ATLAS/MARKETPLACE_BASE_URL, CORS, META_MODEL_ENABLED=true (differs from example false), WHATSAPP_VERIFY_TOKEN, WHATSAPP_API_TOKEN, PHONE_NUMBER_ID + MESSAGING_ACCOUNT_ID (test IDs), ONBOARDING_FLOW_ID (empty), API_BASE_URL v21.0, FLOW_PRIVATE_KEY_PEM (set), META_APP_SECRET (set).
- **Missing in `.env.local` (9 keys)** — file ends after META_APP_SECRET plus one stray char:
  - `META_MODEL_BASE_URL`, `MODEL_API_KEY`, `META_MODEL_NAME` (model replies)
  - `MEDIA_BUCKET`, `AWS_REGION`, `MEDIA_ENDPOINT`, `MEDIA_PRESIGN_MINUTES`, `MEDIA_MAX_IMAGE_BYTES`, `MEDIA_MAX_VIDEO_BYTES` (media/S3 presign)
- **Empty in both**: `WHATSAPP_ONBOARDING_FLOW_ID` (Flow unpublished).
- Stray char: `.env.local` line 31 trailing single char (decoded as U+FFFD) — needs hexdump + cleanup before Spring parses it.
- Action: do NOT hand-edit blindly. Confirm whether MEDIA/MODEL keys live in `.env.prod` (unread by scouts), then append missing keys to `.env.local` with safe placeholders or real values.

## 4. Services / ports / public Nginx matrix

| Service | Port | Public via api.milobroker.com | Notes |
|---|---|---|---|
| Atlas `/api/v1/geo/*`, `/api/contact` | 8083 | Yes — `/atlas/*` | CORS open for `/api/contact` only |
| Marketplace `POST /api/v1/search` | 8082 | Yes — only search | ACTIVE-only, cap 50 |
| Marketplace listings/leads/onboarding | 8082 | No — Nginx 404 | Internal / demo via direct port |
| Concierge webhook + flows | 8081 | Yes — webhook + `/flows/onboarding` | Only public concierge paths |
| Concierge `/api/v1/concierge/*`, webhook `/profile`, actuators | 8081 | No — Nginx 404 | Internal only |

Rules: HTTP-only inter-service (concierge→127.0.0.1:8082/8083); one app user per DB; manual SQL only (`ddl-auto:none`, no Flyway); `/actuator/health` each.

## 5. Meta-only model plan (confirmed)

- Only model path: `MetaModelClient.java` → `POST {baseUrl}/chat/completions`, Bearer `MODEL_API_KEY`, `{model, messages:[system,user], temperature 0.3, max_tokens 300}`. Defaults: base `https://api.meta.ai/v1`, model blank, system "You are Milo, the MiloBroker property assistant on WhatsApp...". Disabled when key/model blank → `ConversationRouter` scripted nudge fallback.
- Full-tree grep for llama/openai/gpt/Muse/anthropic/gemini/groq/mistral/huggingface/ollama/bedrock: only hit is the "Meta's OpenAI-compatible chat API" comment (protocol shape, not an OpenAI model). No vendor deps in concierge pom. **Constraint satisfied: Meta only.**
- EOD stance: stay fallback-scripted unless you supply `MODEL_API_KEY` + `META_MODEL_NAME`. Enabling = set 3 `META_MODEL_*` keys + `META_MODEL_ENABLED=true` (already true locally). Demo one model reply vs fallback line honestly.

## 6. Webhook + Flows plan

- Subscribe URLs (HTTPS public required): `https://api.milobroker.com/concierge/api/v1/whatsapp/webhook` and `.../flows/onboarding`.
- GET verify: `hub.mode/hub.verify_token/hub.challenge` (403 on mismatch; token must match `WHATSAPP_VERIFY_TOKEN`).
- POST: `X-Hub-Signature-256` HMAC via `META_APP_SECRET`; `entry→changes→value` parse; always 200 on parseable (else Meta retries); `nfm_reply` → onboarding.complete + thanks; free text: completed → `ConversationRouter.reply`, incomplete → welcome + Flow button.
- Flows endpoint: raw base64 `text/plain`, ping/INIT/BACK/data_exchange, Atlas state/city options, `FlowCryptography` 421 undecryptable, 503 without `FLOW_PRIVATE_KEY_PEM`.
- Pre-reqs: tokens + private key in env (done locally), live number IDs, per-number pubkey upload, Verify + messages subscribe; needs `WHATSAPP_ONBOARDING_FLOW_ID` publish + public 443 reachability confirmed from laptop (currently unrecorded).

## 7. Phased runbook P0–P4 (today)

- **P0 (30m) health + seed**: pick DB target (compose 5432 vs tunnel 5433); apply 7 SQL in index order with `-v ON_ERROR_STOP=1`; `mvn clean verify`; start atlas→marketplace→concierge; `curl /actuator/health` ×3 + Atlas geo probes + search 200 on seeded ACTIVE listing (create 2–3 BLR/KA RENT if empty). Exit: 3×200 + 1 search hit.
- **P1 (1h) golden path (no WhatsApp)**: Atlas dropdowns → search → Flow INIT/data_exchange via `FlowJourneyTest` path → onboarding profile saved (validates Atlas state/city + 4 intents). Exit: profile row saved.
- **P2 (1h) live WhatsApp**: fill `.env.prod` secrets; upload Flow pubkey per live number ID; Verify + subscribe messages; rehearsed 4-tap Flow on test number (first msg → welcome flow_token=WA id → INIT states → state→cities → submit → COMPLETE → thanks). Exit: end-to-end Flow on phone.
- **P3 wow (if time)**: presigned media upload on DRAFT/PENDING listing; lead request→consent→close; guardrail block + model/fallback line. Exit: each wow leg demoed once.
- **P4 harden**: `.env.prod` perms 400/UID 10001; Nginx 404-block re-verify; per-service `/app/logs` + nginx error log; promotion-site smoke (contact + search from same API).

## 8. Demo script (phenomenal EOD)

1. Laptop browser: promotion-site contact number + search results from same API.
2. Phone: first WhatsApp message → welcome + Flow button → 4 taps (name/role/state/city) → COMPLETE → thanks.
3. Narrate honestly: dormant moderation + model-off fallback are roadmap, not broken.
4. If time: media upload + lead consent + guardrail block as "wow" reel.

## 9. Open questions for owner (need answers to proceed)

1. DB target for EOD: local compose 5432 or EC2 tunnel 5433?
2. Live WhatsApp number IDs known? Is `ONBOARDING_FLOW_ID` publishable today, or demo on test IDs only?
3. Was EC2 443 public reachability ever confirmed from laptop?
4. `MEDIA_BUCKET` stack created? Bucket name + is media in demo scope?
5. Model on or off for demo — will you provide `MODEL_API_KEY` + `META_MODEL_NAME`, or stay fallback-scripted?
6. Promotion-site repo location/state for the laptop-browser leg?
7. Who fills missing MEDIA/MODEL keys — and in which `.env` file?
8. Services show down on local probe — what is the correct way you start them (ports/logs)?

## 10. Owner decisions (2026-11-04 interactive round)

- DB target: **EC2 tunnel 5433** (keep current `.env.local` URLs).
- WhatsApp: **live numbers + publish Flow** (need live number IDs + `WHATSAPP_ONBOARDING_FLOW_ID`).
- Model + media: **provide Meta + media keys now** (need `META_MODEL_BASE_URL`, `MODEL_API_KEY`, `META_MODEL_NAME`, `MEDIA_BUCKET`, `AWS_REGION`, `MEDIA_ENDPOINT`, `MEDIA_PRESIGN_MINUTES`, `MEDIA_MAX_IMAGE_BYTES`, `MEDIA_MAX_VIDEO_BYTES`).
- Still needed from owner: the actual key values / number IDs (paste here or fill directly into `.env.local` + `.env.prod`), plus how services are started (local probe still refused 8081/8082/8083).

## 10b. Live verification log (lead, escalated probes)

- All three `/actuator/health` returned 200 at one point; Atlas `GET /api/v1/geo/countries` + `states?countryIso2=IN` + `cities?state=KA` all 200 with real seed data (IN, KA, BLR Bengaluru + HUB/MLR/MYS). Webhook GET with wrong token correctly 403.
- `POST /api/v1/search` with `{}` correctly 400; with valid filters first 500 with `ERROR: relation "property_listings" does not exist` — bizdb had only `users`.
- Fix applied: `V3_bizdb_property_listings.sql` + `V4_bizdb_moderation.sql` + `V5_bizdb_leads.sql` applied via `postgres:18-alpine` psql over tunnel (all CREATE/COMMIT ok). `\dt` now shows `users, property_listings, listing_media, lead_requests`. Search then returned 200 with `total:0` (no ACTIVE listings seeded yet).
- Seeding 2 demo listings blocked: services started flapping (8081/8082 → 000, atlas → 503 DOWN), marketplace log shows Postgres `Socket.connect` failure, and port 5433 accepts nothing. Diagnosis: EC2 SSM tunnel (`start-db-tunnel.ps1` → `aws ssm start-session ... 5432→5433`) is down. Needs `aws sso login` + tunnel restart — interactive, requires owner.
- Next once tunnel is back: re-health all three, seed 2 ACTIVE KA/BLR RENT listings, re-run search (expect total:2), then P1 golden path.
- 10:05–10:15Z update: tunnel stable, all three `/actuator/health` 200. Owner profile `demo-owner-1` created via onboarding endpoint (`completed:true`); 2 listings seeded (2BHK MG Road ₹25k, 1RK HSR ₹12k — both auto-approved ACTIVE); `POST /api/v1/search` KA/BLR/RENT → `total:2`. P0 complete.
- P1 complete: Atlas states/cities live-verified; `FlowJourneyTest` 10/10 green (run offline-unsandboxed 15:45 IST); onboarding profile save live-verified.
- Concierge DB fixed: `conciergedb` had zero tables; applied `docs/V1_conciergedb.sql` via tunnel (same physical postgres EC2 services use). Now: `conversation_sessions` (per-user triage state), `inbound_events` (webhook receipts, dedupe on `channel_message_id`), `model_executions` (Meta call audit) + 2 indexes.
- Meta model configured: key verified live (`GET /models` 200; 9 IDs incl. `muse-spark-1.3`, `muse-wonder-1.0`, `muse-voice-transcribe-1.0`); `POST /chat/completions` with `muse-spark-1.3` → 200. `META_MODEL_NAME=muse-spark-1.3` + base URL written to `.env.local` (stray ZWSP line removed) and `.env.prod`. Voice model exists but `/audio/transcriptions` → 404, path unknown — STT wiring blocked on endpoint discovery. Concierge restart pending (env loads at startup), then verify live model replies. psql `select count(*) from property_listings` → 0 rows (tables intact), then minutes later psql `select 1` → `server closed the connection unexpectedly`. Services: 503 DOWN across all three, marketplace Hikari `total=0`, `Could not open JPA EntityManager`. Seed POST → 500. Cause is tunnel/DB side, not app code. Waiting on stable tunnel before retry.

## 11. WhatsApp delivery deep-research (webhook → first convo)

Sources: Meta `messages` webhook reference pattern (multiply-sourced: official reference URL
`developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/reference/messages`,
plus community setup guides converging on the same 4 steps), [WebHookRelay guide](https://webhookrelay.com/blog/whatsapp-cloud-api-webhooks/)
(verify handshake, `X-Hub-Signature-256` = HMAC-SHA256 of raw body with app secret, 200-fast, retries up to 7 days,
dedupe on `wamid`), repo handler `WhatsAppWebhookController.java:44-160`, `ConversationRouter.java:26-53`.

Five gates between a phone and our handler — every one must pass:

1. Number registered + app subscribed to the WABA. Otherwise real messages never route even when
   everything else is green. Fix: `POST /{WABA_ID}/subscribed_apps`, confirm number registered.
2. Verify handshake. Manager sends `GET ?hub.mode=subscribe&hub.verify_token=&hub.challenge=`;
   ours echoes `hub.challenge` only on exact token match (`verify()`, 403 otherwise). Until Verify
   passes, Meta delivers nothing.
3. `messages` field subscribed (Manager → Webhooks → Manage). Verification alone delivers nothing —
   the #1 silent-failure cause. This carries both inbound messages and sent/delivered/read statuses.
4. User-initiated first message (no template needed inside the 24h window for our reply).
5. POST handling: `X-Hub-Signature-256` over raw bytes with `META_APP_SECRET`; always 200 once the
   envelope parses (Meta retries non-200 up to 7 days → duplicates, dedupe on message id).

Convo-start chain in our code once POST lands:

- Text, user unknown/incomplete → `welcomeWhenIncomplete`: `claimWelcome` + `sendOnboardingFlow`
  if `WHATSAPP_ONBOARDING_FLOW_ID` set, else plain welcome text.
- Flow completed (`interactive.nfm_reply`) → `onboarding.complete` → thanks text, profile completed.
- Text, user completed → guardrail → `ConversationRouter.reply` → muse-spark-1.3 (now live) or
  fallback nudge on any model failure. Voice → static "can't listen" line until STT lands.
- Ops: `GET /webhook/profile?channelUserId=` shows onboarding state (EC2-local only, nginx-blocked).

Debug ladder if a test message stays silent: (1) GET-verify with the EC2 token (expect challenge),
(2) confirm `messages` subscribed, (3) `docker logs infra-milo-concierge-1 --tail` for the POST,
(4) confirm app→WABA subscription. Still needed: Flow publish → `WHATSAPP_ONBOARDING_FLOW_ID` in
`.env.prod` + EC2 concierge restart (also picks up the new MODEL keys); Manager callback+token+messages.

## 12. Flow endpoint spec audit (vs Meta endpoint guide)

- Keys: 9/9 required keys SET in both `.env.local` and `.env.prod` (length-checked,
  values never printed): `FLOW_PRIVATE_KEY_PEM` (1732ch PKCS#8), `META_APP_SECRET`,
  `WHATSAPP_ONBOARDING_FLOW_ID` (newly set, 16ch — publish progressed),
  `META_MODEL_*` trio, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_API_TOKEN`,
  `WHATSAPP_VERIFY_TOKEN`. Stray ZWSP line in `.env.local` already removed.
- BLOCKER: `WHATSAPP_API_TOKEN` is expired — Graph API returns 190/subcode 463
  (session expired 05:00 PST today). Outbound sends (Flow invite, welcome/model replies)
  fail until a fresh token (System User token, not temp) is installed. Inbound webhook
  unaffected. This was likely also failing sends silently.
- Crypto conformance (`FlowCryptography.java` vs spec): envelope fields
  (`encrypted_flow_data`/`encrypted_aes_key`/`initial_vector`) ✓; RSA/ECB/OAEP-SHA256+MGF1 ✓;
  AES-128-GCM 128-bit tag ✓; bit-flipped response IV ✓; base64 `text/plain` response ✓;
  `data_api_version 3.0` in Flow JSON ✓; ping→`status:active` ✓; INIT/BACK/data_exchange ✓;
  error-notification ack ✓; 421 on undecryptable ✓; HMAC-SHA256 signature enforced only when
  secret configured ✓. Minor deviations: extra `"version":"3.0"` inside response payloads
  (spec samples omit it; clients ignore unknown fields — low risk, no change made).
- Keypair match inconclusive from workspace (sandbox blocked local fingerprint derivation;
  uploaded-key fetch needs the fresh token). Decisive oracle: a real Meta ping — Manager
  health check or opening the Flow on the phone; 200 = pair matches, 421 = re-upload needed.
- Go-live note: spec wants a dedicated keypair per messaging account; the live MiloBroker
  number sits under a different account than the test number, so it needs its own keypair +
  upload + `FLOW_PRIVATE_KEY_PEM` switch in `.env.prod`.

## 13. Conversational pivot — build spec (design team, 4 agents)

Goal: Flow disabled (code bypass, endpoint dormant), everything via chat.
Convo: T0 name → T1 role (1 Rent / 2 Buy / 3 List / 4 Agent + synonyms) → T2 state (Atlas) → T3 city (Atlas, wrong-state rejected) → T4 YES-to-terms → DONE → post-or-search routing. Slots live in `conversation_sessions` (`OB_*` states + `context` JSONB; entity needs context/version mapping + finder). Flow never triggered from webhook (test asserts `never()`); `completeFromFlow` kept for in-flight grace.
Post flow (max 5 ACTIVE/user, 422 on 6th): type → propertyType → city/locality → bedrooms → price → title → description → pre-check count → create ACTIVE → photo/video attach via new `:attach-chat` (media guard widened to ACTIVE). Search unlimited, cards: 1 result = buttons, 2–10 = list message, else plain-text fallback.
Guardrails: input via new `evaluateForSlot` (bare values allowed, HARMFUL never weakened); output via new `OutputGuardrailService` between `model.chat` and `sendText` (PII/off-topic → fallback, long/links → sanitize).
EOD phasing (design team): must-have = onboarding machine + bypass + session mapping + roles + router branch + 5-cap + post slots + plain-text search + both guardrails + unit tests + 1 E2E journey. Defer: media download endpoint, interactive cards (plain-text first), voice STT, key removal.
Open items needing owner: role-code sign-off, ACTIVE-vs-moderated, scope call below.

## Build record (conversational pivot — implemented + verified)

- Builder A (concierge onboarding): `ChatOnboardingService` (OB_ASK_* machine, Atlas validation, YES-terms), webhook Flow block bypassed (in-flight `nfm_reply` grace kept), `OnboardingService` roles +BUY/AGENT, session entity context/version + finder. `ChatOnboardingTest` 12/12, `FlowJourneyTest` 11/11.
- Builder B (marketplace): `countByOwnerUserIdAndStatus`, 5-active 422 cap in `ListingService.create`, `SearchResult.bedrooms` (additive).
- Builder C (router/guards/post): router reorder (guardrail→VOICE→onboarding-machine→POST/SEARCH→model→output guardrail), in-progress drafts route straight to post flow, `PostPropertyFlowService` (7 slots, pre-check, create→"Posted! ref"), `MarketplaceListingsClient`, `evaluateForSlot`, `OutputGuardrailService`.
- Builder D (lead): `ChatJourneyTest` E2E (onboard→post→chat, Flow never triggered, all replies ≤60 words, no links); rewrote 2 stale `GuardrailWiringTest` tests from retired Flow expectations to chat contract (owner-ordered Flow removal); fixed router-vs-guardrail ordering bug found by the E2E (bare slot values were clarity-blocked).
- Full suites green: concierge 105/105, marketplace 46/46, atlas 5/5. Deferred per owner scope call: chat media upload endpoint, interactive cards (plain-text first), voice STT.
- EC2 redeployed by owner (images rebuilt, all 3 containers healthy). Post-deploy bug found from live logs: `context` JSONB column rejected the app's varchar writes — every session save failed, so no conversation could advance. Fixed live (`ALTER COLUMN context TYPE TEXT`; app uses it as an opaque string, no JSON operators anywhere) and `docs/V1_conciergedb.sql` updated to match. Lesson: H2-based tests don't catch Postgres type strictness — live DB is the proof.
- Next: user re-sends the WhatsApp test message (or re-clicks Verify in Manager — Meta retries with a fresh challenge), then confirm clean handling in `docker logs infra-milo-concierge-1`.
- Webhook 500-storm diagnosed from access log (Meta POSTs 20:50–20:51 UTC all 500/128b, Meta retrying): chain is expired `WHATSAPP_API_TOKEN` → every `sendText` throws on Graph 190 → catch-all returns silent 500 (handler never logged — now fixed with a `log.warn` + stacktrace in `ConciergeApiExceptionHandler.unexpected`, tests green). Signature path proven fine (500s, not 403s). Fix = fresh System User token into EC2 `.env.prod` + recreate concierge. 21:29 UTC: Meta still POSTing (5 hits), all 500 — user saw "no logs" because 500s only hit the access log + (unlogged) app handler; running image predates the logging fix. Rebuild must come from the current laptop tree. Real-conversation diagnosis: onboarding completes; post-onboarding searches fall through empty-results → unconfigured model → same fallback nudge ("loop"). In tree: state/city prompts capped at 10 options; empty search sends graceful "no properties listed in {place}, reply REFINE…" notice. Pending EC2 side: MODEL_API_KEY/META_MODEL_NAME in `.env.prod` + rebuild/redeploy. NOTE: `ChatOnboardingService.java` exists nowhere on EC2 checkouts — images arrive by transfer, so the next rebuild must come from the current laptop tree (it carries the router-reorder fix the running image may predate).
- Media decision: reusing site bucket `milobroker-public-site-084767318881-ap-southeast-2` for listings (prefix `listings/*`, presigned PUT/GET only). `MEDIA_BUCKET` set in both envs. `infra/s3/property-media-bucket.yaml` superseded, kept for reference. Verified live: bucket exists; PublicAccessBlock all-true (no direct public access); policy is CloudFront-OAC GetObject only; CORS PUT/GET/HEAD applied for site origins + localhost:5173; both envs carry the full `-an` name. Hardening note: CloudFront path could serve guessed `listings/*` keys — UUID key names make enumeration infeasible; restrict distribution behavior later. Still needed: EC2 restart to load env.

## 14. Voice notes + rate limiting (in tree, 129/129 green 2026-11-05)

- New: `WhatsAppMediaClient` (Cloud API media-id to download URL to bytes, bearer auth, `max-bytes` cap), `MetaVoiceTranscriber` (multipart file+model POST to `VOICE_TRANSCRIBE_URL`, returns trimmed `transcript` from the real `/v1/asr/transcribe` shape (`request` JSON + `audio` WAV parts, PUSH_TO_TALK, Kannada/Hindi/English bias, property keywords, `?sessionId=wa-<from>-<ts>`), `FfmpegAudioConverter` (Opus-in-OGG to mono 16-bit PCM 24 kHz WAV via the doc's ffmpeg recipe, header-validated; ffmpeg added to concierge Dockerfile), `VoiceNoteService` (voice-type gate via `InputGuardrailService.isVoiceType`, extracts `audio.id`/`voice.id`, any failure to empty), webhook hook transcribes before guardrail and re-enters the pipeline as `text`. Env: `VOICE_TRANSCRIBE_ENABLED`, `VOICE_TRANSCRIBE_MODEL` (default `muse-voice-transcribe-1.0`), `VOICE_TRANSCRIBE_URL`, `VOICE_MAX_BYTES` (default 10 MiB, `milobroker.voice.max-bytes`), `TRANSCRIBE_URL` defaulting to the real endpoint, `LANGUAGE_BIAS`/`KEYWORDS`, `FFMPEG_PATH`, `CONVERT_TIMEOUT_SECONDS`. Rate limiting: `RateLimiterService` (25 msgs/hour rolling window per sender number, one pause notice then silent drops; `CHAT_RATE_LIMIT_MAX`/`CHAT_RATE_LIMIT_WINDOW_HOURS`), hooked in the webhook with null-safe backward compat. All clients follow the repo's RestClient-builder pattern; voice/conversion failures keep the existing static can't-listen reply.
- Tests (new, all green): `WhatsAppMediaClientTest` (4), `MetaVoiceTranscriberTest` (5), `WebhookVoiceTest` (transcript joins onboarding as name; STT-500 keeps static reply and session stays `OB_ASK_NAME`). No sockets: `MockRestServiceServer.bindTo(RestClient.Builder)`. Full concierge suite 129/129 (`RateLimiterTest` 4, `WebhookRateLimitTest` 3, `FfmpegAudioConverterTest` 4, transcriber now 6). Note: Spring 7 moved `MultipartBodyBuilder` to `org.springframework.http.client` and it drags in reactive-streams, so the transcriber builds multipart via `LinkedMultiValueMap` + `HttpEntity` file part (plain spring-web, no new deps).
- EC2 proof still needed: real OGG-to-WAV conversion + live transcription (no ffmpeg/Opus fixtures on the Windows dev box); fresh `WHATSAPP_API_TOKEN` + rebuild/redeploy still pending from before.

## 15. Evidence refs + unresolved

- Read: `README.md`, `docs/index.md`, `environment-configuration.md`, `api-domain-deploy.md`, `ec2-access.md`, `whatsapp-setup.md`, `whatsapp-flows.md`, `pom.xml`, `compose.yaml`, `.env.local.example`, `.env.local`, `infra/nginx/api-https.conf`, `MetaModelClient.java`, `ConversationRouter.java`, `ConciergeMessageService.java`, `OnboardingService.java`, `ListingService.java`, `SearchService.java`, `MediaService.java`, controller route grep, same-day `logs/` boot lines.
- Unresolved: `.env.local` stray char; `.env.prod/.stage/.dev` contents; `milobroker-images.tar` contents; promotion-site location; live number IDs / FLOW publish; EC2 443 check; bucket name; model key decision; live port reachability (log-boot only — fresh probe failed).
