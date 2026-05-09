# BigConfig — AI Assistant Guide

## Project Overview

**BigConfig** is a Clojure/Babashka workflow and template engine for infrastructure-as-code (IaC) automation. It provides a "zero-cost build step" by using Clojure to generate configuration files (JSON, YAML, HCL) and orchestrate CLI tools (Terraform/OpenTofu, Ansible, Kubectl, etc.).

The library is published as a Clojure tool and consumed via `clojure -Ttools` or Babashka (`bb`). Versioning follows `0.3.<git-commit-count>`.

---

## Repository Layout

```
big-config/
├── src/clj/            # Production source (Clojure)
│   ├── big_config/     # Core library namespaces
│   └── big_tofu/       # OpenTofu/Terraform HCL constructs
├── test/clj/           # Test files (mirrors src layout)
├── test/resources/     # Shared test data
├── test/fixtures/      # Pre-built template fixtures
├── resources/
│   ├── big-config/     # Template library (devenv, ansible, multi, etc.)
│   └── quickdoc/       # API doc generation config
├── env/
│   ├── dev/clj/        # Dev-environment source (REPL helpers)
│   └── test/resources/ # Test-env resources
├── .big-config/        # Internal project self-bootstrapping config
├── .github/workflows/  # CI/CD (ci.yml)
├── .clj-kondo/         # Linter config (lint-as mappings, Specter macros)
├── .lsp/               # LSP config (clojure-lsp)
├── deps.edn            # Clojure deps and aliases
├── bb.edn              # Babashka tasks
├── build.clj           # tools.build script (uberjar)
├── devenv.nix          # Nix dev environment definition
└── devenv.yaml         # devenv packages list
```

---

## Core Namespaces

| Namespace | Purpose |
|---|---|
| `big-config.core` | Foundational primitives: `ok`, `choice`, `->workflow`, `->step-fn` |
| `big-config.workflow` | High-level orchestration: `->workflow*`, `run-steps`, `parse-args` |
| `big-config.pluggable` | Multimethod extensibility: `handle-step`, `->workflow*` |
| `big-config.render` | Selmer-based template engine |
| `big-config.lock` | Git-tag pessimistic locking (client-side Atlantis) |
| `big-config.unlock` | Force-release locks |
| `big-config.run` | Shell command execution (`generic-cmd`, `run-cmds`) |
| `big-config.store` | Redis-backed event-sourcing store (`write!`, `restore!`) |
| `big-config.system` | Lifecycle management (alternative to Integrant) |
| `big-config.git` | Git helpers (check, push) |
| `big-config.step` | Step DSL: colon syntax `tofu:plan` → `tofu plan` |
| `big-config.step-fns` | Step function wrappers (`->step-fn`, `log-step-fn`) |
| `big-config.utils` | Helpers: `deep-merge`, `debug` macro, `keyword->path`, Specter walkers |
| `big-config.selmer-filters` | Custom Selmer template filters |
| `big-config.toml` | TOML parsing/generation |
| `big-config.tools` | Template scaffolding CLI (`package`, `devenv`, `action`) |
| `big-config.build` | Build orchestration |
| `big-tofu.core` | OpenTofu `To` protocol, `Construct` record, AWS ARN helpers |
| `big-tofu.create` | BigTofu stdlib for common constructs |

---

## Key Concepts

### Workflow Engine

Workflows thread an `opts` map through a sequence of steps. Every step must return `opts` with `::big-config/exit` set to a non-negative integer (0 = success, non-zero = failure).

```clojure
;; Minimal step
(defn my-step [opts]
  (core/ok opts))   ; sets ::big-config/exit 0

;; Step that fails
(defn failing-step [opts]
  (merge opts {:big-config/exit 1 :big-config/err "Something went wrong"}))
```

**Workflow types:**
- `tool-workflow` — renders templates and executes one CLI tool
- `comp-workflow` — sequences multiple `tool-workflows` into a lifecycle (`create`, `delete`)
- `system-workflow` — manages start/stop of background system components

### Pluggable Steps (Multimethods)

Override or add steps via `big-config.pluggable/handle-step`:

```clojure
(require '[big-config.pluggable :as pluggable])
(require '[big-config.core :as core])

(defmethod pluggable/handle-step ::my-step
  [_f step step-fns opts]
  (println "Custom step!")
  (core/ok opts))
```

To register a step in the DSL (so it's not treated as a raw shell command):

```clojure
(binding [workflow/*parse-args-steps* (conj workflow/*parse-args-steps* "my-step")]
  (workflow/parse-args ["my-step" "render"]))
```

### CLI DSL (Babashka)

```shell
bb render lock tofu:init tofu:apply -- alpha prod
#  │      │    │                       │     └── profile
#  │      │    │                       └── module
#  step   step colon-syntax (tofu init)
```

Colon syntax: `tool:subcommand` maps to `tool subcommand` in the shell.
`--` separates workflow steps from positional args (module, profile).

### Configuration Overrides via Environment Variables

Prefix any parameter with `BC_PAR_` to override it in CI:

```shell
export BC_PAR_CLOUDFLARE_ZONE_ID="your-zone-id"
```

### `->workflow` Constructor

```clojure
(core/->workflow
  {:first-step ::start           ; required
   :last-step  ::end             ; optional, defaults to ::end in same ns
   :wire-fn    (fn [step fns]    ; required — returns [f next-step]
                 (case step
                   ::start [my-fn ::end]
                   ::end   [identity]))
   :next-fn    nil})             ; optional — complex branching
```

---

## Development Workflow

### Setup

```shell
bb build          # Bootstrap dev environment (runs devenv:build)
direnv allow      # Load environment via direnv
```

The dev environment uses Nix (`devenv.nix`) and provides: git, babashka, process-compose, just, redis, direnv, clj-kondo, clojure.

### Running Tests

```shell
just test                                          # Run all tests (preferred)
clojure -M:test                                    # Clojure tests only
clj -X:test :dirs '["test/clj"]'                   # Explicit test dir
bb test-bb-task                                    # BigConfig system tests
```

Tests live in `test/clj/` and mirror the `src/clj/` namespace structure.

### REPL Development

The `:dev` alias adds dev tools and test paths:

```shell
clojure -M:dev      # Start REPL with dev tools
```

Key REPL helpers in `env/dev/clj/`:
- `humane-test-output` — formatted test output
- `tools.namespace` — namespace reloading
- `quickdoc` — API documentation generation

Use `(comment ...)` blocks for REPL exploration (they appear throughout the source).

### Building

```shell
clojure -T:build all     # Clean, prep, and build uberjar
clojure -T:build uber    # Uberjar only
clojure -T:build clean   # Clean target/
```

The library artifact name is `motain/big-login` (see `build.clj`).

---

## CI/CD

`.github/workflows/ci.yml` triggers on push to `main` (ignoring `.md` and `.gitignore` changes):

1. **test job**: Pulls `ghcr.io/amiorin/big-container:latest`, mounts the workspace, runs `bb build && direnv allow && direnv exec . just test` inside the container using `fish` shell.
2. **tag job**: On test success, computes version `0.3.$(git rev-list --count HEAD)` and force-pushes a git tag.

To test CI locally:
```shell
cat .github/workflows/ci.yml | jet -i yaml -o json | jq -r .jobs.test.steps[3].run | GITHUB_WORKSPACE=$(pwd) bash -s
```

---

## Code Conventions

### Naming

| Pattern | Meaning | Example |
|---|---|---|
| `->` prefix | Constructor | `->workflow`, `->step-fn`, `->handler` |
| `!` suffix | Side-effecting / destructive | `write!`, `restore!`, `destroy!` |
| `?` suffix | Predicate | `(ifn? f)` |
| `::` qualified keywords | Namespaced options | `::bc/exit`, `::lock/owner` |
| `^:private` | Private var | `^:private def ...` |

### The `opts` Map

All workflow functions receive and return an `opts` map. Reserved top-level keys:

| Key | Type | Meaning |
|---|---|---|
| `:big-config/exit` | `nat-int?` | Exit code (0 = success) |
| `:big-config/err` | `string?` | Error message |
| `:big-config/stack-trace` | `string?` | Exception stack trace |

### Error Handling

- Throw `ex-info` with structured `ex-data` for errors that carry context
- The workflow engine catches exceptions and merges `ex-data` + `:big-config/err` + `:big-config/stack-trace` into `opts`
- Validate at system boundaries only; trust internal function contracts

### Specter Usage

`com.rpl/specter` is used for complex nested data transformations (e.g., `MAP-WALKER`, `deep-sort-maps` in `big-config.utils`). Specter macros are mapped in `.clj-kondo/config.edn` for correct linting.

### `debug` Macro

The `debug` macro in `big-config.utils` supports REPL-driven development. LSP indentation for it is configured in `.lsp/config.edn`.

---

## Dependencies

| Library | Version | Role |
|---|---|---|
| `org.clojure/clojure` | 1.12.4 | Language runtime |
| `babashka/process` | 0.6.25 | Shell command execution |
| `babashka/fs` | 0.5.31 | Filesystem operations |
| `org.babashka/cli` | 0.8.67 | CLI argument parsing |
| `selmer/selmer` | 1.13.1 | Jinja-like template engine |
| `cheshire/cheshire` | 6.1.0 | JSON serialization |
| `com.rpl/specter` | 1.1.6 | Data navigation & transformation |
| `com.taoensso/carmine` | 3.5.0 | Redis client (Store) |
| `io.github.paintparty/bling` | 0.9.2 | Colored terminal output |
| `io.github.clojure/tools.build` | 0.10.12 | Build tooling |
| `io.github.babashka/neil` | git | Dependency management |

**Dev/Test only:** `expound`, `humane-test-output`, `quickdoc`, `tools.namespace`, `classpath`, `cognitect-labs/test-runner`, `babashka/babashka`, `aero`, `integrant`, `integrant/repl`.

---

## Templates (`resources/big-config/`)

BigConfig ships templates for scaffolding new projects:

| Template | Description |
|---|---|
| `package` | Full BigConfig project scaffold |
| `devenv` | Nix dev environment files for Clojure/Babashka |
| `action` | GitHub Actions CI workflow for Clojure |
| `multi` | (deprecated) Multi-module layout |
| `ansible` | (deprecated) Ansible project |

Templates use Selmer syntax. File names and content are interpolated via the `render` step. Whitespace control uses the `{{-` `-}}` workaround (see CHANGELOG for details).

---

## What to Avoid

- **Do not** use `aero/aero`, `big-config.aero`, `big-config.call`, or `big-config.clone` — these namespaces were removed.
- **Do not** pass `step-fns` inside the `->workflow` map — this argument was removed; pass it as the first argument when invoking the workflow function.
- **Do not** use `build` step as a synonym for `render` — `render` is the current name.
- **Do not** modify `.github/workflows/ci.yml` to skip tests; the tag job depends on test success.
- **Do not** push to `main` directly; all development should go through feature branches and CI.

---

## Useful Entry Points

- **Template scaffolding CLI**: `big-config.tools` — functions `package`, `devenv`, `action`
- **Workflow construction**: `big-config.core/->workflow`, `big-config.pluggable/->workflow*`
- **Step composition**: `big-config.core/->step-fn` with `:before-f` / `:after-f`
- **Redis store**: `big-config.store/->handler`, `write!`, `restore!`
- **System lifecycle**: `big-config.system`
- **OpenTofu HCL**: `big-tofu.core` (`To` protocol, `Construct` record)
