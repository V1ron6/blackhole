## Contributing to Blackhole

Thanks for your interest in contributing. This file describes a short workflow and expectations for changes.

1. Fork the repository and create a branch for your change, e.g. `feature/my-change` or `docs/update-homepage`.
2. Keep changes small and focused. Prefer editing `SecureWebView.resolveInput()` for URL/search behavior, `homepage.html` for UI, and `adblock_hosts.txt` for blocklists.
3. Run a local build and smoke test before opening a PR:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

4. Write clear Git commit messages. Open a PR against `main` with a short description and testing notes.
5. For documentation updates, edit `docs/DOCUMENTATION.md` and mention the section you changed in the PR body.

If you want me to enforce other rules (lint, format, CI), tell me and I can add them.
