# Which apps to remove

Package names grouped by how safe they are to remove. Only packages actually installed on the
TV matter (`adb shell pm list packages`). Removal is for the current user only
(`pm uninstall -k --user 0`), so `restore.sh` or a factory reset brings any of them back.

## Remove: unused for most people

| Package | What it is |
| --- | --- |
| `com.google.android.tvrecommendations` | Recommendation rows for the stock home screen |
| `com.google.android.backdrop` | Ambient Mode screensaver (MinimalTV has its own) |
| `com.google.android.apps.tv.dreamx` | Google TV Ambient Mode |
| `com.android.dreams.basic` | Basic colour screensaver |
| `com.google.android.play.games` | Google Play Games |
| `com.google.android.videos` | Google TV / Play Movies |
| `com.google.android.feedback` | Send-feedback service |
| `com.android.tv.feedbackconsent` | Feedback consent screen |
| `com.google.android.partnersetup` | Manufacturer setup helper (used once at setup) |
| `com.android.wallpaper.livepicker` | Live wallpaper picker (not used on TVs) |
| `com.android.printspooler` | Printing |
| `com.android.htmlviewer` | HTML file viewer |
| `com.android.dynsystem` | Dynamic system updates (developer tool) |
| `com.google.android.syncadapters.calendar` | Calendar sync |

## Remove after MinimalTV is the home screen

The stock home screens are the heaviest apps on most TVs. Remove these only once pressing Home
opens MinimalTV.

| Package | What it is |
| --- | --- |
| `com.google.android.apps.tv.launcherx` | Google TV home screen |
| `com.google.android.tvlauncher` | Android TV home screen |
| `com.google.android.leanbacklauncher` | Older Android TV home screen |

## Ask: the user decides

These are real features some people use. List the ones that are installed and ask.

| Package | What it is |
| --- | --- |
| `com.google.android.youtube.tv`, `com.google.android.youtube.tvmusic`, `com.netflix.ninja`, `com.amazon.amazonvideo.livingroom`, `com.disney.disneyplus`, `in.startv.hotstar`, … | Streaming apps that came preinstalled |
| `com.google.android.apps.mediashell` | Chromecast built-in (casting from a phone) |
| `com.google.android.katniss` | Google Assistant and voice search |
| `com.google.android.marvin.talkback`, `com.google.android.tts` | Screen reader and text-to-speech |
| Any user-installed app (`pm list packages -3`) | Apps someone installed; ask about each one |
| Manufacturer extras (screen mirroring, media players, app stores, "TV manager", tuner apps) | Check the label (`dumpsys package <pkg>`) and ask; tuner apps are needed for antenna TV |

## Never remove

Removing these can break the TV, the remote, HDMI inputs or streaming apps.

- `android`, `com.android.systemui`, `com.android.settings`, `com.android.tv.settings`,
  anything `com.android.providers.*`, `com.android.shell`, `com.android.packageinstaller`,
  `com.android.permissioncontroller`, `com.android.vending` (Play Store)
- `com.google.android.gms`, `com.google.android.gsf` (Play services: needed by most apps)
- `com.google.android.webview`, `com.google.android.leanback.ime` (keyboard),
  `com.google.android.tungsten.setupwraith` (setup)
- Remote and Bluetooth: `com.google.android.tv.remote.service`, `com.android.bluetooth`, and any
  manufacturer package with `remote`, `bluetooth`, `keyhandler` or `virtualkey` in the name
- Inputs and picture: `com.android.tv` (Live Channels / inputs on many TVs), and manufacturer
  packages with `input`, `hdmi`, `passthrough`, `livetv`, `tvinput`, `picture` or `smartmenu`
  in the name
- Anything else you can't identify. When unsure, keep it.
