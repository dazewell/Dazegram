import os
import contextlib
from pathlib import Path
from sys import argv

from pyrogram import Client
from pyrogram.types import InputMediaDocument

api_id = os.environ.get("APP_ID")
api_hash = os.environ.get("APP_HASH")
artifacts_path = Path(os.environ.get("ARTIFACTS_PATH") or "artifacts")
build_type = argv[3] if len(argv) > 3 else None
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
    labels = {"test": "Test", "staging": "Staging", "release": "Release"}
    pre = labels.get(build_type, "Release")
    caption = f"{pre} version.\n\n"
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
    abis = ["arm64-v8a"]
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
    # Telegram caps captions at 1024 chars. Keep the AI summary and make room by
    # trimming the commit message; the summary is bounded so the caption always
    # fits even with no message text left.
    limit = 1024
    overhead = len(get_caption(commit_msg_budget=0))
    wrapper = len("\n\n<blockquote expandable></blockquote>")
    ai_summary = get_ai_summary(max_inner=max(0, limit - overhead - wrapper))
    room = limit - overhead - len(ai_summary)
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
    if max_inner is not None and len(inner) > max_inner:
        inner = inner[: max(0, max_inner - 1)].rstrip() + "…"
    return "\n\n<blockquote expandable>" + inner + "</blockquote>"

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
