#!/usr/bin/env python3
"""Claude Code Stop hook: appends Claude's last reply to AI_RESPONSE_LOG.md.

Reads the session transcript (path supplied on stdin by Claude Code), pulls the
most recent assistant text, and records it. Code blocks are collapsed to a
one-line placeholder, since the actual code lives in the repo's commit history.
Never blocks: any failure exits 0 with a note on stderr.
"""
import json
import os
import re
import sys
from datetime import datetime, timezone

LOG = "AI_RESPONSE_LOG.md"
HEADER = """# AI Response Log - Paytm Seat Reservation Take-Home

Companion to AI_PROMPT_LOG.md. Appended automatically by a Claude Code Stop
hook after each reply. Code blocks are collapsed to a placeholder; the code
itself is in the commit history. Chat-session replies before this hook existed
are summarized in AI_PROMPT_LOG.md instead.

"""

def collapse_code(text):
    def repl(m):
        lang = (m.group(1) or "text").strip()
        lines = m.group(2).count("\n") + 1
        return f"_[code block omitted: {lines} lines, {lang} - see commit history]_"
    return re.sub(r"```([^\n]*)\n(.*?)```", repl, text, flags=re.S)

def last_assistant_text(path):
    texts = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            msg = rec.get("message") or rec
            if msg.get("role") != "assistant":
                continue
            content = msg.get("content")
            if isinstance(content, str):
                texts.append(content)
            elif isinstance(content, list):
                parts = [b.get("text", "") for b in content
                         if isinstance(b, dict) and b.get("type") == "text"]
                joined = "\n".join(p for p in parts if p.strip())
                if joined.strip():
                    texts.append(joined)
    return texts[-1] if texts else None

def main():
    try:
        data = json.load(sys.stdin)
        transcript = data.get("transcript_path")
        root = os.environ.get("CLAUDE_PROJECT_DIR") or data.get("cwd") or os.getcwd()
        if not transcript or not os.path.exists(transcript):
            print(f"log_response: transcript not found ({transcript})", file=sys.stderr)
            sys.exit(0)
        text = last_assistant_text(transcript)
        if not text:
            sys.exit(0)
        path = os.path.join(root, LOG)
        existing = open(path, encoding="utf-8").read() if os.path.exists(path) else HEADER
        n = len(re.findall(r"^### \d+", existing, flags=re.M)) + 1
        stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        entry = f"### {n}\n**Time:** {stamp}\n\n{collapse_code(text).strip()}\n\n---\n\n"
        with open(path, "w", encoding="utf-8") as f:
            f.write(existing.rstrip("\n") + "\n\n" + entry)
    except Exception as e:
        print(f"log_response hook error: {e}", file=sys.stderr)
    sys.exit(0)

if __name__ == "__main__":
    main()
