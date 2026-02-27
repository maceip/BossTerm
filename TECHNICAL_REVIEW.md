# BossTerm Deep Technical Review: Security, Performance, and Portable VFS Strategy

*Date: 2026-02-26*
*Scope: `bossterm-core-mpp`, `compose-ui`, `bossterm-app`, build/update pipeline, settings/state persistence*

---

## Executive Summary

BossTerm is a Kotlin/Compose Desktop terminal emulator with a layered architecture: rendering/UI (`compose-ui`), terminal core/emulation (`bossterm-core-mpp`), packaging/app shell (`bossterm-app`), and CI/release automation (`.github/workflows`). The architecture is strong for local desktop terminal performance and feature breadth (shell integration, AI tool integrations, hyperlinks, updates). The highest-leverage next step is to define a **portable filesystem abstraction** that starts as metadata + path normalization + adapter interfaces, then incrementally adds sync and snapshot semantics.

The repository includes cross-platform path recognition and URL handling, but still relies on direct host paths and host command launchers in many places — creating correctness, security, and maintainability risks when users move between Windows/macOS/Linux/WSL environments.

### Top Actions (Least Effort / Highest Return)

1. **Add integrity verification (SHA-256 + signed manifest) to updates before install** — currently relies on HTTPS and filename checks without cryptographic payload verification.
2. **Stop persisting privileged secrets (`BOSSTERM_SUDO_PWD`) in command strings** — move to OS credential store + stdin-only ephemeral token handoff.
3. **Fix shell command construction** — use argument arrays or centralized shell escaping utility to avoid injection on crafted paths.
4. **Centralize all URL/command opening** behind a single policy-enforced service with scheme allowlist and telemetry.
5. **Make settings writes atomic + permission-hardened** (`0600`, temp+rename).
6. **Replace `GlobalScope` in update flows** with lifecycle-bound structured concurrency.
7. **Introduce `PortablePath` + VFS abstraction** and route path-sensitive features through it.
8. **Add low-noise security triage workflow** (SBOM + policy filter + dashboard artifact, no Dependabot spam).

---

## 1) Architecture Assessment

### Strengths

- Clear module split across app shell, Compose UI, and core emulator — good layering for portability. (`settings.gradle.kts`)
- `PlatformServices` already defines `FileSystemService`, `ProcessService`, `ClipboardService` — the right insertion point for a VFS layer.
- Terminal core has clear separation between emulator, terminal state, and connector abstractions (`TerminalStarter`, `TtyConnector`, `ProcessTtyConnector`).
- Hyperlink subsystem includes extensible registry, priority rules, file path validation hooks, and cache-based existence checks. (`FilePathResolver.kt`)
- OSC-7 working directory support exists — critical anchor for path context handoff into VFS. (`WorkingDirectoryOSCListener.kt`)
- Cross-platform URL opening and shell integration centralized in utilities. (`UrlOpener.kt`, `ShellIntegrationInjector.kt`)
- CI runs matrix build/test on macOS/Linux/Windows. (`.github/workflows/test.yml`)
- Strong terminal rendering pipeline with incremental snapshots and low-lock approaches.
- Update subsystem includes path validation and escaping in script generation, plus downgrade checks.
- PTY service has correct UTF-8 boundary handling and graceful/forced kill.
- Shell integration injection supports zsh/bash/fish and OSC-based state tracking.

### Trust Boundaries

- Untrusted terminal output -> hyperlink parser/URL opener.
- Untrusted local FS/path state -> generated shell command strings.
- Network-delivered update artifacts -> local privileged install path.
- User-configured commands/scripts -> shell execution.
- Sensitive local config state -> plain JSON files and process environment.

### Weaknesses

- Path handling is string/`File`-based and local-host centric; WSL, UNC, remote mounts, and mixed normalization rules are not first-class. No policy for path normalization, case sensitivity, symlink controls, permissions, or newline normalization.
- URL/link opening logic is duplicated (`HyperlinkDetector.openUrl` and `UrlOpener`), creating inconsistent behavior and policy drift.
- Multiple shell-command construction paths rely on shell strings (`bash -c`, `cmd /c`) with interpolated working directory/arguments — high-leverage hardening targets.
- Update download/install path lacks cryptographic integrity verification before execution.
- `GlobalScope` use in update operations risks orphaned work and lifecycle leaks.
- Privileged install flows reference `BOSSTERM_SUDO_PWD` in environment and shell script text.
- Settings writes use direct `writeText` — no atomic temp+rename, no permission hardening.
- Compose UI is desktop-only; JS/Wasm targets explicitly disabled.
- Dependabot config exists but conflicts with preference for low-noise security automation.
- No automated SBOM + vulnerability gate in CI.

---

## 2) Security Review

### 2.1 Update Channel: No Cryptographic Verification (Critical)

Update binaries are downloaded over HTTPS from GitHub releases. Validation checks filename/extension, path, version downgrade — but does not verify signed digest. A compromised release path, poisoned cache, or CI/account compromise could deliver tampered binaries.

**Fix:**
- Publish `SHA256SUMS` + detached signature per release. Embed release signing public key in app.
- Verify downloaded artifact digest + signature before installer invocation. Fail closed on mismatch.
- Change out-of-dir canonical check from warning to hard failure.
- **Stretch:** TUF-style metadata or Sigstore/cosign verification.

### 2.2 Command Construction and Injection Surface (High)

String-built shell commands with interpolated working directory/arguments for Git/GH helper flows, tool command builders, and installation commands. If any interpolated field contains shell metacharacters, this produces injection risk.

**Fix:**
- Introduce `ShellEscaper.escapePosix()/escapePowershell()` and route all interpolated tokens through it.
- Prefer process invocation with argv arrays where possible.
- Add centralized "safe command builder" for menu-generated commands.
- **Tests:** unit tests for cwd containing quotes, backticks, `$()`, semicolons; property-style tests for escaping round-trip.

### 2.3 Privileged Secrets in Environment/Scripts (High)

Install command flow passes `BOSSTERM_SUDO_PWD` via environment variable into `sudo -S` through shell script text. Increases exposure in process environments, shell history, and log leakage.

**Fix:**
- Store privileged token in OS-native keychain/credential manager abstraction.
- Pass password only through short-lived stdin stream (never env var, never persisted).
- Scrub related variables after process completion and on failure paths.
- **Medium-term:** move privileged install into smaller native helpers or signed platform installer flows.

### 2.4 URL/Path Opening Attack Surface (Medium)

Multiple code paths execute OS command launchers directly (`open`, `cmd /c start`, `xdg-open`). Terminal content can include arbitrary URLs/paths from shell output.

**Fix:**
- Route all opens via single `UrlOpener.open(url)` with scheme allowlist (`https`, `http`, `mailto`, `file`, `ssh` as policy-configured).
- Add policy gate for `file://` opening (prompt or trust zone).
- Parse/normalize with `URI` and reject malformed values.
- Add telemetry counter for blocked schemes.

### 2.5 Settings/Storage Resilience (Medium)

`writeText` directly to target path can truncate on crash/power loss. No explicit file permission hardening.

**Fix:**
- Write to temp file + `fsync` + atomic rename. Set `0600` permissions.
- Add backup rotation (`settings.json.bak`) and checksum marker.

### 2.6 Shell Integration Resource Integrity (Low-Medium)

Resources are extracted and marked executable; integrity and version metadata are not tracked.

**Fix:**
- Write `manifest.json` in integration dir with version/hash of extracted files; revalidate at startup.
- Add "safe mode" toggle that disables injection and falls back to plain shell startup.

### 2.7 Log Data Leakage (Low)

Update and shell areas log paths, versions, and operational details that could expose local paths or environment context.

**Fix:**
- Add redaction helper for home paths/usernames.
- Gate verbose logs behind debug flag.

### Security Hardening Checklist

- [ ] Verify update artifact signature/hash before install
- [ ] Validate temp script ownership/permissions before execution
- [ ] Enforce URL scheme allowlist for external open
- [ ] Add prompt for opening `file://` and local executable paths
- [ ] Add structured audit logs for update/install/open-url actions
- [ ] Remove env-password pattern; add credential provider abstraction
- [ ] Atomic settings writes with restrictive permissions

---

## 3) Performance Optimization

### Strengths

- Incremental snapshot/rendering optimization and low-lock approaches are well-documented.
- Throughput benchmark materials exist; performance culture is strong.
- Hyperlink precompute hook exists (`precomputedHyperlinks`) for optimization.
- Character analysis and measurement caching already present.

### High-Leverage Opportunities

#### P0 (High ROI, Low Effort)

1. **Command/process probe cache** — cache `which`/`where`, git repo checks, branch lookups with 30–120s TTL. Reduces repeated process spawn overhead.
2. **Settings save debounce** — coalesce rapid updates (100–300ms) into single atomic write. Reduces I/O churn.
3. **Structured coroutine scope in updater/services** — prevent background work leaks, improve shutdown latency.
4. **Path metadata cache** — segment caches per working directory/mount to reduce invalidation collisions. Short TTL for file existence, canonical path, cwd resolution.

#### P1 (High ROI, Medium Effort)

1. **Incremental hyperlink scanning** — only scan changed rows in dirty regions. Token pre-indexing and rolling line fingerprints to reduce regex passes.
2. **Batch VCS status refresh** — single scheduled refresh per tab/window rather than per interaction.
3. **Async IO boundary formalization** — all filesystem/process calls on IO dispatcher with backpressure-friendly APIs.
4. **Download helper unification** — extract `downloadToFileWithProgress(...)` utility with buffered copy and throttled progress callbacks.

#### P2 (Medium ROI, Medium-High Effort)

1. **Unified observability counters** — FS op latency buckets, link resolution failures, process cwd lookup failures, VFS adapter fallback rates. Benchmark budget gates in CI.
2. **Incremental state persistence** — journal-based settings/events then compaction.
3. **Unified process helper service** — reuse subprocess channels where practical.

### Performance Checklist

- [ ] Add counters for render/hyperlink/path-check hot paths
- [ ] Bound and observe cache sizes/hit rates
- [ ] Move expensive path existence checks off immediate render frame
- [ ] Add benchmark scenario with massive file-path-heavy output

---

## 4) Portable VFS (Virtio-9P Style) Strategy

### Goal

When users switch machines/OS (Windows <-> macOS <-> Linux/WSL), they retain terminal preferences, shell integration metadata, tab/session/workspace metadata, snippets/AI presets, and lightweight history — with predictable path semantics, encoding/EOL normalization, and snapshot/restore foundations.

### Design Principles

1. Protocol-oriented VFS API, not OS path strings in domain logic.
2. Capability-based backends (local FS, sync FS, remote FS, archive FS).
3. Deterministic URI namespace.
4. Path identity + metadata cache separated from rendering/UI.
5. Offline-first with reconciliation for sync backend.

### Layer A — Path Dynamics (Required First)

**`PortablePath` model:**
- Root kinds: POSIX `/`, Windows drive, UNC, WSL distro root, virtual mount ID
- Canonical representation: `scheme://authority/path` + `dialect` (`POSIX`, `WIN32`, `WSL`, `UNC`)
- Normalize separators, drive letters, case rules
- Case sensitivity flag by mount
- Unicode NFC normalization at API boundaries

**`PathTranslator`:**
- Host <-> portable path conversion
- WSL bidirectional: `C:\Users\x` <-> `/mnt/c/Users/x`, `\\wsl$\Ubuntu\home\x` <-> `wsl://Ubuntu/home/x`

**`TextFilePolicy`:**
- Store bytes as-is; optional text mode transforms (`LF`, `CRLF`, `auto`, `preserve`)
- UTF-8 default + BOM strategy

**`EnvironmentProbe` service:**
- Detect WSL context, distro, mount roots, case-sensitivity settings

### Layer B — VFS Adapters

```kotlin
interface VfsProvider {
  val id: String
  fun stat(path: PortablePath): VfsStat?
  fun list(path: PortablePath): List<VfsEntry>
  fun read(path: PortablePath, range: LongRange? = null): ByteArray
  fun write(path: PortablePath, data: ByteArray, mode: WriteMode)
  fun watch(path: PortablePath, listener: (VfsEvent) -> Unit): WatchHandle
  fun canonicalize(path: PortablePath): PortablePath
}

interface PathTranslator {
  fun toPortable(input: String, source: PathDialect): PortablePath
  fun toHost(path: PortablePath, target: PathDialect): String
}
```

**Providers:**
- `LocalFsAdapter` (current host, wraps `java.io.File`)
- `WslFsAdapter` (interop command bridge + direct path mapping via `\\wsl$`)
- `SyncFsBackend` (git repo, WebDAV, S3-compatible, or user-chosen folder sync)
- `OverlaySnapshotProvider` (copy-on-write metadata + content-addressed chunks)
- Optional `Virtio9pAdapter` for VM-backed transport

### Layer C — Portable Namespace

Mount roots:
- `portable://config/settings.json` (settings, themes, keymaps)
- `portable://themes/*.json`
- `portable://profiles/*.json`
- `portable://workspaces/<id>/...`
- `portable://history/events.log` (opt-in, encrypted optional)
- `local://device/render-cache/...` (rebuildable)
- `local://device/os-integration/...`

Per-file metadata: `contentHash`, `logicalClock`, `deviceId`, `lastWriter`, `encryptionState`.

### Layer D — Snapshot/Restore

- Snapshot manifest (JSON/CBOR): mount descriptors, workspace roots, shell/session metadata, content checksums.
- Content-addressed chunks to avoid duplication.
- Restore planner: conflict detection (missing mount, case-collision files, symlink constraints), dry-run report before applying.
- Portable bundle format (`bossterm-portable.tar.zst` or zip) with manifest, checksums, optional encrypted sections — enables export/import immediately.

### Layer E — Sync and Conflict Resolution

- Default last-write-wins for low-value state (UI prefs).
- Three-way merge for structured JSON where possible.
- For non-mergeable files: sibling conflict copy + notification.
- Optimistic concurrency (etag, vector clock, or per-file revision).
- Append-only operation journal for conflict recovery.
- End-to-end encryption option with user passphrase-derived key.
- Per-device key wrapping for convenience mode.
- Signed manifest per sync transaction + replay protection via monotonic operation IDs.

### Layer F — 9P-Style Transport (Deferred)

- Keep adapter API transport-agnostic.
- **Phase 1:** in-process adapter layer only.
- **Phase 2:** optional sidecar "workspace agent" process exposing RPC filesystem API (gRPC/stdio).
- **Phase 3:** remote sync + snapshot dedupe.
- If later embedding Linux VM/browser VM, use `Virtio9pAdapter` implementing the same contract.

### Migration Strategy

1. **Adapter mode** — keep existing `File` usage internally, expose `PortablePath` wrappers externally.
2. **Route settings, FilePathResolver, OSC-7 listener** through VFS path resolution.
3. **Provider routing** for WSL + host FS.
4. **Overlay snapshot/restore.**
5. **Optional remote 9p transport.**

### Where to Integrate First

1. `SettingsManager` read/write path
2. `FilePathResolver` and hyperlink file resolution
3. OSC-7 working directory ingestion
4. Shell/startup command references and workspace cwd resolution
5. Drag/drop path conversion

---

## 5) Multi-Agent Workflow + Scope Control

### Agent Context Contracts

- `docs/agents/REPO_STATE.md` (human-readable current truths)
- `docs/agents/REPO_STATE.json` (machine-readable, compact)
- `docs/agents/SESSION_BRIEF.md` (single source short context, <150 lines)
- Regenerated by CI on every main merge.

### Pre-Task Grounding

- `scripts/agent-ground.sh`: emits current branch, dirty files, changed modules, pending risks.
- Agents run this before edits — quick and deterministic.

### Scope Guardrails

- `docs/runbooks/change-intake.md` with risk class, allowed modules, mandatory checks.
- PR template checkboxes for "scope touched" and "risk acknowledged".

### Anti-Compaction Memory Loss

- Pinned `AGENT_START.md` in repo root with latest contracts and branch constraints.
- CI comment bot on PRs posting current context blob and changed architectural contracts.
- Pre-commit hook to refresh key facts (module map, top risks, recent decisions).
- CI check: if core architectural files changed, require SESSION_BRIEF update.

### "Repo Truth" CI Checks

- Validate docs/code drift for key invariants (enabled targets, supported platforms).
- Fail with actionable message if drift detected. Detect stale references to renamed files.

### Agent Ecosystem Compatibility

- Single tool-agnostic policy with thin wrappers for Codex/Claude/Gemini/CLI agents.
- `.github/workflows/agent-check.yml` executes same lint/test contracts.
- `docs/agents/policies.md` defines non-negotiables.
- Fast tiered test entrypoints and task aliases (`make verify-fast`, `make verify-all`).

---

## 6) KMP / Compose Multiplatform Web Strategy

### Current State

Compose UI JS/Wasm targets explicitly disabled. `bossterm-core-mpp` is KMP with JVM + Android targets; iOS/JS/Wasm not yet implemented.

### Staged Path

1. Move pure logic seams (path translation, hyperlink parsing, settings schema) into `commonMain`.
2. Define `expect/actual` filesystem and process adapters; desktop actuals first, web placeholder second.
3. Add `wasmJs` (or JS IR) target for non-PTY shell simulation mode.
4. Use OPFS-backed storage adapter for browser persistence; keep PTY execution remote/emulated.

### Browser Linux Emulation (`maceip/friscy` concept)

- Boot WebAssembly Linux sandbox in browser; expose pseudo terminal stream over message channel.
- Mount OPFS as persistent layer; connect BossTerm frontend to PTY bridge.
- Key challenge: memory pressure and startup latency — mitigate with lazy subsystem boot and snapshotted minimal rootfs.
- Explicit WASM memory boundaries (chunked IO buffers, backpressure, no giant string concatenations).

### OPFS and Snapshot Alignment

- Build snapshot API around chunked binary blobs and metadata manifests.
- Store manifests in OPFS; content-address chunks to avoid duplication.

---

## 7) UX / Terminal Polish Priorities

### Highest-ROI Investments

1. **Semantic prompt/command regions** — visual command boundaries, quick copy of last command/output.
2. **Path-aware click UX** — open/reveal/copy relative path, file-hover previews, context actions.
3. **Search ergonomics** — result map, jump between prompts, preserve regex presets.
4. **Syntax-aware command line hints** — lightweight shell grammar tokenizer, error/path/command highlighting.
5. **Accessibility + readability presets** — contrast, ligature toggle, cursor styles.
6. **Inline diagnostics panel** for failed commands with quick actions.

### Meta/Lexical Verdict

Not immediately for terminal surface. Prefer focused terminal-native enhancements: pluggable lightweight tokenizer for prompt/input zones. Revisit rich editor frameworks only for side panels (notes, runbooks, snippets, command history editor).

---

## 8) Automated Security Notifications (Without Dependabot)

### Model

1. **No auto PR floods.** Deprecate existing Dependabot config.
2. Nightly scan produces:
   - SBOM (`cyclonedx-gradle-plugin` / CycloneDX/SPDX)
   - Vulnerability report (`osv-scanner` or `trivy`)
   - Policy-filtered actionable list (runtime reachable, production-impacting, platform applicable, fix available)
3. Weekly single triage issue with grouped actions:
   - "actionable now" / "watchlist" / "not applicable" with rationale
4. Dashboard artifact:
   - `security-summary.json` (machine-readable)
   - `security-dashboard.md` with trend table + SVG/PNG graphs
   - Metrics: open relevant vulns over time, MTTR, patch cadence
5. Enforce dependency lockfiles/verification metadata for Gradle.

---

## 9) T-Shirt Sizing: Effort vs Impact

| Priority | Initiative | Effort | Impact |
|---|---|---|---|
| P0 | Update artifact verification (SHA-256 + signature) | S | Critical |
| P0 | Centralize URL/command execution + scheme allowlist | S | High |
| P0 | Shell command construction hardening (centralized escaper) | S | High |
| P0 | Atomic settings writes + permission hardening | S | Medium |
| P0 | Remove env-password pattern; credential provider abstraction | S | High |
| P0 | Replace `GlobalScope` with lifecycle scope in updater | S | Medium |
| P1 | `PortablePath` + normalization + resolver integration | S-M | Very High |
| P1 | Capability/command cache (TTL-based) | S | Medium |
| P1 | Agent context contracts (REPO_STATE, SESSION_BRIEF) | S | Medium |
| P1 | Security triage workflow (SBOM + filtered CVEs + graphs) | M | Medium |
| P1 | Portable export/import bundle with checksum manifest | S-M | High |
| P2 | VFS provider interfaces + Host/WSL adapters | M | Very High |
| P2 | Snapshot manifest + dry-run restore planner | M | High |
| P2 | Sync backend (WebDAV/Git/S3) + conflict UX | M-L | High |
| P3 | Overlay snapshot copy-on-write layer | L | Very High |
| P3 | Compose web target with OPFS + VM-backed adapter | XL | Medium |

---

## 10) 90-Day Execution Plan

### Step 1: Hardening Sprint (Weeks 1–2)
- Implement update SHA/signature verification.
- Introduce safe command builder + path escaping utility; migrate highest-risk callsites.
- Remove env-password pattern; add credential provider abstraction.
- Atomic settings writes + permissions hardening.
- Consolidate all URL opening to one service with scheme allowlist.
- Replace `GlobalScope` with lifecycle scope in updater.
- Add `AGENT_STATE.md` generator + `agent-ground` script.

### Step 2: Portability Foundation (Weeks 3–5)
- Add `PortablePath` utility + exhaustive normalization tests (Windows/macOS/Linux/WSL).
- Add VFS interfaces; map existing `DesktopFileSystemService` onto them.
- Wire hyperlink path validation + settings IO + OSC-7 through VFS path resolution.
- Add portable export/import bundle.
- Add capability cache service.
- Add security triage workflow and graph artifacts.

### Step 3: WSL and Snapshot MVP (Weeks 6–8)
- Implement WSL adapter for file/list/stat/read/write.
- Add snapshot manifest and local restore prototype.
- Add telemetry counters for VFS operations and failures.
- Add agent state contract generation in CI.
- Add benchmark budget checks for regression visibility.

### Step 4: Scaling (Weeks 9–12)
- Add SBOM + filtered vuln report + trend graph artifacts in GitHub Actions.
- Add initial cloud sync adapter + conflict UX.
- Add browser `PlatformServices` skeleton and OPFS-backed storage prototype.
- Publish runbooks for multi-agent contribution with CI guardrails.

---

## Risk Register

| Severity | Risk |
|---|---|
| Critical | Update artifact authenticity not cryptographically enforced |
| High | Command construction injection surface via interpolated shell strings |
| High | Privileged credential handling via environment and shell scripts |
| Medium | Non-atomic settings writes and weak local secret hardening |
| Medium | Ancillary subprocess overhead and lifecycle leaks |
| Medium | Missing automated supply-chain gates in CI |
| Medium | Path handling tightly bound to local host FS semantics |

---

## ADR: Portable Filesystem First

**Decision:** All new filesystem-facing features must target the VFS API, not raw `File`.

This single rule unlocks portability and reduces future migration cost. It aligns with the virtio-9p-style portable layer goal without forcing a big-bang rewrite.

---

## Appendix: Key Files Inspected

- Update flow: `compose-ui/.../update/DesktopUpdateService.kt`, `compose-ui/.../update/UpdateInstaller.kt`
- Update scripts: `compose-ui/.../update/UpdateScriptGenerator.kt`
- PTY/process services: `compose-ui/.../PlatformServices.desktop.kt`
- VCS commands: `compose-ui/.../vcs/GitUtils.kt`
- Tool commands: `compose-ui/.../ai/ToolCommandProvider.kt`
- Settings: `compose-ui/.../settings/SettingsManager.kt`
- Hyperlinks: `compose-ui/.../hyperlinks/FilePathResolver.kt`
- URL opening: `compose-ui/.../UrlOpener.kt`
- Shell integration: `compose-ui/.../tabs/ShellIntegrationInjector.kt`
- OSC-7: `compose-ui/.../WorkingDirectoryOSCListener.kt`
- Build: `settings.gradle.kts`, `build.gradle.kts`, module gradle files
- GitHub config: `compose-ui/.../GitHubConfig.kt`
