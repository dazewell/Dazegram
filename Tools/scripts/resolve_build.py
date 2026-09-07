"""Resolve staging.yml build context from one source of truth.

Rules:
- pull_request:labeled build requests are always test builds.
- workflow_dispatch is test only on one unique open exact-head same-repo/ref
  PR targeting dev; no-match is staging; ambiguity/errors fail closed.
- push builds are always staging; metadata lookup is best-effort only.
"""
import json
import os
import sys
import urllib.error
import urllib.request

API_VERSION = "2022-11-28"

OUTCOME_NO_MATCH = "no_match"
OUTCOME_UNIQUE_MATCH = "unique_match"
OUTCOME_LOOKUP_ERROR = "lookup_error"
OUTCOME_AMBIGUOUS = "ambiguous"


class ResolveError(RuntimeError):
    pass


def gh_get(url: str, token: str):
    if not token:
        raise ResolveError("missing token")
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
            payload = resp.read()
    except urllib.error.HTTPError as exc:
        raise ResolveError(f"http_error:{exc.code}") from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        raise ResolveError(f"transport_error:{exc}") from exc

    try:
        return json.loads(payload)
    except (ValueError, json.JSONDecodeError) as exc:
        raise ResolveError("invalid_json") from exc


def _as_dict(value):
    return value if isinstance(value, dict) else {}


def _is_exact_candidate(pr, head_sha, head_repo_full_name, head_ref_name, base_repo_full_name, base_ref_name):
    if not isinstance(pr, dict):
        return False
    if pr.get("state") != "open":
        return False

    head = _as_dict(pr.get("head"))
    head_repo = _as_dict(head.get("repo"))
    base = _as_dict(pr.get("base"))
    base_repo = _as_dict(base.get("repo"))

    return (
        head.get("sha") == head_sha
        and head_repo.get("full_name") == head_repo_full_name
        and head.get("ref") == head_ref_name
        and base_repo.get("full_name") == base_repo_full_name
        and base.get("ref") == base_ref_name
    )


def find_associated_pr(repo_full_name, head_sha, token, head_repo_full_name, head_ref_name, base_ref_name="dev"):
    if not (repo_full_name and head_sha and head_repo_full_name and head_ref_name):
        return {"outcome": OUTCOME_LOOKUP_ERROR, "error": "missing_lookup_inputs", "pr": None}

    try:
        data = gh_get(f"https://api.github.com/repos/{repo_full_name}/commits/{head_sha}/pulls", token)
    except ResolveError as exc:
        return {"outcome": OUTCOME_LOOKUP_ERROR, "error": str(exc), "pr": None}

    if not isinstance(data, list):
        return {"outcome": OUTCOME_LOOKUP_ERROR, "error": "unexpected_response_shape", "pr": None}

    matches = [
        pr for pr in data
        if _is_exact_candidate(
            pr=pr,
            head_sha=head_sha,
            head_repo_full_name=head_repo_full_name,
            head_ref_name=head_ref_name,
            base_repo_full_name=repo_full_name,
            base_ref_name=base_ref_name,
        )
    ]
    if not matches:
        return {"outcome": OUTCOME_NO_MATCH, "error": "", "pr": None}
    if len(matches) > 1:
        numbers = ",".join(str(_as_dict(pr).get("number")) for pr in matches)
        return {"outcome": OUTCOME_AMBIGUOUS, "error": f"multiple_exact_matches:{numbers}", "pr": None}
    return {"outcome": OUTCOME_UNIQUE_MATCH, "error": "", "pr": matches[0]}


def _pr_value(pr, key, default=""):
    if not isinstance(pr, dict):
        return default
    value = pr.get(key)
    return default if value is None else value


def _labels(pr):
    names = set()
    for label in _as_dict(pr).get("labels") or []:
        if isinstance(label, dict) and isinstance(label.get("name"), str):
            names.add(label["name"])
    return names


def _staging_result(lookup_outcome, pr=None):
    return {
        "build_type": "staging",
        "pr_number": _pr_value(pr, "number", None),
        "pr_title": _pr_value(pr, "title", ""),
        "pr_body": _pr_value(pr, "body", ""),
        "unlabel_pr_number": None,
        "lookup_outcome": lookup_outcome,
    }


def resolve(event_name, head_sha, repo_full_name, token, head_repo_full_name, head_ref_name, pr_event=None):
    if event_name == "pull_request":
        pr_event = _as_dict(pr_event)
        return {
            "build_type": "test",
            "pr_number": _pr_value(pr_event, "number", None),
            "pr_title": _pr_value(pr_event, "title", ""),
            "pr_body": _pr_value(pr_event, "body", ""),
            "unlabel_pr_number": None,
            "lookup_outcome": "event_payload",
        }

    lookup = find_associated_pr(
        repo_full_name=repo_full_name,
        head_sha=head_sha,
        token=token,
        head_repo_full_name=head_repo_full_name,
        head_ref_name=head_ref_name,
    )
    outcome = lookup["outcome"]
    pr = lookup["pr"]

    if event_name == "workflow_dispatch":
        if outcome == OUTCOME_UNIQUE_MATCH:
            return {
                "build_type": "test",
                "pr_number": _pr_value(pr, "number", None),
                "pr_title": _pr_value(pr, "title", ""),
                "pr_body": _pr_value(pr, "body", ""),
                "unlabel_pr_number": _pr_value(pr, "number", None) if "build-apk" in _labels(pr) else None,
                "lookup_outcome": outcome,
            }
        if outcome == OUTCOME_NO_MATCH:
            return _staging_result(lookup_outcome=outcome)
        raise ResolveError(f"dispatch_lookup_not_conclusive:{outcome}:{lookup.get('error', '')}")

    if event_name == "push":
        # Push classification is event-invariant staging. Metadata lookup is
        # best-effort only and must never affect build type or label mutation.
        if outcome == OUTCOME_UNIQUE_MATCH:
            return _staging_result(lookup_outcome=outcome, pr=pr)
        return _staging_result(lookup_outcome=outcome)

    return _staging_result(lookup_outcome="unsupported_event")


def write_github_output(path, values):
    with open(path, "a", encoding="utf-8") as fh:
        for key, value in values.items():
            text = "" if value is None else str(value)
            delim = f"OUTEOF_{key}_{os.urandom(4).hex()}"
            fh.write(f"{key}<<{delim}\n{text}\n{delim}\n")


def main():
    event_name = os.environ.get("EVENT_NAME", "")
    repo_full_name = os.environ.get("REPO", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    head_sha = os.environ.get("HEAD_SHA", "")
    head_repo_full_name = os.environ.get("HEAD_REPO_FULL_NAME", "")
    head_ref_name = os.environ.get("HEAD_REF_NAME", "")
    pr_event = None
    if event_name == "pull_request":
        pr_event = {
            "number": os.environ.get("PR_NUMBER") or None,
            "title": os.environ.get("PR_TITLE") or "",
            "body": os.environ.get("PR_BODY") or "",
        }

    try:
        result = resolve(
            event_name=event_name,
            head_sha=head_sha,
            repo_full_name=repo_full_name,
            token=token,
            head_repo_full_name=head_repo_full_name,
            head_ref_name=head_ref_name,
            pr_event=pr_event,
        )
    except ResolveError as exc:
        print(f"resolve_build failed: {exc}", file=sys.stderr)
        raise

    print(
        f"event={event_name} build_type={result['build_type']} "
        f"pr={result['pr_number'] or '<none>'} "
        f"unlabel_pr={result['unlabel_pr_number'] or '<none>'} "
        f"lookup_outcome={result['lookup_outcome']}",
        file=sys.stderr,
    )

    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        write_github_output(output_path, result)
    else:
        print(json.dumps(result))


if __name__ == "__main__":
    main()
