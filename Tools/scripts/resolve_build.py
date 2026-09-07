"""Resolve one staging.yml run's build classification and any label cleanup.

Decides whether a run is a PR-preview ("test") or a staging/release-candidate
build by resolved PR association, not raw event name — a workflow_dispatch
fallback whose head is an open PR's head is a test build too, matching a
`build-apk`-labeled run. Also flags a stale `build-apk` label to clear when a
dispatch fallback substitutes for a dropped labeled event, so the label keeps
behaving as a repeatable button.

This is the single place that resolves "which PR is this build for" so build
classification, the Telegram caption's PR header, and label cleanup can never
disagree with each other — staging.yml's `resolve` job runs this once and
every downstream job/step consumes its outputs instead of re-querying GitHub.
"""
import json
import os
import sys
import urllib.error
import urllib.request

API_VERSION = "2022-11-28"


def gh_get(url: str, token: str):
    req = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": API_VERSION,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())
    except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError):
        # A rate limit, auth hiccup, or a garbled/empty body must degrade to
        # "no PR found" rather than raising -- a dispatch on a branch with no
        # PR, or a transient API failure, are both handled the same way
        # downstream (fall back to a staging/#rc build), never a reason to
        # fail the run and drop the Telegram upload.
        return None


def find_associated_pr(repo: str, head_sha: str, token: str):
    """Return the open PR dict whose head is exactly head_sha, or None.

    commits/{sha}/pulls also returns a PR that merely contains this commit
    somewhere in its history (already merged/closed, or superseded by a later
    push to that PR) -- matching one of those would misclassify a plain
    branch dispatch as a PR preview, or pull a stale label off the wrong PR.
    """
    if not (repo and head_sha and token):
        return None
    data = gh_get(f"https://api.github.com/repos/{repo}/commits/{head_sha}/pulls", token)
    if not isinstance(data, list):
        return None
    for pr in data:
        if not isinstance(pr, dict):
            continue
        if pr.get("state") == "open" and (pr.get("head") or {}).get("sha") == head_sha:
            return pr
    return None


def resolve(event_name, head_sha, repo, token, pr_event=None):
    """pr_event is the github.event.pull_request payload (number/title/body),
    only present for a pull_request-triggered run."""
    if event_name == "pull_request":
        pr_event = pr_event or {}
        return {
            "build_type": "test",
            "pr_number": pr_event.get("number"),
            "pr_title": pr_event.get("title") or "",
            "pr_body": pr_event.get("body") or "",
            # The labeled-PR path clears its own label directly from the
            # event payload (no lookup needed); this field is only for the
            # dispatch-fallback cleanup below.
            "unlabel_pr_number": None,
        }

    pr = find_associated_pr(repo, head_sha, token)
    if not pr:
        return {
            "build_type": "staging",
            "pr_number": None,
            "pr_title": "",
            "pr_body": "",
            "unlabel_pr_number": None,
        }

    labels = {label.get("name") for label in (pr.get("labels") or []) if isinstance(label, dict)}
    # Only a dispatch can be a fallback for a dropped labeled event -- a push
    # to dev is never "for" a PR in the sense that would strand a label, and
    # is never a preview build either, even on the (fast-forward, not
    # merge-commit) edge case where its landed SHA happens to equal some
    # other still-open PR's head exactly. pr_number/title/body are still
    # resolved here so a push's Telegram caption keeps its PR header link,
    # exactly like before this file existed -- only build_type and label
    # cleanup are dispatch-only.
    is_dispatch = event_name == "workflow_dispatch"
    unlabel_pr_number = pr.get("number") if is_dispatch and "build-apk" in labels else None
    return {
        "build_type": "test" if is_dispatch else "staging",
        "pr_number": pr.get("number"),
        "pr_title": pr.get("title") or "",
        "pr_body": pr.get("body") or "",
        "unlabel_pr_number": unlabel_pr_number,
    }


def write_github_output(path, values):
    with open(path, "a", encoding="utf-8") as fh:
        for key, value in values.items():
            text = "" if value is None else str(value)
            delim = f"OUTEOF_{key}_{os.urandom(4).hex()}"
            fh.write(f"{key}<<{delim}\n{text}\n{delim}\n")


def main():
    event_name = os.environ.get("EVENT_NAME", "")
    repo = os.environ.get("REPO", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    head_sha = os.environ.get("HEAD_SHA", "")
    pr_event = None
    if event_name == "pull_request":
        pr_event = {
            "number": os.environ.get("PR_NUMBER") or None,
            "title": os.environ.get("PR_TITLE") or "",
            "body": os.environ.get("PR_BODY") or "",
        }

    result = resolve(event_name, head_sha, repo, token, pr_event)
    print(
        f"event={event_name} build_type={result['build_type']} "
        f"pr={result['pr_number'] or '<none>'} unlabel_pr={result['unlabel_pr_number'] or '<none>'}",
        file=sys.stderr,
    )

    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        write_github_output(output_path, result)
    else:
        print(json.dumps(result))


if __name__ == "__main__":
    main()
