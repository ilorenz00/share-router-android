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

3. **In the app** (launcher icon → Settings):
   - **OpenAPI spec URL** — where your MasterAPI serves its OpenAPI JSON.
   - **Base URL override** — optional; defaults to `servers[0].url` from the spec.
   - **Issuer URL**, **Client ID**, **Scopes** (`openid profile email` by default).
   - Tap **Login** → Authentik in the browser → back to the app.
   - **Test connection** lists every endpoint that would be exposed and how it was matched.

4. **Use it**: in any app, *Share → Share Router*, pick the endpoint, done.

---

## F-Droid repo (CI)

Every push to `main` runs `.github/workflows/fdroid.yml`: it builds a **signed
release APK** (versionCode = CI run number, so updates roll automatically), wraps
it in an F-Droid repository via `fdroidserver`, and publishes it to GitHub Pages.

**Subscribe on the phone:** F-Droid app → Settings → Repositories → `+` →

```
https://ilorenz00.github.io/share-router-android/repo
```

then install/update *Share Router* like any other F-Droid app.

Setup (already done once, documented for disaster recovery):

- Release keystore: `D:\android\sharerouter-release.jks` (alias `sharerouter`,
  credentials in `D:\android\sharerouter-keystore-credentials.txt` → password
  manager). **Losing it means F-Droid clients reject future updates** (signature
  change) — back it up.
- Repository secrets: `SIGNING_KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`,
  `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
- GitHub Pages serves the `gh-pages` branch (pushed by the workflow).

---

## Connecting to the real MasterAPI (k3s cluster)

The cluster side lives in `k3s_cluster_l5` (Authentik blueprint
`share-router-oidc` + JWT federation on `masterapi-provider`; x-share
annotations in the master-api swaggo comments). App settings for production:

| Field | Value |
|---|---|
| OpenAPI spec URL | `https://api.lorenzl5.com/swagger/doc.json` (Swagger 2.0 — supported) |
| Issuer URL | `https://auth.lorenzl5.com/application/o/share-router/` |
| Client ID | `share-router` |
| Scopes | `openid profile email offline_access` |
| Token-Exchange Client ID | client_id of `masterapi-provider` (Authentik UI → Providers) |

The token exchange (client_credentials + JWT-bearer client_assertion) is what
lets a Bearer token pass the Traefik forwardAuth outpost — see
`docs/manual-setup-steps.md` in the cluster repo.

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
