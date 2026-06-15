# Share Router (Android)

Share **anything** from any Android app to *Share Router*. It reads your MasterAPI's
OpenAPI spec, figures out the logical type of what you shared (image / video / audio /
file / URL / text), and offers you every MasterAPI endpoint that can accept it.

| You share… | Classified as | Offered (example) |
|---|---|---|
| a photo | `image` | Stable Diffusion (img2img), OCR |
| a YouTube / stream link | `url` | MeTube, ArchiveBox |
| an audio note | `audio` | Whisper STT |
| a PDF | `file` | Paperless-ngx |
| selected text | `text` | a prompt endpoint, a note-taker |

Native Kotlin + Jetpack Compose. Auth to the MasterAPI via **OIDC / Authentik**
(Authorization-Code + PKCE). Min SDK 26.

---

## How matching works

Standard OpenAPI tells the app an operation accepts e.g. `multipart/form-data` with a
binary field — but **not** that it's "Stable Diffusion for images." So matching is driven,
in priority order, by:

1. **Vendor extensions** you add per operation (authoritative).
2. **Schema inference** as a fallback (binary field → file/image, `format: uri` string →
   url, `text/plain` → text). Unannotated operations still work, just less precisely.

### Vendor-extension contract (add these to your MasterAPI spec)

Put these on the **operation object** (next to `operationId`, `summary`, …):

| Extension | Type | Meaning |
|---|---|---|
| `x-share-accepts` | `[string]` | Logical kinds this endpoint accepts: `image`, `video`, `audio`, `file`, `url`, `text`. **The one field worth adding everywhere.** |
| `x-share-field` | `string` | Which form field / JSON key / query param receives the payload. Inferred if omitted. |
| `x-share-style` | `string` | `multipart` \| `json` \| `text` \| `query`. How to send. Inferred if omitted. |
| `x-share-url-hosts` | `[string]` | For `url` endpoints: only offer when the shared link's host matches (e.g. `["youtube.com","youtu.be"]`). |
| `x-share-defaults` | `object` | Static fields always sent alongside the payload, e.g. `{ "model": "sd_xl_turbo" }`. |

Recognized payload kinds: `image, video, audio, file, url, text`.

### Example spec fragment

```jsonc
{
  "openapi": "3.0.3",
  "info": { "title": "MasterAPI" },
  "servers": [{ "url": "https://api.lorenzl5.com" }],
  "paths": {
    "/sd/img2img": {
      "post": {
        "operationId": "stableDiffusionImg2Img",
        "summary": "Stable Diffusion (img2img)",
        "x-share-accepts": ["image"],
        "x-share-style": "multipart",
        "x-share-field": "init_image",
        "x-share-defaults": { "steps": "30" },
        "requestBody": {
          "content": {
            "multipart/form-data": {
              "schema": {
                "type": "object",
                "properties": { "init_image": { "type": "string", "format": "binary" } }
              }
            }
          }
        }
      }
    },
    "/metube/add": {
      "post": {
        "operationId": "metubeDownload",
        "summary": "MeTube — download video",
        "x-share-accepts": ["url"],
        "x-share-style": "json",
        "x-share-field": "url",
        "requestBody": {
          "content": {
            "application/json": {
              "schema": { "type": "object", "properties": { "url": { "type": "string", "format": "uri" } } }
            }
          }
        }
      }
    }
  }
}
```

With nothing annotated, the inference fallback would still classify `/sd/img2img` as
`image` (binary field named `init_image`) and `/metube/add` as `url` (string field `url`,
`format: uri`). Adding `x-share-accepts` just makes it deterministic and lets you override
the label, the target field, and host filtering.

---

## Setup

1. **Build** (one of):
   - Open the folder in **Android Studio** (Giraffe+). It generates the Gradle wrapper and
     syncs automatically. Run on a device.
   - CLI: from the project root run `gradle wrapper` once (needs a local Gradle ≥ 8.7),
     then `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

2. **Authentik OAuth2 provider** for the app:
   - Create an **OAuth2/OpenID provider** + Application.
   - Client type: **Public** (PKCE, no client secret — this is a mobile app).
   - Redirect URI: `sharerouter:/oauth2redirect`
   - Note the **Issuer URL** (e.g. `https://auth.lorenzl5.com/application/o/share-router/`)
     and **Client ID**.

3. **In the app** (launcher icon → Settings). The screen is ordered for the
   normal flow — actions first, configuration collapsed into dropdowns:
   - **Login Authentik** (top) → Authentik in the browser → back to the app.
     The OIDC config (**Issuer URL**, **Client ID**, **Scopes**) lives in the
     collapsed **config Authentik / OIDC** dropdown right below it.
   - **Tailscale aktiv?** + **Test** — verifies the app can reach and read the
     MasterAPI spec (network/Tailscale + auth) and lists every endpoint that
     would be exposed and how it was matched. The **OpenAPI spec URL** and
     **Base URL override** live in the collapsed **config MasterAPI** dropdown.
   - The toolbar **History** icon opens the response history (see below).

4. **Use it**: in any app, *Share → Share Router*, pick the endpoint, done.

---

## Responses: notification, history, copy & save

Every time you share to an endpoint, the MasterAPI's **response** is captured and:

- shown inline in the share sheet with **Copy** / **Save** buttons, and
- posted as a **notification** (channel *API responses*) carrying the same
  **Copy** (to clipboard) and **Save** (to `Downloads/`) actions. Tapping the
  notification opens the in-app history.

The full response body is persisted to a local **history** (JSON in the app's
`filesDir`, newest first, capped at 200 entries) — open it from the toolbar
**History** icon or a notification tap. Even after the notification is
dismissed, each entry still offers **Copy** and **Save**, and can be deleted
individually or via **Clear all**.

Saved files land in the public **Downloads** directory as
`sharerouter-<timestamp>.<ext>` (extension from the response `Content-Type`:
`json` / `xml` / `html` / `csv` / `txt`) — via MediaStore on Android 10+ (no
storage permission) and a legacy write on Android 9
(`WRITE_EXTERNAL_STORAGE`, `maxSdkVersion=28`).

Permission added: `POST_NOTIFICATIONS` (runtime-requested once on Android 13+;
the history works regardless of the answer). Implementation lives in
`data/history/HistoryStore.kt`, `notify/Notifications.kt` +
`NotificationActionReceiver.kt`, `data/export/ResponseExport.kt`, and
`ui/HistoryScreen.kt`; the response flows from `Dispatcher` (now returns the
full body + `Content-Type`) through `ShareViewModel.recordResponse()`.

### Async jobs (shared GPU queue): the result arrives by notification

The cluster shares a few GPUs, so some endpoints don't answer directly — while
the model loads into VRAM they **enqueue a job and return a ticket**, e.g.:

```json
{"ticket":"gpu_2ae005a8…","status":"queued","priority":1}
```

When the app sees a `{"ticket":…,"status":…}` body it does **not** treat that as
the result. Instead it:

1. writes a **pending** history entry and shows "⏳ In Bearbeitung" in the share sheet,
2. schedules a **WorkManager** job (`work/JobTicketWorker.kt`) that polls
   `GET /api/v2/gpu/jobs/{ticket}` until the status is `done` / `failed` /
   `cancelled` — it survives the share sheet closing and process death, and resumes
   past WorkManager's ~10-min execution cap via `Result.retry()`,
3. on `done`, renders the result **generically**: if it decodes to an image
   (Stable Diffusion's `{"images":["<base64>"]}`, a `data:image…;base64,` URI, or a
   bare base64 blob) it shows the **generated image** in the notification
   (BigPicture) and the history; otherwise it shows the text/JSON result.

This is **queue-agnostic** — it works for any kind that returns a ticket (`sd`,
`ephemeral`, future kinds). A new result media type only needs its own branch in
`extractImageBytes()` (`data/export/ResponseExport.kt`). The poll URL is derived
from the API host (`{origin}/api/v2/gpu/jobs/{ticket}`), independent of the spec
basePath. The notification and the history entry both offer **Save** (image →
`Downloads/*.png`, text → `Downloads/*.txt|json`). Requires the WorkManager
dependency (`androidx.work:work-runtime-ktx`).

---

## F-Droid distribution (CI)

Every push to `main` runs `.github/workflows/fdroid.yml`: it builds a **signed
release APK** (versionCode = CI run number, so updates roll automatically) and
publishes it as a GitHub Release. It then **instantly triggers** the **central
F-Droid repo** ([`ilorenz00/fdroid-lorenzl5`](https://github.com/ilorenz00/fdroid-lorenzl5))
via a `repository_dispatch` (`DISPATCH_TOKEN`, see below), which rebuilds and
republishes the index — so a single push propagates **all the way to the phone
with no manual step**. (Absent the token, the central repo still picks it up on
its 30-min schedule.) All apps live under one subscription URL.

**Subscribe on the phone (once, covers all lorenzl5 apps):**
F-Droid app → Settings → Repositories → `+` →

```
https://ilorenz00.github.io/fdroid-lorenzl5/repo
```

Setup (already done once, documented for disaster recovery):

- Release keystore: `D:\android\sharerouter-release.jks` (alias `sharerouter`,
  credentials in `D:\android\sharerouter-keystore-credentials.txt` → password
  manager). Shared with the central repo for index signing. **Losing it means
  F-Droid clients reject future updates** (signature change) — back it up.
- Repository secrets (here AND in fdroid-lorenzl5): `SIGNING_KEYSTORE_B64`,
  `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
- `DISPATCH_TOKEN` secret (**configured**): a PAT that lets this repo's release
  workflow trigger the central repo's rebuild instantly. Required permission:
  fine-grained PAT with **Contents: Read and write** on `fdroid-lorenzl5` (or a
  classic PAT with `repo` scope). The dispatch API (`POST /repos/.../dispatches`)
  rejects anything less with `403 contents=write`. Rotate by re-running
  `gh secret set DISPATCH_TOKEN -R ilorenz00/share-router-android`.

---

## Connecting to the real MasterAPI (k3s cluster)

The cluster side lives in `k3s_cluster_l5` (Authentik blueprint
`share-router-oidc` + JWT federation on `masterapi-provider`; x-share
annotations in the master-api swaggo comments).

**A fresh install ships with these values pre-filled** (`Defaults` in
`SettingsStore.kt`) — on a new device just open the app, tap **Login**, done:

| Field | Value |
|---|---|
| OpenAPI spec URL | `https://api.lorenzl5.com/swagger/doc.json` (Swagger 2.0 — supported) |
| Issuer URL | `https://auth.lorenzl5.com/application/o/share-router/` |
| Client ID | `share-router` |
| Scopes | `openid profile email offline_access` |
| Token-Exchange Client ID | leave blank — auto-discovered (override only if needed) |

The token exchange (client_credentials + JWT-bearer client_assertion) is what
lets a Bearer token pass the Traefik forwardAuth outpost. The proxy provider's
client_id is discovered automatically: an unauthenticated probe of the spec URL
follows the outpost redirect chain (`…/outpost.goauthentik.io/start` →
`…/application/o/authorize/?client_id=…`) and extracts the `client_id` query
param — no Authentik API access needed. See `docs/manual-setup-steps.md` in the
cluster repo.

---

## Local toolchain & emulator test harness

Verified end-to-end on 2026-06-11 (Pixel 6 AVD, Android 14, WHPX-accelerated emulator).
The full Android toolchain lives on **D:** (C: was nearly full):

| Tool | Path |
|---|---|
| Android SDK (incl. emulator, platform-tools) | `D:\android\Sdk` |
| JDK 17 (Temurin) | `D:\android\tools\jdk17` |
| Gradle 8.7 | `D:\android\tools\gradle-8.7` |
| Gradle cache (`GRADLE_USER_HOME`) | `D:\android\gradle-home` |
| AVDs (`ANDROID_AVD_HOME`) | `D:\android\avd` (AVD: `shareRouterTest`) |

Build + run (PowerShell):

```powershell
$env:JAVA_HOME="D:\android\tools\jdk17"; $env:GRADLE_USER_HOME="D:\android\gradle-home"
D:\android\tools\gradle-8.7\bin\gradle.bat assembleDebug
$env:ANDROID_AVD_HOME="D:\android\avd"
D:\android\Sdk\emulator\emulator.exe -avd shareRouterTest -no-snapshot &
D:\android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

`mock/` contains a stand-in MasterAPI: `serve.ps1` serves `openapi.json` (annotated with
the `x-share-*` contract, mirroring real master-api routes like `/ai/img2img`,
`/ai/image-to-text`, `/tasks`) on port 8765 and logs every received request to
`mock/requests.log`. In the app, set the spec URL to `http://10.0.2.2:8765/openapi.json`
(10.0.2.2 = host loopback from the emulator).

Lessons from the first real build/run:
- `Theme.DeviceDefault.DayNight.NoActionBar` does not exist in the framework → AAPT link
  error. Now `Theme.DeviceDefault.NoActionBar` (Compose draws the actual UI anyway).
- `ShareActivity` had `launchMode="singleTask"`: a second share re-delivered the intent to
  the stale instance (no `onNewIntent` handling), showing the previous share's state and a
  dead URI grant. Removed — every share now gets a fresh activity.
- `android:usesCleartextTraffic="true"` added: LAN/homelab targets and the emulator mock
  are plain http; Android 9+ blocks cleartext by default.

---

## Notes & current limits

- **JSON specs only.** OpenAPI YAML isn't parsed (keeps the app dependency-light). Most
  frameworks (FastAPI, etc.) serve JSON at `/openapi.json` — point the app there.
- **One item per send.** For multi-image shares, the first matching item is sent. Looping
  all items is a straightforward follow-up in `Dispatcher`.
- **Binary → JSON** endpoints receive the payload **base64-encoded** in `x-share-field`.
- **Token storage** uses plain `SharedPreferences` (fine for a trusted personal device).
  Swap `AuthStateStore` to `EncryptedSharedPreferences` if you harden it later.
- The matching engine lives in `data/openapi/` (`OpenApiFetcher`, `EndpointMatcher`) and is
  the part to tune as your MasterAPI grows.
