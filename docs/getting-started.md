# Getting Started

This guide will help you get up and running with ktlsp in your editor.

> [!NOTE]
> Some internal paths and references still use `kls`, an abbreviation of "Kotlin Language Server" (the upstream project).
>
> This name is retained for compatibility and continuity.

## Prerequisites

- **Java 21 or newer** — ktlsp requires a modern JVM. Check with `java -version`.
- **A Kotlin project** — ktlsp works best with Gradle (`build.gradle.kts` or `build.gradle`) or Maven (`pom.xml`) projects.
- **An LSP-compatible editor** — VSCode, Neovim, Emacs, Helix, Sublime Text, and more.

## Quick Start

### 1. Install the Language Server

**From release archive:**

1. Download the latest release from [Codeberg Releases](https://codeberg.org/winlogon/ktlsp/releases)
2. Extract the archive
3. Add the `bin/` directory to your `PATH`

**On Arch Linux (AUR):**

The following AUR packages are available:

- [`ktlsp-bin`](https://aur.archlinux.org/packages/ktlsp-bin) — Prebuilt release binaries *(recommended for most users)*.
- [`ktlsp-git`](https://aur.archlinux.org/packages/ktlsp-git) — Builds the latest commit from the `main` branch.
- [`ktlsp`](https://aur.archlinux.org/packages/ktlsp) — Builds the latest stable release from source.

Install using your preferred AUR helper, e.g.:

```bash
paru -S ktlsp-bin
```

The executable will be installed as `kotlin-language-server` and made available in your `PATH`. If your editor is configured to use the `PATH`, you should be good to go.

**From source:**

```bash
git clone https://codeberg.org/winlogon/ktlsp.git
cd ktlsp
./gradlew :server:installDist
```

The executable is now at `server/build/install/server/bin/kotlin-language-server`.

### 2. Configure Your Editor

Read [Editor Integration](editors.md) on how to set up ktlsp in your editor.

### 3. Open Your Project

Open your Kotlin project in your editor. ktlsp will automatically:

1. Detect your build system (Gradle, Maven, or fallback)
2. Resolve dependencies
3. Start providing language features

The initial classpath resolution may take a few seconds for large projects.

## Verifying It Works

You should see the following features working:

- **Diagnostics** — Compiler errors and warnings appear as you type
- **Code completion** — Press your editor's completion trigger (e.g., `Ctrl+Space`)
- **Hover** — Hover over a symbol to see its type and documentation
- **Go to definition** — Navigate to a symbol's declaration
- **Document symbols** — See the outline of the current file

## Troubleshooting

If features aren't working:

1. **Check that ktlsp is running** — Your editor should show it connected to a language server
2. **Check the logs** — See [Troubleshooting](troubleshooting.md) for how to enable and view logs
3. **Verify your project builds** — ktlsp needs a valid project structure. Run `./gradlew build` or `mvn compile` first
4. **Check Java version** — ktlsp requires Java 21+. Run `java -version` to verify

For common issues and solutions, see [Troubleshooting](troubleshooting.md).

## Next Steps

- [Configuration](configuration.md) — Customize ktlsp behavior
- [Features](features.md) — All available features and their status
- [Troubleshooting](troubleshooting.md) — Solve common problems
- [FAQ](faq.md) — Frequently asked questions
