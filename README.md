# Friction

Self-imposed app blocker for Android, built on learned helplessness: once an app group has
used up its free time for the day, opening it requires passing a sequence of tasks that
may fail anyway. See **[SPEC.md](SPEC.md)** for the concepts and rules.

Code map:
- `engine/`: pure Kotlin rules (stages, sessions, blocks, interruptions, usage maths). Unit-tested in `app/src/test`.
- `data/`: JSON config/state files and the stats log.
- `service/` + `FrictionAccessibilityService`: watches the foreground app and applies the rules.
- `FrictionActivity` + `gate/`: the task-sequence and block screens, and the tasks themselves.
- `MainActivity` + `ui/`: configuration screens.

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

### 5. Grant permissions (in the app, *Setup* screen)

1. **Accessibility**: Settings → Accessibility → Installed apps → Friction → on.
   If it's greyed out ("Restricted setting"): App info → ⋮ (top right) → **Allow restricted settings**,
   then try again. Android does this for apps installed from a file.
2. **Display over other apps**: a fallback that lets Friction open its screen from the background.
3. **Unrestricted battery**: helps keep Samsung from killing the service.
4. **Usage access**: measures today's usage per group (decides the stage).

## Testing checklist (v0.2)

After installing, the Accessibility toggle might need re-enabling. Then also grant **Usage access** (Setup screen).

- [ ] App groups → "Social" → tick a harmless app (e.g. Calculator) → Save.
- [ ] Open it: stage 1 is free, so nothing happens. Status shows usage going up.
- [ ] Status → "Debug: +10 min" ×6 → reopen the app → the "burnout" sequence starts.
- [ ] Let Go on the intro → home, no block. Start → fail on purpose → blocked 10 min (black timer screen).
- [ ] Status → "Debug: clear" (removes the block, keeps the debug usage) → pass the sequence → 15-minute session (Status shows the countdown).
- [ ] +30 min more → reopen: the session ended (new stage) → "hell" → after passing, black clouds show up within 1–3 min of use.
- [ ] Task variants → any → "Save & try" to practise a task without consequences.
- [ ] Log → Share log / Share stats.

## Logs

Every event goes to an in-app log file (*Log* tab) and to Logcat with tag `Friction`.
With adb (Android platform-tools; Android Studio not needed): `adb logcat -s Friction`.
