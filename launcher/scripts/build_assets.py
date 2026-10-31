"""Prepares CleanFrame's photos, glass materials and ink data for the Android launcher.

The TV never blurs or samples pixels at runtime; like CleanFrame, every material is
rendered ahead of time. This script crops them to what the launcher actually draws.

Usage: python build_assets.py <cleanframe-app-dir> <downloads-dir> <android-assets-dir> [keep-ids]
keep-ids: comma-separated wallpaper ids to bundle (default: all fifteen).
"""
import json
import shutil
import sys
from pathlib import Path

from PIL import Image

STAGE_W, STAGE_H = 1920, 1080
DOCK_TOP, DOCK_H = 676, 232  # CleanFrame dock band (y = 908 - 200 - 32)


def load_js_data(path):
    src = path.read_text(encoding="utf8")
    start = min(i for i in (src.find("["), src.find("{")) if i >= 0)
    end = max(src.rfind("]"), src.rfind("}")) + 1
    return json.loads(src[start:end])


def cover_crop(img):
    # object-fit: cover, object-position: 50% 50%
    scale = max(STAGE_W / img.width, STAGE_H / img.height)
    w, h = round(img.width * scale), round(img.height * scale)
    img = img.resize((w, h), Image.LANCZOS) if (w, h) != img.size else img
    left, top = (w - STAGE_W) // 2, (h - STAGE_H) // 2
    return img.crop((left, top, left + STAGE_W, top + STAGE_H))


def main(app_dir, downloads, out, keep=None):
    app_dir, downloads, out = Path(app_dir), Path(downloads), Path(out)
    for sub in ("walls", "mats", "frost", "fields", "thumbs"):
        shutil.rmtree(out / sub, ignore_errors=True)  # drop photos no longer bundled
    for sub in ("walls", "mats", "frost", "fields", "thumbs", "fonts"):
        (out / sub).mkdir(parents=True, exist_ok=True)

    walls = load_js_data(app_dir / "assets/wallpapers/catalog.js")
    if keep:
        ids = keep.split(",")
        walls = sorted((w for w in walls if w["id"] in ids), key=lambda w: ids.index(w["id"]))
    ink = load_js_data(app_dir / "assets/adaptive-ink.js")
    table = []
    for w in walls:
        wid = w["id"]
        photo = Image.open(app_dir / w["src"]).convert("RGB")
        cover_crop(photo).save(out / "walls" / f"{wid}.jpg", quality=90, subsampling=0)

        # Dock materials are registered to the full stage; keep only the dock band of each.
        for material, src in (("soft", "study-soft"), ("frost", "field-frost"), ("clear", "field-clear")):
            mat = cover_crop(Image.open(app_dir / f"assets/materials/{wid}-{src}.jpg").convert("RGB"))
            mat.crop((0, DOCK_TOP, STAGE_W, DOCK_TOP + DOCK_H)).save(
                out / "mats" / f"{wid}-{material}.jpg", quality=90, subsampling=0)

        # List focus plates show the blurred field through a moving window; half size is plenty.
        blur = cover_crop(Image.open(app_dir / f"assets/materials/{wid}-field-blur.jpg").convert("RGB"))
        blur.resize((STAGE_W // 2, STAGE_H // 2), Image.LANCZOS).save(out / "fields" / f"{wid}.jpg", quality=88)

        cover_crop(photo).resize((192, 108), Image.LANCZOS).save(out / "thumbs" / f"{wid}.jpg", quality=85)

        # Frost field backs the All apps sheet; it is a heavy blur, so half size loses nothing.
        frost = cover_crop(Image.open(app_dir / f"assets/materials/{wid}-field-frost.jpg").convert("RGB"))
        frost.resize((STAGE_W // 2, STAGE_H // 2), Image.LANCZOS).save(out / "frost" / f"{wid}.jpg", quality=88)

        p = ink.get(wid, {})
        tl, tr, br = p.get("tl", {}), p.get("tr", {}), p.get("br", {})
        table.append({
            "id": wid,
            "name": w["name"],
            "tone": w.get("tone", "dark"),
            "dockQuiet": bool(w.get("dockQuiet")),
            "frostVeil": w.get("frostVeil", 0.12),
            "listColumn": w.get("listColumn", 0),
            "appInk": p.get("light", "#f6f1e7"),
            "tlPol": tl.get("pol", w.get("ut", {}).get("tl", "light")),
            "tlInk": tl.get("ink", "#f6f1e7"),
            "trPol": tr.get("pol", w.get("ut", {}).get("tr", "light")),
            "trInk": tr.get("ink", "#f6f1e7"),
            "trRest": tr.get("restAlpha", 0.66),
            "trPeer": tr.get("peerAlpha", 0.46),
            "trHalo": tr.get("halo", "rgba(14,10,6,.38)"),
            "brPol": br.get("pol", w.get("ut", {}).get("br", "light")),
            "brInk": br.get("ink", "#f6f1e7"),
            "brRest": br.get("restAlpha", 0.66),
            "brPeer": br.get("peerAlpha", 0.46),
            "brHalo": br.get("halo", "rgba(14,10,6,.38)"),
        })
        print("prepared", wid)

    (out / "walls.json").write_text(json.dumps(table, indent=1), encoding="utf8")
    shutil.copy(downloads / "Manrope-Variable.ttf", out / "fonts" / "Manrope.ttf")
    shutil.copy(app_dir / "assets/fonts/geist/Geist-Variable.ttf", out / "fonts" / "Geist.ttf")
    shutil.copy(app_dir / "assets/fonts/newsreader/Newsreader-Variable.ttf", out / "fonts" / "Newsreader.ttf")


if __name__ == "__main__":
    main(*sys.argv[1:5])
