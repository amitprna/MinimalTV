"""Writes the StandBy line icons (weather, indoor) as tintable vector drawables: res/drawable/wx_*.xml."""
import pathlib, sys

CLOUD_HIGH = "M7.5,14h9a3.6,3.6 0,0 0,0.5,-7.2A5.2,5.2 0,0 0,7,5.6A4.2,4.2 0,0 0,7.5,14z"
ICONS = {
    "cloud": ["M7.5,16h9a3.6,3.6 0,0 0,0.5,-7.2A5.2,5.2 0,0 0,7,7.6A4.2,4.2 0,0 0,7.5,16z"],
    "rain": [CLOUD_HIGH, "M8.5,17l-1,3M12.5,17l-1,3M16.5,17l-1,3"],
    "showers": [CLOUD_HIGH, "M10,17l-1,3M15,17l-1,3"],
    "storm": [CLOUD_HIGH, "M12.5,15.5l-2.2,3.5h3l-2.2,3.5"],
    "drizzle": [CLOUD_HIGH, ("M9,18.5h0.01M12.5,20h0.01M16,18.5h0.01", 2.4)],
    "snow": [CLOUD_HIGH, ("M9,18h0.01M12,20.5h0.01M15,18h0.01M10.5,22.5h0.01M13.5,22.5h0.01", 2.4)],
    "sun": ["M8,12a4,4 0,1 0,8,0a4,4 0,1 0,-8,0",
            "M12,2.5v2M12,19.5v2M2.5,12h2M19.5,12h2M5.3,5.3l1.4,1.4M17.3,17.3l1.4,1.4M5.3,18.7l1.4,-1.4M17.3,6.7l1.4,-1.4"],
    "partly": ["M9,3v1.2M4.6,4.6l0.9,0.9M3,9h1.2M13.4,4.6l-0.9,0.9", "M6.4,11.6A3.2,3.2 0,0 1,11.8,7.2",
               "M9.5,19h8a3.2,3.2 0,0 0,0.4,-6.4A4.6,4.6 0,0 0,9.2,11.6A3.7,3.7 0,0 0,9.5,19z"],
    "fog": ["M5,9h14M3,13h18M6,17h12"],
    "thermo": ["M10,13.6V5.5a2,2 0,0 1,4,0v8.1a4,4 0,1 1,-4,0z", "M12,10v6"],
    "pin": ["M12,21s6,-5.5 6,-11a6,6 0,0 0,-12,0c0,5.5 6,11 6,11z", "M10,10a2,2 0,1 0,4,0a2,2 0,1 0,-4,0"],
    "air": ["M3,8h10a2.5,2.5 0,1 0,-2.5,-2.5M3,12h15a2.5,2.5 0,1 1,-2.5,2.5M3,16h7"],
    "drop": ["M12,4.5s5.5,6 5.5,9.5a5.5,5.5 0,0 1,-11,0C6.5,10.5 12,4.5 12,4.5z"],
}

out = pathlib.Path(sys.argv[1])
for name, paths in ICONS.items():
    body = []
    for p in paths:
        d, w = (p, 1.5) if isinstance(p, str) else p
        body.append(f'    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="{w}" android:strokeLineCap="round" '
                    f'android:strokeLineJoin="round" android:pathData="{d}" />')
    (out / f"wx_{name}.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n    android:height="24dp"\n    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
        + "\n".join(body) + "\n</vector>\n", encoding="utf-8")
print(len(ICONS), "icons")
