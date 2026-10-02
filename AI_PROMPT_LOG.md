# AI Prompt Log - Paytm Seat Reservation Take-Home

Candidate: Dhruv
Tool: Claude (claude.ai chat)
Started: 2026-10-02

## How to read this log

- "Prompt" entries are my messages to Claude, copied verbatim (typos included). Nothing is edited or removed.
- "What Claude gave" is a one-line neutral summary of the reply.
- "What I did with it" is filled in by me as I go (accepted / changed / rejected, and why). TODO means not yet filled in.
- Entries are append-only. Corrections go in as new entries, not edits.
- Scope: this log starts at the point where I asked Claude to keep it. The same chat has earlier messages (reading and summarizing the assignment email, a general plan, a split of work between me and Claude, and an explanation of the reserve query). Those are visible in the full chat link: <PASTE SHARE LINK HERE>
- Any other AI use (IDE autocomplete, other chats) is listed under "Other AI usage" at the bottom.

## Log

### 1
**Prompt:** "before i start i want you to log every prompt of mine in a way that the recruiter can check what all ive asked you."
**What Claude gave:** Created this log file and explained how to use it.
**What I did with it:** Committing it in the repo root with my first commit.

### 2
**Prompt:** "you can clear the file till now we can start from the prompt where i asked to log."
**What Claude gave:** Reset the log to start at entry 1 and added a scope note pointing to the full chat link.
**What I did with it:** TODO

### 3
**Prompt:** "Act as an architect , lets make a concrete plan for this project. My aim is to use the latest version(lte) in springboot, java, and for db any of the popular ones which are free will do. i think we will also have to create a git repo for this, i do have an account. take into consideration everything creating schemas, base service template,  exposing apis, have to follow a standard procedure, we shall always follow only industry standard practices, you're open to correct me, whenever i am not following one."
**What Claude gave:** Looked up current Spring Boot and Java versions, then proposed an architecture plan: stack and version decisions, database choice (PostgreSQL), draft schema, package layout, API contract and error model, transaction flow for reserve, git and CI conventions, phased build order, hosting notes, and open questions for the recruiter. Also corrected two things: Spring Boot has no LTS (Java does), and Claude's earlier MySQL suggestion was revised to PostgreSQL.
**What I did with it:** TODO

### 4
**Prompt:** "from the mail i assume all seats would be of same price?"
**What Claude gave:** Confirmed the assumption from the spec (one price_paise per show), explained how amount is computed, and flagged it as a question to confirm with the recruiter.
**What I did with it:** TODO

### 5
**Prompt:** "Do you want me to make a project for start.spring.io and then you can give me the boilerplate structure skeleton(controller service repo adapter common) or could you make this by yourself?"
**What Claude gave:** Recommended generating the project at start.spring.io myself (Claude cannot reach it or compile here), listed the settings and dependencies to pick, and said Claude would then write the boilerplate skeleton on top of the generated pom. Also pushed back on a separate "adapter" layer as over-engineering for this size.
**What I did with it:** TODO

### 6
**Prompt:** "aight. will let you know once i am done"
**What Claude gave:** Acknowledged; listed what to bring back (pom.xml, main class and package name).
**What I did with it:** N/A (no technical content)

### 7
**Prompt:** "do you think we shoulld add kafka aswell?"
**What Claude gave:** Advised against adding Kafka: not required by the spec, the 201/409 decision must be synchronous and atomic in the DB, no free Kafka hosting, extra failure modes. Suggested mentioning a transactional outbox only as a "what I would do next" item in the WRITEUP.
**What I did with it:** TODO

### 8
**Prompt:** "want to move to terminal would you be able to still track the logs?"
**What Claude gave:** Explained that this chat cannot see terminal sessions, and supplied a Claude Code UserPromptSubmit hook (.claude/hooks/log_prompt.py plus .claude/settings.json) that appends every terminal prompt verbatim with a timestamp to this file. Tested the script locally against a copy of the log.
**What I did with it:** TODO

### 9
**Prompt:** "i am sure there are ways for you to give a brief summary of your responses aswell? isnt it cached in my local device?"
**What Claude gave:** Confirmed Claude Code stores session transcripts locally, and added a Stop hook (.claude/hooks/log_response.py) that appends each reply to a new AI_RESPONSE_LOG.md, with code blocks collapsed to placeholders. Noted the hook records replies verbatim rather than summarizing, and that summaries would need a separate pass.
**What I did with it:** TODO

### 10
**Prompt:** "this shall save the response even if i use from terminal right?"
**What Claude gave:** Confirmed the hooks run only in Claude Code (terminal/IDE), not in the web chat; noted the Stop hook does not fire on interrupted replies and that subagent replies need SubagentStop; gave a verification step.
**What I did with it:** TODO

### 11
**Prompt:** "tell me how do i switch then to terminal"
**What Claude gave:** Step-by-step switch to Claude Code: install (native installer), authenticate, create the GitHub repo, drop in the .claude hook files, verify the hooks fire, and a suggested CLAUDE.md. Also noted Claude Code needs a paid plan and that the web chat log stops at entry 11.
**What I did with it:** TODO

## Other AI usage

- None so far. (Update this if I use Copilot, other chats, or any other tool.)

## Summary for WRITEUP.md (fill in at the end)

- Directed by me / generated by AI:
- Decided by me:
- AI output I rejected or changed, and why:
- Bugs the burst test caught:
