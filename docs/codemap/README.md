# Codemap

A committed knowledge base of things an investigation on this fork already
worked out, so the next one doesn't have to work them out again. It exists
because two separate investigations once reached opposite wrong conclusions
about which button did what, and three reconnaissance rounds in one session
were spent re-killing hypotheses nobody had written down as dead.

This was a deliberate choice over Copilot Memory: memory auto-expires unused
facts after 28 days, can't be written to on purpose, and has no review gate.
This is a plain markdown folder instead — PR-reviewed like any other change,
versioned with the code it describes, and greppable with nothing but `git
grep`. It doesn't expire, and it doesn't need special tooling to read.

## The three sections

- **[`ui-to-code.md`](ui-to-code.md)** — "when the user taps X, the code that
  runs is Y." The highest-value section: a wrong UI→code mapping sends the
  next investigation down the wrong file entirely.
- **[`upstream-traps.md`](upstream-traps.md)** — non-obvious behaviour in
  base-fork code that has already bitten someone. What the trap is, where it
  lives, what it costs if you miss it.
- **[`dead-ends.md`](dead-ends.md)** — hypotheses that were investigated and
  disproven, with the evidence that killed them. Unusual for a knowledge base,
  and that's the point: a theory nobody wrote down as dead gets re-tested by
  the next person who thinks of it.

## The citation requirement

Every entry carries a `file:line` citation and the date it was established
(a commit or PR reference where that's useful). A mapping or trap without a
citation is unverifiable, which makes it worse than not having the entry at
all — it reads as authoritative and isn't.

## Before you rely on an entry

**Re-verify the citation first.** Line numbers drift as the file around them
changes, and a trap or mapping can be fixed, refactored, or superseded without
this file being updated in the same commit. Treat every entry here as a head
start on where to look, not as a substitute for reading the current code at
that location. Two wrong conclusions this fork has already reached both came
from a confident-sounding claim nobody re-checked.

## Contributing

When an investigation — a feature build, a bug fix, a piece of recon — nails
down a durable fact of one of the three kinds above, write it into the
matching file **as part of the same change that discovered it**, not as a
follow-up. Keep it proportionate: this is for facts that would save a future
investigation real time, not a log of everything that got read along the way.
A one-line CI tweak has nothing to contribute here; a two-hour trace that
killed three theories does.

Match the existing entries' shape: a short claim, its `file:line` citation(s),
and the date. Write in your own words — don't paste from a chat transcript or
an issue thread verbatim.
