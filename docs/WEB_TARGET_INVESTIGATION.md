# BossTerm Web Target Investigation

*Date: 2026-02-28*

---

## Executive Summary

**BossTerm has no Kotlin Multiplatform web target today.** The project is a desktop-native terminal emulator with 250+ Kotlin files, virtually all in JVM/desktop-specific source sets. The `commonMain` abstraction layer contains only 2 files. The browser API integration tasks (4A–4H) reference files (`src/App.tsx`, `src/components/TerminalView.tsx`, etc.) that do not exist in this repository — they appear to target a different project with a React/TypeScript + xterm.js + Emscripten stack.

---

## 1. Current Stack Versions

| Component | Version | Latest Stable (Feb 2026) | Assessment |
|-----------|---------|--------------------------|------------|
| Kotlin | 2.1.21 | 2.1.x | **Current** |
| Compose Multiplatform | 1.9.3 | ~1.8.x (public) | Possibly pre-release or custom build |
| Gradle | 8.7 | 8.12+ | Slightly behind |
| kotlinx-coroutines | 1.10.2 | 1.10.x | **Current** |
| kotlinx-serialization | 1.9.0 | 1.9.x | **Current** |

### Compose Version Note

Compose Multiplatform 1.9.3 is not a publicly released stable version as of this analysis. The latest public stable with wasmJs GA support is in the 1.7.x–1.8.x range. This may be a pre-release, internal, or custom-versioned build. **This needs verification** — run `./gradlew dependencies` to confirm what actually resolves.

### Material 3

The project **does use Material 3** (`compose.material3` in commonMain dependencies). It does **not** use Material 3 Expressive (no `compose.material3.adaptive`, no `material3-window-size-class`, no expressive motion/shape APIs).

---

## 2. Module Architecture & Web Readiness

### Module Breakdown

| Module | Files | Source Sets | Web-Ready? |
|--------|-------|------------|------------|
| `bossterm-core-mpp` | 109 .kt | `jvmMain` only (no `commonMain`) | **No** — all JVM |
| `compose-ui` | 141 .kt (desktop), 2 .kt (common) | `commonMain`, `desktopMain` | **No** — 98.6% desktop |
| `bossterm-app` | ~5 .kt | `desktopMain` | **No** — app shell |
| `embedded-example` | ~2 .kt | `desktopMain` | **No** |
| `tabbed-example` | ~2 .kt | `desktopMain` | **No** |

### What Exists in `commonMain` (2 files)

1. **`InputHandler.kt`** — Interface for key/mouse/touch events
2. **`PlatformServices.kt`** — Comprehensive `expect/actual` abstraction with interfaces for:
   - `ClipboardService` (copy/paste)
   - `FileSystemService` (read/write/exists/home/temp)
   - `ProcessService` (PTY spawn/read/write/resize/kill)
   - `PlatformInfo` (platform name, OS detection, mobile/desktop/web booleans)
   - `BrowserService` (open URL)
   - `NotificationService` (notifications, beep)
   - `expect fun getPlatformServices(): PlatformServices`

   This is the correct insertion point for web platform actuals, but only desktop actuals exist.

### JVM-Specific Blockers in Core

The `bossterm-core-mpp` module (the terminal emulation engine) has **zero commonMain code**. Everything is in `jvmMain` and uses:

- `java.util.concurrent.*` (locks, atomic references, concurrent collections)
- `synchronized` blocks throughout
- `com.ibm.icu:icu4j` for grapheme cluster segmentation
- `java.io.*` streams
- `java.lang.Character` Unicode methods
- JVM threading model

**This is the biggest blocker.** Without the core engine in `commonMain`, no web target can render a terminal.

### Desktop-Specific Dependencies in `compose-ui`

- `pty4j` — Native PTY library (no web equivalent)
- `JNA` — Native access for macOS notifications
- `ktor-client-cio` — JVM-specific HTTP client
- `kotlinx-coroutines-swing` — Swing event loop integration
- `compose.desktop.currentOs` — Desktop-specific Compose runtime
- Skiko/Skia canvas rendering via `TerminalCanvasRenderer.kt`

---

## 3. Build Configuration Details

### Explicit Comments in Build Files

From `compose-ui/build.gradle.kts` (lines 58-59):
```kotlin
// Note: JS and Wasm targets removed - no actual implementation exists yet
// Can be added when jsMain/wasmJsMain source sets are implemented
```

From `bossterm-core-mpp/build.gradle.kts` (lines 17-18):
```kotlin
// Note: iOS, JS, and Wasm targets are not yet implemented
// They can be added when actual source implementations exist
```

### gradle.properties
```properties
org.jetbrains.compose.experimental.jscanvas.enabled=true
```
This flag is enabled but vestigial — no JS target exists to use it.

### kotlin-js-store/
Contains only a `yarn.lock` (133KB) — an artifact of the `jscanvas.enabled` flag, not actual JS compilation.

---

## 4. Assessment of Requested Browser API Tasks (4A–4H)

### Files Referenced vs Reality

| Referenced File | Exists? | Notes |
|----------------|---------|-------|
| `src/App.tsx` | **No** | No React/TypeScript in project |
| `src/components/SupportingView.tsx` | **No** | No TSX files exist |
| `src/components/TerminalView.tsx` | **No** | No xterm.js usage |
| `src/lib/router.ts` | **No** | No TypeScript |
| `src/index.css` | **No** | No CSS files |
| `index.html` | **No** | No HTML files |
| `friscy-bundle/overlay.js` | **No** | No JS bundle |
| Web manifest with `display_override` | **No** | No PWA manifest |
| Server config | **No** | No web server |

### Task-by-Task Assessment

| Task | Relevance | Notes |
|------|-----------|-------|
| **4A** Document-Isolation-Policy | **N/A** | No web server, no HTML responses |
| **4B** JSPI growable stacks | **N/A** | No Emscripten, no `-sJSPI=1` flag |
| **4C** Window Controls Overlay | **N/A** | No PWA manifest, no `display_override` |
| **4D** View Transitions | **N/A** | No `SupportingView.tsx` |
| **4E** Navigation API | **N/A** | No browser routing, no `App.tsx` |
| **4F** Local Font Access | **Conceptually relevant** | BossTerm does font management, but via Skiko not xterm.js |
| **4G** EyeDropper | **Conceptually relevant** | BossTerm has theming, but via Compose not browser APIs |
| **4H** Storage Buckets | **N/A** | No OPFS usage, no browser persistence |

**Conclusion: These tasks target a different project.** The reference to `maceip/friscy` in `TECHNICAL_REVIEW.md` (Section 6, "Browser Linux Emulation") suggests these may be intended for a browser-based Linux emulator that would eventually connect to BossTerm's frontend.

---

## 5. Existing Web Strategy (from TECHNICAL_REVIEW.md)

The project's own technical review (Section 6) outlines a staged plan:

1. Move pure logic seams (path translation, hyperlink parsing, settings schema) into `commonMain`
2. Define `expect/actual` filesystem and process adapters; desktop actuals first, web placeholder second
3. Add `wasmJs` (or JS IR) target for non-PTY shell simulation mode
4. Use OPFS-backed storage adapter for browser persistence; keep PTY execution remote/emulated

And a `maceip/friscy` concept:
- Boot WebAssembly Linux sandbox in browser
- Expose pseudo terminal stream over message channel
- Mount OPFS as persistent layer
- Connect BossTerm frontend to PTY bridge

---

## 6. Recommendations

### To Get Web Working: Effort Estimation

| Phase | Effort | Description |
|-------|--------|-------------|
| 1. Core engine `commonMain` migration | **Large** (weeks) | Port 109 JVM files to multiplatform, replace ICU4J, java.util.concurrent, etc. |
| 2. `wasmJs` target scaffolding | **Medium** (days) | Add target, create `wasmJsMain` source sets, web platform actuals |
| 3. Compose for Web rendering | **Large** (weeks) | Port Skia canvas rendering to Compose wasmJs canvas, handle font loading differences |
| 4. PTY bridge | **Large** (weeks) | WebSocket-to-PTY server, or WASM Linux emulation bridge |
| 5. Browser API integrations (4A-4H equivalent) | **Small** (days) | Once web rendering works, add browser APIs via Kotlin/JS interop |

### Immediate Actions (If Web Is a Priority)

1. **Verify Compose version**: Run `./gradlew dependencies` to confirm 1.9.3 resolves and check wasmJs support status
2. **Start commonMain migration**: Begin with the simplest core files (character sets, color tables, escape sequence constants)
3. **Add wasmJs target to build**: Even with empty source sets, this validates the build chain
4. **Implement web `PlatformServices` actual**: The interface is already well-designed for this
5. **Consider upgrading to latest Compose MP**: If 1.9.3 is custom, evaluate moving to official stable with wasmJs GA

### If the Goal is Browser API Integration (Tasks 4A-4H)

Those tasks belong in the `maceip/friscy` project or a new `bossterm-web` module that provides a browser shell. They cannot be implemented in the current codebase because the required files, frameworks (React, xterm.js), and infrastructure (Emscripten, OPFS, web server) do not exist here.

---

## Appendix: File Counts

```
compose-ui/src/commonMain/   2 .kt files
compose-ui/src/desktopMain/ 141 .kt files
bossterm-core-mpp/src/jvmMain/ 109 .kt files (0 in commonMain)
Total project Kotlin files: ~260
Web-compatible files: 0
```
