#!/usr/bin/env python3
"""compose_walls.py -- deterministic README image-wall compositor.

Regenerates the marketing "wall" PNGs under docs/images/ from raw device
screenshots plus a declarative manifest (wall_manifest.toml, next to this
script). It exists so a wall can be tweaked by editing data, not by redoing
pixels by hand in an external editor.

Usage (run from anywhere in the repo; paths in the manifest resolve against
the repo root):

    python Tools/scripts/compose_walls.py                  # build every wall
    python Tools/scripts/compose_walls.py --wall hero.png   # build just one
    python Tools/scripts/compose_walls.py --check           # build to a temp
                                                             # dir and diff
                                                             # against the
                                                             # committed PNGs,
                                                             # without writing

Requirements: Python 3.11+ (for stdlib `tomllib`) and Pillow. Install Pillow
with:

    pip install -r Tools/scripts/requirements-images.txt

(Deliberately a separate file from Tools/scripts/requirements.txt -- that one
is installed by staging.yml on every publish build for upload.py, which has
no use for an imaging library.)

Source screenshots live in docs/screenshots/ (gitignored, populated by hand)
and are never modified or committed by this script. Raw device captures are
full-screen 1080x2354 JPGs; each panel's `crop` rectangle in the manifest
selects the sub-region to composite, in that screenshot's own native pixel
coordinates.

Determinism: given the same manifest, the same screenshots, the same Pillow
version, and the same resolved caption font (the first FONT_CANDIDATES entry
that exists on disk -- see below), re-running this script byte-for-byte
reproduces its output (no timestamps, no randomness, no multithreading). This
is a same-machine guarantee, not a cross-machine one: a different OS, or a
different font installed at the same candidate path, changes glyph rendering
and therefore the output bytes. If a change to Pillow changes its encoder
output, regenerate every wall together rather than mixing PNGs written by
different Pillow versions.
"""

from __future__ import annotations

import argparse
import filecmp
import sys
import tempfile
import tomllib
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

SCRIPT_DIR = Path(__file__).resolve().parent
MANIFEST_PATH = SCRIPT_DIR / "wall_manifest.toml"

# Fonts tried in order; the first one that exists on disk is used. This
# script is only ever run by hand on dazewell's Windows machine, so the
# Windows paths come first, but the Linux/macOS entries cost nothing and mean
# a checkout on another OS still renders something instead of hard-failing.
FONT_CANDIDATES = [
    Path(r"C:\Windows\Fonts\segoeuib.ttf"),
    Path(r"C:\Windows\Fonts\arialbd.ttf"),
    Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
    Path("/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"),
    Path("/System/Library/Fonts/Supplemental/Arial Bold.ttf"),
]


def find_repo_root(start: Path) -> Path:
    """Walk upward from `start` to find the repository root (marked by .git)."""
    for candidate in [start, *start.parents]:
        if (candidate / ".git").exists():
            return candidate
    raise SystemExit(
        f"could not find repo root (no .git) above {start}; "
        "run this script from within the NagramX checkout"
    )


def hex_to_rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[i : i + 2], 16) for i in (0, 2, 4))  # noqa: E203


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if path.exists():
            return ImageFont.truetype(str(path), size)
    raise SystemExit(
        "no bold sans-serif font found (looked for: "
        + ", ".join(str(p) for p in FONT_CANDIDATES)
        + "); install one of these or add its path to FONT_CANDIDATES"
    )


@dataclass
class Settings:
    canvas_width: int
    canvas_height: int
    margin: int
    gutter: int
    corner_radius: int
    shadow_blur: int
    shadow_offset_y: int
    shadow_opacity: float
    bg_top: tuple[int, int, int]
    bg_bottom: tuple[int, int, int]
    caption_color: tuple[int, int, int]
    caption_font_size: int
    caption_gap: int
    stagger_amplitude: int
    screenshots_dir: Path
    output_dir: Path

    @classmethod
    def from_toml(cls, data: dict, repo_root: Path) -> "Settings":
        s = data["settings"]
        shadow_opacity = s["shadow_opacity"]
        if not (0.0 <= shadow_opacity <= 1.0):
            raise SystemExit(
                f"settings.shadow_opacity must be between 0 and 1, got "
                f"{shadow_opacity!r}"
            )
        return cls(
            canvas_width=s["canvas_width"],
            canvas_height=s["canvas_height"],
            margin=s["margin"],
            gutter=s["gutter"],
            corner_radius=s["corner_radius"],
            shadow_blur=s["shadow_blur"],
            shadow_offset_y=s["shadow_offset_y"],
            shadow_opacity=shadow_opacity,
            bg_top=hex_to_rgb(s["bg_top"]),
            bg_bottom=hex_to_rgb(s["bg_bottom"]),
            caption_color=hex_to_rgb(s["caption_color"]),
            caption_font_size=s["caption_font_size"],
            caption_gap=s["caption_gap"],
            stagger_amplitude=s["stagger_amplitude"],
            screenshots_dir=(repo_root / s["screenshots_dir"]).resolve(),
            output_dir=(repo_root / s["output_dir"]).resolve(),
        )

    def max_card_height(self, caption_font: ImageFont.FreeTypeFont) -> int:
        """Tallest a card may be so the full stagger + caption still fits
        inside the canvas margins."""
        ascent, descent = caption_font.getmetrics()
        caption_height = ascent + descent
        budget = (
            self.canvas_height
            - 2 * self.margin
            - 2 * self.stagger_amplitude
            - self.caption_gap
            - caption_height
        )
        if budget <= 0:
            raise SystemExit(
                "settings leave no room for any card height; "
                "loosen margin/stagger_amplitude/caption sizing"
            )
        return budget


def make_vertical_gradient(
    width: int, height: int, top: tuple[int, int, int], bottom: tuple[int, int, int]
) -> Image.Image:
    """A width x height RGB image, linearly interpolated from `top` at row 0
    to `bottom` at the last row."""
    gradient = Image.new("RGB", (1, height))
    for y in range(height):
        t = y / max(height - 1, 1)
        row = tuple(round(top[c] + (bottom[c] - top[c]) * t) for c in range(3))
        gradient.putpixel((0, y), row)
    return gradient.resize((width, height))


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    w, h = size
    r = min(radius, w // 2, h // 2)
    mask = Image.new("L", (w, h), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle([0, 0, w - 1, h - 1], radius=r, fill=255)
    return mask


def _validate_rect(
    rect: list[int], im_width: int, im_height: int, source: str, kind: str
) -> tuple[int, int, int, int]:
    left, top, right, bottom = rect
    if not (0 <= left < right <= im_width and 0 <= top < bottom <= im_height):
        raise SystemExit(
            f"panel {source!r} has an invalid {kind} rect {rect} for a "
            f"{im_width}x{im_height} source image; expected "
            "0 <= left < right <= width and 0 <= top < bottom <= height"
        )
    return left, top, right, bottom


def load_panel_image(
    panel: dict, screenshots_dir: Path
) -> Image.Image:
    src_path = screenshots_dir / panel["source"]
    if not src_path.exists():
        raise SystemExit(
            f"source screenshot not found: {src_path}\n"
            "docs/screenshots/ is gitignored and populated by hand -- copy "
            "the raw captures there before running this tool."
        )
    with Image.open(src_path) as src_im:
        im = src_im.convert("RGB")

    redact_rects = panel.get("redact", [])
    if redact_rects:
        draw = ImageDraw.Draw(im)
        for rect in redact_rects:
            r_left, r_top, r_right, r_bottom = _validate_rect(
                rect, im.width, im.height, panel["source"], "redact"
            )
            draw.rectangle((r_left, r_top, r_right, r_bottom), fill=(0, 0, 0))

    # `blur` obscures a region while keeping its shape/color roughly
    # legible -- for content that should read as "there but not published"
    # (e.g. a real name or message body) rather than an outright block like
    # `redact`. The radius scales with the rect's shorter side so a small
    # rect still comes out genuinely unreadable, not merely softened.
    for rect in panel.get("blur", []):
        b_left, b_top, b_right, b_bottom = _validate_rect(
            rect, im.width, im.height, panel["source"], "blur"
        )
        region = im.crop((b_left, b_top, b_right, b_bottom))
        radius = max(18, min(region.width, region.height) // 3)
        region = region.filter(ImageFilter.GaussianBlur(radius))
        im.paste(region, (b_left, b_top))

    left, top, right, bottom = _validate_rect(
        panel["crop"], im.width, im.height, panel["source"], "crop"
    )
    return im.crop((left, top, right, bottom))


@dataclass
class LaidOutPanel:
    image: Image.Image
    caption: str
    x: int
    y: int
    width: int
    height: int


def layout_wall(
    panels_raw: list[Image.Image],
    captions: list[str],
    settings: Settings,
    caption_font: ImageFont.FreeTypeFont,
) -> list[LaidOutPanel]:
    n = len(panels_raw)
    if n == 0:
        raise SystemExit("a wall must have at least one panel")
    for im, caption in zip(panels_raw, captions):
        if im.width <= 0 or im.height <= 0:
            raise SystemExit(f"panel {caption!r} has a zero-size crop after loading")
    aspects = [im.width / im.height for im in panels_raw]

    available_width = (
        settings.canvas_width - 2 * settings.margin - (n - 1) * settings.gutter
    )
    if available_width <= 0:
        raise SystemExit(
            "settings leave no horizontal room for any card "
            f"(canvas_width={settings.canvas_width}, margin={settings.margin}, "
            f"gutter={settings.gutter}, panel count={n})"
        )
    width_fit_height = available_width / sum(aspects)
    card_height = min(width_fit_height, settings.max_card_height(caption_font))
    card_height = round(card_height)

    widths = [round(a * card_height) for a in aspects]
    row_width = sum(widths) + (n - 1) * settings.gutter
    start_x = (settings.canvas_width - row_width) // 2

    # Vertical center line for the row. `max_card_height()` sizes the whole
    # row+caption block to exactly span the margin-to-margin band when a
    # wall is height-bound (card_height == max_card_height): centering and
    # anchoring the block to the bottom margin then agree, since there is no
    # slack to place either way. When a wall is instead width-bound (more/
    # wider panels than the height budget needs, e.g. a 4-panel row), the
    # block is shorter than that band and *would* leave slack on both the
    # top and the bottom if simply centered in the full canvas -- that is
    # the "dead gradient band at the bottom" defect: a wall with room to
    # spare still reads as leaving the bottom margin unused. Anchoring the
    # caption's bottom edge to the bottom safe margin instead means any
    # unavoidable slack (from a row that physically cannot grow further
    # without violating the margins/gutters/panel count) collects above the
    # row rather than below the captions.
    ascent, descent = caption_font.getmetrics()
    caption_height = ascent + descent
    reserved_bottom = settings.caption_gap + caption_height
    center_y = (
        settings.canvas_height
        - settings.margin
        - reserved_bottom
        - settings.stagger_amplitude
        - card_height // 2
    )

    laid_out = []
    x = start_x
    for i, (im, caption, width) in enumerate(zip(panels_raw, captions, widths)):
        sign = -1 if i % 2 == 0 else 1
        stagger = sign * settings.stagger_amplitude
        y = center_y + stagger - card_height // 2
        resized = im.resize((width, card_height), Image.LANCZOS)
        laid_out.append(LaidOutPanel(resized, caption, x, y, width, card_height))
        x += width + settings.gutter

    return laid_out


def render_wall(wall: dict, settings: Settings, caption_font: ImageFont.FreeTypeFont) -> Image.Image:
    panel_list = wall.get("panel")
    if not panel_list:
        raise SystemExit(
            f"wall {wall.get('output', '<unnamed>')!r} has no [[wall.panel]] "
            "entries; every [[wall]] table in the manifest needs at least one"
        )
    panels_raw = [
        load_panel_image(p, settings.screenshots_dir) for p in panel_list
    ]
    captions = [p["caption"] for p in panel_list]
    laid_out = layout_wall(panels_raw, captions, settings, caption_font)

    canvas = make_vertical_gradient(
        settings.canvas_width, settings.canvas_height, settings.bg_top, settings.bg_bottom
    ).convert("RGBA")

    # Pass 1: soft drop shadows, all blurred together on one transparent layer.
    shadow_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow_alpha = round(255 * settings.shadow_opacity)
    for p in laid_out:
        shadow_shape = Image.new("RGBA", (p.width, p.height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(shadow_shape)
        r = min(settings.corner_radius, p.width // 2, p.height // 2)
        draw.rounded_rectangle(
            [0, 0, p.width - 1, p.height - 1], radius=r, fill=(0, 0, 0, shadow_alpha)
        )
        shadow_layer.alpha_composite(
            shadow_shape, (p.x, p.y + settings.shadow_offset_y)
        )
    shadow_layer = shadow_layer.filter(ImageFilter.GaussianBlur(settings.shadow_blur))
    canvas.alpha_composite(shadow_layer)

    # Pass 2: the panels themselves, masked to a rounded rect.
    for p in laid_out:
        mask = rounded_mask((p.width, p.height), settings.corner_radius)
        canvas.paste(p.image, (p.x, p.y), mask)

    # Pass 3: captions.
    draw = ImageDraw.Draw(canvas)
    for p in laid_out:
        bbox = draw.textbbox((0, 0), p.caption, font=caption_font)
        text_width = bbox[2] - bbox[0]
        text_x = p.x + p.width // 2 - text_width // 2 - bbox[0]
        text_y = p.y + p.height + settings.caption_gap
        draw.text(
            (text_x, text_y), p.caption, font=caption_font, fill=settings.caption_color
        )

    return canvas.convert("RGB")


def build_walls(settings: Settings, manifest: dict, only: str | None, out_dir: Path) -> list[Path]:
    caption_font = load_font(settings.caption_font_size)
    written = []
    for wall in manifest["wall"]:
        if only is not None and wall["output"] != only:
            continue
        image = render_wall(wall, settings, caption_font)
        out_path = out_dir / wall["output"]
        # Explicit encoder options rather than Pillow's defaults, so output
        # stays byte-identical even if a future Pillow version changes its
        # PNG default compression level.
        image.save(out_path, format="PNG", compress_level=6, optimize=False)
        written.append(out_path)
        print(f"wrote {out_path}  {image.width}x{image.height}  {out_path.stat().st_size} bytes")
    if only is not None and not written:
        raise SystemExit(f"no wall with output == {only!r} in {MANIFEST_PATH}")
    return written


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--wall",
        metavar="FILENAME",
        help="only rebuild the wall whose manifest `output` matches this (e.g. hero.png)",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=MANIFEST_PATH,
        help="path to the manifest TOML (default: %(default)s)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="render to a temp directory and diff against docs/images/ instead of writing there",
    )
    args = parser.parse_args()

    repo_root = find_repo_root(SCRIPT_DIR)
    with args.manifest.open("rb") as f:
        manifest = tomllib.load(f)
    settings = Settings.from_toml(manifest, repo_root)

    if args.check:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            written = build_walls(settings, manifest, args.wall, tmp_path)
            mismatches = []
            for path in written:
                committed = settings.output_dir / path.name
                if not committed.exists() or not filecmp.cmp(path, committed, shallow=False):
                    mismatches.append(path.name)
            if mismatches:
                print(f"OUT OF DATE: {', '.join(mismatches)}", file=sys.stderr)
                return 1
            print("up to date")
            return 0

    settings.output_dir.mkdir(parents=True, exist_ok=True)
    build_walls(settings, manifest, args.wall, settings.output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
