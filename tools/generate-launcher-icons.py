#!/usr/bin/env python3
"""Generate the Android launcher icon set from the Prestasi Group mark.

Run with a Python that has Pillow available:

    python3 tools/generate-launcher-icons.py

Two sets are produced because minSdk is 23:

  * Adaptive icons (API 26+) — a transparent foreground layer over a solid
    background colour, which the launcher masks into whatever shape the device
    uses (circle, squircle, teardrop...).
  * Legacy PNGs (API 23-25) — pre-masked square and round bitmaps, since older
    launchers do not composite layers themselves.

The safe zone is the reason the logo is not simply scaled to fill. An adaptive
icon canvas is 108dp, but launchers crop to the middle 72dp and only the central
66dp is guaranteed visible on every mask. Anything drawn outside that risks
being sliced off on some devices, so the mark is inset to ~58% of the canvas.
"""

from pathlib import Path
from PIL import Image, ImageDraw

SOURCE = Path("/Users/langg__/collection_app/public/logo-prestasi.png")
RES = Path(__file__).resolve().parent.parent / "app/src/main/res"

# The logo is crimson and near-black, so it needs a light ground to read at all.
BACKGROUND = (255, 255, 255, 255)

# dp -> px multiplier per density bucket.
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

ADAPTIVE_DP = 108   # full adaptive canvas
LEGACY_DP = 48      # legacy launcher icon
SAFE_FRACTION = 0.58  # of the adaptive canvas; stays inside the 66dp safe zone
LEGACY_FRACTION = 0.76  # legacy icons are already masked, so the mark can breathe wider


def fit(logo: Image.Image, box: int) -> Image.Image:
    """Scale the logo to fit a square of `box` px, preserving aspect ratio."""
    w, h = logo.size
    scale = box / max(w, h)
    return logo.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)


def centered(canvas: Image.Image, logo: Image.Image) -> Image.Image:
    x = (canvas.width - logo.width) // 2
    y = (canvas.height - logo.height) // 2
    canvas.alpha_composite(logo, (x, y))
    return canvas


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Logo tidak ditemukan: {SOURCE}")
    logo = Image.open(SOURCE).convert("RGBA")
    written = []

    for bucket, mult in DENSITIES.items():
        out = RES / f"mipmap-{bucket}"
        out.mkdir(parents=True, exist_ok=True)

        # --- adaptive foreground: transparent, mark inside the safe zone ---
        size = round(ADAPTIVE_DP * mult)
        fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        centered(fg, fit(logo, round(size * SAFE_FRACTION)))
        fg.save(out / "ic_launcher_foreground.png")
        written.append(f"mipmap-{bucket}/ic_launcher_foreground.png ({size}px)")

        # --- legacy square ---
        legacy = round(LEGACY_DP * mult)
        sq = Image.new("RGBA", (legacy, legacy), BACKGROUND)
        centered(sq, fit(logo, round(legacy * LEGACY_FRACTION)))
        sq.save(out / "ic_launcher.png")

        # --- legacy round: same art, circular mask ---
        rnd = Image.new("RGBA", (legacy, legacy), (0, 0, 0, 0))
        circle = Image.new("L", (legacy * 4, legacy * 4), 0)
        ImageDraw.Draw(circle).ellipse((0, 0, legacy * 4 - 1, legacy * 4 - 1), fill=255)
        circle = circle.resize((legacy, legacy), Image.LANCZOS)  # anti-aliased edge
        plate = Image.new("RGBA", (legacy, legacy), BACKGROUND)
        centered(plate, fit(logo, round(legacy * LEGACY_FRACTION)))
        rnd.paste(plate, (0, 0), circle)
        rnd.save(out / "ic_launcher_round.png")
        written.append(f"mipmap-{bucket}/ic_launcher.png + _round.png ({legacy}px)")

    # --- adaptive icon descriptors (API 26+) ---
    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />\n'
        "</adaptive-icon>\n"
    )
    (anydpi / "ic_launcher.xml").write_text(xml)
    (anydpi / "ic_launcher_round.xml").write_text(xml)
    written.append("mipmap-anydpi-v26/ic_launcher.xml + _round.xml")

    colors = RES / "values/ic_launcher_background.xml"
    colors.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        '    <color name="ic_launcher_background">#FFFFFF</color>\n'
        "</resources>\n"
    )
    written.append("values/ic_launcher_background.xml")

    print(f"{len(written)} berkas ditulis ke {RES}:")
    for w in written:
        print("  " + w)


if __name__ == "__main__":
    main()
