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

Determinism: given the same manifest, the same screenshots, and the same
Pillow version, re-running this script byte-for-byte reproduces its output
(no timestamps, no randomness, no multithreading). The caption font
(Tools/scripts/fonts/Inter-VariableFont.ttf, see load_font() below) is
vendored in the repository rather than resolved from whatever happens to be
installed on the machine running this, so it no longer varies output across
machines or after an OS update -- the remaining cross-machine variable is
your installed Pillow (and its bundled FreeType) version, since a different
FreeType build can rasterize the same font file's hinting slightly
differently. If a change to Pillow changes its encoder or rasterizer output,
regenerate every wall together rather than mixing PNGs written by different
Pillow versions.
"""

from __future__ import annotations

import argparse
import filecmp
import sys
import tempfile
import tomllib
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont, UnidentifiedImageError

SCRIPT_DIR = Path(__file__).resolve().parent
MANIFEST_PATH = SCRIPT_DIR / "wall_manifest.toml"

# A repository-owned font, not a system one: determinism is the entire
# reason this tool exists ("same inputs, same output" is what makes
# regenerating a wall a re-run instead of a hand-rebuild in an external
# editor), and a font resolved from wherever the OS happens to have it
# installed breaks that guarantee -- a missing/updated system font would
# silently change every caption's glyph rendering, and therefore the output
# bytes, for a reason nobody could see from the manifest or the diff.
#
# Inter (SIL Open Font License 1.1, license file alongside it in fonts/) is
# a bold humanist/geometric UI sans designed for on-screen legibility at
# small sizes -- the same brief as the captions here -- and is the closest
# visual match to the previously hand-set Segoe UI Bold captions. It ships
# as a single variable font; `load_font()` below selects its "Bold" named
# instance rather than needing a separate static-weight file.
CAPTION_FONT_PATH = SCRIPT_DIR / "fonts" / "Inter-VariableFont.ttf"


def find_repo_root(start: Path) -> Path:
    """Walk upward from `start` to find the repository root (marked by .git)."""
    for candidate in [start, *start.parents]:
        if (candidate / ".git").exists():
            return candidate
    raise SystemExit(
        f"could not find repo root (no .git) above {start}; "
        "run this script from within the NagramX checkout"
    )


def hex_to_rgb(value: str, *, field: str) -> tuple[int, int, int]:
    raw = value.lstrip("#")
    if len(raw) != 6:
        raise SystemExit(
            f"settings.{field} must be a 6-digit hex color like '#121C31', "
            f"got {value!r}"
        )
    try:
        return tuple(int(raw[i : i + 2], 16) for i in (0, 2, 4))  # noqa: E203
    except ValueError:
        raise SystemExit(
            f"settings.{field} must be a 6-digit hex color like '#121C31', "
            f"got {value!r}"
        ) from None


def load_font(size: int) -> ImageFont.FreeTypeFont:
    if not CAPTION_FONT_PATH.exists():
        raise SystemExit(
            f"caption font not found at {CAPTION_FONT_PATH}; this tool "
            "depends on a repository-owned font for deterministic output "
            "and deliberately does not fall back to a system font -- "
            "restore Tools/scripts/fonts/ instead of installing one locally"
        )
    font = ImageFont.truetype(str(CAPTION_FONT_PATH), size)
    try:
        font.set_variation_by_name("Bold")
    except OSError as exc:
        raise SystemExit(
            f"{CAPTION_FONT_PATH} does not expose a 'Bold' named variation "
            f"instance ({exc}); this file may have been replaced with a "
            "different build of the font"
        ) from None
    return font


# --- Path confinement -------------------------------------------------
#
# This is a personal, hand-run tool with no adversarial input -- nobody is
# attacking wall_manifest.toml. The real risk is dazewell mistyping a path
# months from now (a stray leading "/", a "..", a wall `output` that's
# accidentally an absolute path) and silently overwriting a file outside
# docs/images/, or a `--check` run -- which is supposed to be read-only --
# writing to disk anyway because an absolute `output` bypasses its temp
# directory (joining an absolute path onto a base with `/` in pathlib
# discards the base entirely; it does not raise). The checks below turn
# that into a clear, immediate failure instead of a silent one.


def _confined_settings_dir(
    repo_root: Path, raw: str, expected_relative: str, *, field: str
) -> Path:
    """Resolve settings.<field> and require it to be exactly the repo's
    canonical docs/screenshots or docs/images directory -- not merely
    somewhere under the repo, but that specific documented path."""
    resolved = (repo_root / raw).resolve()
    expected = (repo_root / expected_relative).resolve()
    if resolved != expected:
        raise SystemExit(
            f"settings.{field} must resolve to {expected} (the documented "
            f"{expected_relative}/ directory), got {resolved} (from {raw!r})"
        )
    return resolved


def _confine_source(raw: str, screenshots_dir: Path, *, panel_context: str) -> Path:
    """Resolve a panel's `source` against screenshots_dir and require the
    result to stay inside it -- rejects an absolute path or a '..' that
    would otherwise let a panel read (and this tool only reads sources, so
    only read, not write) a file from outside docs/screenshots/."""
    candidate = Path(raw)
    if candidate.is_absolute():
        raise SystemExit(
            f"{panel_context}: source {raw!r} must be relative to "
            f"screenshots_dir ({screenshots_dir}), not an absolute path"
        )
    resolved = (screenshots_dir / candidate).resolve()
    if not resolved.is_relative_to(screenshots_dir):
        raise SystemExit(
            f"{panel_context}: source {raw!r} resolves to {resolved}, "
            f"outside screenshots_dir ({screenshots_dir}); remove any '..' "
            "components"
        )
    return resolved


def _require_plain_png_filename(raw: object, *, context: str) -> str:
    """Require a wall's `output` to be a bare '<name>.png' filename -- no
    directory separators, no drive/UNC anchor, no '..' -- so it can be
    safely joined onto either the real output_dir or --check's temp
    directory without a chance of writing outside it."""
    if not isinstance(raw, str) or not raw:
        raise SystemExit(f"{context}: output must be a non-empty string, got {raw!r}")
    path = Path(raw)
    if len(path.parts) != 1 or path.is_absolute():
        raise SystemExit(
            f"{context}: output must be a plain filename with no directory "
            f"separators, drive, or '..', got {raw!r}"
        )
    if not raw.endswith(".png"):
        raise SystemExit(f"{context}: output must end in .png, got {raw!r}")
    return raw


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
            bg_top=hex_to_rgb(s["bg_top"], field="bg_top"),
            bg_bottom=hex_to_rgb(s["bg_bottom"], field="bg_bottom"),
            caption_color=hex_to_rgb(s["caption_color"], field="caption_color"),
            caption_font_size=s["caption_font_size"],
            caption_gap=s["caption_gap"],
            stagger_amplitude=s["stagger_amplitude"],
            screenshots_dir=_confined_settings_dir(
                repo_root, s["screenshots_dir"], "docs/screenshots", field="screenshots_dir"
            ),
            output_dir=_confined_settings_dir(
                repo_root, s["output_dir"], "docs/images", field="output_dir"
            ),
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
    src_path = _confine_source(
        panel["source"], screenshots_dir, panel_context=f"panel {panel['source']!r}"
    )
    if not src_path.exists():
        raise SystemExit(
            f"source screenshot not found: {src_path}\n"
            "docs/screenshots/ is gitignored and populated by hand -- copy "
            "the raw captures there before running this tool."
        )
    try:
        with Image.open(src_path) as src_im:
            im = src_im.convert("RGB")
    except UnidentifiedImageError as exc:
        raise SystemExit(
            f"could not decode source screenshot {src_path} for panel "
            f"{panel['source']!r}: {exc}"
        ) from None

    redact_rects = panel.get("redact", [])
    if redact_rects:
        draw = ImageDraw.Draw(im)
        for rect in redact_rects:
            r_left, r_top, r_right, r_bottom = _validate_rect(
                rect, im.width, im.height, panel["source"], "redact"
            )
            # ImageDraw.rectangle()'s bottom-right is inclusive, but our rect
            # contract (matching crop's [left, top, right, bottom]) treats
            # right/bottom as exclusive -- shift by one so the drawn box
            # covers exactly the same pixels crop/blur would select.
            draw.rectangle(
                (r_left, r_top, r_right - 1, r_bottom - 1), fill=(0, 0, 0)
            )

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
    # Floor, not round: rounding a fractional card_height up (and then each
    # panel width up) can push row_width past available_width, driving
    # start_x negative and clipping the row off the canvas edges. Flooring
    # only ever gives back a spare fractional pixel of margin/gutter slack,
    # never takes the row out of bounds.
    card_height = int(card_height)

    widths = [int(a * card_height) for a in aspects]
    row_width = sum(widths) + (n - 1) * settings.gutter
    start_x = (settings.canvas_width - row_width) // 2

    # Vertical center line for the row. card_height above is already
    # "bind-first" scaled: it's the largest a card can be without either
    # overrunning the available row width (width_fit_height) or the
    # available vertical budget (max_card_height) -- whichever of those two
    # constraints is tighter for this wall's panel count/aspect mix is what
    # actually caps it. That leaves nothing further to grow on the binding
    # axis. Any slack that's left over is on the *other*, non-binding axis,
    # and centering the row+caption block in the vertical margin-to-margin
    # band splits that slack evenly top and bottom -- the one placement
    # that reads as deliberate padding rather than a layout that slid off
    # one edge. It also falls out as one uniform rule for every wall: a
    # height-bound wall (e.g. privacy-profiles, two tall panels) saturates
    # the vertical budget so there's no slack to place either way, and a
    # width-bound wall (e.g. hero, four full-width panels) gets its
    # unavoidable vertical slack split evenly instead of pushed to one
    # edge -- without this code needing to know which case it's in.
    ascent, descent = caption_font.getmetrics()
    caption_height = ascent + descent
    reserved_bottom = settings.caption_gap + caption_height
    center_y = settings.margin + (
        settings.canvas_height - 2 * settings.margin - reserved_bottom
    ) // 2

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
    for i, wall in enumerate(manifest["wall"]):
        output_name = _require_plain_png_filename(
            wall.get("output"), context=f"wall #{i + 1}"
        )
        if only is not None and output_name != only:
            continue
        image = render_wall(wall, settings, caption_font)
        out_path = out_dir / output_name
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
