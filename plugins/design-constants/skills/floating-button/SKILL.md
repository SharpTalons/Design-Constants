---
name: floating-button
description: Build a floating button that stays over other apps while an Android app keeps working in the background, like a chat head. It appears when the person leaves the app with its work on, brings them back with a tap, snaps to the nearer screen edge, closes the app when dragged onto a ✕, and can glow when something happens. Comes with the foreground service that keeps the work alive, its notification (Stop, Open and an optional toggle such as Mute), and the Settings switch that asks for Android's "Display over other apps". Use this whenever the user mentions a floating button, bubble, chat head, overlay, "Display over other apps", SYSTEM_ALERT_WINDOW, a button over other apps, keeping an app running in the background, a foreground service with a notification, or "get back to the app quickly", even if they never say "floating button".
---

# Floating button and keep running

Some apps keep doing their job while the person is in another app: a drum trigger
listening to a mic, a recorder, a timer. This feature keeps that work alive and leaves a
small button over every other app, so getting back takes one tap and stopping takes one
drag.

The full specification is `references/spec.md`. This file is the map.

Reference implementation: DrumTrig (`FloatingBubble.kt`, `KeepAliveService.kt`,
`MainActivity.kt`, Settings › On stage › Keep running).

## Before you start

Read the target app first and answer these, because they change what you write:

- **Is it Android, and does work really continue in the background?** The button is only
  honest if something keeps running. If the app stops when it leaves the screen, build
  neither the service nor the button.
- **What is "the work", and which foreground service type fits it?** Android 14 requires
  a type that matches (microphone, mediaPlayback, location…), declared in the manifest
  and passed to `startForeground`. Pick the real one; a wrong type crashes on start.
- **Does the app have a Lock** (a mode that stops accidental changes)? If yes, the ✕ only
  hides the button while Locked. If not, leave `isLocked` null.
- **Is the app in Google Play?** "Display over other apps" is allowed, but Play reviews
  it; say plainly in the listing what the button is for.

## Build order

1. `KeepAliveService`: a foreground service with a low-importance notification: "<App>
   is running / Tap to open", with Stop and Open (and an optional toggle). Start it when
   the work starts, stop it when the work stops.
2. `FloatingBubble`: the overlay button and the ✕ target, as two overlay windows.
3. The activity hooks: show on leaving, hide on return, close from the ✕, and watch for
   the permission while Android's settings page is open.
4. The Settings switch: "Floating button", off by default, asking for the permission.
5. Optional: a shortcut inside the app that leaves to the button on purpose (DrumTrig:
   double-tap the logo), and `FloatingBubble.flash()` on the app's events.

Copy-ready Kotlin for all of these is in `assets/`, with the manifest lines and tests.

## The rules that matter

**Off until the person turns it on.** Drawing over other apps is intrusive. The setting
starts off, and turning it on opens Android's own "Display over other apps" page (there
is no dialog for it). The switch shows on only when both the setting and the permission
are on, and says "Waiting for…" in between.

**Show it only when they really left.** Home, Recents or switching app: yes. A screen the
app opened itself (a file picker, a permission page), a rotation, or the screen turning
off: no. Back to the app: hide it at once.

**Tap opens, drag closes.** A tap brings the app back. A drag past the touch slop shows
the ✕ at the bottom; over it, the ✕ grows and turns red with "Close <App>", the bubble
pulls onto it and the phone gives a tick. Letting go there closes the app completely:
work stopped, service and notification gone. Letting go anywhere else snaps to the
nearer side edge.

**Respect Lock.** While Locked, the ✕ turns amber with "Locked: hides the button" and a
drop only hides it; the work keeps running. The notification's Stop follows the same rule.

**Stay inside the safe area.** Never under the status bar, the navigation buttons or a
camera cutout, even while the bars are hidden. Keep the side and the height share across
rotations.

**Stopping the work removes the button.** The service's `onDestroy` hides it, so the
button never points at something that is no longer running.

**Make it usable without sight.** The bubble reads "<App> is running. Open <App>", with a
"Close <App>" accessibility action. A glow on events respects "remove animations" with a
plain blink.

## Adapting to the stack

The reference is Kotlin with Android Views for the overlay (Compose cannot draw in an
overlay window without a lifecycle owner, so plain `View`s are simpler and lighter) and
Jetpack Compose for the Settings row. In React Native or Flutter, write the overlay and
the service natively and expose `show`, `hide`, `flash` and a close callback to the app.
On iOS there is no equivalent: apps cannot draw over other apps.

## Pairs with

- **update-check**: the same "is the app on screen" signal decides whether Android's
  installer can open.
- **design-foundations**: the accent colour and the dark colours of the ✕.

## Reference files

| File | What it is |
|---|---|
| `references/spec.md` | The complete specification: behaviour, geometry, copy, edge cases, checklist. |
| `assets/FloatingBubble.kt` | The overlay: bubble, ✕ target, drag, snap, close, flash. |
| `assets/KeepAliveService.kt` | The foreground service and its notification. |
| `assets/MainActivityHooks.kt` | What the main activity and view model add. |
| `assets/SettingsRow.kt` | The "Floating button" switch and the permission page. |
| `assets/AndroidManifest.snippet.xml` | Permissions and the service declaration. |
| `assets/BubbleTest.kt` | Unit tests for the snap and the ✕ hit test. |

## Record what you built

When this feature is built, or brought up to a newer version, add or change one line in
`.design-constants` at the project's root: `floating-button <version>`. Take the version from
`skill-versions.txt`, which sits next to this plugin's `skills/` folder
(`plugins/design-constants/skill-versions.txt` in Design-Constants). That line is how
the project is told about later versions: a check at the start of each session compares it
with the newest one.
