#!/usr/bin/env python3
"""Attribution guard: fails a commit range that carries AI/assistant attribution.

The repo's one hard rule is that git history and app source never name an AI
assistant as an author or contributor. Until now that rule was enforced only
by humans reading diffs. This script is the automated backstop: it scans a
git commit range (base..head, i.e. the commits a pull request actually adds)
across three surfaces:

  1. commit author/committer identity (name + email)
  2. the full commit message (subject, body, and trailers)
  3. added lines (``+`` lines) in ``*.java``/``*.kt``/``*.xml`` diffs

and fails the range if any of them names a known AI vendor, a vendor-owned
Co-authored-by trailer, or an authorship-attribution phrase.

Design note on false positives (read before touching the patterns below):
this fork ships a real LLM integration feature (``tw.nekomimi.nekogram.llm``)
that legitimately talks about OpenAI, Gemini, DeepSeek, etc. as *product*
functionality, and plenty of real commit messages describe that feature using
those exact vendor words ("update Gemini model", "add OpenAI-compatible API
support"). A bare vendor-keyword scan of commit messages or source code would
misfire constantly on this repo. So:

  - Identity fields (author/committer name+email) ARE scanned with a bare
    vendor-keyword match. Nobody's real git identity is coincidentally named
    "Copilot" or sits on "anthropic.com" -- this is a low-risk, high-signal
    surface, and it is also where vendor-owned email domains show up (the
    domain string itself contains the vendor keyword, e.g. "anthropic.com",
    "openai.com", "minimax.io", so no separate domain list is needed).
  - Co-authored-by trailers are parsed structurally and only their *subject*
    (the name/email after the colon) is keyword-matched -- never the trailer
    line's mere presence, because human Co-authored-by trailers are normal
    and expected here (they ride in with upstream merges).
  - Commit message bodies and added source lines are NOT bare-keyword
    scanned. Instead they are checked for authorship-attribution PHRASES
    (e.g. "written by Claude", "generated with Copilot", "ai-generated", a
    bare "co-authored-by" mention inside source code) -- patterns that a
    legitimate sentence about using an AI vendor's API would not produce.

A note on a rule this script deliberately does NOT implement: the original
brief for this guard asked to flag any "[bot]" account as author/committer.
Verified against this repo's own history: ``github-actions[bot]`` already
appears as a committer for ordinary CI-driven commits. Flagging any "[bot]"
account would immediately misfire on legitimate infrastructure automation
that has nothing to do with AI attribution. This script only flags a "[bot]"
identity when its name/email ALSO contains an AI vendor keyword (which is
exactly the leak this guard exists to catch, e.g. "copilot-swe-agent[bot]").
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass

# ---------------------------------------------------------------------------
# Vendor / attribution patterns
# ---------------------------------------------------------------------------

# Vendor names/terms. "gpt-" is handled separately because it's a prefix
# (gpt-4, gpt-5.6, ...), not a standalone word.
VENDOR_TERMS = [
    "copilot",
    "claude",
    "anthropic",
    "openai",
    "chatgpt",
    "gemini",
    "bard",
    "minimax",
    "codex",
    "mistral",
    "deepseek",
    "llama",
]

_VENDOR_ALT = "|".join(re.escape(t) for t in VENDOR_TERMS)

# One vendor "token": a bare vendor word (word-bounded, so "villamaria" does
# not match "llama", but "MiniMax-M3", "gpt-5.6 Luna", "copilot-swe-agent[bot]"
# all do, since the surrounding punctuation still creates a \b boundary).
VENDOR_TOKEN = rf"(?:\b(?:{_VENDOR_ALT})\b|\bgpt-)"

# Bare vendor-keyword match, used only for identity fields and for a
# Co-authored-by trailer's subject -- both short, structured, high-confidence
# surfaces where a vendor mention is essentially never innocent.
VENDOR_REGEX = re.compile(VENDOR_TOKEN, re.IGNORECASE)

# "ai-generated" / "ai generated" -- unambiguous, distinct from the very
# common legitimate "AI-powered" / "AI Translator" product language in this
# repo's own commit history.
AI_GENERATED_REGEX = re.compile(r"\bai[-\s]generated\b", re.IGNORECASE)

# Verbs that, combined with "by" + a vendor name, assert the vendor performed
# the action ("implemented by Copilot", "written by Claude"). Deliberately
# excludes verbs like "powered"/"built" that this repo uses to legitimately
# describe a feature built on top of a vendor's API ("AI Translator ...
# powered by Gemini"), and deliberately requires "by" (agency) rather than
# "with"/"using"/"via" (which read as tool/technology descriptions here,
# e.g. "implemented using the OpenAI streaming API").
AUTHOR_VERBS = [
    "generated",
    "written",
    "wrote",
    "authored",
    "co-authored",
    "drafted",
    "coded",
    "suggested",
    "assisted",
    "prompted",
    "reviewed",
    "created",
    "implemented",
]
_VERB_ALT = "|".join(AUTHOR_VERBS)

# "<verb> ... by ... <vendor>", with a small token gap on either side of "by"
# so it matches "reviewed by GitHub Copilot" as well as "reviewed by Copilot".
VERB_BY_VENDOR_REGEX = re.compile(
    rf"\b(?:{_VERB_ALT})\b(?:\s+\S+){{0,4}}?\s+\bby\b(?:\s+\S+){{0,3}}?\s*{VENDOR_TOKEN}",
    re.IGNORECASE,
)

# The canonical auto-inserted footer shape ("Generated with [Claude Code]",
# "Generated using Copilot"). Restricted to "generated" specifically (not the
# full verb list) because "generated with/using X" is a much stronger and
# more specific authorship claim than e.g. "implemented using X".
GENERATED_WITH_VENDOR_REGEX = re.compile(
    rf"\bgenerated\b(?:\s+\S+){{0,2}}?\s+\b(?:with|using)\b(?:\s+\S+){{0,3}}?\s*{VENDOR_TOKEN}",
    re.IGNORECASE,
)

# A bare "co-authored-by" mention in *source code* (not commit messages,
# where it's a normal, structurally-parsed trailer). There is no legitimate
# reason for this phrase to appear in a .java/.kt/.xml file.
CO_AUTHORED_BARE_REGEX = re.compile(r"co-?authored[-\s]?by", re.IGNORECASE)

# A Co-authored-by trailer line. MUST be applied with re.MULTILINE: commit
# bodies are multi-line strings and the trailer is essentially never on the
# first line, so an un-anchored-per-line "^Co-authored-by:" match against the
# whole message silently never fires -- exactly the bug that would make this
# guard pass everything while looking clean. `--self-test` below asserts this
# explicitly (it fails if re.MULTILINE is ever dropped from this pattern).
TRAILER_REGEX = re.compile(r"^\s*Co-authored-by:\s*(.+?)\s*$", re.IGNORECASE | re.MULTILINE)

SOURCE_EXTENSIONS = ("*.java", "*.kt", "*.xml")


@dataclass
class Violation:
    commit: str
    subject: str
    surface: str
    detail: str
    location: str | None = None

    def format(self) -> str:
        loc = f" ({self.location})" if self.location else ""
        short = self.commit[:12]
        return f"::error::attribution-guard: {short} [{self.surface}]{loc}: {self.detail} -- commit subject: {self.subject!r}"


def run_git(args: list[str]) -> subprocess.CompletedProcess:
    # Git commit data is UTF-8 regardless of the OS default locale. Windows'
    # default codepage (cp1252) chokes on the non-ASCII commit messages this
    # guard exists to scan (upstream history is full of them), so decode
    # explicitly instead of relying on subprocess's locale-guessed default.
    return subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def resolve_commit(ref: str) -> str | None:
    result = run_git(["rev-parse", "--verify", f"{ref}^{{commit}}"])
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def find_message_attribution(text: str) -> list[tuple[str, str]]:
    """Phrase-based attribution hits in a commit message body (or a single
    added source line). Returns (label, matched snippet) pairs."""
    hits = []
    for regex, label in (
        (VERB_BY_VENDOR_REGEX, "authorship phrase"),
        (GENERATED_WITH_VENDOR_REGEX, "generated-with footer"),
        (AI_GENERATED_REGEX, "ai-generated phrase"),
    ):
        m = regex.search(text)
        if m:
            hits.append((label, m.group(0).strip()))
    return hits


def find_source_attribution(line: str) -> list[tuple[str, str]]:
    hits = find_message_attribution(line)
    m = CO_AUTHORED_BARE_REGEX.search(line)
    if m:
        hits.append(("co-authored-by mention in source", m.group(0)))
    return hits


def check_identity(sha: str, subject: str, field_name: str, value: str) -> list[Violation]:
    m = VENDOR_REGEX.search(value)
    if not m:
        return []
    return [
        Violation(
            commit=sha,
            subject=subject,
            surface="identity",
            detail=f"{field_name} {value!r} names an AI vendor (matched {m.group(0)!r})",
        )
    ]


def check_message(sha: str, subject: str, body: str) -> list[Violation]:
    violations: list[Violation] = []

    for trailer_match in TRAILER_REGEX.finditer(body):
        trailer_subject = trailer_match.group(1)
        vendor_match = VENDOR_REGEX.search(trailer_subject)
        if vendor_match:
            violations.append(
                Violation(
                    commit=sha,
                    subject=subject,
                    surface="trailer",
                    detail=(
                        f"Co-authored-by subject {trailer_subject!r} names an AI vendor "
                        f"(matched {vendor_match.group(0)!r})"
                    ),
                )
            )

    for label, snippet in find_message_attribution(body):
        violations.append(
            Violation(
                commit=sha,
                subject=subject,
                surface="message",
                detail=f"{label}: {snippet!r}",
            )
        )

    return violations


def iter_commit_records(base_sha: str, head_sha: str):
    fs, rs = "\x1f", "\x1e"
    fmt = f"%H{fs}%P{fs}%an{fs}%ae{fs}%cn{fs}%ce{fs}%B{rs}"
    result = run_git(["log", f"--format={fmt}", f"{base_sha}..{head_sha}"])
    if result.returncode != 0:
        raise RuntimeError(f"git log failed: {result.stderr.strip()}")
    for record in result.stdout.split(rs):
        record = record.strip("\n")
        if not record:
            continue
        parts = record.split(fs, 6)
        if len(parts) < 7:
            continue
        sha, parents, an, ae, cn, ce, body = parts
        yield {
            "sha": sha,
            "parents": parents,
            "is_merge": len(parents.split()) > 1,
            "an": an,
            "ae": ae,
            "cn": cn,
            "ce": ce,
            "body": body,
            "subject": body.splitlines()[0] if body else "",
        }


def iter_added_lines(diff_text: str):
    """Yield (file, content) for each added (`+`) line in a unified diff,
    tracking the current file via `+++ <path>` headers. Split out from
    check_source_diff() so the header-vs-content edge case (a content line
    that itself starts with `++`) has a standalone, git-free regression test.
    """
    current_file: str | None = None
    for line in diff_text.splitlines():
        if line.startswith("+++ "):
            # Real unified-diff file headers are always "+++ <path>" with a
            # space. Without the space check, an added *content* line whose
            # text happens to start with "++" (rare, but legal source) would
            # render as "+++<content>" and get misread as a header, silently
            # dropping current_file and skipping that line (and anything
            # after it, until the next real header resets it).
            path = line[4:].strip()
            if path.startswith("b/"):
                path = path[2:]
            current_file = None if path in ("", "/dev/null") else path
            continue
        if not line.startswith("+"):
            continue
        if current_file is None:
            continue
        yield current_file, line[1:]


def check_source_diff(sha: str, subject: str, parents: str) -> list[Violation]:
    # A merge commit's diff is ambiguous against ALL parents (git show's
    # default "combined diff" for merges is unreliable to line-scan), but its
    # diff against the FIRST parent is exactly what the addition would look
    # like if a contributor resolved a conflict by hand and typed something
    # in -- that's real added content in the range, not noise, so merges are
    # scanned too rather than skipped outright.
    parent_list = parents.split()
    if parent_list:
        diff_args = ["diff", "--no-color", "--unified=0", parent_list[0], sha]
    else:
        # Root commit (no parent): everything in it is "added".
        diff_args = ["show", "--no-color", "--unified=0", "--format=", sha]
    result = run_git([*diff_args, "--", *SOURCE_EXTENSIONS])
    if result.returncode != 0:
        raise RuntimeError(f"git diff failed for {sha}: {result.stderr.strip()}")

    violations: list[Violation] = []
    for current_file, content in iter_added_lines(result.stdout):
        for label, snippet in find_source_attribution(content):
            violations.append(
                Violation(
                    commit=sha,
                    subject=subject,
                    surface="source",
                    detail=f"{label}: {snippet!r}",
                    location=current_file,
                )
            )
    return violations


def scan_range(base_sha: str, head_sha: str) -> list[Violation]:
    violations: list[Violation] = []
    for record in iter_commit_records(base_sha, head_sha):
        sha, subject = record["sha"], record["subject"]
        for field_name, value in (
            ("author name", record["an"]),
            ("author email", record["ae"]),
            ("committer name", record["cn"]),
            ("committer email", record["ce"]),
        ):
            violations.extend(check_identity(sha, subject, field_name, value))
        violations.extend(check_message(sha, subject, record["body"]))
        violations.extend(check_source_diff(sha, subject, record["parents"]))
    return violations


def run_self_test() -> int:
    """Regression tests for the pattern set, runnable standalone with
    `attribution_guard.py --self-test` (no git repo needed). Covers the
    multiline-anchoring trap explicitly, plus one representative case per
    surface for both a real finding and a known false-positive risk."""
    failures = []

    def check(label: str, condition: bool):
        if not condition:
            failures.append(label)

    # --- The multiline trap: a trailer buried past the first line of a
    # commit body must be found only because TRAILER_REGEX carries
    # re.MULTILINE. Prove it by asserting the identical pattern WITHOUT that
    # flag is blind -- if someone ever drops re.MULTILINE from TRAILER_REGEX,
    # this test starts failing instead of the guard silently going blind.
    buried_trailer_body = (
        "add per-account chat lock\n\n"
        "A paragraph of unrelated explanation about the change goes here,\n"
        "spanning a couple of lines before the trailer shows up.\n\n"
        "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>\n"
    )
    naive_no_multiline = re.compile(r"^\s*Co-authored-by:\s*(.+?)\s*$", re.IGNORECASE)
    check(
        "sanity: naive non-multiline pattern is blind to a buried trailer (proves the trap is real)",
        not naive_no_multiline.search(buried_trailer_body),
    )
    check(
        "TRAILER_REGEX (re.MULTILINE) finds the same buried trailer",
        bool(TRAILER_REGEX.search(buried_trailer_body)),
    )

    # --- Identity surface: real positive + a known false-positive risk that
    # must stay clean (generic infra bot, unrelated to AI attribution).
    check("identity: 'Claude' author name is flagged", bool(VENDOR_REGEX.search("Claude")))
    check("identity: vendor email domain is flagged", bool(VENDOR_REGEX.search("noreply@anthropic.com")))
    check("identity: 'copilot-swe-agent[bot]' is flagged", bool(VENDOR_REGEX.search("copilot-swe-agent[bot]")))
    check(
        "identity: generic infra bot 'github-actions[bot]' is NOT flagged (real dev history has this)",
        not VENDOR_REGEX.search("github-actions[bot]"),
    )
    check("identity: ordinary human name is NOT flagged", not VENDOR_REGEX.search("Jane Doe"))

    # --- Trailer surface, using the real fork-history fixtures a sibling
    # session supplied (NextAlone/Nagram commits 05bea8292d, a6bc5199c6,
    # 107de24f02): vendor-named Co-authored-by subjects must be flagged;
    # a human co-author must not be.
    check(
        "trailer: MiniMax-M3 co-author subject is flagged",
        bool(VENDOR_REGEX.search("MiniMax-M3 <model@minimax.io>")),
    )
    check(
        "trailer: GPT-5.6 Luna co-author subject is flagged",
        bool(VENDOR_REGEX.search("GPT-5.6 Luna <codex@openai.com>")),
    )
    check(
        "trailer: human co-author subject is NOT flagged",
        not VENDOR_REGEX.search("Jane Doe <jane@example.com>"),
    )

    # --- Message surface: authorship phrase caught; legitimate product
    # sentences about the fork's own LLM feature must stay clean.
    check(
        "message: 'written by Claude' phrase is flagged",
        bool(find_message_attribution("this function was written by Claude during a pairing session")),
    )
    check(
        "message: 'generated with Copilot' footer is flagged",
        bool(find_message_attribution("Generated with Copilot")),
    )
    check(
        "message: legitimate 'update Gemini model' commit text is NOT flagged",
        not find_message_attribution("update Gemini model references and add OpenAI-compatible API support"),
    )
    check(
        "message: 'AI-powered speech-to-text' product language is NOT flagged",
        not find_message_attribution("feat: AI-powered speech-to-text conversion"),
    )
    check(
        "message: a merge subject naming a copilot/* branch is NOT bare-keyword flagged",
        not find_message_attribution("Merge pull request #67 from dazewell/copilot/fix-cancel-button-background"),
    )

    # --- Source surface: ai-generated comment and bare co-authored-by
    # mention caught; legitimate LLM-client code untouched.
    check(
        "source: '// ai-generated' comment is flagged",
        bool(find_source_attribution("    // ai-generated: quick helper")),
    )
    check(
        "source: bare 'Co-Authored-By:' left in a source comment is flagged",
        bool(find_source_attribution("    // Co-Authored-By: Copilot")),
    )
    check(
        "source: legitimate OpenAI-compatible client code is NOT flagged",
        not find_source_attribution("public class OpenAICompatClient extends VertexGeminiClient {"),
    )

    # --- Diff-parsing edge case: an added content line that itself starts
    # with "++" (e.g. a line beginning with a C-style pre-increment pair)
    # must NOT be mistaken for a "+++ <path>" file header -- that would drop
    # current_file and silently skip scanning it (and everything after it,
    # until the next real header). This is a standalone parser test, no git
    # repo needed: a synthetic unified diff for one file, where the second
    # added line's content happens to start with "++".
    synthetic_diff = (
        "diff --git a/Foo.java b/Foo.java\n"
        "--- a/Foo.java\n"
        "+++ b/Foo.java\n"
        "@@ -1,0 +1,2 @@\n"
        "+int x = 1;\n"
        "+++x; // written by Claude\n"
    )
    parsed = list(iter_added_lines(synthetic_diff))
    check(
        "diff parser: both added lines are attributed to Foo.java, not misread as a second header",
        parsed == [("Foo.java", "int x = 1;"), ("Foo.java", "++x; // written by Claude")],
    )

    if failures:
        for f in failures:
            print(f"::error::attribution-guard self-test FAILED: {f}", file=sys.stderr)
        print(f"attribution-guard --self-test: {len(failures)} failure(s).", file=sys.stderr)
        return 1
    print("attribution-guard --self-test: all checks passed.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="Base ref/sha (exclusive) of the range to scan.")
    parser.add_argument("--head", help="Head ref/sha (inclusive) of the range to scan.")
    parser.add_argument(
        "--report-only",
        action="store_true",
        help="Print findings but always exit 0 for content findings (range-resolution failures still fail).",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run the built-in regression tests (no git repo or --base/--head needed) and exit.",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test()

    if not args.base or not args.head:
        parser.error("--base and --head are required unless --self-test is given")

    base_sha = resolve_commit(args.base)
    head_sha = resolve_commit(args.head)
    if not base_sha or not head_sha:
        print(
            f"::error::attribution-guard: cannot resolve base ({args.base!r}) or head ({args.head!r}) "
            "as commits -- failing closed (check fetch-depth / that both refs exist).",
            file=sys.stderr,
        )
        return 1

    merge_base = run_git(["merge-base", base_sha, head_sha])
    if merge_base.returncode != 0 or not merge_base.stdout.strip():
        print(
            "::error::attribution-guard: cannot compute a merge-base for the given range "
            "(shallow clone, unrelated histories, or missing base ref) -- failing closed.",
            file=sys.stderr,
        )
        return 1

    try:
        violations = scan_range(base_sha, head_sha)
    except RuntimeError as exc:
        print(f"::error::attribution-guard: {exc} -- failing closed.", file=sys.stderr)
        return 1

    if not violations:
        print(f"attribution-guard: clean ({base_sha[:12]}..{head_sha[:12]}, no AI/assistant attribution found).")
        return 0

    for v in violations:
        print(v.format())
    print(f"attribution-guard: {len(violations)} finding(s) in {base_sha[:12]}..{head_sha[:12]}.", file=sys.stderr)

    if args.report_only:
        print("attribution-guard: --report-only set, not failing the run for the findings above.")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
