---
applyTo: "TMessagesProj/build.gradle"
---

# You are editing the app's build file

This file is **blob-pinned whole** by the sync guard. Any edit anywhere in it —
a comment, a placeholder, a version bump — fails the required `Sync guard check`
with `signing-config build.gradle blob changed: <blob>`, because `Test-SignerBlobs`
compares the git blob hash of the entire file against one recorded value rather
than diffing the signing block.

**Repin it in the same PR, in its own commit, after the file is final:**

```powershell
git rev-parse HEAD:TMessagesProj/build.gradle   # recompute at the final tree
# put that value in .github/sync/pins.env as SIGNING_GRADLE_BLOB
```

Never hand-copy the hash out of a CI log — that value is from the tree CI
built, which may no longer be yours. Verify `pin == blob` before pushing, and
repin again if a later commit or an upstream merge touches the file. Precedent:
`da79971452`, `#dazegram-icons`.

Also true of this file:

- **Per-variant values go through `APP_PACKAGE.endsWith('.beta')`** — `.beta` is
  Official (Dazegram), anything else is Unofficial (DazegramX). That expression
  is the only variant discriminator; `BuildVars.isBetaApp()` returns
  `BuildConfig.DEBUG` in this fork and is **not** a package test.
- **`manifestPlaceholders` and `buildConfigField` are the two ways out.** A
  placeholder reaches the manifest, a build-config field reaches Java. If both
  express the same decision, say so in a comment on each so they stay in step.
- **`sourceSets.main.res.srcDirs` picks exactly one icon dir** per variant. The
  comment above that line documents a resource-qualifier trap; read it before
  adding art to `src/iconOfficial` or `src/iconUnofficial`.

Full rules: `AGENTS.md` and the `nagramx-workflow` skill.
