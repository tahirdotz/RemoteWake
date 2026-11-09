# Remote Wake

Wake your office phone (**Phone A**) from another phone (**Phone B**), even when the two phones are on
different networks (office Wi-Fi, home Wi-Fi, mobile data). Phone B shows whether it worked, along
with Phone A's battery level.

Install the same app on both phones and pick a role on each.

## How it works

```
Phone B  ──HTTPS──▶  ntfy relay  ──HTTPS (kept open)──▶  Phone A
  "wake"             (ntfy.sh)                            screen turns on
Phone B  ◀──HTTPS──  ntfy relay  ◀──HTTPS──────────────  "awake, 87%, charging"
```

- Both phones only make **outgoing** HTTPS connections, so routers, NAT and mobile networks are not a problem.
- The relay is [ntfy](https://github.com/binwiederhier/ntfy), a free and open-source push service.
  The public server `https://ntfy.sh` needs no account. You can also run your own ntfy server and
  enter its address on both phones.
- Phone A keeps one connection open in a foreground service (you see a "Listening for Phone B" notification).
- The 16-character **pairing code** is the secret. Commands go to the topic `rw-<code>-a` and replies to `rw-<code>-b`.
  Without the code, nobody can find or send to your phone.
- Phone A ignores commands older than 3 minutes and never acts on the same command twice.

## Build and install (Android Studio)

1. In Android Studio, choose **File → Open** and select this `RemoteWake` folder.
2. Connect a phone with USB debugging on, then press **Run ▶**. Do this for both phones.

From the command line: `./gradlew assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`.

## Setup

**Phone A (office phone, Pixel 5/6):**
1. Open Remote Wake, tap **This is Phone A**, and allow notifications.
2. Fix every ❌ in the **Setup checklist**:
   - *Battery usage unrestricted*: tap **Fix → Allow**. Without it, Phone A loses the connection when idle.
   - *Full-screen notifications*: on Android 14 and later, tap **Fix** and turn it on. This is what turns the screen on.
3. Tap **Test: lock this phone, wake it in 10 s**, then lock the phone. The screen should come on by itself.
4. Note the pairing code, or tap **Share code** to send it to yourself.
5. Leave the phone on its charger, connected to Wi-Fi or mobile data.

**Phone B (the remote):**
1. Tap **This is Phone B** and type Phone A's pairing code.
2. Tap **Wake Phone A**. Within a few seconds you see `✅ Phone A is awake: its screen is on`.
3. **Check Phone A** asks whether Phone A is online, without turning its screen on.

## What it does not do

- **It does not unlock the phone.** Your pattern lock stays in place. Wake turns the screen on and
  shows a "Woken by Phone B" screen for one minute. To use Phone A's apps from a PC, pair it with a
  remote-control app (AnyDesk, TeamViewer, or scrcpy over Tailscale) and draw the pattern there.
- **After Phone A reboots,** Android starts the app only once you have unlocked the phone one time.
  Until then, Phone B gets "No answer".
- Phone A needs power and an internet connection. If it is offline, a command sent from Phone B is
  carried out only if Phone A reconnects within 3 minutes.

## Tested

On Android 16 emulators (Pixel 6 as Phone A, Pixel 5 as Phone B) through the public `ntfy.sh` relay:
- Wake from screen-off with a pattern lock set: the screen turns on, the lock stays, and Phone B shows the confirmation.
- Wake while Phone A is in deep Doze and unplugged.
- Ping without waking. The **Unlock** button shows the pattern pad.
- The listener comes back after a reboot and after an app update.

Unit tests for the message format: `./gradlew testDebugUnitTest`.

## Code

| File | What it does |
|---|---|
| `Protocol.kt` | Pairing codes, topic names, the message format |
| `Relay.kt` | Minimal ntfy client: publish, poll, and a long-lived stream |
| `ListenerService.kt` | Phone A: keeps the connection, reconnects on network change, replies |
| `Waker.kt`, `WakeActivity.kt` | Turn the screen on and show over the lock screen |
| `MainActivity.kt` | Role chooser, Phone A and Phone B screens |
| `BootReceiver.kt` | Restarts listening after a reboot or update |
