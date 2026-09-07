"""Unit tests for resolve_build.py decision logic."""
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
import resolve_build  # noqa: E402


def make_pr(
    number=316,
    state="open",
    head_sha="abc123",
    head_repo="dazewell/Dazegram",
    head_ref="2026-09-07-telegram-build-tags-fix",
    base_repo="dazewell/Dazegram",
    base_ref="dev",
    labels=(),
    title="title",
    body="body",
):
    return {
        "number": number,
        "state": state,
        "head": {
            "sha": head_sha,
            "ref": head_ref,
            "repo": {"full_name": head_repo},
        },
        "base": {
            "ref": base_ref,
            "repo": {"full_name": base_repo},
        },
        "labels": [{"name": name} for name in labels],
        "title": title,
        "body": body,
    }


class ResolveBuildTests(unittest.TestCase):
    def test_labeled_pr_is_always_test(self):
        result = resolve_build.resolve(
            event_name="pull_request",
            head_sha="abc123",
            repo_full_name="dazewell/Dazegram",
            token="tok",
            head_repo_full_name="dazewell/Dazegram",
            head_ref_name="2026-09-07-telegram-build-tags-fix",
            pr_event={"number": 316, "title": "t", "body": "b"},
        )
        self.assertEqual(result["build_type"], "test")
        self.assertEqual(result["pr_number"], 316)
        self.assertIsNone(result["unlabel_pr_number"])

    def test_dispatch_unique_match_is_test_and_targets_stale_label(self):
        pr = make_pr(labels=("build-apk",))
        with mock.patch.object(resolve_build, "find_associated_pr", return_value={
            "outcome": resolve_build.OUTCOME_UNIQUE_MATCH,
            "error": "",
            "pr": pr,
        }):
            result = resolve_build.resolve(
                event_name="workflow_dispatch",
                head_sha="abc123",
                repo_full_name="dazewell/Dazegram",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="2026-09-07-telegram-build-tags-fix",
            )
        self.assertEqual(result["build_type"], "test")
        self.assertEqual(result["pr_number"], 316)
        self.assertEqual(result["unlabel_pr_number"], 316)

    def test_dispatch_unique_match_without_label_does_not_mutate(self):
        pr = make_pr(labels=())
        with mock.patch.object(resolve_build, "find_associated_pr", return_value={
            "outcome": resolve_build.OUTCOME_UNIQUE_MATCH,
            "error": "",
            "pr": pr,
        }):
            result = resolve_build.resolve(
                event_name="workflow_dispatch",
                head_sha="abc123",
                repo_full_name="dazewell/Dazegram",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="2026-09-07-telegram-build-tags-fix",
            )
        self.assertEqual(result["build_type"], "test")
        self.assertIsNone(result["unlabel_pr_number"])

    def test_dispatch_no_match_is_staging(self):
        with mock.patch.object(resolve_build, "find_associated_pr", return_value={
            "outcome": resolve_build.OUTCOME_NO_MATCH,
            "error": "",
            "pr": None,
        }):
            result = resolve_build.resolve(
                event_name="workflow_dispatch",
                head_sha="deadbee",
                repo_full_name="dazewell/Dazegram",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="no-pr-branch",
            )
        self.assertEqual(result["build_type"], "staging")
        self.assertIsNone(result["pr_number"])
        self.assertIsNone(result["unlabel_pr_number"])

    def test_dispatch_lookup_error_fails_closed(self):
        with mock.patch.object(resolve_build, "find_associated_pr", return_value={
            "outcome": resolve_build.OUTCOME_LOOKUP_ERROR,
            "error": "timeout",
            "pr": None,
        }):
            with self.assertRaises(resolve_build.ResolveError):
                resolve_build.resolve(
                    event_name="workflow_dispatch",
                    head_sha="abc123",
                    repo_full_name="dazewell/Dazegram",
                    token="tok",
                    head_repo_full_name="dazewell/Dazegram",
                    head_ref_name="2026-09-07-telegram-build-tags-fix",
                )

    def test_dispatch_ambiguous_match_fails_closed(self):
        with mock.patch.object(resolve_build, "find_associated_pr", return_value={
            "outcome": resolve_build.OUTCOME_AMBIGUOUS,
            "error": "multiple_exact_matches:1,2",
            "pr": None,
        }):
            with self.assertRaises(resolve_build.ResolveError):
                resolve_build.resolve(
                    event_name="workflow_dispatch",
                    head_sha="abc123",
                    repo_full_name="dazewell/Dazegram",
                    token="tok",
                    head_repo_full_name="dazewell/Dazegram",
                    head_ref_name="2026-09-07-telegram-build-tags-fix",
                )

    def test_push_is_always_staging_on_unique_match(self):
        pr = make_pr(number=317)
        with mock.patch.object(resolve_build, "find_associated_pr", return_value={
            "outcome": resolve_build.OUTCOME_UNIQUE_MATCH,
            "error": "",
            "pr": pr,
        }):
            result = resolve_build.resolve(
                event_name="push",
                head_sha="abc123",
                repo_full_name="dazewell/Dazegram",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="dev",
            )
        self.assertEqual(result["build_type"], "staging")
        self.assertEqual(result["pr_number"], 317)
        self.assertIsNone(result["unlabel_pr_number"])

    def test_push_is_staging_on_lookup_error(self):
        with mock.patch.object(resolve_build, "find_associated_pr", return_value={
            "outcome": resolve_build.OUTCOME_LOOKUP_ERROR,
            "error": "rate_limited",
            "pr": None,
        }):
            result = resolve_build.resolve(
                event_name="push",
                head_sha="abc123",
                repo_full_name="dazewell/Dazegram",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="dev",
            )
        self.assertEqual(result["build_type"], "staging")
        self.assertIsNone(result["pr_number"])
        self.assertIsNone(result["unlabel_pr_number"])

    def test_push_is_staging_on_ambiguous(self):
        with mock.patch.object(resolve_build, "find_associated_pr", return_value={
            "outcome": resolve_build.OUTCOME_AMBIGUOUS,
            "error": "multiple_exact_matches:1,2",
            "pr": None,
        }):
            result = resolve_build.resolve(
                event_name="push",
                head_sha="abc123",
                repo_full_name="dazewell/Dazegram",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="dev",
            )
        self.assertEqual(result["build_type"], "staging")
        self.assertIsNone(result["pr_number"])
        self.assertIsNone(result["unlabel_pr_number"])

    def test_find_associated_pr_returns_unique_match(self):
        pr = make_pr()
        with mock.patch.object(resolve_build, "gh_get", return_value=[pr]):
            lookup = resolve_build.find_associated_pr(
                repo_full_name="dazewell/Dazegram",
                head_sha="abc123",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="2026-09-07-telegram-build-tags-fix",
            )
        self.assertEqual(lookup["outcome"], resolve_build.OUTCOME_UNIQUE_MATCH)
        self.assertEqual(lookup["pr"]["number"], 316)

    def test_find_associated_pr_rejects_head_ref_mismatch(self):
        pr = make_pr(head_ref="another-branch")
        with mock.patch.object(resolve_build, "gh_get", return_value=[pr]):
            lookup = resolve_build.find_associated_pr(
                repo_full_name="dazewell/Dazegram",
                head_sha="abc123",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="2026-09-07-telegram-build-tags-fix",
            )
        self.assertEqual(lookup["outcome"], resolve_build.OUTCOME_NO_MATCH)

    def test_find_associated_pr_rejects_non_dev_base(self):
        pr = make_pr(base_ref="main")
        with mock.patch.object(resolve_build, "gh_get", return_value=[pr]):
            lookup = resolve_build.find_associated_pr(
                repo_full_name="dazewell/Dazegram",
                head_sha="abc123",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="2026-09-07-telegram-build-tags-fix",
            )
        self.assertEqual(lookup["outcome"], resolve_build.OUTCOME_NO_MATCH)

    def test_find_associated_pr_rejects_different_base_repo(self):
        pr = make_pr(base_repo="other/repo")
        with mock.patch.object(resolve_build, "gh_get", return_value=[pr]):
            lookup = resolve_build.find_associated_pr(
                repo_full_name="dazewell/Dazegram",
                head_sha="abc123",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="2026-09-07-telegram-build-tags-fix",
            )
        self.assertEqual(lookup["outcome"], resolve_build.OUTCOME_NO_MATCH)

    def test_find_associated_pr_marks_ambiguous_when_multiple_exact(self):
        pr1 = make_pr(number=1)
        pr2 = make_pr(number=2)
        with mock.patch.object(resolve_build, "gh_get", return_value=[pr1, pr2]):
            lookup = resolve_build.find_associated_pr(
                repo_full_name="dazewell/Dazegram",
                head_sha="abc123",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="2026-09-07-telegram-build-tags-fix",
            )
        self.assertEqual(lookup["outcome"], resolve_build.OUTCOME_AMBIGUOUS)
        self.assertIn("multiple_exact_matches", lookup["error"])

    def test_find_associated_pr_marks_lookup_error_for_bad_shape(self):
        with mock.patch.object(resolve_build, "gh_get", return_value={"error": "rate_limited"}):
            lookup = resolve_build.find_associated_pr(
                repo_full_name="dazewell/Dazegram",
                head_sha="abc123",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="2026-09-07-telegram-build-tags-fix",
            )
        self.assertEqual(lookup["outcome"], resolve_build.OUTCOME_LOOKUP_ERROR)

    def test_find_associated_pr_marks_lookup_error_on_transport_failure(self):
        with mock.patch.object(resolve_build, "gh_get", side_effect=resolve_build.ResolveError("http_error:403")):
            lookup = resolve_build.find_associated_pr(
                repo_full_name="dazewell/Dazegram",
                head_sha="abc123",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="2026-09-07-telegram-build-tags-fix",
            )
        self.assertEqual(lookup["outcome"], resolve_build.OUTCOME_LOOKUP_ERROR)

    def test_write_github_output_round_trips_multiline_and_none(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "out")
            open(path, "w").close()
            resolve_build.write_github_output(path, {
                "build_type": "test",
                "pr_number": None,
                "pr_title": "line1\nline2",
            })
            with open(path, encoding="utf-8") as fh:
                content = fh.read()
        self.assertIn("build_type<<", content)
        self.assertIn("test", content)
        self.assertIn("pr_number<<", content)
        self.assertIn("pr_title<<", content)
        self.assertIn("line1\nline2", content)


if __name__ == "__main__":
    unittest.main()
