#!/usr/bin/env python3

import os
import sys
import json
import requests
import subprocess
from telethon import TelegramClient
from telethon.errors import RPCError
import tempfile
import shutil


def get_git_commit_info():
    commit_author = subprocess.check_output(
        ["git", "log", "-1", "--pretty=format:%an"]
    ).decode("utf-8")
    commit_message = subprocess.check_output(
        ["git", "log", "-1", "--pretty=format:%s"]
    ).decode("utf-8")
    commit_hash = subprocess.check_output(
        ["git", "log", "-1", "--pretty=format:%H"]
    ).decode("utf-8")
    commit_hash_short = subprocess.check_output(
        ["git", "log", "-1", "--pretty=format:%h"]
    ).decode("utf-8")
    return commit_author, commit_message, commit_hash, commit_hash_short


def fetch(url, token=None):
    headers = {"Accept": "application/vnd.github.v3+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    response = requests.get(url, headers=headers)
    response.raise_for_status()
    return response.json()


def download_file(url, dest, token=None):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    response = requests.get(url, stream=True, headers=headers)
    response.raise_for_status()
    with open(dest, "wb") as f:
        for chunk in response.iter_content(chunk_size=8192):
            f.write(chunk)


def main():
    try:
        # Load .env file if exists
        if os.path.exists(".env"):
            with open(".env") as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#"):
                        key, _, value = line.partition("=")
                        os.environ[key.strip()] = value.strip()

        # Get environment variables
        bot_token = os.getenv("TELEGRAM_BOT_TOKEN")
        chat_id = os.getenv("TELEGRAM_CHAT_ID")
        thread_id = os.getenv("TELEGRAM_THREAD_ID")
        api_id = os.getenv("API_ID") or os.getenv("APP_ID")
        api_hash = os.getenv("API_HASH")
        repository = os.getenv("GITHUB_REPOSITORY", "sang765/ArchiveTune-Nightly")
        github_token = os.getenv("GITHUB_TOKEN")

        if not bot_token or not chat_id:
            print("Error: TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID must be set")
            sys.exit(1)

        if not api_id or not api_hash:
            print("Error: API_ID and API_HASH must be set for Telethon")
            sys.exit(1)

        print("=== Telegram Notification (Telethon) ===")
        print(f"Repository: {repository}")
        print(f"Chat ID: {chat_id}")
        print(f"Thread ID: {thread_id}")

        api_id = int(api_id)

        # Fetch latest release from GitHub
        latest_release_url = (
            f"https://api.github.com/repos/{repository}/releases/latest"
        )
        latest_release = fetch(latest_release_url, github_token)
        release_tag = latest_release["tag_name"]
        release_url = latest_release["html_url"]

        print(f"Latest Release: {release_tag}")

        # Get APK assets
        apk_assets = [
            asset
            for asset in latest_release.get("assets", [])
            if asset.get("name", "").endswith(".apk")
        ]

        if not apk_assets:
            print("No APK files found in release")
            sys.exit(0)

        # Download all APK files to temp directory
        temp_dir = tempfile.mkdtemp(prefix="apks_")
        downloaded_files = []

        try:
            for asset in apk_assets:
                file_name = asset["name"]
                download_url = asset["browser_download_url"]
                print(f"Downloading {file_name}...")
                dest_path = os.path.join(temp_dir, file_name)
                download_file(download_url, dest_path, github_token)
                downloaded_files.append(dest_path)
                print(f"Downloaded {file_name}")

            # Build message
            message = (
                f"**ArchiveTune Nightly {release_tag} Released**\n\n"
                f"🔗 [Changelog]({release_url})\n\n"
                f"📱 **APK files:** {len(downloaded_files)} file(s) attached below"
            )

            # Create Telethon client
            session_file = "bot_session.session"
            if os.path.exists(session_file):
                os.remove(session_file)

            client = TelegramClient("bot_session", api_id, api_hash)
            client.start(bot_token=bot_token)
            client.parse_mode = "markdown"

            # Send all files as a single message
            send_kwargs = {
                "entity": int(chat_id),
                "file": downloaded_files,
                "caption": message,
            }

            if thread_id:
                send_kwargs["reply_to"] = int(thread_id)

            print(f"Sending {len(downloaded_files)} files in one message...")
            client.loop.run_until_complete(client.send_file(**send_kwargs))
            print(
                f"Successfully sent all {len(downloaded_files)} APK files in one message!"
            )

        finally:
            # Cleanup: disconnect client if connected
            try:
                if "client" in locals() and client.is_connected():
                    client.loop.run_until_complete(client.disconnect())
            except Exception:
                pass  # ignore disconnect errors

            # Cleanup temp directory
            if os.path.exists(temp_dir):
                shutil.rmtree(temp_dir)
            if os.path.exists("bot_session.session"):
                os.remove("bot_session.session")

    except requests.RequestException as e:
        print(f"GitHub API error: {e}")
        sys.exit(1)
    except RPCError as e:
        print(f"Telegram API error: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}")
        import traceback

        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
