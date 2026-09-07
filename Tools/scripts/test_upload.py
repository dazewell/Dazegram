"""Regression coverage for upload.py's caption assembly -- specifically that
the trailing #test/#rc hashtag always survives Telegram's 1024-unit caption
budget regardless of how long the commit message or AI summary are.

Run with: python -m unittest Tools/scripts/test_upload.py

upload.py imports pyrogram at module level (Client, error types, InputMediaDocument)
purely for typing/wiring that these tests never exercise (no network send here),
so a minimal stub is installed into sys.modules before import rather than
requiring the real dependency to be installed just to test caption math.
"""
import html
import os
import sys
import types
import unittest

_THIS_DIR = os.path.dirname(__file__)


def _install_pyrogram_stub():
    if "pyrogram" in sys.modules:
        return
    pyrogram = types.ModuleType("pyrogram")
    pyrogram.Client = object

    errors = types.ModuleType("pyrogram.errors")

    class FloodWait(Exception):
        pass

    class FloodPremiumWait(Exception):
        pass

    errors.FloodWait = FloodWait
    errors.FloodPremiumWait = FloodPremiumWait

    types_mod = types.ModuleType("pyrogram.types")

    class InputMediaDocument:
        def __init__(self, media=None):
            self.media = media
            self.caption = None

    types_mod.InputMediaDocument = InputMediaDocument

    sys.modules["pyrogram"] = pyrogram
    sys.modules["pyrogram.errors"] = errors
    sys.modules["pyrogram.types"] = types_mod


_install_pyrogram_stub()
sys.path.insert(0, _THIS_DIR)
import upload  # noqa: E402


class HashtagAndBudgetTests(unittest.TestCase):
    def setUp(self):
        # Isolate each test's environment; upload.py reads everything through
        # os.environ at call time (not at import time), so mutating it here
        # is enough -- no reload needed.
        self._env_backup = dict(os.environ)
        for key in (
            "COMMIT_ID", "COMMIT_URL", "COMMIT_MESSAGE", "BRANCH", "BRANCH_URL",
            "PR_NUMBER", "PR_TITLE", "PR_URL", "AI_SUMMARY",
        ):
            os.environ.pop(key, None)
        os.environ["COMMIT_ID"] = "abc123def"
        os.environ["COMMIT_MESSAGE"] = "fix something #infra"

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._env_backup)

    def _set_build_type(self, value):
        upload.build_type = value

    def test_test_build_uses_test_emoji_and_hashtag(self):
        self._set_build_type("test")
        os.environ["PR_NUMBER"] = "316"
        os.environ["PR_TITLE"] = "tune early-send and chat privacy sheets"
        caption = upload.get_caption()
        self.assertIn("🧪", caption)
        self.assertNotIn("🚀", caption)
        self.assertTrue(upload.get_hashtag().endswith("#test"))

    def test_staging_build_uses_rocket_and_rc_hashtag(self):
        self._set_build_type("staging")
        caption = upload.get_caption()
        self.assertIn("🚀", caption)
        self.assertTrue(upload.get_hashtag().endswith("#rc"))

    def test_unknown_build_type_falls_back_to_staging_symbols(self):
        self._set_build_type("something-unexpected")
        caption = upload.get_caption()
        self.assertIn("🚀", caption)
        self.assertTrue(upload.get_hashtag().endswith("#rc"))

    def test_hashtag_survives_long_commit_message_and_ai_summary(self):
        self._set_build_type("test")
        os.environ["PR_NUMBER"] = "316"
        os.environ["PR_TITLE"] = "x" * 500
        os.environ["COMMIT_MESSAGE"] = "line one\n" + ("very long commit body " * 200)
        os.environ["AI_SUMMARY"] = "<b>✨ Features</b>\n\n" + "\n".join(
            f"• feature number {i} with some more descriptive filler text" for i in range(60)
        )
        caption = self._build_caption()
        self.assertLessEqual(upload.tg_len(caption), 1024)
        self.assertTrue(caption.rstrip().endswith("#test"), caption[-60:])

    def test_hashtag_survives_pathologically_long_everything_for_staging(self):
        self._set_build_type("staging")
        os.environ["COMMIT_MESSAGE"] = "y" * 5000
        os.environ["AI_SUMMARY"] = "z" * 5000
        caption = self._build_caption()
        self.assertLessEqual(upload.tg_len(caption), 1024)
        self.assertTrue(caption.rstrip().endswith("#rc"), caption[-60:])

    def test_hashtag_survives_special_html_characters_in_commit_message(self):
        self._set_build_type("test")
        os.environ["PR_NUMBER"] = "316"
        os.environ["PR_TITLE"] = "a <script>alert(1)</script> & <b>title</b>" * 20
        os.environ["COMMIT_MESSAGE"] = "<weird> & </weird>" * 200
        caption = self._build_caption()
        self.assertLessEqual(upload.tg_len(caption), 1024)
        self.assertTrue(caption.rstrip().endswith("#test"), caption[-60:])
        # No dangling unescaped entity from a mid-entity truncation.
        self.assertNotRegex(caption, r"&[a-zA-Z]*$")

    def test_no_pr_falls_back_to_commit_subject_header(self):
        self._set_build_type("staging")
        os.environ["COMMIT_MESSAGE"] = "a plain dev push\n\nbody text"
        caption = upload.get_caption()
        self.assertIn("a plain dev push", caption)

    def _build_caption(self):
        # Mirrors get_document()'s budget math without touching the
        # filesystem for APKs -- this is what's actually under test.
        limit = 1024
        hashtag = upload.get_hashtag()
        overhead = upload.tg_len(upload.get_caption(commit_msg_budget=0)) + upload.tg_len(hashtag)
        content_budget = max(0, limit - overhead)
        escaped_commit_message = html.escape(upload.get_commit_info()[2])
        msg_reserve = min(upload.tg_len(escaped_commit_message), content_budget // 2)
        ai_summary = upload.get_ai_summary(max_inner=max(0, content_budget - msg_reserve))
        room = limit - overhead - upload.tg_len(ai_summary)
        base_caption = upload.get_caption(commit_msg_budget=max(0, room))
        return base_caption + ai_summary + hashtag


if __name__ == "__main__":
    unittest.main()
