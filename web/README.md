# ftbquestssync — web viewer (companion app)

Read-only questbook viewer + admin reset console that reads the same MySQL schema `ftb-quests-mysql-sync` writes. Runs as an Express sub-router; mount it under any path you like.

## Why this is in the repo

Issue [#8](https://github.com/panpeacemaker/ftb-quests-mysql-sync/issues/8) showed that the same JOIN this viewer relies on (`team_membership` × `ftbquests_player_names`) was the lens that made the bug visible. Shipping the viewer next to the mod lets you reason about both sides of the contract in one place.

If you'd rather keep the mod repo pure, this is also fine to extract into its own repo. The code is self-contained — no upward imports.

## What it does

- **`GET /agrarius`** — quest map UI. Cookie-auth gated.
- **`GET /agrarius/api/status`** — DB + Redis ping, table list.
- **`GET /agrarius/api/teams`** — list teams from `ftbquests_teamdata` joined with `team_membership` and `ftbquests_player_names`.
- **`GET /agrarius/api/team/:id/data`** — decoded TeamData blob (NBT → JSON).
- **`POST /agrarius/api/reset`** — admin-only quest progress reset. Writes audit row to `ftbquests_reset_audit`, publishes Redis event on `ftbquests:team:updated`.

## Install

```bash
git clone <this-repo>
cd ftb-quests-mysql-sync/web
npm install
cp .env.example .env
# edit .env with your MySQL/Redis details
```

Then mount the router from your Express app:

```js
const express = require('express');
const app = express();
app.use(express.json());
app.use(require('./web/routes'));
app.listen(3000);
```

Or run as standalone with a thin wrapper script (not included — write one).

> **Proxy note:** If the app sits behind a reverse proxy (nginx, Velocity, etc.), the host Express app must call `app.set('trust proxy', true)` (or a trusted proxy list) so `req.ip` reflects the real client IP. Without this, rate-limiting and any IP-based gates will see the proxy's address instead.

## Auth model (heads up)

The viewer ships with **cookie-forwarding auth** — it expects a sibling Express service that exposes:

- `POST <WOT_LOGIN_URL>` accepting `{username, password}` returning `Set-Cookie` plus `{loggedIn, username, role}`
- `GET <WOT_ME_URL>` returning `{loggedIn, username, role}` based on the forwarded cookie

This is the auth we run in production (a World-of-Tanks themed login service on the same host). It's deliberately a thin wrapper — replace it with whatever you want:

- The role/admin gate is `routes.js → canUseAgrarius()` — swap that.
- Two env vars steer it: `AGRARIUS_ROLES` (comma-separated role names) and `AGRARIUS_ADMINS` (comma-separated usernames).
- If `WOT_*` URLs return 404 or are unreachable the API returns 503 — the viewer just won't load. Static assets under `/agrarius` are public.

If you want to drop the WoT-style cookie flow entirely, replace `requireWotAgrarius()` with your own middleware (passport, basic-auth, jwt — anything that hands `{username, role}` to the route).

## Quest data source

Two modes:

1. **`AGR_QUEST_LOCAL=/path/to/snapshot`** (default for prod) — reads `chapter_groups.snbt` + `chapters/*.snbt` directly from disk. Drop the files there during deploy.
2. **`AGR_QUEST_CT` + `AGR_PVE_HOST`** (dev fallback) — runs `ssh <node> 'pct exec <ct> cat ...'`. Disabled unless both env vars are set. Don't enable this in production — it requires passwordless SSH to a Proxmox host.

If neither is configured the viewer returns an empty questbook (`source: 'unconfigured'`) — the rest of the API still works.

## Schema dependencies

Tables this viewer reads (all written by the mod):

| Table | Read | Notes |
|---|---|---|
| `ftbquests_teamdata` | yes | blob column → NBT-decoded |
| `team_membership` | yes | for team/player JOIN |
| `ftbquests_player_names` | yes | for nickname column on UI — **bug #8 surfaced here when rows were missing** |

Table it creates on first reset:

| Table | Notes |
|---|---|
| `ftbquests_reset_audit` | one row per reset action; admin actor, scope, mode, summary |

## Not included

- No tests yet.
- No standalone server wrapper — mount it into your own Express app.
- No textures/icon assets — those come from the modpack; fetch them yourself.
- No CSS framework / build step — `public/` is hand-written.

## License

Same as the mod (MIT).
