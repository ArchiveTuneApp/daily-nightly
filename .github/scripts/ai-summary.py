#!/usr/bin/env python3
"""Read changelog.md + changelog-diff.md, call OpenRouter API, output summary."""

import json
import os
import sys
import time
import urllib.request

CHANGELOG_FILE = sys.argv[1] if len(sys.argv) > 1 else "changelog.md"
DIFF_FILE = sys.argv[2] if len(sys.argv) > 2 else "changelog-diff.md"
API_KEY = os.environ.get("OPENROUTER_API_KEY", "")
MODEL = "google/gemma-4-31b-it:free"

if not API_KEY:
    print("Error: OPENROUTER_API_KEY not set", file=sys.stderr)
    sys.exit(1)

instructions = """Summarize the following commit messages and file changes into a clean, professional, and categorized release note.
The first section contains commit messages with authors. The second section contains the actual code diffs per commit.
Use the diffs to deeply understand what changed and categorize accordingly.
Use these categories (only include categories that have relevant commits):
- ✨ Features — new functionality
- 🐞 Bug Fixes — fixes for issues
- 🚀 Improvements — enhancements to existing features
- 🎨 UI — visual changes, layouts, themes, animations
- 🎵 Audio — playback, streaming, audio-related changes
- 🌐 Translation — localization, language updates
- ⚡ Performance — speed, memory, efficiency improvements
- 🔧 Logic — business logic, data handling, backend changes
- 🧹 Refactor — code restructuring without behavior change
- 📦 Dependencies — library updates, version bumps
- 🛡️ Security — vulnerability fixes, permission changes
- 📝 Docs — documentation, comments, README updates
Keep it concise and highlight the most important changes.
IMPORTANT: Do NOT include raw code diffs or file paths in the release note. Summarize changes in natural language only.

Additionally, look at the '## 🏆 MVP Committer' section in the provided text.
Pick the most impactful commit from that specific user and write a short 'MVP Highlight' (1-2 sentences) explaining why it was their best contribution.

Format the output as:
## What changed on this release?
[Your categorized summary here]

## 🌟 MVP Highlight
[Your highlight for the MVP committer here]
"""

def read_file(path: str) -> str:
    try:
        with open(path, "r") as f:
            return f.read()
    except FileNotFoundError:
        return ""

parts = [instructions, "", read_file(CHANGELOG_FILE), "", "File Changes (diffs):", ""]
if os.path.exists(DIFF_FILE):
    parts.append(read_file(DIFF_FILE))

content = "\n".join(parts)

payload = json.dumps({
    "model": MODEL,
    "messages": [{"role": "user", "content": content}]
}).encode("utf-8")

req = urllib.request.Request(
    "https://openrouter.ai/api/v1/chat/completions",
    data=payload,
    headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {API_KEY}",
    },
)

max_retries = 3
wait = 15

for attempt in range(max_retries):
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read())
        ai_text = (data.get("choices") or [{}])[0].get("message", {}).get("content", "")
        if ai_text:
            print(ai_text)
            print("")
            print("---")
            sys.exit(0)
        else:
            print("Error: empty AI response", file=sys.stderr)
            sys.exit(1)
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        if e.code == 429:
            print(f"Rate limited (429). Retrying in {wait}s...", file=sys.stderr)
            time.sleep(wait)
            wait *= 2
            continue
        print(f"API Error ({e.code}): {body}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

print("Error: max retries exceeded", file=sys.stderr)
sys.exit(1)
