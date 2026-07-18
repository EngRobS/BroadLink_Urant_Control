# Broadlink Ctrl

One Android app, two tabs:
- **Setup / Reset** — walks you through factory-resetting an SP3 (or a URANT
  plug/switch of the same era — they use identical hardware/firmware, device
  id `0x2728`) and pushing your home WiFi credentials to it.
- **Control** — scans your LAN and lets you toggle power on any Broadlink-
  protocol plug it finds, entirely locally (no cloud, no Broadlink account).

This talks directly to the plug over your WiFi using Broadlink's local UDP
protocol (AES-128-CBC), the same one used by Home Assistant's Broadlink
integration and the open-source `python-broadlink` project. Nothing goes
through Broadlink's servers.

## Get a working APK (no Android Studio needed)

1. Create a free GitHub account if you don't have one: https://github.com/join
2. Create a new **public or private** repository (any name).
3. Upload every file in this project to that repo, keeping the folder
   structure exactly as-is (the `.github/workflows/build.yml` file must stay
   at that exact path — GitHub's web uploader preserves folders if you drag
   the whole project folder in, or you can use "Add file → Upload files" and
   drag the top-level folder).
4. Go to the **Actions** tab of your repo. A workflow called "Build APK"
   should start automatically (or click "Run workflow" if it doesn't).
5. Wait ~2–4 minutes for it to finish (green checkmark).
6. Click into the finished run → scroll to **Artifacts** → download
   `BroadlinkCtrl-debug-apk`. Unzip it — you'll have `app-debug.apk`.
7. Transfer that APK to your phone (email it to yourself, Google Drive, USB,
   whatever's easiest) and tap it to install. You'll need to allow "install
   from unknown sources" for whichever app you used to open it — Android will
   prompt you the first time.

This is a debug build, self-signed automatically by Gradle — totally normal
for personal use, just not distributable via Play Store as-is.

## Using it

**Resetting a plug:** Plug it in, hold the button ~5–10s until the LED blinks
rapidly. It'll broadcast its own temporary WiFi network. Connect your phone to
that network manually (the app has a button that jumps straight to WiFi
settings), come back to the app, enter your **2.4GHz** home WiFi name and
password, and hit send. These devices don't support 5GHz networks.

**Controlling plugs:** Once a plug has joined your home WiFi, switch back to
your normal network, open the Control tab, hit Scan. Found devices show a
toggle switch — flipping it sends the on/off command directly to the plug.

## A few honest caveats

- I ported the protocol from the maintained open-source spec, but I don't
  have physical SP3/URANT hardware to test against, so treat the first run as
  a test — if a scan finds nothing, double-check the plug actually joined
  your WiFi (check your router's client list) before assuming the app is
  broken.
- Some very old SP3 firmware variants encrypt slightly differently around
  edge cases (e.g. nightlight-equipped models shift the power byte). If
  toggling seems to do nothing on a specific unit, that's the most likely
  culprit and worth flagging to me — the fix is a one-line change.
- Android 10+ won't let an app join a WiFi network for you automatically;
  that's why step 2 of setup opens system WiFi settings instead of doing it
  silently — Google intentionally restricts this for security.
