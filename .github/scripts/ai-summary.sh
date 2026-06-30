#!/usr/bin/env bash
set -euo pipefail

# Reads changelog.md and changelog-diff.md from cwd, calls OpenRouter API,
# outputs an AI-generated release note summary.

CHANGELOG_FILE="${1:-changelog.md}"
DIFF_FILE="${2:-changelog-diff.md}"

if [ ! -f "$CHANGELOG_FILE" ]; then
    echo "Error: $CHANGELOG_FILE not found" >&2
    exit 1
fi

# Build user content by concatenating instructions + files
cat > /tmp/ai_instructions.txt << 'INSTRUCTIONS_EOF'
Summarize the following commit messages and file changes into a clean, professional, and categorized release note.
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

Commits:
INSTRUCTIONS_EOF

cat /tmp/ai_instructions.txt > /tmp/ai_content.txt
echo "" >> /tmp/ai_content.txt
cat "$CHANGELOG_FILE" >> /tmp/ai_content.txt
echo "" >> /tmp/ai_content.txt
echo "File Changes (diffs):" >> /tmp/ai_content.txt
echo "" >> /tmp/ai_content.txt
if [ -f "$DIFF_FILE" ]; then
    cat "$DIFF_FILE" >> /tmp/ai_content.txt
fi

MODEL="google/gemma-4-31b-it:free"

jq -n \
    --arg model "$MODEL" \
    --rawfile content /tmp/ai_content.txt \
    '{model: $model, messages: [{role: "user", content: $content}]}' \
    > /tmp/ai_payload.json

MAX_RETRIES=3
RETRY_COUNT=0
WAIT_TIME=15

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    RESPONSE=$(curl -s https://openrouter.ai/api/v1/chat/completions \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $OPENROUTER_API_KEY" \
        -d @/tmp/ai_payload.json)

    ERROR=$(echo "$RESPONSE" | jq -r '.error.message // empty')

    if [ -z "$ERROR" ]; then
        break
    fi

    if echo "$RESPONSE" | jq -e '.error.code == 429' > /dev/null; then
        echo "Rate limited (429). Retrying in ${WAIT_TIME}s... (Attempt $((RETRY_COUNT + 1))/$MAX_RETRIES)" >&2
        sleep $WAIT_TIME
        RETRY_COUNT=$((RETRY_COUNT + 1))
        WAIT_TIME=$((WAIT_TIME * 2))
    else
        echo "API Error: $ERROR" >&2
        break
    fi
done

AI_TEXT=$(echo "$RESPONSE" | jq -r '.choices[0].message.content // empty')

if [ -z "$AI_TEXT" ] || [ "$AI_TEXT" = "null" ]; then
    echo "AI summarization failed. Response: $RESPONSE" >&2
    exit 1
fi

echo "$AI_TEXT"
echo ""
echo "---"
