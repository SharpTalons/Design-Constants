# How to Build the Floating Button and Keep Running

The specification behind `SKILL.md`. Reference implementation: DrumTrig, a live drum
trigger whose audio engine keeps listening while the drummer is in another app.

## Contents

- [1. What the feature is](#1-what-the-feature-is)
- [2. The pieces](#2-the-pieces)
- [3. Keeping the work alive](#3-keeping-the-work-alive)
- [4. When the button shows and hides](#4-when-the-button-shows-and-hides)
- [5. The button](#5-the-button)
- [6. Dragging, snapping and the ✕](#6-dragging-snapping-and-the-)
- [7. Closing, and Lock](#7-closing-and-lock)
- [8. The Settings switch and the permission](#8-the-settings-switch-and-the-permission)
- [9. Copy](#9-copy)
- [10. Accessibility](#10-accessibility)
- [11. Acceptance checklist](#11-acceptance-checklist)
- [12. File manifest](#12-file-manifest)

## 1. What the feature is

- While the app's work is on, a **foreground service** keeps it alive, with an ongoing
  notification: "<App> is running / Tap to open", and **Stop** and **Open** buttons
  (DrumTrig adds **Mute / Unmute** first).
- With **Settings › Floating button** on, leaving the app shows a round button over every
  other app. **Tap** it to come back. **Drag** it to move it; it settles against the
  nearer side. **Drag it onto the ✕** that appears at the bottom to close the app.
- The button can **glow** briefly when the app's event happens (DrumTrig: every hit), so
  the person can see the app is still working.

## 2. The pieces

| Piece | Job |
|---|---|
| `KeepAliveService` | Foreground service and notification; Stop goes through the same close rules as the ✕. |
| `FloatingBubble` | A singleton holding two overlay windows: the bubble (touchable) and the ✕ target (not touchable). Every call is safe from any thread; work is posted to the main looper. |
| Main activity | Shows the button on leaving, hides it on return, gives the bubble its close and Lock callbacks, and watches for the permission. |
| View model | `leftApp()`, `refreshPermissions()` (an observable `overlayOk`), `exitApp()`, the `bubble` setting. |
| Settings row | The switch and Android's permission page. |

## 3. Keeping the work alive

- Start the service with `ContextCompat.startForegroundService` when the work starts; stop
  it with `stopService` when the work stops. `onStartCommand` calls `startForeground` with
  the right type on Android 10+ (`FOREGROUND_SERVICE_TYPE_MICROPHONE` for DrumTrig),
  returns `START_STICKY`.
- Notification channel: low importance ("Running in the background"), so it is silent.
  Ongoing, tinted with the accent colour, the app's small white icon.
- Buttons are `PendingIntent`s to the service with actions (`TOGGLE`, `STOP`) and one to
  the launch intent (Open). All `FLAG_IMMUTABLE`.
- The optional toggle (DrumTrig: Mute) calls back into the view model through
  `KeepAliveService.onToggle` and reads its state through `isToggled`; when it changes in
  the app, `KeepAliveService.refresh()` redraws the notification (only if it is showing).
- `onDestroy` hides the floating button.
- Ask for `POST_NOTIFICATIONS` during setup (Android 13+). Without it the service still
  runs; only the notification is hidden.

## 4. When the button shows and hides

**Show** (`FloatingBubble.show`) from the activity's `onStop`, through `vm.leftApp()`,
only when all of these hold:

- the work is running;
- the setting is on and `Settings.canDrawOverlays` is true;
- the screen is on (`PowerManager.isInteractive`): turning the screen off is not leaving;
- the activity is not finishing or changing configuration (rotation);
- the app did not open the screen in front itself. The activity sets `ownScreenOpen` in
  `startActivityForResult` (file pickers and permission pages go through it) and clears
  it in `onStart`.

**Permission watch:** if the app opened a screen of its own and the button is not yet
allowed (most likely Android's "Display over other apps" page), poll once a second for up
to 5 minutes. The moment the permission turns on, the person has in effect left the app,
so refresh `overlayOk` and show the button at once.

**Hide** (`FloatingBubble.hide`) in `onStart`, when the bubble is tapped, when it is
dropped on the ✕, and when the service is destroyed.

**Leaving on purpose (optional):** an in-app shortcut can call `moveTaskToBack(true)`;
`onStop` then shows the button. DrumTrig: double-tap the logo. When it can't (work off,
setting off, no permission) it says why in a short message instead.

## 5. The button

- An overlay window (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`,
  `FLAG_LAYOUT_NO_LIMITS`, `FLAG_LAYOUT_IN_SCREEN`, translucent, gravity top-start, no
  inset fitting) 68 dp square: a 52 dp disc in the accent colour with a soft shadow and the
  app's mark on it, and room for the glow ring.
- First appearance: right edge, 30% down. Later appearances reuse the last side and
  height share for as long as the process lives.
- **Glow** (`flash()`): a 4 dp ring in the accent colour, full at once, fading over 260 ms
  after an 80 ms hold. With animations off: on for 160 ms, then off.
- After a rotation (a `ComponentCallbacks` registered while showing), the same side and
  height share are applied to the new screen.

## 6. Dragging, snapping and the ✕

- A touch that moves less than the system touch slop and lifts is a **tap**.
- Past the slop it is a **drag**: the bubble follows the finger, and the ✕ target appears.
- **The ✕ target:** its own overlay window (220 × 104 dp, not touchable), added first so
  the bubble draws above it. A 26 dp-radius dark circle (80% opacity) with a thin edge and
  a ✕, centred horizontally, its label room inside the safe area's bottom.
- **Over the ✕** (bubble centre within 56 dp of the ✕ centre): the circle grows to 34 dp,
  turns red (amber while Locked) with a white ✕, a label pill appears below it, the bubble
  jumps onto it, and a `VIRTUAL_KEY` haptic fires on entering and leaving.
- **Let go over the ✕:** close (§7). **Elsewhere:** hide the ✕ and animate (220 ms,
  decelerate) to the nearer side edge of the safe area, its top clamped 16 dp inside the
  safe top and bottom.
- **The safe area** is the screen minus the system bars and display cutout, counted even
  while the bars are hidden (`getInsetsIgnoringVisibility`, Android 11+).

`bubbleSnap()` and `overHideTarget()` are pure functions with unit tests.

## 7. Closing, and Lock

- **Close** = the app's own full exit: stop the work, stop the service, remove the task
  (`finishAndRemoveTask`). The activity registers it as `FloatingBubble.onClose`.
- If the activity is gone, the bubble runs `onCloseWithoutScreen` (stop the work) and
  stops the service itself.
- **Lock:** while `isLocked()` is true, a drop on the ✕ only hides the button, with a
  long message "Locked. <App> keeps running. Turn off Lock to close it." The notification's
  Stop follows the same rule (`FloatingBubble.requestClose`).
- The activity removes its callbacks in `onDestroy`, but only if they are still its own
  (a newer activity may have set its own).

## 8. The Settings switch and the permission

One row in the "keep running" part of Settings (DrumTrig: Settings › On stage › Keep
running), off by default:

| State | Switch | Detail line |
|---|---|---|
| Off | off | "Off. A bubble over other apps that brings you back to <App>. Turning it on asks Android for "Display over other apps"." |
| On, permission missing | off | "Waiting for Android's "Display over other apps". Turn it on again to open that page." |
| On | on | "When you leave with <App> running, a bubble stays on screen. Tap it to come back; drag it onto ✕ to close <App>." |

Turning it on saves the setting and, without the permission, opens
`ACTION_MANAGE_OVERLAY_PERMISSION` for this package (the general list if that fails).
`refreshPermissions()` in `onResume` updates the row on return. The app's setup screen
can list the permission too, as optional.

## 9. Copy

- Bubble (screen reader): "<App> is running. Open <App>" (say what it is doing: DrumTrig
  "is listening").
- ✕ label: "Close <App>"; while Locked: "Locked: hides the button".
- Notification: "<App> is running" (with the toggle on: "…, muted"), "Tap to open";
  buttons Stop, Open (and the toggle's own words).
- Say "floating button" in Settings, not "overlay", "bubble window" or "chat head".

## 10. Accessibility

- The bubble is clickable with the click action labelled "Open <App>", plus a custom
  "Close <App>" action, so TalkBack users can close without dragging.
- The ✕ target is not important for accessibility (it only exists during a drag).
- The glow respects "remove animations".
- The bubble is 52 dp, above the 48 dp minimum.

## 11. Acceptance checklist

- [ ] With the setting off, leaving the app shows nothing.
- [ ] Turning it on opens Android's page; coming back with it allowed shows the switch on;
      allowing it while still on that page shows the button straight away.
- [ ] Home, Recents or another app: the button appears. Screen off, rotation, a file
      picker the app opened: it does not.
- [ ] Tap: the app returns and the button goes.
- [ ] Drag: the ✕ appears; letting go elsewhere snaps to the nearer edge, never under the
      navigation buttons or a cutout, in both orientations.
- [ ] Over the ✕: it grows, turns red, says "Close <App>", ticks; letting go closes the
      app, the work and the notification.
- [ ] Locked: amber, "Locked: hides the button"; letting go only hides it. Notification
      Stop also refuses with the same message.
- [ ] Stopping the work from inside the app removes the button.
- [ ] TalkBack: "Open <App>" and the "Close <App>" action both work.
- [ ] The notification's Stop and Open work with the app in the background, and the
      toggle (if any) stays in step with the app.
- [ ] `BubbleTest` passes.

## 12. File manifest

| File | Where it goes |
|---|---|
| `assets/FloatingBubble.kt` | `app/src/main/java/<package>/FloatingBubble.kt` |
| `assets/KeepAliveService.kt` | `app/src/main/java/<package>/KeepAliveService.kt` |
| `assets/MainActivityHooks.kt` | Merge into the main activity and view model. |
| `assets/SettingsRow.kt` | The keep-running part of Settings. |
| `assets/AndroidManifest.snippet.xml` | Merge into `AndroidManifest.xml`. |
| `assets/BubbleTest.kt` | `app/src/test/java/<package>/BubbleTest.kt` |
