# MiloBroker 3-minute video — shoot runbook
<!-- One continuous take per leg, edit between legs. Total budget 3:00. All bot lines below are verbatim from the code. -->

## STEP 0 — Pre-flight (no camera)

1. EC2 runs the NEW conversational build (redeploy done, all 3 `/actuator/health` 200).
2. WhatsApp subscription live: callback + verify token saved, `messages` field subscribed (send yourself a test message first and see a reply).
3. Use a FRESH sender number (never onboarded before — completed profiles can't re-onboard on camera).
4. Onboard as RENT intent (needed: demo search hits RENT listings).
5. Keep each sent message short; wait for the reply before sending the next.

## LEG 1 — Public site (0:00–0:40, screen-record the browser)

- ACTION: open `https://milobroker.com`. Scroll the hero, then the live search section.
- SAY: "Everything starts at milobroker.com. The contact number and these search results are live — served by the same backend API the WhatsApp assistant uses. No separate system, no mocks."
- ACTION: run one site search (e.g. BLR rentals) so results render; then click the WhatsApp chat button (wa.me link).
- SAY: "One click takes us from the website into WhatsApp — and from here, Milo the broker takes over."

## LEG 2 — Redirect lands in chat (0:40–0:55, phone screen)

- ACTION: WhatsApp opens on MiloBroker's number. Send: `hi`
- SAY: "We're now talking to the broker itself. Watch a full onboarding with zero forms."
- BOT (verbatim): "Hi {your-WhatsApp-name}! Welcome to MiloBroker. What is your full name?"

## LEG 3 — Onboarding (0:55–1:30, keep a brisk rhythm, ~7s per turn)

- SEND `Asha` → BOT: "Thanks Asha! What brings you to MiloBroker? Reply with 1) Rent, 2) Buy, 3) List a property, or 4) Agent."
- SAY: "One tap chooses the journey — I'm renting."
- SEND `1` → BOT asks state: "Which state are you looking in? Options: Karnataka (KA), Tamil Nadu (TN). Just reply with the code or name."
- SEND `Karnataka` → BOT: "Which city in Karnataka? Options: Bengaluru (BLR). Just reply with the code or name."
- SAY: "Typed the full name, matched live against real location data."
- SEND `Bengaluru` → BOT: "Almost done, Asha! Reply YES to accept the MiloBroker terms and finish onboarding."
- SEND `YES` → BOT: "Thanks Asha! Your setup is complete. Tell me what you are looking for - a rental, a purchase, or listing your property."
- SAY: "Onboarded in five turns. Now the same user switches sides and lists a property."

## LEG 4 — Post a property (1:30–2:10, send exactly these)

- SEND `I want to post my flat` → BOT: "Let's post your property. Is it for RENT or SALE? Just reply with one word."
- SEND `rent` → BOT: "Got it - RENT. What kind of property? Apartment, House, Villa, PG, Plot, Commercial, or Other."
- SEND `apartment` → BOT: "Which city and locality? Reply like: BLR, Whitefield."
- SEND `BLR, Whitefield` → BOT: "How many bedrooms? Reply with a number 0-20."
- SEND `2` → BOT: "What's the expected price in rupees? Reply with a number (e.g. 25000)."
- SEND `25000` → BOT: "Give your listing a short title."
- SEND `Sunny 2BHK Whitefield` → BOT: "Add a short description for buyers or tenants."
- SEND `Bright 2BHK near metro, 5 min walk` → BOT: "Posted! ref {6-char ref}."
- SAY: "Live in eight messages — and every owner is capped at five active listings, so the marketplace can't be spammed."

## LEG 5 — Search + AI reply (2:10–2:45)

- SEND `2bhk in Whitefield under 30000` → BOT: "Hi Asha! Here are some options:" followed by a numbered list, e.g. "1. Sunny 2BHK Whitefield - Rs.25000 (WHITEFIELD)" — the listing just posted, found by real search. Owner contact details are never shown.
- SAY: "Posted and found in the same thread — and no phone numbers leak until both sides consent."
- SEND `what is the deposit usually?` → BOT answers conversationally via the Meta Muse model, short and on-topic.
- SAY: "That answer came from Meta's Muse model, with guardrails keeping every reply short, on-property, and private."

## LEG 6 — Close (2:45–3:00, back to site or title card)

- SAY: "Onboard, post, search — three journeys, one WhatsApp thread, zero apps installed. MiloBroker: text your broker. Thank you."

## If a take goes wrong

- Wrong input mid-flow: send `BACK` (steps back one slot) or `RESTART` (starts over) — both work on camera and actually demo well.
- No reply at all: stop, check EC2 health + `messages` subscription; do not improvise — reshoot the leg.
- Keep each leg as its own clip; hard cuts between legs hide all waiting time.
