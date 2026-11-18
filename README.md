# ACodexT for Android

An independent ARM64 Android APK with a Termux-style terminal and OpenAI Codex CLI. Its package ID is `com.acodext`; it installs beside an existing Termux app and uses an independent app sandbox, prefix, home directory, provider profiles, credentials, and Codex history.

This is an unofficial community build, not an app published by OpenAI or the Termux maintainers. It contains the Codex CLI and compatible Termux packages inside the APK. The first launch unpacks them into the app's own storage. Allow around 1 GB of free space. No Termux installation or Termux:API package is needed.

On first launch, Android asks you to grant shared-file access and ignore battery optimizations, then prompts for an API URL, token, and model. Connections are labeled automatically from the URL. The app offers the Responses API and Chat Completions compatibility mode. For example, enter `https://api.xingchiapi.com` and select the protocol supported by the model provider. `Fetch models` optionally reads the API's `/v1/models` endpoint; a 403 there does not prevent you from entering a model manually. Use `codex exec` to try an actual prompt. The token stays in this app's private storage, is excluded from Android backups, and is passed to Codex as an environment variable.

The terminal opens Codex automatically after provider setup. Useful commands:

```sh
codex
codex -m MODEL_ID
codex exec --skip-git-repo-check "Say hello"
codex --sites
codex --site PROVIDER_NAME
codex resume
pkg update
pkg install PACKAGE
```

New terminal sessions open with Codex. Use the menu for a plain shell, another Codex session, provider profiles, shared storage access, font size, clipboard paste, or a CPU wake lock. Project files start in `~/projects`. If you grant the startup file permission, shared files are available at `~/storage/shared`; you can grant it later from the menu. The app executes programs with Android's app UID and Android filesystem restrictions; it does not have root access. Commands run by Codex can use the app's permissions and can spend the configured provider's API credit.

## Build

Requirements: ARM64 Ubuntu, Java 17+, Android `aapt`, `zipalign`, `apksigner`, `keytool`, `ar`, `curl`, Python 3, a C compiler, and the Android platform `android.jar`. `ANDROID_NDK_HOME` can point to an Android NDK; otherwise the native PTY library uses the existing host Android compiler when available.

```sh
bash scripts/build.sh
```

The script creates `dist/ACodexT-v1.6.1-arm64.apk` and its SHA-256 file. Ctrl+C stops the active terminal session and returns to the project picker. The bottom bar includes COPY and PASTE buttons. After an interactive Codex session exits, ACodexT returns to its project picker, where you can resume, start a new chat, or create a project. Pass `--runtime` to refresh the bundled ARM64 runtime. The package versions and SHA-256 hashes used to create it are pinned in `runtime-lock.json`. Build tools, signing keys, and cached downloads are local under `.tools/` and are ignored by Git. Debug signing uses the default local password `android`; change that before distributing a trusted production build.

The terminal sources in `vendor/termux-app/` are pinned to Termux App v0.118.3. Codex and all bundled package licenses and notices must be retained when redistributing the APK. Package version metadata, repository checksums, and source references are in `runtime-lock.json` and `LICENSES/`.
