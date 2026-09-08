# Editor Integration

> [!NOTE]
> Some extensions listed here will download the [upstream](https://github.com/fwcd/kotlin-language-server) language server, so *you won't be using ktlsp by default*.
>
> To use ktlsp, you need to explicitly configure your editor's extension to do so, either by [building the LSP from source](building.md), downloading the [`server.zip`](https://codeberg.org/winlogon/ktlsp/releases) and pointing it to the intended executable, or by installing it from the [AUR](https://aur.archlinux.org/packages?K=ktlsp) (Arch Linux).

## Visual Studio Code

See [vscode-kotlin](https://github.com/fwcd/vscode-kotlin) or install the extension from the [marketplace](https://marketplace.visualstudio.com/items?itemName=fwcd.kotlin).

If you use the `server.zip` from releases, you need to point the extension to use the `server/bin/kotlin-language-server` (use the .bat one if you're on Windows).

If you're on Arch Linux and installed ktlsp from the AUR (e.g., `ktlsp-bin`), the executable is already at `/usr/bin/kotlin-language-server`, so you can point the extension to that path.

## Sublime Text

See [lsp-kotlin](https://github.com/sublimelsp/LSP-kotlin).

## Emacs

_using [`lsp-mode`](https://github.com/emacs-lsp/lsp-mode)_

There are two ways of setting up the language server with `lsp-mode`:

- Add the language server executable to your `PATH`. This is useful for development and for always using the latest version from the `main`-branch.
- Let `lsp-mode` download the server for you (`kotlin-ls`). This will use [the latest release](https://github.com/fwcd/kotlin-language-server/releases/latest) from upstream.

### Run/debug code lenses

If you use [dap-mode](https://github.com/emacs-lsp/dap-mode), you can set `(setq lsp-kotlin-debug-adapter-enabled t)` to enable the debug adapter. You will need to have [Kotlin Debug Adapter](https://github.com/fwcd/kotlin-debug-adapter) on your system. A simple configuration of `dap-mode` for Kotlin may look like:

```emacs-lisp
(require 'dap-kotlin)
(setq lsp-kotlin-debug-adapter-enabled t)
;; replace the path below to the path to your Kotlin Debug Adapter
(setq lsp-kotlin-debug-adapter-path "/path/to/kotlin-debug-adapter")
```

Then you can activate `lsp-kotlin-lens-mode` to see the Run/Debug code lenses at your main-functions.

### Override members (e.g, toString and equals)

The language server provides a custom protocol extension for finding overridable members of a class (variables and methods). `lsp-mode` provides a function that uses this called `lsp-kotlin-implement-member`. You can run it while hovering a class name, and you will get a menu with all available overridable members. (protip: Bind this function to a key!). If you have [Helm](https://github.com/emacs-helm/helm) or [Ivy](https://github.com/abo-abo/swiper) installed, one of them will be utilized.

## Vim

_using [`LanguageClient-neovim`](https://github.com/autozimu/LanguageClient-neovim)_

Add the language server to your `PATH` and include the following configuration in your `.vimrc`:

```vim
autocmd BufReadPost *.kt setlocal filetype=kotlin

let g:LanguageClient_serverCommands = {
    \ 'kotlin': ["kotlin-language-server"],
    \ }
```

_using [`coc.nvim`](https://github.com/neoclide/coc.nvim)_

Add the following to your coc-settings.json file:

```json
{
    "languageserver": {
        "kotlin": {
            "command": "[path to cloned language server]/server/build/install/server/bin/kotlin-language-server",
            "filetypes": ["kotlin"]
        }
    }
}
```

Note that you may need to substitute `kotlin-language-server` with `kotlin-language-server.bat` on Windows.\
You should also note, that you need a syntax highlighter like [udalov/kotlin-vim](https://github.com/udalov/kotlin-vim) or [sheerun/vim-polyglot](https://github.com/sheerun/vim-polyglot) to work well with coc.

## Neovim

_using [`nvim-lspconfig`](https://github.com/neovim/nvim-lspconfig)_

> [!IMPORTANT]
> The legacy `nvim-lspconfig` setup API is deprecated in Neovim 0.11+ in favor of `vim.lsp`.

Using Neovim's [nvim-lspconfig](https://github.com/neovim/nvim-lspconfig), register
the language server using the following.

```lua
require('lspconfig').kotlin_language_server.setup({})
```

If desired, you can also pass in your own defined options to the setup function.

```lua
require('lspconfig').kotlin_language_server.setup({
    on_attach = on_attach,
    flags = lsp_flags,
    capabilities = capabilities,
})
```

_using `vim.lsp` (recommended)_

In your init.lua

```lua
vim.lsp.config('kotlin_language_server', {
  cmd = {'kotlin-language-server'},
  filetypes = {'kotlin'},
  root_markers = {'build.gradle.kts', 'pom.xml' , 'build.gradle'},
})

vim.lsp.enable('kotlin_language_server')
```

## Monaco Editor

See [kotlin-monaco-language-server](https://github.com/yahorbarkouski/kotlin-monaco-language-server).

## Helix

Using [languages.toml](https://docs.helix-editor.com/languages.html)

```toml
[language-server.kotlin-language-server]
command = "kotlin-language-server"

[[language]]
name = "kotlin"
scope = "source.kotlin"
file-types = ["kt", "kts"]
roots = ["settings.gradle", "settings.gradle.kts"]
comment-token = "//"
block-comment-tokens = { start = "/*", end = "*/" }
indent = { tab-width = 4, unit = "    " }
language-servers = [ "kotlin-language-server" ]

[[grammar]]
name = "kotlin"
source = { git = "https://github.com/fwcd/tree-sitter-kotlin", rev = "a4f71eb9b8c9b19ded3e0e9470be4b1b77c2b569" }
```

## Other Editors

Install a [Language Server Protocol client](https://microsoft.github.io/language-server-protocol/implementors/tools/) for your tool. Then invoke the language server executable in a client-specific way.

The server can be launched in three modes:

- `Stdio` (the default mode)
  * The language server uses the standard streams for JSON-RPC communication
- `TCP Server`
  * The language server starts a server socket and listens on `--tcpServerPort`
- `TCP Client`
  * The language server tries to connect to `--tcpClientHost` and `--tcpClientPort`

The mode is automatically determined by the arguments provided to the language server.
