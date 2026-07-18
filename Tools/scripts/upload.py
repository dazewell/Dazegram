import os
import re
import contextlib
from pathlib import Path
from sys import argv

from pyrogram import Client
from pyrogram.types import InputMediaDocument

api_id = os.environ.get("APP_ID")
api_hash = os.environ.get("APP_HASH")
artifacts_path = Path(os.environ.get("ARTIFACTS_PATH") or "artifacts")
metadata_chat_id = argv[4] if len(argv) > 4 else None

def find_apk(abi: str) -> Path:
    for apk in artifacts_path.rglob("*.apk"):
        if abi in apk.name:
            return apk

def get_commit_info():
    commit_id_raw = os.environ.get("COMMIT_ID") or "unknown"
    commit_id = commit_id_raw[:7]
    commit_url = os.environ.get("COMMIT_URL") or "https://github.com/risin42/NagramX/commits"
    commit_message = os.environ.get("COMMIT_MESSAGE") or "unknown"
    branch = os.environ.get("BRANCH") or "unknown"
    return commit_id, commit_url, commit_message, branch

def get_caption(commit_msg_budget=None) -> str:
    commit_id, commit_url, commit_message, branch = get_commit_info()
    # Trimming the plain-text commit message (never the assembled HTML) keeps
    # the caption valid while leaving room for the AI summary.
    if commit_msg_budget is not None and len(commit_message) > commit_msg_budget:
        commit_message = commit_message[: max(0, commit_msg_budget - 1)].rstrip() + "…"
    caption = ""
    header = ""
    if build_label := os.environ.get("BUILD_LABEL"):
        header += f"{build_label} build\n"
    if package_name := os.environ.get("PACKAGE_NAME"):
        header += f"Package: <code>{package_name}</code>\n"
    if header:
        caption += header + "\n"
    caption += f"Commit Message:\n<blockquote expandable>{commit_message}</blockquote>\n\n"
    caption += f"See commit details [{commit_id}]({commit_url}) on <code>{branch}</code>"
    return caption

def get_document() -> list["InputMediaDocument"]:
    documents = []
    abis = ["arm64-v8a", "universal"]
    for abi in abis:
        if apk := find_apk(abi):
            documents.append(
                InputMediaDocument(
                    media = str(apk),
                )
            )
    if not documents:
        documents.append(
        InputMediaDocument(
            media = str("TMessagesProj/src/main/" + "ic_launcher_nagram_block_round-playstore.png")
        ))
    # Telegram caps captions at 1024 chars, measured as visible text in UTF-16
    # units (tg_len) — not Python's len(). Split the budget so the commit
    # message always keeps a share (it used to be starved to "…" by a long
    # summary); the summary then takes whatever the message doesn't need.
    limit = 1024
    overhead = tg_len(get_caption(commit_msg_budget=0))
    content_budget = max(0, limit - overhead)
    commit_message = get_commit_info()[2]
    msg_reserve = min(len(commit_message), content_budget // 2)
    ai_summary = get_ai_summary(max_inner=max(0, content_budget - msg_reserve))
    room = limit - overhead - tg_len(ai_summary)
    base_caption = get_caption(commit_msg_budget=max(0, room))
    documents[-1].caption = base_caption + ai_summary
    print(documents)
    return documents
def get_metadata():
    commit_id = "<code>" + (os.environ.get("COMMIT_ID") or "unknown")[:7] + "</code>"
    commit_message = "<code>" + (os.environ.get("COMMIT_MESSAGE") or "unknown") + "</code>"
    build_timestamp = "<code>" + (os.environ.get("BUILD_TIMESTAMP") or "-1") + "</code>"
    branch = "<code>" + (os.environ.get("BRANCH") or "unknown") + "</code>"
    return build_timestamp + " " + commit_id + " " + branch + "\n" + commit_message

def get_ai_summary(max_inner=None):
    ai_summary = os.environ.get("AI_SUMMARY", "")
    if not ai_summary:
        return ""
    inner = normalize_message(ai_summary)
    if max_inner is not None and tg_len(inner) > max_inner:
        inner = truncate_to_lines(inner, max_inner)
    return "\n\n<blockquote expandable>" + inner + "</blockquote>"

def tg_len(text: str) -> int:
    # Telegram measures a caption by its visible text in UTF-16 code units:
    # HTML tags are stripped and don't count, and characters above the BMP
    # (emoji like 🐛/🧹) count as two. Python's len() matches neither, so a
    # raw-len budget let the longer-header package overflow 1024 and Telegram
    # dropped its <blockquote> while the shorter one kept it.
    visible = re.sub(r"<[^>]+>", "", text)
    return sum(2 if ord(ch) > 0xFFFF else 1 for ch in visible)

def truncate_to_lines(text: str, budget: int) -> str:
    # Drop whole trailing lines to fit the budget so a cut never lands inside a
    # tag or entity: each summary line carries balanced markup, and a mid-tag
    # slice would make the wrapping <blockquote> unparseable, which is what made
    # one build render the summary as plain text.
    lines = text.split("\n")
    while lines and tg_len("\n".join(lines) + "\n…") > budget:
        lines.pop()
    return ("\n".join(lines).rstrip() + "\n…") if lines else "…"

def normalize_message(text: str) -> str:
    return (text or "").replace("\\n", "\n")

def retry(func):
    async def wrapper(*args, **kwargs):
        for _ in range(3):
            try:
                return await func(*args, **kwargs)
            except Exception as e:
                print(e)
    return wrapper

@retry
async def send_to_channel(client: "Client", cid: str):
    with contextlib.suppress(ValueError):
        cid = int(cid)
    await client.send_media_group(
        cid,
        media = get_document(),
    )

@retry
async def send_metadata(client: "Client", cid: str):
    with contextlib.suppress(ValueError):
        cid = int(cid)
    await client.send_message(
        chat_id = cid,
        text = get_metadata(),
    )

def get_client(bot_token: str):
    return Client(
        "helper_bot",
        api_id=api_id,
        api_hash=api_hash,
        bot_token=bot_token,
    )

async def main():
    bot_token = argv[1]
    chat_id = argv[2]
    client = get_client(bot_token)
    await client.start()
    await send_to_channel(client, chat_id)
    if metadata_chat_id:
        await send_metadata(client, metadata_chat_id)
    await client.log_out()

if __name__ == "__main__":
    from asyncio import run
    run(main())
