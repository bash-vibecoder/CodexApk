# Changelog

## 1.6.6

- Home and Back leave Codex running. Continue reconnects to the open project session, avoiding the conversation lock error.
- When Codex exits, ACodexT returns to the project picker. Continue then resumes the saved chat.

## 1.6.5

- Made interrupted runtime upgrades recover using a fresh staging directory while keeping the previous runtime until the replacement is ready.
- Kept Root mode changes in the running app; turning Root off applies to new sessions without an app restart.

## 1.6.4

- Continue remembers the latest Codex session ID separately for each project and resumes that exact conversation.

## 1.6.3

- Continue now resumes the latest saved Codex conversation for the selected project.

## 1.6.2

- Kept a single Continue action on existing project cards and clarified the project picker text.
- Added the bottom-right Root toggle, which requests superuser access and enables root sessions only after confirming UID 0.
- Removed provider-specific examples from the setup guide.

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
