#!/usr/bin/env python3
"""compose_walls.py -- deterministic README image-wall compositor.

Regenerates the marketing "wall" PNGs under docs/images/, and the plain
cropped figures under docs/images/features/ used by FEATURES.md, from raw
device screenshots plus a declarative manifest (wall_manifest.toml, next to
this script). It exists so a wall or figure can be tweaked by editing data,
not by redoing pixels by hand in an external editor.

Usage (run from anywhere in the repo; paths in the manifest resolve against
the repo root):

    python Tools/scripts/compose_walls.py                  # build everything
    python Tools/scripts/compose_walls.py --wall hero.png   # build just one wall
    python Tools/scripts/compose_walls.py --figure timezones-1.png  # just one figure
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

Source screenshots live in docs/screenshots/, populated by hand and gitignored
-- with one exception: docs/screenshots/composed/ is tracked. A composed source
is one assembled out of other captures rather than photographed, so it cannot
be re-shot if it is lost, and committing it is the only thing that keeps its
wall regenerable. Either way this script only ever reads a source; it never
modifies or commits one. Raw device captures are
full-screen 1080x2354 JPGs; each panel's (or figure's) `crop` rectangle in
the manifest selects the sub-region to composite, in that screenshot's own
native pixel coordinates. A `[[figure]]` table is a single crop with no
caption, canvas, or shadow -- just the redact/blur/crop pipeline a wall
panel also uses, downscaled (never upscaled) so its longest side is at most
FIGURE_MAX_DIMENSION pixels before being written to docs/images/features/.

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

from PIL import Image, ImageDraw, ImageFilter, ImageFont

SCRIPT_DIR = Path(__file__).resolve().parent
MANIFEST_PATH = SCRIPT_DIR / "wall_manifest.toml"

# A plain cropped screenshot for FEATURES.md -- no navy canvas, no caption,
# no drop shadow, unlike a wall panel. Downscaled (never upscaled) so its
# longest side is at most this many pixels, keeping the committed PNG small
# while FEATURES.md still renders it at a fixed, legible `height`.
FIGURE_MAX_DIMENSION = 1000

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
    # Reject any drive/UNC anchor outright, not just is_absolute(): a
    # Windows drive-relative value like "C:foo.png" is not is_absolute(),
    # and if its drive letter happens to match screenshots_dir's, joining
    # it below silently treats it as plain-relative rather than raising --
    # an accident of how PureWindowsPath.__truediv__ handles same-drive
    # components, not a guarantee. A `source` is documented as relative to
    # screenshots_dir, so any anchor is rejected rather than relied upon to
    # resolve safely.
    if candidate.anchor:
        raise SystemExit(
            f"{panel_context}: source {raw!r} must be relative to "
            f"screenshots_dir ({screenshots_dir}), not an absolute or "
            "drive/UNC-anchored path"
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
    # Check path.anchor explicitly rather than relying only on is_absolute():
    # a Windows drive-relative value like "C:foo.png" is *not* is_absolute()
    # (it's relative to the current directory on that drive), but it still
    # carries a non-empty anchor, and joining it onto out_dir would ignore
    # out_dir entirely rather than raising.
    if len(path.parts) != 1 or path.anchor:
        raise SystemExit(
            f"{context}: output must be a plain filename with no directory "
            f"separators, drive, or '..', got {raw!r}"
        )
    if not raw.endswith(".png"):
        raise SystemExit(f"{context}: output must end in .png, got {raw!r}")
    return raw


# --- Manifest validation ------------------------------------------------
#
# This tool runs by hand, rarely, months apart -- a raw KeyError/TypeError
# or a Pillow traceback several calls deep from the actual mistake turns a
# routine manifest edit into an archaeology session. Everything below runs
# as one pass before any rendering, so a malformed manifest fails fast with
# a message naming the offending wall/panel/field rather than partway
# through building the first wall.

_REQUIRED_SETTINGS: dict[str, type | tuple[type, ...]] = {
    "canvas_width": int,
    "canvas_height": int,
    "margin": int,
    "gutter": int,
    "corner_radius": int,
    "shadow_blur": int,
    "shadow_offset_y": int,
    "shadow_opacity": (int, float),
    "bg_top": str,
    "bg_bottom": str,
    "caption_color": str,
    "caption_font_size": int,
    "caption_gap": int,
    "stagger_amplitude": int,
    "screenshots_dir": str,
    "output_dir": str,
    "figures_dir": str,
}


def _check_rect_shape(rect: object, *, context: str) -> None:
    if not isinstance(rect, (list, tuple)) or len(rect) != 4:
        raise SystemExit(
            f"{context}: must be a 4-element [left, top, right, bottom] "
            f"list, got {rect!r}"
        )
    if not all(isinstance(v, int) and not isinstance(v, bool) for v in rect):
        raise SystemExit(f"{context}: all four values must be integers, got {rect!r}")
    left, top, right, bottom = rect
    if not (left < right and top < bottom):
        raise SystemExit(
            f"{context}: rect {rect!r} must have left < right and top < bottom"
        )


def validate_manifest_shape(manifest: dict, manifest_path: Path) -> None:
    """Check required keys, types, rect shapes, and duplicate wall outputs.
    Does not touch the filesystem or need Settings -- see
    validate_manifest_sources() for the confinement pass that does."""
    settings = manifest.get("settings")
    if not isinstance(settings, dict):
        raise SystemExit(f"{manifest_path}: missing or malformed [settings] table")
    for key, expected_type in _REQUIRED_SETTINGS.items():
        if key not in settings:
            raise SystemExit(f"{manifest_path}: [settings] is missing required key {key!r}")
        value = settings[key]
        if not isinstance(value, expected_type) or isinstance(value, bool):
            raise SystemExit(
                f"{manifest_path}: settings.{key} must be a "
                f"{expected_type}, got {value!r}"
            )

    walls = manifest.get("wall")
    if not isinstance(walls, list) or not walls:
        raise SystemExit(f"{manifest_path}: needs at least one [[wall]] table")

    seen_outputs: set[str] = set()
    for i, wall in enumerate(walls, start=1):
        wall_context = f"wall #{i}"
        if not isinstance(wall, dict):
            raise SystemExit(f"{wall_context}: must be a table, got {wall!r}")
        output = _require_plain_png_filename(wall.get("output"), context=wall_context)
        # Compare case-insensitively: Windows and other case-insensitive
        # filesystems treat "Hero.png" and "hero.png" as the same file, so
        # two walls with differently-cased names would otherwise pass this
        # check and then silently overwrite each other's output.
        output_key = output.casefold()
        if output_key in seen_outputs:
            raise SystemExit(
                f"duplicate wall output {output!r} -- every [[wall]] needs a "
                "unique output filename (case-insensitively, since some "
                "filesystems treat differently-cased names as the same file)"
            )
        seen_outputs.add(output_key)
        wall_context = f"wall {output!r}"

        panels = wall.get("panel")
        if not isinstance(panels, list) or not panels:
            raise SystemExit(
                f"{wall_context}: needs at least one [[wall.panel]] entry"
            )
        for j, panel in enumerate(panels, start=1):
            panel_context = f"{wall_context} panel #{j}"
            if not isinstance(panel, dict):
                raise SystemExit(f"{panel_context}: must be a table, got {panel!r}")
            source = panel.get("source")
            if not isinstance(source, str) or not source:
                raise SystemExit(
                    f"{panel_context}: source must be a non-empty string, "
                    f"got {source!r}"
                )
            caption = panel.get("caption")
            if not isinstance(caption, str) or not caption:
                raise SystemExit(
                    f"{panel_context}: caption must be a non-empty string, "
                    f"got {caption!r}"
                )
            _check_rect_shape(panel.get("crop"), context=f"{panel_context} crop")
            for kind in ("redact", "blur"):
                rects = panel.get(kind, [])
                if not isinstance(rects, list):
                    raise SystemExit(
                        f"{panel_context}: {kind} must be a list of rects, "
                        f"got {rects!r}"
                    )
                for k, rect in enumerate(rects, start=1):
                    _check_rect_shape(rect, context=f"{panel_context} {kind} #{k}")

    figures = manifest.get("figure", [])
    if not isinstance(figures, list):
        raise SystemExit(f"{manifest_path}: [[figure]] must be a list of tables")
    seen_figure_outputs: set[str] = set()
    for i, figure in enumerate(figures, start=1):
        figure_context = f"figure #{i}"
        if not isinstance(figure, dict):
            raise SystemExit(f"{figure_context}: must be a table, got {figure!r}")
        output = _require_plain_png_filename(
            figure.get("output"), context=figure_context
        )
        output_key = output.casefold()
        if output_key in seen_figure_outputs:
            raise SystemExit(
                f"duplicate figure output {output!r} -- every [[figure]] "
                "needs a unique output filename (case-insensitively)"
            )
        seen_figure_outputs.add(output_key)
        figure_context = f"figure {output!r}"
        source = figure.get("source")
        if not isinstance(source, str) or not source:
            raise SystemExit(
                f"{figure_context}: source must be a non-empty string, "
                f"got {source!r}"
            )
        _check_rect_shape(figure.get("crop"), context=f"{figure_context} crop")
        for kind in ("redact", "blur"):
            rects = figure.get(kind, [])
            if not isinstance(rects, list):
                raise SystemExit(
                    f"{figure_context}: {kind} must be a list of rects, "
                    f"got {rects!r}"
                )
            for k, rect in enumerate(rects, start=1):
                _check_rect_shape(rect, context=f"{figure_context} {kind} #{k}")


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
    figures_dir: Path

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
            figures_dir=_confined_settings_dir(
                repo_root, s["figures_dir"], "docs/images/features", field="figures_dir"
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


def validate_manifest_sources(manifest: dict, settings: Settings) -> None:
    """Confine every panel's and figure's `source` inside
    settings.screenshots_dir. Runs once, up front, so a stray absolute path
    or '..' fails with a clear wall/panel- or figure-named message instead
    of surfacing later as a confusing "file not found" for a path nobody
    wrote by hand."""
    for wall in manifest["wall"]:
        for j, panel in enumerate(wall["panel"], start=1):
            _confine_source(
                panel["source"],
                settings.screenshots_dir,
                panel_context=f"wall {wall['output']!r} panel #{j}",
            )
    for i, figure in enumerate(manifest.get("figure", []), start=1):
        _confine_source(
            figure["source"],
            settings.screenshots_dir,
            panel_context=f"figure {figure.get('output', i)!r}",
        )


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
    # NEAREST, not left to Pillow's default: this is a horizontal-only
    # stretch of a column that is already a single solid color per row, so
    # every resampling filter would produce the same pixels anyway -- but
    # relying on an unspecified default still leaves the output dependent
    # on whatever Pillow's default happens to be, which this script
    # otherwise pins explicitly (see the PNG encoder options in
    # build_walls()).
    return gradient.resize((width, height), Image.NEAREST)


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
        if src_path.parent.name == "composed":
            raise SystemExit(
                f"source screenshot not found: {src_path}\n"
                "docs/screenshots/composed/ is tracked, so this file should "
                "have arrived with the checkout -- restore it from git. It "
                "was assembled from other captures, not photographed, so "
                "re-shooting it is not an option."
            )
        raise SystemExit(
            f"source screenshot not found: {src_path}\n"
            "docs/screenshots/ is gitignored and populated by hand -- copy "
            "the raw captures there before running this tool."
        )
    try:
        with Image.open(src_path) as src_im:
            im = src_im.convert("RGB")
    except OSError as exc:
        # Covers Pillow's UnidentifiedImageError (an unrecognised format) as
        # well as a recognised-but-truncated/corrupt file -- both subclass
        # OSError, and both are realistic after a bad file copy into
        # docs/screenshots/, so both get the same named, non-traceback error.
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
    wall_name: str,
) -> list[LaidOutPanel]:
    n = len(panels_raw)
    if n == 0:
        raise SystemExit("a wall must have at least one panel")
    for im, caption in zip(panels_raw, captions):
        if im.width <= 0 or im.height <= 0:
            raise SystemExit(
                f"wall {wall_name!r} panel {caption!r} has a zero-size crop "
                "after loading"
            )
    aspects = [im.width / im.height for im in panels_raw]

    available_width = (
        settings.canvas_width - 2 * settings.margin - (n - 1) * settings.gutter
    )
    if available_width <= 0:
        raise SystemExit(
            f"wall {wall_name!r}: settings leave no horizontal room for any "
            f"card (canvas_width={settings.canvas_width}, "
            f"margin={settings.margin}, gutter={settings.gutter}, "
            f"panel count={n})"
        )
    width_fit_height = available_width / sum(aspects)
    card_height = min(width_fit_height, settings.max_card_height(caption_font))
    # Floor, not round: rounding a fractional card_height up (and then each
    # panel width up) can push row_width past available_width, driving
    # start_x negative and clipping the row off the canvas edges. Flooring
    # only ever gives back a spare fractional pixel of margin/gutter slack,
    # never takes the row out of bounds.
    card_height = int(card_height)
    if card_height <= 0:
        raise SystemExit(
            f"wall {wall_name!r}: computed card height is {card_height}px "
            f"for {n} panel(s) -- widen the canvas, shrink the margins/"
            "gutter, or use fewer/wider panels"
        )

    widths = [int(a * card_height) for a in aspects]
    for caption, width in zip(captions, widths):
        if width <= 0:
            raise SystemExit(
                f"wall {wall_name!r} panel {caption!r}: computed width is "
                f"{width}px at card_height={card_height}px -- its crop's "
                "aspect ratio is too extreme for this canvas/panel count"
            )
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
    wall_name = wall.get("output", "<unnamed>")
    laid_out = layout_wall(panels_raw, captions, settings, caption_font, wall_name)

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


def render_figure(figure: dict, settings: Settings) -> Image.Image:
    """A figure is a plain cropped screenshot -- no canvas, no caption, no
    shadow -- reusing the same redact/blur/crop pipeline as a wall panel.
    `load_panel_image` only reads source/redact/blur/crop keys from the
    dict it's given, all of which a [[figure]] table also has, so the
    figure table is passed straight through rather than reshaped."""
    im = load_panel_image(figure, settings.screenshots_dir)
    longest = max(im.width, im.height)
    if longest > FIGURE_MAX_DIMENSION:
        scale = FIGURE_MAX_DIMENSION / longest
        # Clamp to 1px: an extreme aspect ratio (e.g. a 1px-wide crop) can
        # round the scaled short side down to 0, which Image.resize() rejects.
        new_size = (
            max(1, round(im.width * scale)),
            max(1, round(im.height * scale)),
        )
        im = im.resize(new_size, Image.LANCZOS)
    return im


def build_figures(settings: Settings, manifest: dict, only: str | None, out_dir: Path) -> list[Path]:
    written = []
    for i, figure in enumerate(manifest.get("figure", [])):
        output_name = _require_plain_png_filename(
            figure.get("output"), context=f"figure #{i + 1}"
        )
        if only is not None and output_name != only:
            continue
        image = render_figure(figure, settings)
        out_path = out_dir / output_name
        # Same fixed encoder options as build_walls(), for the same reason:
        # byte-identical output regardless of Pillow's current PNG defaults.
        image.save(out_path, format="PNG", compress_level=6, optimize=False)
        written.append(out_path)
        print(f"wrote {out_path}  {image.width}x{image.height}  {out_path.stat().st_size} bytes")
    if only is not None and not written:
        raise SystemExit(f"no figure with output == {only!r} in {MANIFEST_PATH}")
    return written


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--wall",
        metavar="FILENAME",
        help="only rebuild the wall whose manifest `output` matches this (e.g. hero.png)",
    )
    parser.add_argument(
        "--figure",
        metavar="FILENAME",
        help="only rebuild the figure whose manifest `output` matches this (e.g. timezones-1.png)",
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
        help="render to a temp directory and diff walls against docs/images/ and figures "
        "against docs/images/features/ instead of writing there",
    )
    args = parser.parse_args()
    if args.wall and args.figure:
        raise SystemExit("--wall and --figure are mutually exclusive")

    repo_root = find_repo_root(SCRIPT_DIR)
    try:
        with args.manifest.open("rb") as f:
            manifest = tomllib.load(f)
    except OSError as exc:
        # Covers a missing file as well as less common but realistic
        # mistakes -- a directory given instead of a file, a permission
        # error -- not just FileNotFoundError.
        raise SystemExit(f"could not open manifest {args.manifest}: {exc}") from None
    except UnicodeDecodeError as exc:
        raise SystemExit(
            f"manifest {args.manifest} is not valid UTF-8: {exc}"
        ) from None
    except tomllib.TOMLDecodeError as exc:
        raise SystemExit(f"could not parse manifest {args.manifest}: {exc}") from None

    validate_manifest_shape(manifest, args.manifest)
    settings = Settings.from_toml(manifest, repo_root)
    validate_manifest_sources(manifest, settings)

    if args.check:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            mismatches = []
            if not args.figure:
                written = build_walls(settings, manifest, args.wall, tmp_path)
                for path in written:
                    committed = settings.output_dir / path.name
                    if not committed.exists() or not filecmp.cmp(path, committed, shallow=False):
                        mismatches.append(path.name)
            if not args.wall:
                fig_tmp = tmp_path / "features"
                fig_tmp.mkdir()
                written = build_figures(settings, manifest, args.figure, fig_tmp)
                for path in written:
                    committed = settings.figures_dir / path.name
                    if not committed.exists() or not filecmp.cmp(path, committed, shallow=False):
                        mismatches.append(path.name)
            if mismatches:
                print(f"OUT OF DATE: {', '.join(mismatches)}", file=sys.stderr)
                return 1
            print("up to date")
            return 0

    if not args.figure:
        settings.output_dir.mkdir(parents=True, exist_ok=True)
        build_walls(settings, manifest, args.wall, settings.output_dir)
    if not args.wall:
        settings.figures_dir.mkdir(parents=True, exist_ok=True)
        build_figures(settings, manifest, args.figure, settings.figures_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
