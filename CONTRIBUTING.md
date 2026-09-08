# Contribution guidelines

We encourage **any** type of contribution, from a small "fix typo" to adding new features!

> [!NOTE]
> Issues that cannot be reproduced due to specific or expensive hardware (ie. Apple Silicon machines) may be difficult to fix immediately.

## Quick Links

- [Building the project][building]
- [Editor integration][editors]
- [Troubleshooting][troubleshooting]
- [Coding Guidelines][coding-guidelines]

## How to get a PR merged

1. **Fork the repository**. You need to fork the repository to propose changes: <https://codeberg.org/winlogon/ktlsp/fork>
2. **Write the changes**. Make sure your commits are named properly (*optional*: follow [Conventional Commits][conv-commits]), but as long as it's descriptive, it's okay. **Even better**, the commit body should summarise the purpose of the commit and the issue it solves.

   **Keep changes scoped and incremental**. Avoid monolithic rewrites.
3. **Run tests**. We highly recommend running unit tests but *optional*, as CI does it anyway.

   This guarantees that ktlsp's behavior will remain consistently reliable, even when faced with edge cases.

   To do so, running `just test` or `./gradlew test` and checking whether they pass is enough.
4. **Create a pull request**.
   - Give your PR a descriptive title related to your changes.
   - In the description, explain why the changes were made and summarize them; avoid just pasting commit messages.
5. **Wait for a review**. If everything looks good, we'll merge the pull request.

You may receive comments or requests from the maintainers while the PR is being reviewed, and this is a normal part of the process, so don't get discouraged!

## How to get bugs fixed

> [!NOTE]
> If you're dealing with long stack traces or large logs, we kindly ask that you use a paste service like [GitHub Gist][gists] or [Pastebin][pastebin], and include the link in your issue instead of pasting the entire trace.
>
> This will help keep the description nice and clean, without any of that noise from stack traces.

The more detail you provide, the easier it is for maintainers to help you.

1. **Describe your environment properly**. This includes:
   - Java version (if it's older than 21, you'll need to update it)
   - Editor (ie. VSCode, Neovim, etc)
   - Operating system
2. **Describe the bug**.
   - _List clear steps_ to reproduce it.
   - If ktlsp shows an error (or an exception), you should _provide the full stack trace_ as it helps maintainers debug errors without guesses.
   - _Include any relevant configuration_ or files.

If you're sharing an exception or whatever error (unless it's something visual like a popup in your editor), do **NOT** send it as a screenshot, but rather as *text*.

**We will NOT prioritize issues that send errors as screenshots instead of text.** Reports sent as images may be closed as invalid.

### Formatting logs and stack traces

When copying logs or stack traces, line endings may be escaped as literal text (e.g., `\r\n` instead of actual CRLF), which makes exception traces unreadable. We provide an utility script to convert these escaped sequences to proper line endings:

**Script location:** [`scripts/normalize_line_endings.py`](scripts/normalize_line_endings.py)

**Usage:**
```bash
python3 scripts/normalize_line_endings.py input_log.txt normalized_log.txt
```

This converts sequences like `\r\nat x.y.z.Class` to a proper newline followed by `at x.y.z.Class` for readable stack traces.

[building]: docs/building.md
[editors]: docs/editors.md
[troubleshooting]: docs/troubleshooting.md
[coding-guidelines]: docs/contributing/coding-guidelines.md
[conv-commits]: https://www.conventionalcommits.org/en/v1.0.0/
[gists]: https://gist.github.com
[pastebin]: https://pastebin.com
