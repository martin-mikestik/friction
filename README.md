# Friction (dummy build)

Self-imposed app blocker for Android. This dummy version only proves the plumbing:
permissions, app picker, AccessibilityService detection, and a transparent
5-second countdown screen followed by 15 seconds of grace.

## How the build loop works

```
edit code ──git push──▶ GitHub ──Actions──▶ signed APK ──Release──▶ phone downloads & installs
```

`.github/workflows/build.yml` runs on every push to `main`:

1. Starts a fresh Ubuntu machine (Android SDK is preinstalled there).
2. Restores the signing key from the repo's encrypted secrets.
3. Runs `./gradlew assembleDebug`.
4. Publishes the APK as a GitHub Release named `build-<N>`.

Build number `N` is also the app's `versionCode`, so each build installs over the previous one.

## One-time setup

### 1. Create the repository

On github.com: **New repository** → name `friction` → **Private** → no README/.gitignore
(the project has them) → Create. Then, in the unzipped project folder:

```bash
git init -b main          # skip if .git already exists
git add .
git commit -m "Dummy Friction app"
git remote add origin git@github.com:<you>/friction.git   # or the https URL
git push -u origin main
```

This first push starts a build, which **fails** because the secrets aren't set yet. That's expected.

### 2. Add the signing secrets

Repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**:

| Name | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | whole contents of `SIGNING_KEYSTORE_BASE64.txt` |
| `SIGNING_PASSWORD` | contents of `SIGNING_PASSWORD.txt` |

Keep `friction.jks` and the password somewhere safe (a password manager, for example) and **out of the repo**.
If you lose the key, the next build can't update the installed app. You'd have to uninstall and
set up all the permissions again.

### 3. Run the build

**Actions** tab → *Build APK* → **Run workflow** (or just push again). About 3–5 minutes.
Green check = success. Red = open the run and read the failed step's log.

### 4. Install on the phone

On the phone, sign in to github.com in the browser → `github.com/<you>/friction/releases/latest`
(bookmark it) → tap `friction-N.apk` → open it → allow "Install unknown apps" for the browser
the first time → Install. Play Protect may warn about an unknown app: *More details → Install anyway*.

Later builds install over the old one and keep your permissions (same key, higher versionCode).

Optional, and more comfortable: **Obtainium** (open-source, from GitHub/F-Droid) can watch the
repo's releases and update the app for you. A private repo needs a GitHub token in Obtainium's settings.

### 5. Grant permissions (in the app, *Setup* tab)

1. **Accessibility**: Settings → Accessibility → Installed apps → Friction → on.
   If it's greyed out ("Restricted setting"): App info → ⋮ (top right) → **Allow restricted settings**,
   then try again. Android does this for apps installed from a file.
2. **Display over other apps**: a fallback that lets Friction open its screen from the background.
3. **Unrestricted battery**: helps keep Samsung from killing the service.

## Testing checklist

- [ ] *Setup → Preview friction screen*: the countdown appears **over** the Friction UI, which stays visible behind it (tinted/blurred).
- [ ] *Apps*: tick an app (e.g. Calculator) → open it → countdown over it → after 5 s it disappears.
- [ ] Use the app for 15 s → the countdown comes back.
- [ ] Press Back during the countdown → you land on the home screen.
- [ ] Reopen within the 15 s grace → no countdown.
- [ ] *Log* tab shows the events; **Share** sends the log (e.g. to yourself, or paste it to Claude).

## Logs

Every event goes to an in-app log file (*Log* tab) and to Logcat with tag `Friction`.
With adb (Android platform-tools; Android Studio not needed): `adb logcat -s Friction`.
