# MinimalTV: instructions for AI agents

The user pasted this repo and wants their Android TV or Google TV faster. Your job, in order:
connect to the TV, measure it, remove the apps they don't need (only with their approval),
install the MinimalTV home screen in place of the heavy stock one, apply the speed settings
they agree to, then measure again and report.

The user is probably not technical. Talk in plain words, give exact menu paths for the remote,
do one thing at a time, and confirm before every change to the TV. Everything here can be
undone; keep a record of what you changed (`speedup/removed.log` does this for apps).

## 1. Tools on the computer

You need `adb` (Android platform-tools) and `curl`. Install `adb` if `adb version` fails:

- Windows: `winget install Google.PlatformTools`
- macOS: `brew install android-platform-tools`
- Linux: `sudo apt install adb` (or the distro's equivalent)

Or download platform-tools from https://developer.android.com/tools/releases/platform-tools.
The scripts in `speedup/` and `scripts/` are bash; on Windows run them from Git Bash.

## 2. Connect to the TV

Ask the user to do this on the TV with the remote:

1. **Turn on Developer options:** Settings › System › About, select **Android TV OS build**
   (or **Build**) seven times until it says "You are now a developer". On some TVs it's
   Settings › Device Preferences › About.
2. **Turn on debugging:** Settings › System › Developer options › **USB debugging** on. If there
   is a **Network debugging** or **ADB over network** switch, turn that on too.
3. **Find the TV's IP address:** Settings › Network & Internet › select the Wi-Fi network; it
   looks like `192.168.1.20`.

Then run `adb connect <ip>:5555` and ask the user to choose **Allow** (tick "Always allow")
on the TV. Check with `adb devices` (it must say `device`, not `unauthorized`). Use
`TV=<ip>:5555` and `adb -s <ip>:5555` from here on.

If the connection is refused and the TV only has **Wireless debugging** (Android 11+): ask
the user to open it, choose **Pair device with pairing code**, then run
`adb pair <ip>:<pair-port>` with the code shown, then `adb connect <ip>:<port shown on that screen>`.

Check the Android version: `adb shell getprop ro.build.version.sdk` must be **30 or higher**
(Android 11). On older TVs, do the app cleanup (step 4) but skip MinimalTV (step 5).

## 3. Measure first

Record these to compare at the end:

```bash
adb -s $TV shell cat /proc/meminfo | grep -E "MemTotal|MemAvailable"
adb -s $TV shell pm list packages | wc -l
```

## 4. Remove unused apps (ask first)

1. List what's installed: `adb -s $TV shell pm list packages` (and `-3` for user-installed apps).
   For unclear names, get the visible label from `adb -s $TV shell dumpsys package <pkg>` or
   ask the user what they see on the TV.
2. Sort the installed ones with [`speedup/apps.md`](speedup/apps.md): **Remove**, **Ask** and
   **Never remove**. Packages that aren't installed don't matter. Anything not in the list and
   not clearly identifiable goes under keep.
3. Show the user a short, plain list: "I'd remove these (what each one is)", then each **Ask**
   app as a yes/no question ("Do you use Chromecast to cast from your phone?"). Never remove a
   **Never remove** package, and never remove anything they didn't approve.
4. Remove the approved ones: `TV=$TV speedup/remove.sh <pkg> <pkg> ...`. It backs each app up
   to `speedup/backup/` first and logs to `speedup/removed.log`.
5. Leave the stock home screen packages for step 5.

## 5. Install the MinimalTV home screen

1. Download the app:
   `curl -L -o MinimalTV.apk https://github.com/amitprna/MinimalTV/releases/latest/download/MinimalTV.apk`
2. `adb -s $TV install -r MinimalTV.apk`
3. Make it the screensaver and let its settings set the sleep timer:

   ```bash
   adb -s $TV shell settings put secure screensaver_components io.github.amitprna.minimaltv/.ClockDream
   adb -s $TV shell pm grant io.github.amitprna.minimaltv android.permission.WRITE_SECURE_SETTINGS
   ```

4. Ask the user to press **Home** on the remote. If the TV asks which app to use, pick
   **MinimalTV** › **Always**.
5. If the stock home screen still opens, find it with
   `adb -s $TV shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME`
   and, with the user's OK, remove it with `speedup/remove.sh` (see "Remove after MinimalTV is
   the home screen" in `speedup/apps.md`). Then press Home again and confirm MinimalTV opens.
6. **Weather on the screensaver (optional):** ask for their town or city. Look up coordinates
   with `https://geocoding-api.open-meteo.com/v1/search?name=<city>&count=1`, write
   `{"name": "<city>", "lat": <lat>, "lon": <lon>}` to `config/location.json`, and run
   `TV=$TV scripts/push_to_tv.sh` (it also pushes scenes when there are any). Then restart the
   app: `adb -s $TV shell am force-stop io.github.amitprna.minimaltv` and press Home.
7. **Apps on the dock:** it shows VLC, YouTube and Netflix when installed. Tell the user they
   can change it on the TV: the settings icon on the right › Apps on Home.

## 6. Speed settings (ask first)

- **Faster animations:** halves every transition.

  ```bash
  adb -s $TV shell settings put global window_animation_scale 0.5
  adb -s $TV shell settings put global transition_animation_scale 0.5
  adb -s $TV shell settings put global animator_duration_scale 0.5
  ```

  Undo with `1` in place of `0.5`.
- **Clear app caches:** `adb -s $TV shell pm trim-caches 999G`
- **Suggest, don't change:** on the TV, Google Play Store › Settings › Auto-update apps ›
  "Don't auto-update apps" stops updates running in the background (they then update apps from
  the Play Store themselves). Settings › Apps › See all apps: disable any app they never open.
- **Restart the TV:** `adb -s $TV reboot`, then reconnect (`adb connect $TV`).

## 7. Measure again and report

Run step 3 again after the restart (wait about a minute). Tell the user, in plain words, how
much memory is now free compared with before, how many apps were removed, and what changed.
Mention they can turn **USB debugging** off again, and how to undo (below).

## Live wallpapers (optional, if the user asks)

MinimalTV can play a rain scene at night and a waterfall by day, with sound. The files are free
on Pixabay but can't be redistributed, so the user downloads them:

1. Ask them to download these four (free account may be needed) into one folder:
   [rain video](https://pixabay.com/videos/room-rain-forest-ambience-262372/),
   [rain sound](https://pixabay.com/sound-effects/nature-gentle-rain-01-437305/),
   [waterfall video](https://pixabay.com/videos/waterfall-water-flow-japan-natural-236928/),
   [waterfall sound](https://pixabay.com/sound-effects/nature-sounds-of-a-powerful-waterfall-sounds-of-nature-145254/).
2. Install `ffmpeg` (Windows `winget install Gyan.FFmpeg`, macOS `brew install ffmpeg`, Linux
   `sudo apt install ffmpeg`) and Python 3.
3. `scripts/prepare_scenes.sh <rain.mp4> <rain.mp3> <waterfall.mp4> <waterfall.mp3>`
   (writes `scenes/`), then `TV=$TV scripts/push_to_tv.sh`.
4. Restart the app (force-stop, press Home). Day and night times are in its Settings ›
   Background.

## Room temperature (optional)

A Tuya-compatible Wi-Fi temperature and humidity sensor can show on the screensaver. The user
needs a Tuya IoT cloud project linked to the sensor (https://developer.tuya.com). Fill
`config/tuya.json` from `config/tuya.example.json` and run `scripts/push_to_tv.sh`. Never print
or commit the secret.

## Undo

- Apps: `TV=$TV speedup/restore.sh --all` (or name packages).
- MinimalTV: `adb -s $TV uninstall io.github.amitprna.minimaltv`, then
  `adb -s $TV shell settings delete secure screensaver_components`. Restore the stock home screen
  first if it was removed, or the TV will have no home screen.
- Animations: set the three scales back to `1`.
- Everything at once: a factory reset (Settings › System › Reset) restores the TV as it came.
