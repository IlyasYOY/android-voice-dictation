#!/usr/bin/env bash
# Source this file from Bash. It changes this shell's Android variables and PATH only.

ANDROID_PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_PROJECT_ROOT

# ANDROID_HOME is the current SDK variable; tolerate the legacy name when it agrees.
if [[ -n "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" &&
      "${ANDROID_HOME%/}" != "${ANDROID_SDK_ROOT%/}" ]]; then
    printf '%s\n' 'ANDROID_HOME and ANDROID_SDK_ROOT disagree. Set both to the same SDK directory, or unset ANDROID_SDK_ROOT.' >&2
    return 1 2>/dev/null || exit 1
fi
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${HOME:?HOME must be set}/.local/share/android-sdk}}"
if [[ "$ANDROID_HOME" != /* ]]; then
    printf '%s\n' 'ANDROID_HOME must be an absolute path.' >&2
    return 1 2>/dev/null || exit 1
fi
ANDROID_HOME="${ANDROID_HOME%/}"
if [[ -z "$ANDROID_HOME" ]]; then
    printf '%s\n' 'Refusing to use / as ANDROID_HOME. Choose a dedicated SDK directory.' >&2
    return 1 2>/dev/null || exit 1
fi
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Keep these in sync with app/build.gradle.kts and docs/cloud-environment.md.
export ANDROID_COMPILE_API=36
export ANDROID_BUILD_TOOLS=35.0.0
export ANDROID_COMMAND_TOOLS_BUILD=15859902
export ANDROID_COMMAND_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
# A project-owned, pinned install does not replace an existing cmdline-tools/latest.
export ANDROID_SCAFFOLD_TOOLS="$ANDROID_HOME/.scaffold-tools/$ANDROID_COMMAND_TOOLS_BUILD/cmdline-tools"

for android_bin in "$ANDROID_HOME/platform-tools" "$ANDROID_SCAFFOLD_TOOLS/bin"; do
    case ":${PATH:-}:" in
        *":$android_bin:"*) ;;
        *) PATH="$android_bin${PATH:+:$PATH}" ;;
    esac
done
export PATH
unset android_bin
