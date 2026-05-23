<div align="center">
<img width="150" height="150" src="https://raw.githubusercontent.com/sang765/ArchiveTune-Nightly/main/images/logo.webp" alt="Archivetune">

# <picture> <source media="(prefers-color-scheme: dark)" srcset="images/icon-white.png"> <source media="(prefers-color-scheme: light)" srcset="images/icon-black.png"> <img src="images/icon-black.png" width="30" alt="ArchiveTune Icon"> </picture> ArchiveTune Nightly <picture> <source media="(prefers-color-scheme: dark)" srcset="images/icon-white.png"> <source media="(prefers-color-scheme: light)" srcset="images/icon-black.png"> <img src="images/icon-black.png" width="30" alt="ArchiveTune Icon"> </picture>
**Official CI build for daily Nightly version of [ArchiveTune](https://github.com/koiverse/ArchiveTune). Redefining the YouTube Music Experience on Android.**  
🕛 Runs at <b><code>0:00 AM</code></b> every day, **UTC 0** time zone 🕛

<sub>Don't forget to leave a ⭐ for this repository if you visit. Thank you so much ♥️</sub>

[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/sang765/ArchiveTune-Nightly/daily-nightly-build.yml?branch=main&style=for-the-badge&logo=github&label=Status&labelColor=1e1e2e)](https://github.com/sang765/ArchiveTune-Nightly/actions/workflows/daily-nightly-build.yml)
[![GitHub Stars](https://img.shields.io/github/stars/sang765/ArchiveTune-Nightly?style=for-the-badge&color=6366f1&labelColor=1e1e2e&logo=github)](https://github.com/sang765/ArchiveTune-Nightly)
[![GitHub Downloads (all assets, latest release)](https://img.shields.io/github/downloads/sang765/ArchiveTune-Nightly/total?style=for-the-badge&label=DOWNLOAD%20COUNT&labelColor=1e1e2e&color=ff00ff&logo=downdetector)](https://github.com/sang765/ArchiveTune-Nightly/releases)
[![GitHub Release](https://img.shields.io/github/v/release/sang765/ArchiveTune-Nightly?display_name=release&style=for-the-badge&label=LATEST%20RELEASE&logo=Github&color=000fa0&labelColor=1e1e2e)](https://github.com/sang765/ArchiveTune-Nightly/releases/latest)
[![GitHub License](https://img.shields.io/github/license/sang765/ArchiveTune-Nightly?style=for-the-badge&labelColor=1e1e2e&logo=gplv3&logoColor=%23BD0000&color=%23BD0000)](./LICENSE)  
[![Telegram](https://img.shields.io/badge/Telegram-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/ArchiveTuneGC)
[![Discord](https://img.shields.io/badge/Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/XF2fpb9rTq)  

</div>

<h4 align="center">Download</h4>

<div align="center">

<!-- GIF_COUNTERS:NNNNNNNN -->

| Stable | Nightly |
|----------|---------|
| <div align="center"> [![Stable build](https://img.shields.io/github/actions/workflow/status/koiverse/ArchiveTune/release.yml?labelColor=27303D&label=Stable&labelColor=06599d&color=043b69)](https://github.com/koiverse/ArchiveTune/actions/workflows/release.yml) <br> [![GitHub downloads](https://img.shields.io/github/downloads/koiverse/ArchiveTune/latest/total?label=Latest%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/koiverse/ArchiveTune/releases/latest) [![GitHub downloads](https://img.shields.io/github/downloads/koiverse/ArchiveTune/total?label=Total%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/koiverse/ArchiveTune/releases) </div> | <div align="center"> [![Nightly build](https://img.shields.io/github/actions/workflow/status/sang765/ArchiveTune-Nightly/daily-nightly-build.yml?labelColor=27303D&label=Nightly&labelColor=2c2c47&color=1c1c39)](https://github.com/sang765/ArchiveTune-Nightly/actions/workflows/daily-nightly-build.yml) <br> [![GitHub downloads](https://img.shields.io/github/downloads/sang765/ArchiveTune-Nightly/latest/total?label=Latest%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/sang765/ArchiveTune-Nightly/releases/latest) [![GitHub downloads](https://img.shields.io/github/downloads/sang765/ArchiveTune-Nightly/total?label=Total%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/sang765/ArchiveTune-Nightly/releases) </div> |

</div>

> [!WARNING]
> - We **DO NOT ACCEPT** any issues, bug reports, or new ideas on "this repository". If you wanna create an issue or bug report, please create it [here](https://github.com/koiverse/ArchiveTune/issues/new/choose).

This repository contains the nightly build setup for ArchiveTune, an advanced YouTube Music client for Android. Read bellow to read more about this app.

<div align="center">

## ℹ️ [ArchiveTune](https://github.com/koiverse/ArchiveTune) [README](https://raw.githubusercontent.com/koiverse/ArchiveTune/main/README.md) ℹ️

</div>

<!-- README.md -->

<div align="center">

## 👥 [ArchiveTune](https://github.com/koiverse/ArchiveTune) [CONTRIBUTING](https://raw.githubusercontent.com/koiverse/ArchiveTune/dev/CONTRIBUTING.md) 👥

</div>

<!-- CONTRIBUTING.md -->

<div align="center">

## 🛠️ Development Environment 🛠️

</div>

---

**Repository secrets (required for nightly builds):**

| Secret | Description |
|--------|-------------|
| `SIGNING_KEY_BASE64` | Your Android signing key (`.jks` or `.keystore`) converted to base64. Used to sign the APK. Generate with: `openssl base64 < YOUR_KEY.jks \| tr -d '\n' \| tee YOUR_KEY.jks.base64` |
| `KEYSTORE_PASSWORD` | Password used to protect your keystore file. Must match the password set when creating the keystore. |
| `KEY_ALIAS` | The alias name assigned to your signing key inside the keystore. Used during APK signing process. |
| `KEY_PASSWORD` | Password for the specific key alias within the keystore. May be different from keystore password. |
| `LASTFM_API_KEY` | Last.fm API key for scrobbling and integrated Last.fm features. Get it from [Last.fm API](https://www.last.fm/api). |
| `LASTFM_SECRET` | Last.fm API secret corresponding to your API key. Required for authenticated Last.fm requests. |
| `TOGETHER_BEARER_TOKEN` | Token for online music Together. Private and only @koiverse know it |
| `CANVAS_BEARER_TOKEN` | Token for canvas, only @koiverse know |
| `PAT_TOKEN` | GitHub [Personal Access Token](https://github.com/settings/personal-access-token) with `Actions` (trigger/run workflows), `Contents` (commit, push, create release), and optionally `Workflows` (cancel failed builds) permissions. |

---

<div align="center">

### ⏰ Cron-Job Schedule Setup ⏰

</div>

---

To automatically trigger the nightly build at a specific time, set up a cron-job using [cron-job.org](https://cron-job.org) or any similar service:

**Title**:
Anything you want

**URL:**
```
https://api.github.com/repos/${{ github.repository }}/actions/workflows/trigger.yml/dispatches
```
`${{ github.repository }}` is `your-username/your-repo-name` so change it to your name and repository name.

**Headers:**
| Header | Value |
|--------|-------|
| `Accept` | `application/vnd.github.v3+json` |
| `Authorization` | `Bearer YOUR_GITHUB_PAT` |
| `Content-Type` | `application/json` |

**Request menthod:**
POST

**Body:**
```json
{
  "ref":"main"
}
```

**Timeout:**
You can set 1 second if you can't wait.

**Cron Schedule Examples:**

| Schedule | Cron Expression | Description |
|----------|-----------------|-------------|
| Daily at midnight (UTC) | `0 0 * * *` | Runs every day at 00:00 UTC |
| Daily at midnight (UTC+0) | `@daily` | Same as above, simplified |
| Every 6 hours | `0 */6 * * *` | At minute 0 of every 6th hour |
| Weekly on Sunday | `0 0 * * 0` | Every Sunday at midnight |

> [!TIP]
> - The workflow uses `repository_dispatch` trigger, so the external cron service needs a PAT token with `repo` scope to send the dispatch event.
> - Alternatively, you can use GitHub's built-in scheduler by adding a `schedule` trigger to the workflow, but the external cron-job.org method gives you more control.

---

<div align="center">

### ⚒️ Build ⚒️

</div>

- For nightly builds, see `.github/workflows/nightly-build.yml`

<div align="center">

### 🔰 License 🔰

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

</div>

<div align="center">

### 🔗 Links 🔗

</div>

- Latest dev build in **ArchiveTune** repository:  
  [![Latest Dev Branch Workflow Build](https://img.shields.io/github/actions/workflow/status/koiverse/ArchiveTune/build.yml?branch=dev&style=for-the-badge&logo=githubactions&logoColor=ffffff&label=Last%20DEV%20Build&labelColor=1e1e2e&color=6366f1)](https://github.com/koiverse/ArchiveTune/actions/workflows/build.yml?query=branch%3Adev)
- [ArchiveTune Repository](https://github.com/koiverse/ArchiveTune)
