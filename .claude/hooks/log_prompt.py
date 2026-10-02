#!/usr/bin/env python3
"""Claude Code UserPromptSubmit hook: appends every prompt, verbatim and
timestamped, to AI_PROMPT_LOG.md in the project root. Never blocks a prompt."""
import json
import os
import re
import sys
from datetime import datetime, timezone

SECTION = "## Other AI usage"

def main():
    try:
        data = json.load(sys.stdin)
        prompt = data.get("prompt", "")
        root = os.environ.get("CLAUDE_PROJECT_DIR") or data.get("cwd") or os.getcwd()
        path = os.path.join(root, "AI_PROMPT_LOG.md")
        text = open(path, encoding="utf-8").read() if os.path.exists(path) else ""
        n = len(re.findall(r"^### \d+", text, flags=re.M)) + 1
        stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        entry = (
            f"### {n}\n"
            f"**Time:** {stamp} (logged automatically by hook, terminal session)\n"
            f"**Prompt:**\n\n```text\n{prompt}\n```\n"
            f"**What I did with it:** TODO\n\n"
        )
        if SECTION in text:
            text = text.replace(SECTION, entry + SECTION, 1)
        else:
            text = text + ("\n" if text and not text.endswith("\n") else "") + entry
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
    except Exception as e:  # logging must never block the prompt
        print(f"log_prompt hook error: {e}", file=sys.stderr)
    sys.exit(0)

if __name__ == "__main__":
    main()
