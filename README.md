# SaplingSync 🌱

A mobile-first farming app for smallholder farmers in Tamil Nadu, India. Helps farmers request, track, and grow 100 saplings — with AI-powered disease diagnosis, community features, and a full admin dashboard. Supports English and Tamil.

---

## File Overview

### `index.html`
The entire frontend — a single-file React app loaded via CDN (no build step). Uses Babel standalone for JSX in-browser. Broken into these sections:

| Section | What it does |
|---|---|
| **Constants & Config** | Supabase client, color palette, plant/soil/water type lists in English and Tamil |
| **Shared UI** (`Card`, `Btn`, `Chip`, `Pill`, `Steps`, `VInput`, etc.) | Reusable components used everywhere. `VInput` has built-in voice input via the Web Speech API |
| **Geo helpers** (`parseGeo`, `stripGeo`, `getGeoNow`) | GPS coordinates are stored as a `[📍lat,lng]` prefix inside text fields to avoid schema changes |
| **`LiveMap`** | Leaflet map with Google satellite tiles showing the farmer's land |
| **`WeatherCard`** | 7-day weather forecast fetched from Open-Meteo using the farmer's GPS coordinates |
| **`Auth`** | Login / signup screen. Accepts email, phone number, or any username — converts all to a valid Supabase email internally. No OTP needed |
| **`ProfileSetup`** | 3-step wizard: personal info → land info (soil, water, GPS, survey number) → existing crops list |
| **`NewPlants`** | Request new saplings from 5 types (Coconut, Mango, Guava, Lemon, Teak). Shows request status (Requested → Approved → Delivered) with edit/delete for pending requests |
| **`GrowthJournal`** | Photo-based plant growth tracker. First photo = planting day, then quarterly. Stores height, notes, and GPS with each upload. Shows admin ratings. Includes a scrollable photo gallery and lightbox |
| **`DiseaseCheck`** | List of past disease checks + wizard to submit a new one (description → photo → submit). Each submission can be diagnosed by AI (Claude Haiku via Edge Function) or reviewed by an admin. Shows treatment/prevention advice |
| **`Community`** | Social feed — post text or photos, like posts, and reply in nested threads (parent encoded as `[>>UUID]` prefix in reply content) |
| **`Notifs`** | Notification feed showing admin ratings on growth photos and disease diagnosis results |
| **`AdminDash`** | Admin-only dashboard with: farmer list + drill-down, pending growth photo review queue (1–5 star rating), disease report management with AI diagnosis, GPS verification with Google Maps links, and satellite map per farmer |
| **`News`** | Hardcoded farm advisory items (IMD alerts, government schemes, price updates) that auto-hide after 24 hours |
| **`Home`** | Landing screen with welcome banner, weather card, quick-action grid, and satellite map |
| **`App` (root)** | Session management, language toggle (EN/TA saved to profile), role-based routing (farmer vs admin), sticky header, and bottom nav bar |

### `supabase/functions/diagnose-plant/index.ts`
A Supabase Edge Function (Deno runtime) that calls Claude Haiku 4.5 with a plant photo and returns a structured disease diagnosis.

- **Input:** `photoUrl`, `cropName`, `details`, `lang` (`en` or `ta`), optional `recordId`
- **Output:** `disease`, `confidence` (0–1), `severity` (low/medium/high/unknown), `treatment`, `prevention`, `needs_expert`, `retake_advice`
- Treatment/prevention are returned in Tamil if `lang=ta`, English otherwise
- If `recordId` is provided, writes the diagnosis back to the `pest_checks` row in Supabase
- Cases with `confidence < 0.7` or `needs_expert: true` are flagged as `ai-haiku-4.5+pending` for admin follow-up
- Requires `ANTHROPIC_API_KEY` set in Supabase Edge Function secrets

---

## Tech Stack

| Layer | Tech |
|---|---|
| Frontend | React 18 (CDN), Babel standalone (in-browser JSX) |
| Backend / DB | Supabase (Postgres + Auth + Storage + Edge Functions) |
| AI | Claude Haiku 4.5 via Anthropic SDK (vision) |
| Maps | Leaflet + Google satellite tiles |
| Weather | Open-Meteo API |
| Fonts | Google Fonts — Noto Sans + Noto Sans Tamil |

---

## Supabase Tables

| Table | Purpose |
|---|---|
| `profiles` | Farmer profile — name, location, soil/water type, GPS, language, role |
| `plant_requests` | Sapling requests per farmer with status flow |
| `plants` | Farmer's plants (auto-created on request) |
| `growth_updates` | Monthly growth photos with height, notes, GPS, admin rating |
| `pest_checks` | Disease check submissions with AI/admin diagnosis |
| `community_posts` | Community feed posts |
| `community_replies` | Replies to posts (nested via `[>>UUID]` prefix) |
| `community_likes` | Like records per user per post |

---

## Setup

1. Create a Supabase project and set up the tables above
2. Set `ANTHROPIC_API_KEY` in Supabase → Project Settings → Edge Functions → Secrets
3. Deploy the Edge Function: `supabase functions deploy diagnose-plant`
4. Update the Supabase URL and anon key in `index.html` (line 28)
5. Open `index.html` directly in a browser — no build step needed

---

## Key Design Decisions

- **Single `index.html`** — zero build tooling, deployable anywhere as a static file
- **GPS in text fields** — coordinates stored as `[📍lat,lng]` prefix to avoid schema migrations
- **Nested replies** — parent reply ID encoded as `[>>UUID]` prefix in content, same reason
- **AI + admin hybrid** — AI gives immediate triage; low-confidence cases are flagged for human expert review
- **Bilingual** — all UI strings have English and Tamil variants; language preference saved to profile
