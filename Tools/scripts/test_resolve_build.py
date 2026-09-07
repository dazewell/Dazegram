"""Regression coverage for resolve_build.py's classification and label-cleanup
decisions. Run with: python -m unittest Tools/scripts/test_resolve_build.py
(no network, no GitHub Actions runtime needed -- find_associated_pr is
monkeypatched so these exercise pure decision logic only).
"""
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
import resolve_build  # noqa: E402


def make_pr(number=316, state="open", head_sha="abc123", labels=(), title="a title", body="a body"):
    return {
        "number": number,
        "state": state,
        "head": {"sha": head_sha},
        "labels": [{"name": name} for name in labels],
        "title": title,
        "body": body,
    }


class ResolveTests(unittest.TestCase):
    def test_labeled_pr_is_always_test(self):
        # A pull_request:labeled run is a test build straight from the event
        # payload -- no API lookup needed, and no label mutation queued here
        # (the labeled path clears its own label directly from the event).
        result = resolve_build.resolve(
            "pull_request", "abc123", "dazewell/Dazegram", "tok",
            pr_event={"number": 316, "title": "tune sheets", "body": "desc"},
        )
        self.assertEqual(result["build_type"], "test")
        self.assertEqual(result["pr_number"], 316)
        self.assertIsNone(result["unlabel_pr_number"])

    def test_dispatch_on_open_pr_head_is_test_and_flags_stale_label(self):
        # The exact scenario from the #316 incident: a fallback dispatch on a
        # branch whose head is an open PR's head must be a test build, and if
        # that PR still carries build-apk (the labeled event was dropped),
        # queue it for cleanup.
        pr = make_pr(number=316, head_sha="abc123", labels=("build-apk",))
        with mock.patch.object(resolve_build, "find_associated_pr", return_value=pr):
            result = resolve_build.resolve("workflow_dispatch", "abc123", "dazewell/Dazegram", "tok")
        self.assertEqual(result["build_type"], "test")
        self.assertEqual(result["pr_number"], 316)
        self.assertEqual(result["unlabel_pr_number"], 316)

    def test_dispatch_on_open_pr_head_without_label_does_not_mutate(self):
        pr = make_pr(number=316, head_sha="abc123", labels=())
        with mock.patch.object(resolve_build, "find_associated_pr", return_value=pr):
            result = resolve_build.resolve("workflow_dispatch", "abc123", "dazewell/Dazegram", "tok")
        self.assertEqual(result["build_type"], "test")
        self.assertIsNone(result["unlabel_pr_number"])

    def test_dispatch_with_no_open_pr_is_staging_and_no_mutation(self):
        with mock.patch.object(resolve_build, "find_associated_pr", return_value=None):
            result = resolve_build.resolve("workflow_dispatch", "deadbee", "dazewell/Dazegram", "tok")
        self.assertEqual(result["build_type"], "staging")
        self.assertIsNone(result["pr_number"])
        self.assertIsNone(result["unlabel_pr_number"])

    def test_push_to_dev_is_staging(self):
        with mock.patch.object(resolve_build, "find_associated_pr", return_value=None):
            result = resolve_build.resolve("push", "deadbee", "dazewell/Dazegram", "tok")
        self.assertEqual(result["build_type"], "staging")
        self.assertIsNone(result["unlabel_pr_number"])

    def test_push_never_queues_unlabel_even_if_pr_resolves(self):
        # A push landing a PR's squashed commit onto dev can still resolve an
        # associated PR (usually now-merged/closed and filtered out anyway,
        # but guard the push branch explicitly): a push is never "for" a PR
        # in the sense that would strand a label, so it must never mutate one.
        pr = make_pr(number=316, head_sha="deadbee", labels=("build-apk",))
        with mock.patch.object(resolve_build, "find_associated_pr", return_value=pr):
            result = resolve_build.resolve("push", "deadbee", "dazewell/Dazegram", "tok")
        self.assertEqual(result["build_type"], "test")
        self.assertIsNone(result["unlabel_pr_number"])

    def test_find_associated_pr_filters_closed_pr(self):
        # A closed PR that happens to contain this commit in its history must
        # never cause a branch dispatch to be marked #test or mutate a label.
        closed_pr = make_pr(number=99, state="closed", head_sha="abc123")
        with mock.patch.object(resolve_build, "gh_get", return_value=[closed_pr]):
            pr = resolve_build.find_associated_pr("dazewell/Dazegram", "abc123", "tok")
        self.assertIsNone(pr)

    def test_find_associated_pr_filters_non_matching_head_sha(self):
        # An open PR whose current head has moved past this commit (a later
        # push superseded it) must not match either -- only the exact
        # dispatched head counts.
        stale_head_pr = make_pr(number=42, state="open", head_sha="newer-sha")
        with mock.patch.object(resolve_build, "gh_get", return_value=[stale_head_pr]):
            pr = resolve_build.find_associated_pr("dazewell/Dazegram", "abc123", "tok")
        self.assertIsNone(pr)

    def test_find_associated_pr_handles_malformed_response(self):
        for bad in (None, {"error": "rate limited"}, "not json shaped like a list"):
            with mock.patch.object(resolve_build, "gh_get", return_value=bad):
                pr = resolve_build.find_associated_pr("dazewell/Dazegram", "abc123", "tok")
            self.assertIsNone(pr)

    def test_write_github_output_round_trips_all_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "output")
            open(path, "w").close()
            resolve_build.write_github_output(path, {"build_type": "test", "pr_number": None, "pr_title": "a\nb"})
            with open(path, encoding="utf-8") as fh:
                content = fh.read()
        self.assertIn("build_type<<", content)
        self.assertIn("test", content)
        self.assertIn("pr_title<<", content)
        self.assertIn("a\nb", content)


if __name__ == "__main__":
    unittest.main()
