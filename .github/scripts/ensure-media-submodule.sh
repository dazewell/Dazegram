#!/bin/sh
# Check out TMessagesProj_Modules/media, the one submodule the Java compile
# needs (settings.gradle applies its core_settings.gradle). The others are
# native-build inputs and are left alone, as ci.yml does.
#
# Every worktree used to clone it from GitHub on its own (~450 MB each), so
# most never did and the compile gate failed on the environment. Instead one
# shallow cache lives in the shared git dir, holding just the pinned commits,
# and each worktree clones from it locally: no network, about 30 seconds.
#
# Runs from .githooks/post-checkout on every new worktree. Run it by hand in a
# worktree created before that hook existed, or after a merge bumps the pin:
#   sh .github/scripts/ensure-media-submodule.sh

path=TMessagesProj_Modules/media
cd "$(git rev-parse --show-toplevel)" || exit 1

# ' ' = checked out at the pinned commit; '-' = not initialised; '+' = wrong commit.
state=$(git submodule status -- "$path" 2>/dev/null | cut -c1)
[ "$state" = " " ] && exit 0

sha=$(git rev-parse "HEAD:$path") || exit 1
url=$(git config -f .gitmodules "submodule.$path.url")
cache="$(git rev-parse --path-format=absolute --git-common-dir)/nax-media-cache.git"

[ -d "$cache" ] || git init -q --bare "$cache" || exit 1
if ! git -C "$cache" cat-file -e "$sha^{commit}" 2>/dev/null; then
  echo "media submodule: fetching $sha into the shared cache (one-time per pinned commit)"
  git -C "$cache" fetch -q --depth 1 "$url" "$sha" || exit 1
fi
# A branch per pinned commit: a clone only carries branches, and it keeps gc off the commit.
git -C "$cache" update-ref "refs/heads/pin-$sha" "$sha" || exit 1

# Register the real url first, so the override below only steers this clone.
git submodule init -- "$path" || exit 1
# The clone reads from the cache. It copies rather than shares the objects:
# git won't borrow from a shallow repo, and a full-history cache runs to
# gigabytes. protocol.file is off for submodules by default since git 2.38.1.
# The repo's test data runs past Windows' 260-character path limit under a
# worktree's longer root.
git -c protocol.file.allow=always -c core.longpaths=true -c "submodule.$path.url=$cache" \
  submodule update -- "$path" || exit 1
git -C "$path" config core.longpaths true
