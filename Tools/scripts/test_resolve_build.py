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


def make_squash_merged_pr():
    return make_pr(
        number=315,
        state="closed",
        head_sha="feature-head-sha",
        head_ref="2026-09-07-telegram-build-tags-fix",
        base_repo="dazewell/Dazegram",
        base_ref="dev",
        title="build labels fix",
        body="restore post-land caption metadata",
    )


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
        with mock.patch.object(resolve_build, "gh_get", return_value=[pr]) as gh_get:
            result = resolve_build.resolve(
                event_name="workflow_dispatch",
                head_sha="abc123",
                repo_full_name="dazewell/Dazegram",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="2026-09-07-telegram-build-tags-fix",
            )
        self.assertEqual(gh_get.call_count, 1)
        self.assertEqual(result["build_type"], "test")
        self.assertEqual(result["pr_number"], 316)
        self.assertEqual(result["unlabel_pr_number"], 316)

    def test_dispatch_unique_match_without_label_does_not_mutate(self):
        pr = make_pr(labels=())
        with mock.patch.object(resolve_build, "gh_get", return_value=[pr]):
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

    def test_dispatch_with_squash_merged_fixture_is_not_preview_match(self):
        # Closed PR with different head SHA/ref shape from push-to-dev must not
        # satisfy strict dispatch preview matching.
        with mock.patch.object(resolve_build, "gh_get", return_value=[make_squash_merged_pr()]):
            result = resolve_build.resolve(
                event_name="workflow_dispatch",
                head_sha="dev-push-sha",
                repo_full_name="dazewell/Dazegram",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="dev",
            )
        self.assertEqual(result["build_type"], "staging")
        self.assertIsNone(result["pr_number"])
        self.assertIsNone(result["unlabel_pr_number"])

    def test_dispatch_no_match_is_staging(self):
        with mock.patch.object(resolve_build, "gh_get", return_value=[]):
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
        with mock.patch.object(resolve_build, "gh_get", side_effect=resolve_build.ResolveError("http_error:403")):
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
        pr1 = make_pr(number=1)
        pr2 = make_pr(number=2)
        with mock.patch.object(resolve_build, "gh_get", return_value=[pr1, pr2]):
            with self.assertRaises(resolve_build.ResolveError):
                resolve_build.resolve(
                    event_name="workflow_dispatch",
                    head_sha="abc123",
                    repo_full_name="dazewell/Dazegram",
                    token="tok",
                    head_repo_full_name="dazewell/Dazegram",
                    head_ref_name="2026-09-07-telegram-build-tags-fix",
                )

    def test_push_preserves_metadata_for_squash_merged_pr_shape(self):
        merged_pr = make_squash_merged_pr()
        with mock.patch.object(resolve_build, "gh_get", return_value=[merged_pr]) as gh_get:
            result = resolve_build.resolve(
                event_name="push",
                head_sha="dev-push-sha",
                repo_full_name="dazewell/Dazegram",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="dev",
            )
        self.assertEqual(gh_get.call_count, 1)
        self.assertEqual(result["build_type"], "staging")
        self.assertEqual(result["pr_number"], 315)
        self.assertEqual(result["pr_title"], "build labels fix")
        self.assertEqual(result["pr_body"], "restore post-land caption metadata")
        self.assertIsNone(result["unlabel_pr_number"])

    def test_push_multiple_base_matches_returns_empty_metadata(self):
        with mock.patch.object(resolve_build, "gh_get", return_value=[make_squash_merged_pr(), make_squash_merged_pr()]):
            result = resolve_build.resolve(
                event_name="push",
                head_sha="dev-push-sha",
                repo_full_name="dazewell/Dazegram",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="dev",
            )
        self.assertEqual(result["build_type"], "staging")
        self.assertIsNone(result["pr_number"])
        self.assertIsNone(result["unlabel_pr_number"])

    def test_push_lookup_error_returns_empty_metadata(self):
        with mock.patch.object(resolve_build, "gh_get", side_effect=resolve_build.ResolveError("transport_error:timeout")):
            result = resolve_build.resolve(
                event_name="push",
                head_sha="dev-push-sha",
                repo_full_name="dazewell/Dazegram",
                token="tok",
                head_repo_full_name="dazewell/Dazegram",
                head_ref_name="dev",
            )
        self.assertEqual(result["build_type"], "staging")
        self.assertIsNone(result["pr_number"])
        self.assertIsNone(result["unlabel_pr_number"])

    def test_load_associated_prs_marks_top_level_bad_shape(self):
        with mock.patch.object(resolve_build, "gh_get", return_value={"error": "shape"}):
            loaded = resolve_build.load_associated_prs("dazewell/Dazegram", "abc", "tok")
        self.assertEqual(loaded["outcome"], resolve_build.OUTCOME_LOOKUP_ERROR)

    def test_load_associated_prs_marks_item_bad_shape(self):
        with mock.patch.object(resolve_build, "gh_get", return_value=[{"number": 1}, "not-a-pr"]):
            loaded = resolve_build.load_associated_prs("dazewell/Dazegram", "abc", "tok")
        self.assertEqual(loaded["outcome"], resolve_build.OUTCOME_LOOKUP_ERROR)

    def test_select_dispatch_preview_pr_filters_on_exact_identity(self):
        pr = make_pr(head_ref="different-branch")
        selected = resolve_build.select_dispatch_preview_pr(
            candidates=[pr],
            repo_full_name="dazewell/Dazegram",
            head_sha="abc123",
            head_repo_full_name="dazewell/Dazegram",
            head_ref_name="2026-09-07-telegram-build-tags-fix",
        )
        self.assertEqual(selected["outcome"], resolve_build.OUTCOME_NO_MATCH)

    def test_select_push_metadata_pr_filters_by_base_repo_and_ref(self):
        pr = make_pr(base_ref="main")
        selected = resolve_build.select_push_metadata_pr(
            candidates=[pr],
            repo_full_name="dazewell/Dazegram",
            pushed_ref_name="dev",
        )
        self.assertEqual(selected["outcome"], resolve_build.OUTCOME_NO_MATCH)

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
