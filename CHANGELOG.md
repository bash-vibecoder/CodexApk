# Changelog

## 1.6.1

- Ctrl+C from hardware keys or the Android keyboard interrupts the active terminal input and immediately returns to the ACodexT project picker.
- Replaced the keyboard icon in the terminal toolbar with a Paste button.
- Kept the standalone ARM64 terminal, bundled Codex CLI, provider profiles, project picker, and one-shot Ctrl/Alt/Shift modifiers.

## 1.6.0

- Added the standalone ACodexT application identity and bundled ARM64 runtime.
- Added provider setup for OpenAI-compatible Responses and Chat Completions APIs.
- Added model selection, Codex exec, and maximum reasoning effort by default.
- Added project folder creation, resume/new-chat actions, and project picker navigation.
- Added file-access and battery-optimization prompts, extra terminal keys, clipboard controls, and an optional wake lock.
- Fixed runtime extraction, dangling symlink cleanup, storage validation, and terminal renderer initialization.
