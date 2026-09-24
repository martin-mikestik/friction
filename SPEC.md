# Friction — specification (v0.2)

Goal: use *learned helplessness* to make opening social media apps tedious and uncertain.
When a group of apps runs out of free time for the day, opening it requires passing a
sequence of tasks. Some are merely slow, some are rigged by chance, and failing blocks the
group for a while.

## Concepts

| Id | Concept | In code |
|---|---|---|
| T1 | **Task Type**: what a task looks like, its pass criterion, and which variables it has | `TaskTypes` (`engine/Catalog.kt`), UI in `gate/Tasks.kt` |
| T3 | **Task Variable**: one parameter of a type. Always a number (choices = index, booleans = 0/1); either **fixed** or a **random range**, rolled anew each time the task starts | `ParamSpec`, `ParamValue` (`engine/Params.kt`) |
| T2 | **Task Variant**: a Task Type with its variables set, named by the user (names are unique) | `TaskVariant` |
| T4 | **Task Sequence**: named, ordered list of task variants, plus the *block time after failing*. Empty = free pass | `TaskSequence` |
| A1 | **Individual App**: an installed app (package name) | `String` |
| A2 | **App Group**: named set of apps with one stage sequence and one shared usage budget. An app can be in at most one group | `AppGroup` |
| O1 | **Stage Sequence**: named, reusable, ordered list of stages | `StageSequence` |
| O2 | **Stage**: duration (minutes of the group's *usage*), a task sequence (or none), and session rules | `Stage` |
| O3 | **Session (Rules)**: what passing unlocks: clock-time length (or unlimited), an interruption set, and launcher settings | `SessionRules`; the running session is `SessionState` |
| I1 | **Interruption Type**: e.g. black clouds | `InterruptionTypes` |
| I2 | **Interruption Variant**: named type with variables set | `InterruptionVariant` |
| I3 | **Interruption Set**: the variants a session may show (may be empty) | `SessionRules.interruptionIds` |
| I4 | **Interruption Launcher**: waits a random time in [min, max] seconds of in-app time, then shows a random variant from the set; repeats after each one ends | `SessionRules.launcherMin/MaxSeconds` |

## Rules

**Day and stages**
- One global daily reset time (default 04:00). At the reset every group returns to stage 1, today's usage starts at 0, and running sessions end. **Blocks survive the reset.**
- *Usage* = time an app of the group is in the foreground with the screen on (from Android UsageStats). Time spent in Friction's own screens doesn't count; time under an interruption does.
- The current stage follows from today's usage: stage 1 lasts `duration₁` minutes of usage, stage 2 the next `duration₂`, and so on. **The last stage lasts until the reset**, whatever its duration.

**Opening an app of a group**
1. A valid session is running → the app opens.
2. The group is blocked → black screen with the remaining time.
3. The stage has no task sequence, or an empty one → a session starts silently and the app opens.
4. Otherwise the stage's task sequence starts.

**Task sequence**
- Order is fixed. Random variables are rolled when each task starts.
- **Let Go** (no penalty) exists only on the intro screen and between tasks. Back on the intro counts as Let Go.
- **Fail** = failing a task, or during/between tasks: pressing Back, leaving the screen (home, recents, another app), the screen turning off, an incoming call. *If in doubt, it's a fail.* Task screens keep the screen awake.
- On fail: go home, and the whole group is blocked for the sequence's block time (**real time**).
- On pass: a session starts with the rules of the stage the group is in.

**Session**
- Length is **clock time** from unlocking, or unlimited (lasts until the stage changes or the reset).
- While it runs, all apps of the group open freely; leaving and coming back is free.
- A session **always ends when the group moves to another stage**.
- When a session ends while you're in the app, Friction sends you to the home screen (no warning). In a free stage it renews silently instead.

**Interruptions**
- Only during a session, from that session's interruption set.
- The launcher counts only in-app time. An interruption's hidden duration pauses while you're outside the group's apps and resumes when you return.
- *Black clouds*: dark puffs drift over the whole screen (touches are swallowed); density 1–10; duration fixed or random range (default 7–30 s), never shown.

## Task types (v0.2)

| Type | Variables | Pass |
|---|---|---|
| **Wait** | seconds | wait it out |
| **Fate** (dice) | chance % (hidden from the user) | a roll after ~3.5 s of suspense; a flat "Granted." / "Denied." |
| **Shake** | target intensity (m/s², gravity removed, smoothed ~0.3 s), allowed deviation ±, hold time, deadline to reach the band, how long you may stray outside the band | reach the band before the deadline, then hold it; being outside longer than allowed = fail |
| **Tilt** | max target angle, tolerance (°), hold time, deadline | a random target on both axes; roll the "ball" into the ring and hold it; the deadline covers reaching and holding |
| **Type** | length, character set (letters / digits / both / look-alikes such as `l1I O0`), case sensitivity, deadline (0 = none) | retype exactly and submit; no paste or keyboard suggestions (one character at a time) |

## Editing the configuration
- Everything refers to everything else by id: editing a variant updates every sequence using it.
- Deleting something that's still used is refused, with a list of where it's used.
- Changes apply immediately. The stage is recomputed from today's usage, so a session whose stage changed ends.
- Export/Import the whole configuration as JSON (Setup screen).

## Logging
- `friction.log`: every decision, human-readable (Log screen → Share log).
- `stats.jsonl`: one JSON event per line (Log screen → Share stats). Events:
  - `task_result`, `sequence_result` (PASSED / FAILED / LET_GO)
  - `open_while_blocked`, `kick`, `interruption`
  - `service_connected` / `service_unbound`
  - `debug_*`

  These answer the dice-roll question (retries after failure, walk-aways, attempts per day).

## Debug tools (temporary)
Status screen → "+10 min" fakes usage (to reach later stages), and "clear" resets a group's
session and block. Both are recorded in the stats. Remove them once testing is done.

## Out of scope for now
Web versions of apps, tamper resistance (turning off the service, loosening rules),
per-weekday rules, statistics UI.
