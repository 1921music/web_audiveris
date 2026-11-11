# TeaVM API compatibility layer

The generic layer absorbs reusable platform API differences without editing Audiveris sources.
The opt-in headless layer also uses the small source boundaries documented below. Compatibility
is opt-in (`-Pcompat=true`) in the audit harness and targets TeaVM 0.16.0 / Wasm GC.
It does not yet make the complete recognition engine executable in a browser.

The latest all-method analysis is **stage 26**: **9,091 roots / 8,653 bodies**, with all earlier
roots retained. The original root set is still preserved; historical stage counts below describe
their own runs. See the verified result and TEXTS continuation sections for current coverage.

## Approach

- `CompilerSubstitutions` uses TeaVM's substitution SPI to replace `java.awt.Point`,
  `java.awt.Dimension` and `java.awt.Color` with compatible implementations. Call sites keep their
  original Java API names. These classes retain their geometry/paint interfaces and are tested
  against the JDK rather than replaced by inert stubs.
- `CompilerPatches` adds missing methods to TeaVM's compiler class model: two `Collections`
  methods, four `String` methods and `File.toPath()`. These delegate to small compatibility helpers.
  The patch applies only when a method is absent. Original sources and dependency JARs are untouched.
- The same plugin repairs TeaVM 0.16.0's default-path `normalize()` implementation, which raises
  an index exception for relative/empty paths in the Wasm probe. This repair is explicit, including
  the bridge method, and should be revisited when upgrading TeaVM.
- `StandardApiPatches` adds 19 more missing methods through a shared delegation mechanism:
  byte-array comparison, unsigned integer parsing, charset capability checks, class name/type
  helpers, enumeration/iterator and service-loader adapters, spliterators, date conversion,
  content-length parsing, atomic compare/exchange and four thread queries/diagnostics/hints.
- `jdk.internal.util.ByteArray` and `ByteArrayLittleEndian` are replaced by unaligned byte-array
  operations expressed with shifts and masks. Both byte orders, floating-point bit conversions,
  bounds checks and failure before mutation are compared directly with JDK 25's utilities.
- Three classes from the unchanged Jackson Core 2.20.0 JAR use their shipped Java 8 base
  implementations instead of multi-release variants: `BigSignificand`, `FastDoubleSwar` and
  `FastIntegerMath`. This removes VarHandle/newer arithmetic-intrinsic requirements without
  modifying library sources or JARs. The selected bytes and original JAR are hashed in provenance.
- TeaVM's existing `Double.parseDouble` and `Float.parseFloat` bodies are replaced in the compiler
  model. The added tests exposed incorrect subnormal rounding and unsupported hexadecimal
  input in library fallback paths. The replacement validates Java numeric syntax and uses exact
  integer arithmetic followed by one nearest-even rounding step. This also rejects trailing junk
  and handles overflow, underflow and signed zero. It is a deliberate correctness repair, not a
  missing-only addition, and should be reviewed on a TeaVM upgrade.
- `UUID` is replaced by a two-long value implementation with JDK-compatible formatting, parsing,
  ordering and time-based fields. Version-4 generation uses TeaVM's `SecureRandom`/host crypto.
  `Collections.emptySortedMap`, `unmodifiableSortedMap` and `Method.isDefault` are supplied too.
- Classpath resources are an explicit bundle described by `resources.json`. Standard class/classloader
  resource methods return streams and `resource:` URLs that survive URI conversion. This includes
  the real language dictionary. The URL handler is registered once per Wasm instance; arbitrary
  JVM classloader/module resource namespaces are outside this initial monolithic bundle.
- `Properties.loadFromXML` uses `new KXmlParser()` directly, avoiding reflective factory lookup.
  The adapter checks the properties DTD/elements and preserves stream ownership. A build-generated
  overlay fixes kxml2 2.3.0's truncation of supplementary numeric character references, including
  attribute values, without modifying the original JAR. Its source/output hashes are recorded.
  `-PxmlBackend=jdk` keeps the original pure-Java JDK handler available for comparison.
- `Properties.store(Writer,String)` is supplied, and a compiler-model mask fix preserves 16-bit
  Reader characters in TeaVM's existing text-properties loader.
- Reflection metadata is explicit (`audiveris.compat.reflectionClasses` on the compiler JVM).
  Original method flags are retained for those classes, because TeaVM lowers default/private
  interface bodies to abstract declarations. Static methods are not currently returned by TeaVM's
  reflective `getDeclaredMethods`; that separate limitation remains. The probe's reflection
  allowlist does not add engine audit roots or become an implicit application-wide reflection list.
- `Reference.reachabilityFence` is a Wasm GC body intrinsic. It stores the argument in a nullable
  module-local GC root, then clears it; it is not an empty Java method. Tests inspect the emitted
  store/null/store sequence and verify the root is clear after calls. Recheck this invariant before
  adding a separate Wasm post-optimizer. Each worker/module instance has its own root.
- Module APIs represent TeaVM's flat class space as unnamed modules, cached by classloader.
  They supply class/module resource lookup and the unnamed-module read/open/export contract;
  they do not implement JPMS named-module isolation. Class lookup still requires TeaVM's explicit
  reflection metadata. Package manifest attributes are unknown (`null`), not copied from input JARs.
- `System.Logger.log(Level,String,Object[])` delegates to the existing logger implementation.
  Comparable-array comparison preserves Java's null ordering and lexicographic behavior.
  `Stream.ofNullable` delegates to the existing empty/single-element stream implementations.
- Thread context classloaders are stored per logical thread and inherited at construction,
  including an explicitly null parent context. Named classloaders retain their name and parent.
  The platform-loader query returns the existing system loader in this single class space;
  parallel-loader registration reports `false`. These APIs add no dynamic class definition or
  shared-memory threading. Independent worker instances remain independent.
- Bundled `.properties` files are registered with TeaVM's build-time ResourceBundle provider
  mechanism. A compiler-model repair sets each newly created bundle's locale; parent fallback
  retains the locale of the actual provider. The probe compares root/French resources and fallback
  with the JDK. This does not add arbitrary runtime bundle discovery.

The compiler-side service providers are placed before the standard class library on the compiler
classpath. Both providers require `audiveris.compat.enabled=true`; the original inventory and
root selection always run without substitutions. No application methods are removed to improve
the diagnostic count.

## Current coverage

| API | Implemented behavior |
| --- | --- |
| `Point`, `Dimension` | Integer storage, constructors, copy/move/resize, double rounding, equality/hash and inherited geometry behavior |
| `Color` | Common sRGB constructors/constants, integer and float components, alpha, brighter/darker, HSB conversion, decode/property lookup, solid-color paint context |
| `Collections.unmodifiableSortedSet`, `emptySortedSet` | Live read-only views, comparator/range/iterator behavior and rejected mutation |
| `String.lines()` | Lazy CR/LF/CRLF splitting without regular expressions |
| Deprecated `String.getBytes(int,int,byte[],int)` and two high-byte constructors | Low-byte/high-byte conversion and bounds checks |
| `File.toPath()` | Default filesystem conversion with per-file cached path |
| Default `Path.normalize()` | Lexical normalization, including relative `..`, roots, empty paths and Unicode |
| Additional standard methods | `Arrays.compare(byte[],byte[])`, `Integer.parseUnsignedInt(String)`, `Charset.isSupported`, `Class.getPackageName/descriptorString/componentType/arrayType/forPrimitiveName`, `Enumeration.asIterator`, `ServiceLoader.findFirst`, `Spliterators.emptySpliterator`, `Collections.singletonSpliterator`, `Date.from`, `URLConnection.getContentLengthLong`, `AtomicLong.compareAndExchange` |
| Thread queries/hints | `threadId()` reads the stored ID independently of overrides of `getId()`; `isVirtual()` is false for TeaVM's cooperative platform threads; `onSpinWait()` is a permitted no-action processor hint; `dumpStack()` prints the current exception stack to stderr |
| JDK byte-array utilities | Big/little endian boolean, char, short, int, long, float and double reads/writes, including raw-bit forms |
| Floating-point parsing | Decimal and hexadecimal inputs, strict syntax, exact nearest-even rounding and signed zero |

General Java2D rendering, fonts, native raster implementations and arbitrary color-space operations
are not supplied by these replacements. The solid-color paint context is tested on the JVM;
this does not establish browser support for the Java2D raster stack. Sorted-set Java serialization
is outside the current coverage. Filesystem conversion/normalization is lexical and does not
provide browser file access.

Charset capabilities and optional aliases come from the backend's existing `Charset.forName`;
the capability check does not add new encoders. Atomic operations inherit TeaVM's existing
`AtomicLong` semantics. This work adds neither shared Java heaps across workers nor virtual threads.
The exact floating-point fallback uses `BigInteger`, trading extra code and arithmetic work for
correctness; Jackson's normal fast paths remain in its own shipped implementations. Full-engine
size and throughput remain unmeasured; focused recognition-prefix sizes are reported below. Test sources use
JDK 25 so they can call recent API names directly; compatibility implementations target Java 17.

## Reproduction

### Worker platform boundaries

`-Pheadless=true -Pcompat=true` enables application/platform substitutions in addition to the
generic API compatibility layer. The default audit keeps headless substitutions disabled. This mode rewrites TeaVM's
class model. The root-selection policy and dependency JARs remain unchanged. Stages 1–3 used unchanged
application sources; stage 4 also makes the small source changes described below. `buildSrc`
is unchanged.

| Boundary | Worker policy |
| --- | --- |
| `WellKnowns.<clinit>` | Preserve identity constants; use UTF-8, browser platform flags and bundled `resource:` URIs. Define `/audiveris/config`, `/audiveris/data`, `/audiveris/log` paths without desktop discovery, Logback setup, reflection-based dumps or GTK/JNA initialization. |
| `OmrExecutors` | Separate high/low/cached serial queues within each module. Report one engine CPU and disable Java pool parallelism; host Workers own parallel job scheduling. Preserve pool shutdown/restart and rejection after shutdown. |
| `LoggerFactory`, `MDC`, `LogUtil` | Console/event logger with SLF4J formatting and an MDC adapter, without provider discovery. Preserve book/sheet context hooks. Desktop file/GUI appender setup explicitly throws `UnsupportedOperationException`. |
| `StepMonitoring` | Emit progress/step/message events through `HeadlessEvents.Sink`. The host supplies `setSheetId` before a job and clears it afterward; notification does not query desktop sheet/book formatting. Creating a Swing monitor explicitly fails. |
| `OMR.gui` guards | Reads are compiler constants (`null`); null-branch elimination runs before dependency analysis. Non-null GUI assignments explicitly fail. Work outside the UI branch remains. |
| Index presentation services | Disable `BasicIndex.entityService` and `GlyphIndex.glyphService` using the same null/rejection policy. Index registration and recognition data remain outside this policy. |

Six private synthetic callbacks are desktop-only in the pinned baseline: `Book.lambda$setModified$0`,
`Sheet.lambda$reset$0`, `SheetStub.lambda$setUpgraded$0`, and `lambda$publish$0` in `BasicIndex`,
`GlyphIndex` and `FilamentIndex`. Their ordinary callers are guarded by the policies above, but
the all-method audit also roots the callbacks independently. Their bodies explicitly reject desktop
execution; they are not removed from the root manifest. The compiler checks that the named callbacks
still exist. Recognition callbacks such as `SheetStub.lambda$doOneStep$0` are retained.

The serial executor queues work until `Future.get`, `invokeAll`/`invokeAny`, `OmrExecutors.shutdown` or an explicit
`SerialExecutor.drain()` drives it. Fire-and-forget `execute` needs that host drain call; there is no
automatic event-loop scheduling yet. Nested waits pump the queue; cyclic waits fail explicitly.
Cancellation and deadlines are observed at task boundaries. A timed wait cannot preempt a synchronous
task; a host deadline must terminate an unresponsive Worker. `cancel(true)` does not create a Java
interruptible thread. MDC and the host sheet ID are captured on submission and restored around tasks.
The host must bind job context before submission. Each Worker/module has independent queues and context;
Java objects/Callables are not transferable between Workers.

Folders are logical backend paths, not Windows directories or persistent browser storage. Startup
does not create directories or implement file access. A storage adapter remains necessary for actual
configuration/model writes. Likewise, the event sink is a synchronous Java boundary; a host bridge
must forward events to `postMessage`. These substitutions do not yet make the full engine runnable.

Configuration now has a Java host boundary: call `HeadlessConfiguration.configure(runOverrides,
persistedValues, saveEachSheetStep)` before engine initialization. Keys are the original qualified
constant names. Lookup order is run override, persisted value, then the original `Constant` default.
The input maps are copied; the first lookup freezes configuration. Use a fresh module/Worker to
change settings that may already be cached in static fields. Original decoding, mutation/reset,
duplicate registration guards and registry removal remain in use. This is a Java boundary tested
through exported probe methods, not a completed production JavaScript messaging API.

`ConstantManager` uses this snapshot instead of CLI discovery or `run.properties` file access in
headless mode. `storeResource()` sends an immutable, cleaned snapshot to an installed persistence
callback; without one it explicitly fails. Cleanup preserves unknown saved keys and removes values
equal to their source defaults. A host using asynchronous browser storage must await its own write
acknowledgment before reporting durable persistence; callback acceptance alone is not that guarantee.
`saveEachSheetStep` defaults to false and preserves the original `storeSheet()` path when enabled;
sheet/model storage is still a separate unimplemented backend.

`setOutputFolder(absoluteLogicalPath)` supplies the former CLI output override before the first
lookup. `BookManager` retains its original fallback selection and directory/storage instructions.
Its explicit `setBaseFolder` operation clears that override and updates the original base-folder
constant; this does not imply that the browser filesystem backend is implemented.

`SheetStub.doOneStep` takes its timeout from the original `org.audiveris.omr.Main.sheetStepTimeOut`
configuration key and save policy from the host, without initializing `Main` or querying `CLI`.
The default timeout is mechanically read from `Main.java` (120 seconds); overrides must be positive.
The compiler retains step scheduling, recognition, timeout handling, finalization and the save call.
The serial executor still cannot preempt a synchronous step; enforce hard deadlines from the host.

Small deliberate source changes keep this boundary straightforward:

- `ConstantSet.getUnitName()` isolates the existing JVM namespace lookup. TeaVM emits per-subclass
  overrides from the class file's declaring-class metadata; JVM lookup behavior stays the same.
  Compiler-added methods are analyzed as dependencies, while the audit enumerates input methods
  for the root policy. No original method is silently dropped.
- Failed registration/map initialization now throws with its cause in `Constant` and `ConstantSet`.
  Previously swallowed failures could leave an uninitialized constant spinning in its wait loop.
  A Worker that fails initialization must be discarded; configuration updates are not transactional
  recovery of partially constructed engine state.
- `BookManager.setBaseFolder` initializes its constant set before assigning the new value. Otherwise
  a later first read could register defaults over an update made before registration.

Only declared fields of reached `ConstantSet` subclasses receive reflection metadata. Their original
map-building logic remains; there is no runtime classpath scan or reflective method opt-in for this
feature. Worker-local pending-set and package-child collections use sorted snapshot traversal so
registration during initialization does not invalidate iteration. They are intended for the single
execution lane within each module. TODO: measure complete engine initialization/metadata size and
consider generated direct field access if it is significant.

Host Worker count is a host option. Drawing defaults such as template opacity retain upstream
values; logical storage paths still need a host storage adapter. These boundaries do not replace
recognition or font settings with compiler literals.

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
  'verifyWasmCompatibilityBrowser', 'traceTeaVMExclusions', 'compareWasmCompatibilityAudit',
  '-I', 'dev/teavm-audit/audit.init.gradle', '-PauditScope=selected',
  '-Pcompat=true', '-Pheadless=true', '-PallowAddedRoots=true', '--console=plain')
```

Stages 1–5 analyze **9,001 roots / 8,588 bodies**. Stage 6 preserves every one of those roots and
adds 18 scale-source API/adapter methods. Stage 8 also roots `Book.createMemoryBook`, giving
**9,020 roots / 8,599 bodies**. Stage 9 adds nine pixel-model methods, giving **9,029 roots / 8,608 bodies**.
Stage 11 adds a bracket-geometry method, giving **9,030 roots / 8,609 bodies**.
Stage 12 adds three raw-pixel staff-removal methods, giving **9,033 roots / 8,612 bodies**.
Stage 13 adds a network-data constructor and copy/validation helper: **9,035 roots / 8,614 bodies**.
`-PallowAddedRoots=true` explicitly permits/report additions; it never permits removal, changed
body/native coverage or reclassification of existing roots. Generic compatibility has 544 diagnostics;
the initial Worker boundary stage has 525 and the current Worker compatibility stage has **494**
(203 unique messages). No diagnostics are located directly in selected engine sources.

| Residual exclusion candidates | Initial Worker boundary | Current presentation boundary |
| --- | ---: | ---: |
| Linked classes | 441 | **76** |
| Used input methods inside excluded classes | 1,681 | **251** |
| Used input methods under all exclusion rules, including method-only rules | 1,863 | **320** |

Of the 76 classes, **56 have used methods and 20 are type-only**. The stage-26 increase includes
the isolated JVM Graphics2D raster backend (**one class / nine methods**); the focused TEXTS
entry still compiles without diagnostics. These candidates include enums,
rendering/output helpers and non-UI host dependencies, as well as remaining presentation paths;
they are not all removable widgets. The exclusion guard still fails. Fast analysis is conservative,
not a runtime trace. Some inferred witness paths can disagree with the transformed method model.
The full audit separately checks that `FilamentIndex.publish` has no entity-service read or
selection-event construction after transformation. Original-reference crossing tables include original
UI branches even after substitution; inspect modified methods and distinguish inferred edges.

Presentation-only entry points (display windows, check boards, trackers, UI-task impact overloads,
manual link/task construction and plot windows) explicitly reject desktop execution. The recognition
overload `RhythmsStep.impact(Collection)`, ledger geometry, grid recognition, MusicXML building and
image-difference processing remain rooted. The configuration and scale-source changes above are
the initial source boundaries; later memory, font and raster boundaries are documented below.
`buildSrc` remains unchanged.

`OctaveShiftInter.setGlyph` keeps its original setter and geometry instructions, obtaining the sheet
from `HeadlessSheetContext.requireSheet()` instead of the selected desktop tab. The host must bind
the actual job sheet before submitting work and clear it afterward. Queued tasks capture and restore
this sheet alongside MDC and the host sheet ID; missing context fails explicitly.

Small data/drawing dependencies are separated from window initialization at build time:

- The six Bravura beat-unit codes are mechanically derived from upstream Java sources. The metronome
  regex uses equivalent BMP `\\uXXXX` fragments because TeaVM rejects Java's `\\x{...}` form here.
  JVM checks compare positive and negated classes over every BMP character (131,072 comparisons).
- The template opacity is the same cached `SRC_OVER`/0.25 composite. The two stroke helpers are
  mechanically copied from the original **Java source**, without `UIUtil`'s window initialization.
  Source hashes and extraction guards detect upstream changes; this is not handwritten bytecode.
- `TemplateFactory.buildDecoratedImage` retains its original instruction order except these
  opacity/stroke references. Offscreen JVM checks cover stroke scaling and saved-stroke identity.
  Full font/template rendering in Wasm remains unverified.

`UIUtil` now initializes its original constants, logger and font ratio without constructing the
anonymous desktop window-closing listener. The compiler verifies that all other initializer
instructions remain in order, and application reads of `closeWindow` explicitly reject desktop use.
Both precise and fast compiler-model checks confirm that configuration/font initialization remains.

`verifyHeadlessProbe` runs shared serial-scheduling checks on the JVM and compiles real `WellKnowns`,
`OmrExecutors`, logging and progress API calls into Wasm. Node loads two module instances; Chromium
runs two dedicated Workers. Closing one instance leaves the other operational. Tests cover resource
URI resolution, FIFO/nested futures, errors, cancellation, zero-budget timeouts, shutdown/restart,
task context restoration and GUI guard pruning that preserves a fixture's engine work.
Additional checks use the original `Constant`/`ConstantSet`, inject a font ratio of 1.5 into real
`UIUtil`, exercise `BookManager` output overrides/updates, verify per-Worker run options and reject
invalid numeric initialization with its cause. The shared JVM tests cover snapshot isolation,
priority/freeze behavior and pending-set iteration; compiler-model tests compare generated unit
names with JVM class metadata. These checks do not
validate real sheet recognition or all rewritten model methods. The expanded headless probe is
**481,678 bytes**; it also exercises configuration, constant-set metadata, regex and template data.
Stage 3's smaller probe was 383,077 bytes. These are test-program sizes, not engine size estimates.
Review snapshots are in [headless-results](headless-results); raw graphs/probes are archived by stage
under `wsl-artifacts/wasm-headless`. Stage 1 records the initial Worker boundary; stage 2 records
the presentation changes (80 classes / 249 class-excluded methods / 320 methods across all rules).
Stage 3 separates the window-listener initializer (79 classes / 247 class-excluded methods / 318
methods across all rules). Stage 4 adds the configuration/output-path boundary and the small
source changes above, reaching **76 / 242 / 313**. The 9,001 selected input methods still match;
the new namespace hook is dependency-only and compiler-generated overrides are not forced roots.
Stage 5 adds the pixel-processing boundary below. The broad audit totals remain **76 / 242 / 313**
and **524 diagnostics / 216 unique messages**; independently compiled numerical probes verify real
execution paths rather than treating the broad fast graph as proof of runtime compatibility.
Stage 6 separates scale input from `Sheet`, expands coverage to **9,019 roots / 8,598 bodies**,
and retains the same exclusion/diagnostic totals. The original 9,001-root subset is preserved.
Stage 7 adds single-lane ordered Map/Set replacements, reaching **495 diagnostics / 204 unique
messages**, with the same **9,019 / 8,598** coverage and **76 / 242 / 313** exclusion totals.
Stage 8 adds the memory-model startup path and its new factory root (**9,020 / 8,599**); diagnostics and exclusion totals remain unchanged.
Use `dev/teavm-audit/export_headless.py` with the Windows checkout, Linux build directory and tool SHA
to export the comparison against the archived stage-5 generic audit. Add `--stage 2` to compare
against headless stage 1; `--stage 4` compares against headless stage 3. CPU usage remains capped at eight logical CPUs and memory at 8 GiB,
with no swap. These changes do not yet produce a complete runnable recognition engine.

### Pixel input verification

`GrayInput.copyOf(width, height, pixels)` accepts packed unsigned 8-bit grayscale rows (0 black,
255 white). It validates dimensions before allocating a copy. `takeProcessor()` transfers that
owned copy to the original ImageJ `ByteProcessor` once, without another copy; input reuse fails.
`takeOwnership` instead accepts a buffer whose caller relinquishes all references. The probe's
JavaScript bridge uses `Int8Array.copyToJavaArray()` / `copyFromJavaArray()` for bulk conversion,
then transfers that owned Java array without another copy. `GrayPreprocessor.global` and `adaptive`
call the original algorithms with explicit thresholds/coefficients. Invalid options do not consume
the input. File decoding/PDF rendering and production Worker job messaging remain outside this API.

The headless compiler changes only ImageJ 1.54p's `ImageProcessor` default-font initialization:
pixel construction does not initialize the desktop `IJ.font12`. Reads of an unset processor font
explicitly reject unsupported text rendering. Pixel math is not replaced. This does not supply
ImageJ's broader GUI/plugin APIs or a font backend.

`verifyPixelProbe` compares original algorithms with a JVM oracle in two Node modules and two
Chromium Workers: `GlobalFilter` (30 cases / 20,268 pixels), adaptive `VerticalFilter` and
`RandomFilter` (45 cases / 30,402 pixels), and horizontal/vertical run tables (30 cases). Tests cover
one-pixel-wide/high inputs, uniform black/white and varied pixels, threshold boundaries, run/pixel
round trips, input immutability, consume-once ownership and TypedArray subviews with nonzero offsets.

The supplied eight PNG/TIFF samples also match the JVM adaptive-binary reference **byte for byte
over 48,192,656 pixels**, in Node and a separate Chromium Worker. JVM-only fixture preparation uses
ImageIO and the original `Picture.adjustImageFormat` / gray-source conversion convention. Gray/binary
hashes and dimensions fix the comparison input; decoding does not run inside Wasm. TIFF preparation
uses the first image in each file. The probe is **154,071 bytes**, including the tested pixel and
run-table operations. The earlier global-only probe was 67,111 bytes; neither is an engine size estimate.
Stage 5 did not yet validate scale estimation. Stage 6 additionally verifies it as described below;
staff detection, OCR and MusicXML output remain unverified.

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
  'verifyPixelProbe', '-I', 'dev/teavm-audit/audit.init.gradle',
  '-Pcompat=true', '-Pheadless=true', '--console=plain')
```

Raw probe output is under `build/wasm-compat/pixel-probe` in the Linux mirror.
Add `-PpixelSamples=/mnt/g/work/20261029/1921music/score_samples` to the command to prepare and compare
the supplied samples. Omit it for the small synthetic suite. Review snapshots are in
[pixel-results](pixel-results), with large raw inputs/references outside Git under headless stage 5.
`export_headless.py --stage 5 --pixels` archives both audit and pixel verification results.

### Scale input and upstream comparison

`ScaleBuilder.Source` supplies vertical binary runs, optional interline/beam specifications,
an existing scale, page identity/width and invalid-page decisions. The existing `ScaleBuilder(Sheet)`
constructor delegates through a sheet adapter. `RunScaleSource` provides the same data/decisions
for a standalone page, without constructing a `Sheet`/`Book` or loading images. The numerical
histograms and estimation formulas remain in the original builder. One collection operation now
removes a less-significant tail using `subList(i, size).clear()` rather than `retainAll` with a live
prefix view; the latter raised `ConcurrentModificationException` in TeaVM on real score data.

`prepareUpstreamScale` extracts **unmodified `ScaleBuilder.java` from
04697c74fae431ef8436a9429386957a5cb25993**, and `compileUpstreamScale` builds it separately.
An isolated JVM loads those classes first; the oracle asserts their code-source directory.
A second JVM runs the refactored sheet adapter and compares results. The shared model/runtime
is the current checkout; this is an independent scale-algorithm reference, not a complete pristine
upstream application build. The launcher supplies the Windows Git source through
`AUDIVERIS_SOURCE_ROOT`; a standalone build can pass `-PupstreamGitRoot=...`.

Node and Chromium Workers match this reference for all eight supplied pages, both from the binary
input and directly from gray input through adaptive binarization/runs/scale in one Wasm call.
The five TIFF pages yield interline **21**, line thickness **4** and beam thickness **10**;
the three PNGs are rejected by upstream's default resolution check (interlines 6, 9 and 6).
The refactor preserves these decisions and invalid flags. Four synthetic cases also cover regular
staves, interline/beam overrides, a blank page and preservation of a supplied scale object.
The combined verification module is **401,767 bytes**; this is not an engine size estimate.

Add `-PpixelScale=true` alongside `-PpixelSamples=...` to `verifyPixelProbe` to reproduce the separate
JVM comparisons and Wasm checks. The scale strings are verification output, not a production Worker
message schema. Source/class hashes and oracle results are in [pixel-results](pixel-results).
Archive stage 6 with `export_headless.py --stage 6 --pixels --allow-added-roots`.

### Generic compatibility reproduction

Run from the Windows repository using the existing WSL launcher. It synchronizes sources to the
Linux mirror and enforces the shared limit of eight logical CPUs and 8 GiB RAM, with no swap.

```powershell
# Baseline: original selected roots, no compatibility layer.
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
  'traceTeaVMExclusions', '-I', 'dev/teavm-audit/audit.init.gradle',
  '-PauditScope=selected', '--console=plain')

# Same roots, compatibility enabled; includes JVM and Node/Wasm verification.
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
  'traceTeaVMExclusions', 'compareWasmCompatibilityAudit',
  '-I', 'dev/teavm-audit/audit.init.gradle',
  '-PauditScope=selected', '-Pcompat=true', '--console=plain')
```

For browser verification, install the locked development dependency in the WSL mirror and its
Chromium runtime once, using the capped launcher:

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Run -CommandArgs @('bash', '-lc',
  'cd ~/work/web_audiveris/wasm-compat && npm ci && npx playwright install chromium')
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
  'verifyWasmCompatibilityBrowser', '-I', 'dev/teavm-audit/audit.init.gradle',
  '--console=plain')
```

The compatibility-only verification task does not require a full application audit. Raw outputs
are under `build/wasm-compat/probe`; audit outputs are under
`build/teavm-audit/fast-selected-compat`. The baseline is kept separately in `fast-selected`.

## Verified result

The current audit covers **9,091 methods**, including **8,653 method bodies**. Every original
**9,001-method / 8,588-body** root is preserved. Earlier memory/raster/configuration/template
boundaries add 53 methods. The text-font facade, provider and measurement record plus music-word
constructor/factory add another **26 methods / 19 bodies**. The JVM-only Java2D default provider
and holder are classified as excluded; the recognition-facing provider API remains selected.
Stage 26 adds the cleanup raster API and entry constructors: **11 roots / three bodies**.
The JVM-only Graphics2D raster backend is excluded from forced roots, while the original cleaner
traversal and its recognition-facing raster API remain selected.
With generic compatibility and the opt-in Worker/presentation substitutions enabled, diagnostics
fall from **1,276 to 494** (782 fewer); unique messages fall from **336 to 203**.
The stages are **1,276 → 789 → 702 → 609 → 545 → 544 → 525 → 524 → 495 → 494**. The 544/220 result is the
generic compatibility layer alone; 525/217 is the initial Worker boundary, 524/216 is the
stage-6 boundary, 495/204 is the stage-7–9 result, and **494/203** is the current stage-26 result (unchanged from stage 10).
Remaining exclusion candidates are **76 classes / 251 used input methods inside those classes**,
or **320 methods** when method-only exclusion rules are also included. Of the 76 classes, **56**
have used methods and **20** are type-only links.
There are no remaining missing-API messages for the generic layer's targeted APIs. The broad fast
analysis also exposes additional unsupported dependency paths; generic-layer new messages and their
callers are recorded in `results/new-diagnostics.tsv`, and current headless results in
`headless-results`. These are
dependency-analysis results, not a successful full-engine
Wasm build or proof that every selected method executes correctly.

- JVM oracle: **653,763 checks**, comparing the replacements with the standard JDK, including
  randomized and edge-case inputs.
- A probe compiled from code that imports the original standard Java API names executes under
  Wasm GC in Node and Chromium **158.0.8127.18**. Its 4,096 numeric values match the JVM exactly,
  along with sorted-set, string and path result arrays; 79 standard-API cases, 640 byte-array cases,
  322 numeric-parser cases, UUID/collection/reflection cases, XML/resource payloads and captured
  stack output also match. Chromium Web Worker checks exercise XML, resources, crypto-backed UUIDs
  and the GC fence without DOMParser. Module/resource-bundle, logger and context-classloader
  fixtures also match in Node, Chromium and its Worker. The JVM fixture uses Jackson's
  original multi-release JAR; Wasm uses the selected unchanged base entries.
- The expanded probe Wasm is **768,055 bytes**, including XML/resources and Jackson's large-number parsers.
  This is a compatibility test program, not an engine
  size estimate. No engine throughput claim is made from these tests.
- The generic stage-5 snapshot used unchanged `app` and `buildSrc` sources from baseline
  `04697c74fae431ef8436a9429386957a5cb25993`. Headless stages 4–5 modify only `Constant.java`,
  `ConstantSet.java` and `BookManager.java`; stage 6 also changes `ScaleBuilder.java`. Stage 8 adds
  the model/configuration seams described below. All changed/added application Java sources are
  discovered against the baseline and hashed in provenance.
  Their hashes are recorded in headless provenance. The capped runs recorded
  no OOM or hard memory-limit hits.

Reviewable snapshots are in [results](results); bulky audit graphs and runnable probe artifacts
are kept outside Git in `wsl-artifacts/wasm-compat/stage5`. The original stage's artifacts remain
in `wsl-artifacts/wasm-compat/audit` and `probe`; its comparison is retained in
`results/stage1-comparison.json`. Each `results/stageN-comparison.json` compares that stage directly
with its predecessor. `dev/teavm-audit/export_compat.py --stage N` packages reports and provenance.

## Worker ordered indexes

With `-Pheadless=true`, standard `ConcurrentSkipListMap` / `ConcurrentSkipListSet` references resolve to a
TreeMap-backed implementation for the single Java execution lane in each module. This lets
`BasicIndex` and `Picture` retain their original source and declared map types without linking
the JDK skip-list's `VarHandle` implementation. Separate Workers still own separate maps;
this does not implement shared-memory Java threads. Map and Set are replaced together because
the original JDK Set calls private skip-list Map methods and nested implementation classes.

Range and descending views remain live. The replacement manages bounds itself: delegating
view operations to TeaVM 0.16.0's TreeMap did not match the JDK in the operation-stream oracle.
Iterators use successive key lookups, permit changes during traversal and support removal
without copying the whole map. Lookup/update is O(log n), and a full traversal is O(n log n).
TODO: measure index traversal on recognition workloads before choosing a faster representation.

`verifyOrderedMapProbe` compares 24,000 deterministic randomized Map operations, 10,000 Set
operations, both comparator directions, 440 range-boundary cases, conditional updates, live
views, Set streams and exception cases with the JDK.
It also runs the unchanged `BasicIndex` registration, navigation, removal, reset and live-collection
paths. The standard-API caller is checked in Node module instances and Chromium Workers.
This is coverage of the exercised single-lane APIs, not a claim of complete concurrent-JDK behavior.
The component probe is **117,991 bytes**; it is not a full-engine size estimate. Small reports are
in `ordered-map-results`; archive stage 7 with `export_headless.py --stage 7 --ordered-maps`.

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
  'verifyOrderedMapProbe', '-I', 'dev/teavm-audit/audit.init.gradle',
  '-Pcompat=true', '-Pheadless=true', '--console=plain')
```

### Memory model and font-family configuration

Stage 8 makes a few source-level seams instead of cloning model initialization in the compiler:

- `FontFamilyDefaults` owns the existing `MusicFont.Constants` and `TextFont.Constants` instances.
  Both font APIs keep aliases to the same parameter objects; original keys and typed constants
  remain intact. `Params.BookParams` can now establish parent settings without initializing AWT
  fonts. Only package access to the two existing constant sets/family fields is widened.
- `Book.createMemoryBook(radix)` initializes the normal parameters and model identity without
  input-file alias loading or archived-sheet migration. The existing file constructor is retained.
- The existing `Sheet(SheetStub)` constructor is public, so a model can be constructed before pixels
  are attached; its initialization body is unchanged.

Two narrow compiler adaptations complete this startup path. `Picture.convertOldTables` preserves
null/empty legacy-table cleanup but explicitly rejects actual old `.omr` table data; old-project
migration is outside this Worker entrypoint. Book/SheetStub lock construction uses `SerialLock`,
which preserves reentry, ownership and balanced release in one execution lane. Wrong-owner
unlock fails; contended blocking and Condition waits are explicitly unsupported. No shared-memory
Java locking is claimed. JVM checks compare reentry/interruption with the JDK and verify rejection
of wrong-owner release and unsupported contention.

`verifyFontDefaultsProbe` checks original namespaces, overrides, source reset, full book-parameter
inheritance and separate module/Worker settings. JVM checks also verify identity of the old font
API aliases. `verifySheetModelProbe` compares the memory book with the original file-backed model
initialization, then runs the original Sheet/GlyphIndex/InterIndex/StaffManager/LagManager setup in
Node and Chromium Workers. Its model component is **779,348 bytes**, and the font-configuration
component is **521,679 bytes**. Neither is a full recognition engine or engine size estimate.

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
  'verifyFontDefaultsProbe', 'verifySheetModelProbe',
  '-I', 'dev/teavm-audit/audit.init.gradle', '-Pcompat=true', '-Pheadless=true', '--console=plain')
```

Small reports are in `model-results`; archive with `export_headless.py --stage 8 --models
--allow-added-roots`. Pixels and staff detection are not yet attached to this model probe.
The exploratory initial 1 GiB compiler heap was insufficient before separating initialization;
`buildSheetModelProbe` uses a 5 GiB heap inside the same overall 8 GiB cgroup limit. That heap
failure is separate from the archived stage-6 runs.

### Raw pixels in the original Sheet

Stage 9 adds `SheetStub.createMemorySheet(gray, binary)` and `PixelPicture`: the caller gives up
ownership of its ImageJ buffers, an optional gray buffer must match binary dimensions, and binary
pixels must be 0/255. The stub is installed only after successful initialization and is marked
LOAD/BINARY done. A duplicate installation is rejected. The original Sheet indexes and managers
remain in use. A protected Picture geometry constructor and a raw-pixel Sheet constructor support
this path; the original image/file constructors remain available on the JVM.

Worker `getSheet`/`getPicture` substitutions require already supplied data and reject an unbound
page; they do not fall back to image decoding or `.omr` loading. Book's nullable parameter dialog
and browser fields use the existing presentation-service cut. Page invalidation and score-model
updates are preserved.

`verifyPixelSheetProbe` runs the original `ScaleBuilder(Sheet)` path on all eight supplied samples,
compares with the pinned unmodified upstream scale oracle, and checks Node and Chromium Workers.
All **48,192,656 binary pixels** are supplied, with input hashes and transfer ownership checked.
Five pages succeed and the same three low-resolution pages are rejected. The component probe is
**870,360 bytes**. This verifies scale through the actual model, not staff detection or a full engine.

`PixelPicture` currently supplies GRAY/BINARY sources and a vertical BINARY RunTable. Derived
sources (NO_STAFF/MEDIAN/GAUSSIAN), replacing tables, and encoded images explicitly throw until
their backend is implemented; the original Picture implementations remain in the codebase.
`Picture.setTable` becomes overridable so the pixel backend can enforce this boundary. TODO:
extend these capabilities as the corresponding recognition steps are exercised.

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
  'verifyPixelSheetProbe', '-I', 'dev/teavm-audit/audit.init.gradle',
  '-Pcompat=true', '-Pheadless=true',
  '-PpixelSamples=/mnt/g/work/20261029/1921music/score_samples', '--console=plain')
```

Stage 9 preserves all previous roots and adds nine pixel-model methods (**9,029 roots / 8,608
bodies**). Diagnostics remain **495 / 204 unique**. Residual candidates are **75 classes / 242
class-excluded used input methods / 311 methods across all rules**. Small reports are in
`pixel-sheet-results`; archive with `export_headless.py --stage 9 --pixel-sheet --allow-added-roots`.

## Horizontal staff retrieval and rounding correctness (stage 10)

The original horizontal staff-retrieval algorithms now execute in Wasm, including a single call
from packed gray pixels through adaptive binarization, scale estimation and staff retrieval.
Five accepted sample pages produce **8 / 10 / 10 / 10 / 10 staves** (48 total); the other three
retain the original resolution rejection. Binary and gray paths, a synthetic two-staff fixture,
Node and two Chromium Workers compare **4,181 numbers with maximum difference 0** against JVM
results. The verifier allows 1e-8 pixels and 1e-12 slope, but observes exact equality.

This exposed a correctness defect in TeaVM 0.16.0's existing `Math.rint` and `Math.round`:
16 of 25 selected edge inputs differ from the JDK in an unpatched Wasm baseline. Compatibility
substitutions preserve ties, signed zero, NaN, infinities and saturation. `rint` compiles to
Wasm `f64.nearest`; its emitted function body is checked. The JVM oracle performs **233,556
checks**, while Wasm compares edge cases and an 8,192-input checksum in two Node instances and
two Chromium Workers. The focused corrected module is **19,228 bytes**, versus 19,155 bytes
for the stock baseline (+73 bytes for this test component, not an engine size estimate).

JGraphT's original supplier code remains intact apart from its constructor-access query, which
uses a helper scoped to that caller. Unknown same-package access cases fail explicitly.
Reflection metadata exposes only `StaffFilament(int)` and `StraightFilament(int)` constructors
for engine use; additional fixture metadata is confined to the test module. Supplier access,
fresh allocation and missing-constructor failures are compared with the JDK.

No application sources change in this stage. The oracle uses the pinned original ScaleBuilder
and unchanged grid, dynamic-filament, lag, math and run sources, with the current shared model
runtime; it is not an entirely pristine upstream application. Original image-backed and raw-pixel
JVM paths also match. The staff probe is **1,182,981 bytes**, including test fixtures.
Barline/system completion, OCR, symbol recognition and MusicXML output remain to be verified.

Reproduce through the capped WSL launcher with Gradle tasks `verifyStaffProbe`,
`verifyMathRoundingProbe` and `compareMathRoundingBaseline`, the audit init script and
`-Pcompat=true -Pheadless=true -PpixelSamples=/mnt/g/work/20261029/1921music/score_samples`.
Archive with `export_headless.py --stage 10 --rounding --staff`. Small reports live in
`rounding-results` and `staff-results`; raw modules and inputs live under
`wsl-artifacts/wasm-headless/stage10`. The audit still covers **9,029 roots / 8,608 bodies**,
with **75 classes / 242 class-excluded used methods / 311 methods across all exclusion rules**.

## Barline and system retrieval (stage 11)

The complete `GridBuilder` algorithm now executes after scale estimation: staff retrieval,
short-section inclusion, barline/system construction and staff-line completion. Five accepted
sample pages contain **4 / 5 / 5 / 5 / 5 systems** and **52 / 68 / 68 / 68 / 72 staff barlines**;
the three original resolution rejections remain. Binary and gray input paths compare staff
endpoints, thickness, nine interior spline samples, system membership/bounds, part counts and
barline medians/grades. **24,112 numeric comparisons have maximum difference 0** in Node and
Chromium Workers, including the font-geometry fixtures below.

Bracket recognition needs serif dimensions from the selected music font. `BracketInter` now
separates its existing area-construction body into a protected overload accepting measured
rectangles. Its JVM font path is unchanged. The Worker substitution changes only font acquisition:
`HeadlessBracketMetrics` accepts explicit measurements keyed by family and interline, freezes on
first lookup, rejects invalid/duplicate/late registration and fails for missing measurements.
There is no fixed interline range or scaled approximation. Measurements and state are isolated
per Wasm instance. A production host font-measurement backend is still required; the test host
supplies measurements generated by the original JVM fonts for the requested interlines.

The oracle puts unmodified `ScaleBuilder` and `BracketInter` from commit `04697c74` first on its
classpath and verifies both class origins. **144 bracket cases** cover all six music families,
two input-derived interlines, all four bracket kinds and three slopes; area bounds and sampled
containment match. Grid/dynamic-filament/lag/math/run source algorithms remain unchanged.
The raw-image JVM path also matches the original image-backed path. These references share the
current model runtime, rather than representing an entirely pristine upstream application.

The focused Wasm module is **1,548,544 bytes**, including test fixtures; this is not a full-engine
size estimate. `GridStep` also performs staff-line removal and score updating, which this stage
does not yet exercise. OCR, symbol recognition and MusicXML remain outstanding.

Reproduce with `verifyGridProbe -I dev/teavm-audit/audit.init.gradle -Pcompat=true -Pheadless=true`
through the capped WSL launcher; add `-PpixelSamples=/mnt/g/work/20261029/1921music/score_samples`
when preparing fresh sample fixtures. Archive using `export_headless.py --stage 11 --grid
--allow-added-roots`. Reports are in `grid-results`; raw modules and inputs are under
`wsl-artifacts/wasm-headless/stage11`. The full audit covers **9,030 roots / 8,609 bodies**,
with **494 diagnostics / 203 unique messages** and **75 classes / 242 class-excluded used methods /
311 methods across all exclusion rules**. Every original root/body is preserved.

## Staff-line removal and score updating (stage 12)

The Worker probe now performs the recognition calls made by `GridStep`: `GridBuilder`,
`StaffLineCleaner.process()` and `Book.updateScores()`. `PixelPicture` lazily builds `NO_STAFF`
by cloning its binary buffer and erasing the same glyph-run rectangles as the original Java2D
renderer, clipping to image bounds. Horizontal runs use array fills; vertical runs use direct
pixel writes. The original binary input remains unchanged. The derived source is cached and
can be disposed/recomputed; exported pixel arrays are copies. Other derived sources and encoded
images still require their respective backends.

The JVM reference retains the original `Picture.buildNoStaffBuffer()` body, checked against the
pinned baseline at export. Original image-backed and raw-pixel paths match for the fixture and
all five accepted real pages, totaling **43,781,600 pixels** per input path. Node compares all
staff-free bytes for binary and gray inputs; Chromium Workers compare complete SHA-256 hashes.
The other three real inputs retain their original resolution rejection. A separate JVM comparison
covers **256 raster/clipping cases / 274,688 pixels**, both run orientations and extreme offsets.
Cache identity, invalidation/recomputation and output-copy isolation are checked as well.

Geometry, bracket cases and score counts compare **24,158 numbers with maximum difference 0**
in two Node instances and two Chromium Workers. Each accepted real page creates one in-memory
score, with the same system/part organization as the JVM path. As before, the oracle pins original
ScaleBuilder and BracketInter while sharing the current model runtime. The focused Wasm is
**1,575,109 bytes**, including fixtures and pixel-export/cache checks; this is not a full-engine
size estimate. OCR, headers, symbols, remaining recognition steps and MusicXML remain outstanding.

Reproduce with `verifyCleanGridProbe -I dev/teavm-audit/audit.init.gradle -Pcompat=true
-Pheadless=true` through the capped WSL launcher. Archive using `export_headless.py --stage 12
--clean-grid --allow-added-roots`. `clean-grid-results` holds small comparisons and staff-free
image hashes; raw images and modules are under `wsl-artifacts/wasm-headless/stage12`.
The full audit preserves all previous roots and covers **9,033 roots / 8,612 bodies**. Diagnostics
remain **494 / 203 unique**, with **75 classes / 242 class-excluded used methods / 311 methods
across all exclusion rules**. The source-erasure helper is named `eraseRuns` so the existing
name-based UI classifier recognizes it as an engine method; no root-selection rule changes.

During this stage, WSL stalled on an ext4 directory read. The user-authorized WSL-wide restart
restored builds. The relaunched jobs retain **8 logical CPUs / 8 GiB / no swap**, and verification
records resource-limit values and the new VM's memory-event counters.

## Classifier model loading and inference (stage 13)

The original `BasicClassifier`, connected-component extraction, glyph descriptors, normalization,
and neural forward/sigmoid computations now execute in Wasm. Only `BasicClassifier.load` is
substituted: the host explicitly installs a model archive before recognition, rather than relying
on JVM training directories. ZIP streams and directly constructed kxml2 parsers read the original
`model.xml`, `means.xml` and `stds.xml`; there is no reflective XML factory or JAXB at this boundary.
The bridge retains the original engine-label compatibility check. Installation publishes only
complete, structurally valid model data and freezes on first use. Invalid/truncated archives,
zero standard deviations and dimension mismatches fail; failed installation preserves prior data.

`NeuralNetwork` gains a constructor for supplied weights and a validating copy helper. Existing
inference and JAXB entry points remain unchanged. The constructor copies labels and matrices,
rejects non-finite weights, and preserves learning parameters and epoch metadata without random
initialization. The oracle compiles the original NeuralNetwork from commit `04697c74`, verifies
its class origin, and uses the original classifier/JAXB path. The portable JVM loader has **exact
weight and normalization hashes**, matching metadata (**110 / 149 / 149 cells, 160 epochs**).
Original classifier/features/moments sources and forward/run/sigmoid bodies are checked at export.

The test uses 164 selected components: four from the synthetic fixture and up to 32 from each of
five accepted real staff-free pages. It compares raw features, all 149 natural grades, the winning
shape and the unconditioned top three candidates, plus 128 deterministic neural-input vectors.
There are **62,392 portable-JVM comparisons and 162,880 Wasm comparisons** in two Node instances
and two Chromium Workers. Maximum numeric difference is **3.7471e-15**; winning/top-three classes
match. The verifier allows 2e-13 absolute error for grades and 1e-12 relative/scaled error for
features. Per-system CHECKED shape decisions and complete HEADERS processing remain unverified.

The original model is an external **396,100-byte ZIP**, supplied to each Worker with transferable
buffers. It is not embedded in generic compatibility resources or Wasm. The classifier probe is
**814,106 bytes**, including lifecycle/copy fixtures; this is not a full-engine estimate. The
latest local Node series measured roughly **127–137 ms** to install the model once per instance.
Timing details are diagnostic samples, not a throughput benchmark: kernel/page timings include
fixture JSON generation, and page calls also include connected components and features.
TODO: measure representative browser cold-start/allocation costs before considering a predecoded
binary asset; retain the original supplied double values in any such format.

Reproduce with `verifyClassifierProbe -I dev/teavm-audit/audit.init.gradle -Pcompat=true
-Pheadless=true` through the capped launcher. The task also prepares the stage-12 JVM staff-free fixtures; add
`-PpixelSamples=/mnt/g/work/20261029/1921music/score_samples` for a fresh sample build.
Archive using `export_headless.py --stage 13 --classifier --allow-added-roots`.
`classifier-results` contains small summaries, input hashes and provenance; full numeric matrices,
model/invalid-model assets and raw input images live under `wsl-artifacts/wasm-headless/stage13`.
The audit preserves all previous roots: **9,035 roots / 8,614 bodies**, **494 diagnostics / 203 unique**,
**75 classes / 242 class-excluded used methods / 309 methods across all exclusion rules**. The
last count drops by two classifier-training paths after explicit host model acquisition.

## Header recognition (stage 14)

`HeaderBuilder.processHeader` now executes after the original grid/staff-cleaning/score calls.
Clef, key and time recognition, classifier evaluation, candidate selection and header freezing
remain in the original engine. Recognition font queries use `SymbolGeometry.Provider`: the JVM
default still measures the original music fonts, while Wasm receives explicit host measurements.
Bounds, normalized centroid offsets and time dimensions are keyed by family, shape, interline
and numerator/denominator. Pitch offsets retain the upstream Bravura 200-point reference.
The test supplies 20 measurements (3,241 bytes) obtained from the JVM fonts. These are fixture
resources, not universal font constants or a complete browser font backend. Entries are immutable
and append-only; missing measurements fail explicitly, and an installed custom provider is honored.
The host must install additional measurements between page calls as needed.

Five font/raster callers gain source seams (`AbstractInter`, `ClefBuilder`, `TimeWholeInter`,
`TimeCustomInter`, `KeyRoi`). The independent JVM reference compiles those five original classes
from `04697c74`, checks their class origins, and uses the previously pinned scale/bracket/neural
references. Its results match the current JVM exactly. `KeyRoi` erases neighboring glyph runs
directly into its gray buffer using the same clipped raster helper as `PixelPicture`; ImageJ's
original buffered-image sharing and the Java2D clipping reference are checked separately.

Four fresh-buffer header COPY calls use a scoped raw-GRAY8 adapter, avoiding ImageJ's unrelated
float/display-LUT branches. Original ImageJ agrees for 512 cases / 334,446 pixels, including
nonbinary bytes and extreme offsets. Eight inline `retainAll(subList(0, n))` sites in the header
call chain snapshot the argument through a compiler substitution. This avoids TeaVM's iterator
invalidation while preserving prefix-membership semantics, including duplicate tail values.
The original recognition source is unchanged at these sites; 528 cases compare against the JDK
and run in each Wasm instance. Prefix limits still come from the engine's existing parameters.

All eight supplied images are checked as both binary input and adaptive-filtered GRAY8 input in
Node and two Chromium Workers. Five pages yield **48 clefs / 48 key signatures / 2 time signatures**;
three retain the original low-resolution rejection. Grid geometry, scores, header positions,
selected inter types/shapes, grades, bounds, fifths and time ratios agree with the pinned reference:
**25,928 numeric comparisons**, maximum difference **7.2165e-16** (grade tolerance 2e-13).
Separate instances and transferable image/model buffers are exercised without a DOM.
The probe is **1,996,919 bytes**, including test fixtures; the classifier ZIP remains an external
396,100-byte resource. This is not a final-engine size or throughput estimate.

Reproduce using `verifyHeaderProbe -I dev/teavm-audit/audit.init.gradle` through the capped launcher;
add `-PpixelSamples=/mnt/g/work/20261029/1921music/score_samples` to prepare fresh fixtures.
`-PprobeDebug=true` optionally emits TeaVM debug information; `audit.DecodeWasmStack` resolves
numeric stack addresses from that `.teadbg` file. Archive with
`export_headless.py --stage 14 --header --allow-added-roots`. Small reports and font profiles are
in `header-results`; full outputs, pinned classes, images and the model live in the ignored raw archive.
The full audit preserves all previous roots: **9,037 roots / 8,616 bodies**, **494 diagnostics / 203 unique**,
**75 classes / 242 class-excluded used methods / 309 methods across all exclusion rules**.
Of those 75 classes, 55 have used methods and 20 are linked only as types. These conservative
full-root counts are unchanged from stage 13; the HEADERS component itself has no compiler diagnostics.
STEM_SEEDS and later recognition, general browser font measurement/rasterization, OCR and complete
MusicXML output remain outstanding.

## Stem scaling and vertical seeds (stage 15)

The original `StemScaler.retrieveStemWidth`, `StemChecker` and `VerticalsBuilder` now execute
after HEADERS. Their source files are unchanged. The JVM reference compiles those three classes
from `04697c74`, verifies their class origins, and retains the earlier scale/header/bracket/neural
references. The portable JVM font-query seams produce the same results as that reference.

The first strict stem probe exposed 92 diagnostics through Java2D. Investigation found that
the pinned ImageJ 1.54p `ByteProcessor.getBufferedImage()` exports a **detached copy**. Upstream
`StemScaler.getBuffer` paints that copy, then returns the original NO_STAFF processor without
reading the painted pixels back. This differs from `new ByteProcessor(bufferedImage)`, which
shares that image's raster. Both directions of each relationship are tested against ImageJ.
All **43,781,600 recognition pixels** across five real pages and the synthetic fixture remain
unchanged after the original JVM stem preparation.

Only `StemScaler.getBuffer` is substituted to preserve that current behavior. It returns the
NO_STAFF processor and avoids unused debug drawing. The compiler checks the expected source,
image-copy/graphics calls and returned-buffer identity, including local variable copies.
`saveStemImage` is read from the original `Constant.Boolean` at runtime; requesting it fails
explicitly until a host debug raster backend exists. It is not silently disabled or hard-coded.
An independent Wasm instance verifies the configured true case. Other PageCleaner callers and
rendering algorithms are retained.

TODO: revisit this version-specific boundary if upstream begins copying the painted image back.
That change would require a real raster backend and a new JVM baseline. Optional JVM graphics
recording (`testStemTraceJvm`) confirms that observation preserves both outputs and pixels.
`compare-stem-canvas.cjs` and `testStemCenterJvm` are research tools that apply otherwise detached
drawing to recognition pixels; their reported differences are **not Canvas precision measurements**
and neither is the engine's rendering backend.

The strict stem component now has **zero diagnostics**. Across Node and two Chromium Workers,
the eight images are checked as binary and adaptive-filtered GRAY8 input. Five pages yield
**1,136 seeds** (190 / 200 / 219 / 285 / 242), with stem main/max thickness **4 / 4** on each page.
Three low-resolution inputs retain the original rejection. Header decisions, stem scales, seed
positions/sizes/weights and run-table fingerprints match the pinned JVM: **57,840 numeric
comparisons**, maximum difference **7.2165e-16**. In addition, **175,126,400 pixel bytes** match
exactly, including Worker SHA-256 checks. The existing 20 host font measurements suffice.
The probe is **2,067,995 bytes**; the model remains external. These component sizes do not estimate
the eventual complete engine, and the tests do not establish throughput.
The full-root audit remains **9,037 roots / 8,616 bodies**, **494 diagnostics / 203 unique**.
Remaining candidates are **75 classes / 242 class-excluded used methods / 308 methods across
all exclusion rules**. The last count falls by one detached debug-image path; class counts remain unchanged.

Reproduce with `verifyStemProbe -I dev/teavm-audit/audit.init.gradle` through the capped launcher.
Use `-PpixelSamples=/mnt/g/work/20261029/1921music/score_samples` for fresh input preparation.
Archive using `export_headless.py --stage 15 --stem --allow-added-roots`. Small summaries and
reference hashes are in `stem-results`; full outputs, images and optional raster research live
in the ignored stage-15 archive. BEAMS and subsequent recognition, browser font/raster backends,
OCR and complete MusicXML output remain outstanding.

## Beam recognition and head-spot preparation (stage 16)

The original median/Gaussian filters, morphological closing, connected-component extraction,
beam/hook recognition, multiple-rest processing and black-head statistics now run after STEM_SEEDS.
The strict beam probe drops from 92 diagnostics to zero. `java.awt.image.Kernel` uses a data-only
substitution with the same coefficient copying, origins, destination-array and clone behavior;
**2,507 JVM conformance checks** cover random float bits, invalid/overflow dimensions and subclass
cloning. Original API names also exercise that contract inside the Wasm instances.

`BlackHeadSizer` changes only its two-line font acquisition. `SymbolGeometry` retains the original
`MusicFont.computePointSize` interpolation and requests glyph widths explicitly in **point-size
units**, separately from interline-based bounds. The default JVM provider measures the same fonts;
the host supplies immutable widths in Wasm. The original and new calculations match for **774
widths across six families**. Actual pages use seven extra width records and estimate music point
sizes **90 / 92 / 92 / 91 / 91**, matching the upstream reference. These measurements remain
external fixture resources, and a general browser font backend remains to be implemented.

`PixelPicture` stores a HEAD_SPOTS run table in memory for later recognition, preserving the owned
input dimensions. Binary-table replacement and encoded-image output remain separate capabilities.
One fresh full-frame gray snapshot call and the spot-preview output calls receive scoped compiler
adaptations. Recognition filtering/thresholds are preserved; debug output requests fail explicitly.
The headless spot-preparation catch now propagates failure instead of quietly returning a partial
result. The original GUI/JVM behavior is unchanged.

`BeamBeamRelation()` joins the two existing filament constructors in the explicit reflection
metadata; no general classpath scan is introduced. A missing constructor initially produced ERROR
logs while shapes were still returned. The verifier now rejects those logs and checks **all graph
relations**, not just shapes. The JVM reference pins seven original beam classes, along with the
earlier reference classes, checks their origins, and rejects swallowed exceptions or ERROR logs.
The final relation inventory includes **13 BeamBeamRelation edges** among 813 relations on the
five accepted real pages. Beam-only free glyphs are removed at the original epilog; glyphs that
also belong to other groups are retained as upstream requires.

Binary and adaptive-filtered GRAY8 inputs agree in Node and two Chromium Workers. Five pages yield
**69 BeamInter / 20 BeamHookInter** objects (22 / 14 / 10 / 27 / 16 per page); three preserve the
original low-resolution rejection. Header/seed results, beam geometry, grades, head-size statistics,
music scaling and relation endpoints/ratios match: **72,284 numeric comparisons**, maximum
difference **7.2165e-16**. Head-spot rasters match for **175,126,400 pixel bytes**, including Worker
SHA-256 checks. Independent instances verify both stem-debug and spot-preview configuration errors.
The probe is **2,232,168 bytes** with an external 396,100-byte classifier model. No complete-engine
size or throughput claim follows from these component measurements.
The complete audit retains **9,037 roots / 8,616 bodies**, with **494 diagnostics / 203 unique**.
Remaining candidates are **75 classes / 242 class-excluded used methods / 308 methods across
all exclusion rules**, unchanged from stage 15; the beam component itself compiles without diagnostics.

Reproduce with `verifyBeamProbe -I dev/teavm-audit/audit.init.gradle` through the capped launcher;
add `-PpixelSamples=/mnt/g/work/20261029/1921music/score_samples` for fresh fixtures. Archive with
`export_headless.py --stage 16 --beam --allow-added-roots`. Small evidence lives in `beam-results`;
full matrices, relation lists, intermediate images and pinned classes live in the ignored raw
archive. The stem/beam verifiers accept both live build folders and the exported artifact folders.
At this stage LEDGERS and later recognition, general font/raster backends, OCR and complete
MusicXML remained outstanding; stage 17 extends the comparison below.

## Ledger recognition (stage 17)

The strict ledger probe continues through the original `LedgersFilter.process`,
`LedgersBuilder.buildLedgers` and `LedgersPostAnalysis.process`, including candidate checks,
population-based rejection, rebuilding affected systems and `Staff.buildAllLedgerLines`.
The JVM reference compiles four original ledger classes from the pinned application baseline
and checks their loaded origins. The portable JVM run uses the current application classes.

One application change replaces eager `String.format` in a debug message with SLF4J arguments.
An empty ledger population leaves nullable thresholds: the JDK formats these as `null`, while
TeaVM throws on the `%f` conversion even when debug logging is disabled. Only log presentation
changes; candidate thresholds and recognition calculations are retained. Export guards check
this exact logging edit and reject other post-analysis source changes.

The Worker-only compiler adaptation changes the ledger builder's catch from logging a warning
and continuing with partial results to propagating the failure. JVM reference verification and
Wasm verification also reject captured ERROR logs. The other ledger recognition source remains
unchanged; no additional geometry, font or reflection adapter is needed for this stage.

Five accepted real pages yield **288 ledgers** (38 / 42 / 53 / 79 / 76). Besides their staff,
index, bounds, grade and glyph weight, the probe samples **2,592 pitch positions** above and below
the staves to check the derived external ledger-line geometry. Three low-resolution inputs
retain their upstream rejection. Binary and adaptive GRAY8 inputs are compared in Node and two
independent Chromium Workers, alongside the preceding header, stem, beam and head-spot results.
There are **93,452 numeric comparisons**, with maximum difference **7.2165e-16**, and
**175,126,400 head-spot pixel bytes** agree. The strict component has no diagnostics and produces
a **2,295,959-byte Wasm** with the same external classifier and font measurements as stage 16.

Reproduce with `verifyLedgerProbe -I dev/teavm-audit/audit.init.gradle` through the capped launcher.
Archive with `export_headless.py --stage 17 --ledger --allow-added-roots`; small reports live in
`ledger-results`, while full matrices, pixels and pinned classes remain in the ignored raw
archive. HEADS and subsequent recognition, general font/template backends, OCR and complete
MusicXML remain outstanding.

## Raw distance preparation (stage 18)

`DistancesBuilder.buildDistances` now extracts only its initial raster acquisition into a
protected `erasedBuffer` boundary. The default JVM implementation still exports an ImageJ image,
paints it with the original `ImagePainter`, and wraps the result in a `ByteProcessor`. The
headless compiler selects `erasedRawBuffer`, which clones the binary pixels and uses integer
run rectangles and clipped single-pixel erasure. Both paths call the same original `paintLines`.
The Chamfer transform and the second pass that neutralizes table cells remain unchanged.

Unlike the earlier stem buffer, this code constructs a new processor from the painted image:
the erasure affects the recognition distances even though ImageJ exports a detached image.
The original binary input remains unchanged. The original `ImagePainter` and `TablePainter`
remain available, and source guards retain all original method roots and compare their bodies.

The reference loads the original `DistancesBuilder` from the pinned upstream commit. A separate
portable JVM run compares the current native and raw paths cell by cell, then the verifier checks
the entire serialized short table against upstream. Node compares every table byte; independent
Chromium Workers compare SHA-256 hashes. The ledger/header/beam/seed comparisons and head-spot
pixel checks are also retained. This verifies HEADS preparation only; template resources and
actual note-head recognition remain outstanding.

The original and raw JVM paths agree on **43,781,600 cells**. Node and the two Workers compare
**350,252,800 distance-table bytes** and **175,126,400 head-spot pixel bytes**. All **93,620 numeric
comparisons** pass, with maximum difference **7.2165e-16**. The strict component compiles without
diagnostics; its Wasm is **2,306,285 bytes**, with the same external classifier and font profiles.
The full audit retains all original roots and now selects **9,040 methods / 8,619 bodies**.
It still reports **494 diagnostics / 203 unique messages** and **75 classes / 242 class-excluded
used methods**. Across all method-exclusion rules the count is **310**: the two added
`RawPainter.paintGlyph` / `paintPixel` methods match the existing `paint*` UI-name heuristic,
but are retained and executed as recognition raster operations. They are not missing UI cuts.

Reproduce with `verifyDistanceProbe -I dev/teavm-audit/audit.init.gradle` through the capped launcher.
Archive using `export_headless.py --stage 18 --distance --allow-added-roots`.
`buildHeadsBaselineProbe` separately invokes the unchanged HEADS substeps and currently fails
strict compilation with **94 diagnostics**. Representative paths reach native font/AWT/image
code through template generation, and JAXB through `DrumSet` loading. This is a diagnostic
baseline, not a successful HEADS build; its full paths are retained in the raw archive.

## Drum configuration (stage 19)

`DrumSet` separates XML decoding and URI opening into two small methods. Its JVM defaults still
use JAXB and the original resource/user-file order. Worker compilation replaces only these two
methods: kxml2 constructs the existing XML data objects, then the original `loadConfiguration`
performs all additions, replacements and removals. The DTOs and three existing attribute adapters
are accessible to the backend, avoiding reflective construction. Export guards compare the
original merge body, transport loop, attribute adapters and mapping methods against the pinned
upstream source; the bundled `drum-set.xml` is unchanged.

The optional user overlay is copied into each Wasm instance before use and frozen when loading
starts. The standard definition uses the existing resource URL backend. With no overlay the
user-file read reports absence, retaining the original optional-file behavior. A later job with
different drum configuration uses a fresh instance. No shared mutable mapping is introduced
between Workers.

**230 XML cases** match pinned JAXB 2.3.1 and the current JVM implementation, including unknown
elements/attributes, namespaced input, nullable/invalid enum values and XML integer edge cases.
The original XML integer conversion accepts embedded whitespace/signs and wraps overflow;
the small decoder retains those behaviors rather than substituting `Integer.parseInt`.
Node and two Chromium Workers perform **920 matching decodings** and compare complete default
and overridden maps, including replacement, duplicate entries and removal tombstones. Malformed
documents are rejected; overlay ownership, freezing, transfer and instance isolation are checked.
Both the source default for drum-set dumping and an explicit override are exercised.
The strict drum component has no diagnostics and is **511,232 bytes**; this is not a complete
engine-size estimate.

The full audit retains every original root and selects **9,042 methods / 8,621 bodies**.
Its counts remain **494 diagnostics / 203 unique messages**, and **75 classes / 242 class-excluded
used methods / 310 methods across all exclusion rules**. The focused HEADS diagnostics fall
from **94 to 92**; JAXB owner diagnostics disappear from that component. Native font/template/image
dependencies remain, and complete HEADS recognition is still unverified.

Reproduce using `verifyDrumProbe` and `buildHeadsBaselineProbe` through the capped launcher.
The latter intentionally fails until the remaining HEADS boundaries are implemented. Archive with
`export_headless.py --stage 19 --drum --allow-added-roots`; small reports live in `drum-results`.

## Font-derived template resources (stage 20)

`TemplateFactory.Source` supplies template dimensions, slim bounds, anchors and matching key
points. The JVM default calls the original `Builder`; its rasterization, hole processing,
distance transform, reduction and anchor algorithms are unchanged. Worker compilation replaces
only the default source with an immutable catalog backend. The original `Template` source,
including weighted matching and foreground extraction, is unchanged. An explicitly installed
source is honored; source replacement is rejected after catalogs are in use.

Catalogs are external binary resources keyed by family and point size, with a versioned format
and the seven factory parameters that affect their contents. Installation validates a complete
catalog before publication, copies data into owned records, rejects duplicate/trailing/malformed
resources, and retains explicit null entries for symbols unsupported by a font. Lookup rejects
parameter mismatches and missing resources; preview output requires a host image backend.
The tested families and sizes are fixture choices, not limits on the runtime source API.

`Staff.getHeadPointSize` delegates to the font-free `FontFamilyDefaults` calculation. The same
original music-font constants and scaling rules remain active; **15,760 JVM comparisons** cover
scale interpolation, fractional interlines and head-ratio overrides.

Foreground dilation exposed ImageJ LUT/global-preference initialization. Only the single
`ByteProcessor.dilate()` call on Template's freshly created, white-filled binary buffer is
adapted. Compiler guards verify buffer construction, fill value and receiver identity. Its 3x3
minimum matches native ImageJ for **2,048 random/edge cases**, three iterations each and
**1,890,453 pixel comparisons**. Other ImageJ dilation, LUT and preference behavior is unchanged.

The pinned original factory and current JVM factory produce identical **30 catalogs** across
six families and five point sizes: **720 templates / 460,129 key points**. Node and two Chromium
Workers compare complete catalog data and all encoded matching, hole evaluation and foreground
pixel results, including clipping and all available anchors: **275,086,940 bytes agree exactly**.
Checks also cover record ownership, explicit source injection, immutable catalogs, atomic install,
parameter mismatch, duplicate input and preview configuration. The focused module is
**416,472 bytes**; catalog files remain external. This is not an engine size/throughput estimate.
TODO: measure actual catalog transfer/allocation costs before considering per-shape lazy bundles.

The focused HEADS entry now passes strict compilation with **zero diagnostics**. Its recognition
on real pages is not yet verified. The full method-preserving audit selects **9,053 roots /
8,630 bodies**, retaining all original roots, and still reports **494 diagnostics / 203 unique**.
Remaining candidates stay **75 classes / 242 class-excluded used methods / 310 methods across
all exclusion rules**. Compiler success for this component does not establish full-engine parity.

Reproduce with `verifyTemplateProbe` and `buildHeadsBaselineProbe` through the capped launcher.
Archive using `export_headless.py --stage 20 --templates --allow-added-roots`; small reports are in
`template-results`, with complete catalogs, matching vectors and pinned classes in the raw archive.

## Note-head recognition (stage 21)

The HEADS probe runs the original distance/spot preparation, per-system `NoteHeadsBuilder`,
`HeadSeedTally.analyze` and the image-discard epilog. The pinned JVM reference compiles five
original note-head classes, the original template factory and preceding reference classes;
the note-head classes use JDK 25 because the original source contains pattern switches.
The portable JVM path uses raw raster preparation and installs the external template catalogs.
Reference class origins are checked, and swallowed exceptions/ERROR logs reject a result.

Comparison includes every head's staff, shape, pitch, bounds and grade, complete glyph run
sequences, all post-HEADS graph relations and support ratios, and the derived head/seed offsets.
The preceding grid, header, seed, beam, ledger, distance-table and head-spot pixel results remain
in the same snapshot. Native and portable JVM results match for all eight input images: five
accepted pages yield **2,273 heads** (380 / 319 / 517 / 524 / 533); three retain their upstream
low-resolution rejection.

Binary and adaptive GRAY8 inputs agree in Node and two independent Chromium Workers:
**1,306,632 numeric comparisons**, maximum difference **7.2165e-16**. Full pre-discard head-spot
rasters agree for **175,126,400 bytes**, and distance tables for **350,252,800 bytes**. Post-HEADS
graphs contain **3,063 relations** across the accepted pages. Four Bravura catalogs at 48, 90,
91 and 92 points are sufficient for these inputs; their total external size is **1,019,020 bytes**,
alongside the external classifier and font measurements. The strict probe is **2,440,747 bytes**.
These are component artifacts and do not establish full-engine size or throughput.

HEADS discards the HEAD_SPOTS image for persistence while a still-live weak table cache remains
usable. The raw backend now releases its strong table ownership at that point and retains a
weak reference. A reachability fence checks live-cache identity in both backends; a separate
pixel snapshot makes validation independent of later collection. This preserves cache behavior
without requiring encoded images or retaining the table strongly for the rest of the job.

The Node driver yields to a new event-loop task between input images, matching the Worker's
message boundaries. A synchronous multi-image loop reached the separate **384 MiB Node heap**
limit while dereferenced WeakRef targets remained alive for the JavaScript job. With task
boundaries, all eight binary and GRAY8 Node inputs pass under that same heap limit; the capped
WSL group records no OOM or memory-limit events. Hosts should likewise finish an image/step task
before starting another rather than batching an unbounded number of synchronous calls.
This agrees with the [ECMAScript WeakRef read-consistency rule](https://tc39.es/ecma262/multipage/managing-memory.html#sec-weakref.prototype.deref):
a successfully dereferenced target remains available until the current execution completes.

Reproduce with `verifyHeadsProbe -I dev/teavm-audit/audit.init.gradle` through the capped launcher.
`-PheadsPages=1` is a smaller smoke run; its report is explicitly marked as a subset and the
exporter rejects it as full verification. The host supplies only the family/point-size catalogs
required by the input pages. Archive complete results using `export_headless.py --stage 21
--heads --allow-added-roots`.

## Stem connections (stage 22)

The STEMS probe performs the original `StemsStep` prolog, per-system `StemsRetriever.process`,
beam finalization and graph contextualization. Completed step flags through HEADS are recorded
before STEMS starts. Ten original stem/linker classes are compiled from the application baseline,
their JVM class origins are checked, and native/portable JVM results agree for all eight inputs.
Five accepted pages produce **587 stems** (99 / 99 / 135 / 137 / 117) and **2,099 stem connections**;
three retain their original low-resolution rejection.

Snapshots include stem bounds, median lines, widths, intrinsic/contextual grades and complete
glyph runs; all graph edges and support ratios; head side/consistency, beam portion, connection
gaps and extension points; and the grades of every contextualized graph vertex. The preceding
HEADS and raster snapshots remain part of the same comparison. ERROR logs and exceptions
captured by the original algorithms reject a result.

All eight binary and adaptive GRAY8 inputs agree in Node and two independent Chromium Workers:
**1,539,000 numeric comparisons**, maximum difference **1.7764e-15**. Pre-discard head-spot rasters
agree for **175,126,400 bytes**, and distance tables for **350,252,800 bytes**. The accepted pages
have **5,401 post-STEMS graph edges**. The strict component is **2,636,716 bytes**, with the same
four external template catalogs and classifier asset used for HEADS. Full-engine size and
throughput remain to be measured.

The validator releases completed Node snapshots and module references before collecting the
Worker results. Its old-space cap is **768 MiB**: the earlier 384 MiB cap was reached while
returning the complete dense comparison snapshots. The WSL group keeps its **8 GiB total**
limit, and records zero memory-limit/OOM events. The completed run verifies both Node and Worker
paths with the same Wasm hash.

Two desktop debug-image methods, `StemBuilder.saveConnection` and the corner linker's
`saveStump`, are replaced by explicit unsupported-operation failures. Their signatures and
image-creation/save calls are checked before replacement. Original flags, defaults and call-site
conditions remain in place; recognition methods are retained and Audiveris source is unchanged
in this stage. A Wasm check exercises the connection-image rejection directly.

The mixed comparator in `StemBuilder.sortItems` compares two half-linkers by reference point,
but other pairs by their line endpoints. Such rules can be non-transitive: TeaVM's bottom-up
merge sort and the JDK's TimSort then produce different candidate orders. On the first real page,
this changed a few head/beam connections despite identical stem glyphs. Only that one sort call
now uses the unchanged JDK `java.util.TimSort` class through a compiler bridge. Other collection
sorts retain their normal implementation. The comparator and original recognition code are kept.
TimSort executes as compiled Wasm inside each Worker.
The JDK release, TimSort source hash and class hash are recorded with the reference sources.

`testStemSortJvm` compares the wrapper with native list sorting on **768 lists**, including both
directions, lengths through 128, sublist offsets, unchanged surrounding elements and comparator
failure cases. Normal Node instances and both Workers compare the complete results with that
JVM reference. TODO: reconsider the original mixed comparator when intentionally revising the
recognition algorithm; a consistent ordering could remove this implementation-specific dependency.

Run `verifyConnectedStemsProbe -I dev/teavm-audit/audit.init.gradle` through the capped launcher.
`-PconnectedStemsPages=1` limits the Wasm run for diagnosis. JVM `-PconnectedStemsTrace=x,y,w,h`
traces a region on the first page into separate `*-trace` reference directories; Node supports
`AUDIVERIS_STEMS_TRACE_RECT=x,y,w,h`. Full exports reject subset or trace runs, check the verified
Wasm hash and require committed source/tool files. Archive with `export_headless.py --stage 22
--connected-stems --allow-added-roots`.

The following REDUCTION entry also compiles with zero diagnostics. Its pinned/native and
portable JVM snapshots are prepared separately; Wasm runtime recognition is still unverified
at this stage. `reduction-baseline.json` records that compilation-only status explicitly.

## Foundation reduction (stage 23)

The REDUCTION probe invokes the original `ReductionStep.doSystem` and protected epilog through
a test subclass. `SigReducer.reduceFoundations`, configurable stem-end refinement, the free-length
calculation and glyph-index cleanup retain their original source. Reference JVM classes for
`ReductionStep`, `SigReducer` and `HeadHeadRelation` are compiled from the baseline and their
classpath origins are checked. Audiveris source is unchanged in this stage.

The runtime change adds only `HeadHeadRelation()` to the explicit constructor reflection policy.
The first Wasm attempt exposed `NoSuchMethodException` in the original `SIGraph.insertSupport`;
its swallowed exceptions were rejected by the probe. With that constructor registered, all eight
images agree between the original JVM, portable JVM, Node and two independent Chromium Workers,
for both binary and adaptive GRAY8 input. Five accepted pages retain **2,405 graph vertices**
(415 / 416 / 508 / 537 / 529) and **2,460 edges** (484 / 409 / 511 / 483 / 573); three remain rejected.

Comparison covers every surviving interpretation's bounds, staff, intrinsic/contextual grade,
flags, glyph position/orientation and complete runs; head pitches; horizontal/vertical median
lines; stem widths and free lengths; and all remaining edge endpoints/grades/support ratios.
Every remaining indexed glyph must belong to a staff line or surviving graph interpretation.
All preceding phase snapshots, raster comparisons and configuration/resource checks also run.

There are **2,302,728 numeric comparisons**, maximum difference **1.7764e-15**, plus **175,126,400
head-spot bytes** and **350,252,800 distance bytes**. The component is **2,717,414 bytes** and uses
the same four external template catalogs and classifier asset. The validator uses a **768 MiB**
Node old-space cap within the unchanged **8 GiB / 8 CPU** group limits. The full audit retains
**9,054 roots / 8,631 bodies**, **494 diagnostics / 203 unique messages**, and **75 classes / 242
class-excluded used methods / 310 used methods across all exclusion rules**.

Reproduce using `verifyReductionProbe -I dev/teavm-audit/audit.init.gradle` through the capped
launcher; `-PreductionPages=1` limits the Wasm comparison. Archive full results with
`export_headless.py --stage 23 --reduction --allow-added-roots`.
The following CUE_BEAMS entry compiles with zero diagnostics; the active cue-beam path remains
unverified at this stage, separately from the default switch-off path.

## Cue beams (stage 24)

Both the default processing switches and an explicit `smallHeads=true` configuration now pass
the complete eight-image comparison, with binary and adaptive GRAY8 inputs, original and portable
JVM references, Node instances and two independent Chromium Workers. The original `CueBeamsStep`
and its algorithms remain unchanged. The default setting preserves the original switch-off
behavior; the enabled setting executes cue recognition on all five accepted pages and detects
one small-head beam in sample 01. Three low-resolution samples retain the original rejection.

The two configurations perform **2,340,360 / 3,478,604 numeric comparisons**, respectively,
with maximum difference **1.7764e-15**. Each also compares **175,126,400 head-spot bytes** and
**350,252,800 distance bytes**, including the preceding recognition stages. Both use the same
**2,736,465-byte** Wasm component and existing external template/classifier assets.
The Worker harness compares and records each image through a Playwright binding rather than
returning all dense graph snapshots in one protocol response. Node old space remains capped at
768 MiB inside the shared **8 GiB / 8 CPU** limit; no engine worker-sharing assumption is added.

Run `verifyCueBeamsDefaultProbe` and `verifyCueBeamsEnabledProbe` through the capped Gradle launcher
with `-I dev/teavm-audit/audit.init.gradle`; `-PcueBeamsPages=1` is a smoke subset only.
Archive complete evidence using `export_headless.py --stage 24 --cue-beams --allow-added-roots`.
Tracked summaries are in `cue-beams-results`, with dense runnable evidence in
`wsl-artifacts/wasm-headless/stage24`. The broad audit remains **494 diagnostics / 203 unique
messages**, with **75 classes / 242 class-excluded used methods / 310 methods across all rules**.

The following original TEXTS entry has **111 strict compilation diagnostics**. This is dependency
discovery, not runtime verification: raster cleanup, font measurement and JavaCPP/Tesseract loading
still need platform boundaries. OCR has not been replaced with empty results or disabled to claim
a successful text-recognition run. The agreed asynchronous OCR request/resume boundary remains
the target for that work.

## OCR request/resume boundary

`platform.OcrExchange` is a Worker-owned, per-session boundary with one outstanding request.
It keeps no suspended Java stack. The recognition caller retains its page/phase context, emits
a packed GRAY8 request, and consumes a reply before continuing. Requests identify the job, page,
execution generation and monotonically increasing request number. A cancelled exchange is terminal;
restarting uses a new generation. Late, duplicate and mismatched replies cannot resume processing.
Input pixels and nested result lists are owned snapshots. Confidence uses `[0,1]`, character boxes
are required, and absent baseline/font metadata stays explicit. Successful empty results and
model/unsupported/execution failures are separate outcomes. Coordinates refer to the submitted image;
the engine retains the crop/padding translation.

This component is not yet connected to `TextsStep`: the page raster cleaner, font measurement
and conversion into original `TextLine` objects remain separate work. The host adapter is described below.
`verifyOcrExchangeProbe` checks its lifecycle in JVM, Node and independent Chromium Workers,
including actual event-loop gaps between requesting, cancelling and responding.
After adding image-frame coverage, all **9,062 lifecycle/ownership/frame checks** pass in the JVM,
two Node module instances and two independent Chromium Workers. The Worker checks include separate
message/event-loop turns, cancellation and late replies after replacing an execution generation.
The standalone component is **47,912 bytes**, including image-frame test coverage; this is not an OCR backend or engine size estimate.
Reports and source revision are in `ocr-exchange-results`, with runnable artifacts in
`wsl-artifacts/wasm-components/ocr-exchange`. The shared **8 CPU / 8 GiB / no swap** limits remain
in force, with no cgroup OOM or hard-limit events. TEXTS integration and recognition quality remain
unverified by this lifecycle test.

`OcrImageFrame` adds the white border used by `OcrUtil.scan` to packed GRAY8 pixels and retains
the matching request's origin translation. Margin size is supplied by the caller; nonpositive
values disable padding as in the original code. **640 cases / 890,694 bytes** match the native
Java2D border operation exactly. Matching replies restore word/character boxes and optional line/
word baselines once before resuming; font metadata and confidence are preserved. Coordinates are
not clipped to the image, arithmetic overflow is rejected, and a failed translation leaves the
request waiting. Stale, duplicate and cancelled replies cannot apply the translation again.

Backend details must be checked rather than inferred from the library name. Tesseract.js requires
explicit [`blocks` output](https://github.com/naptha/tesseract.js/blob/master/docs/api.md) for its
structured hierarchy. The currently inspected tesseract-wasm
[`TextUnit` API](https://github.com/robertknight/tesseract-wasm/blob/master/src/ocr-engine.ts)
exposes line/word units, so that API alone does not satisfy the required character-box capability.
A backend lacking it must report unsupported capability; word boxes must not be split into invented
character positions. Missing baseline/font fallback quality is still unverified.

## OCR host transport

`OcrTransport` converts requests to plain host data and imports results into the immutable
`OcrExchange` model. The request uses a transferable packed `Uint8Array`; it owns a snapshot and
cannot detach or modify the engine's working pixels. Results carry lines, words, actual character
boxes, confidence and optional line/word baselines and font metadata. Field types, finite numbers,
int32 coordinates and model constraints are checked. Java objects, reflection and a Java JSON
parser are not part of the wire contract. Callers check the token before decoding a late result.

The native JVM model fixture round-trips through two Node instances and two Chromium Workers,
including supplementary Unicode characters and transferred/detached buffers. Each instance passes
**45 checks**, including **25 malformed-data cases**, owned inbound snapshots, actual asynchronous
Worker-to-host requests/replies, stale-before-decode rejection, empty results and all three failure
codes. The standalone transport probe is **47,586 bytes**, including padded real-image request/resume test exports. Its fixture serializer uses reflection
only in the separate JVM test entry, which is not reachable from the Wasm entry. The lifecycle
probe's **9,062 checks** still pass with the extended model.

Run `verifyOcrTransportProbe` with the audit init script through the capped launcher. Reports are
in `ocr-transport-results` and runnable artifacts in `wsl-artifacts/wasm-components/ocr-transport`.
This establishes the host data boundary. Text-font fallback, TEXTS raster cleanup
and conversion into original `TextLine` objects remain separate work.

## Tesseract.js OCR adapter

`host/tesseract-js-ocr.mjs` supplies the asynchronous service with an injected worker factory,
language catalog, asset locations, image encoder and configurable OEM. The default **OEM 0** matches
the original `TesseractOrder` selection; **OEM 1** is a separate LSTM-only option. Requests are
serialized per adapter, with one initialized OCR Worker reused across images. Input pixels are
snapshotted before queueing. Execution failures discard the failed Worker so the next request can
initialize a fresh one; missing models, unsupported metadata and successful blank recognition remain
distinct. Closing an adapter shuts down its Worker; cancelling an engine session rejects its late reply.

Tests pin **Tesseract.js/core 7.0.0** and **`@tesseract.js-data/eng` 1.0.0**. The actual OCR engine
identifies itself as **5.1.0-288-g2a9c1**. Both OEM profiles recognize the native 700×180 fixture as
**Allegro / moderato / Music / 123**, with **23 real character boxes**. Results, coordinates and
confidence agree exactly across Node and two independent Chromium Workers within each profile.
Wasm emits the image request and consumes the real OCR reply through `OcrTransport`; blank and
missing-model replies also resume with distinct outcomes. Inbound field validation still passes
all **45 transport checks**. Recovery and language reinitialization contracts additionally use
injected workers; actual recognition here covers English.
Both profiles additionally run a Wasm-generated request with a configured 10-pixel white border.
The actual OCR reply is restored to image coordinates inside Wasm, matching independently shifted
host data in Node and both browser Workers; duplicate replies cannot shift coordinates twice.

GRAY8 image encoding uses native `CompressionStream`. The **126,000-byte** fixture becomes a
**4,529-byte** PNG and decodes back to identical gray pixels. Browser tests load every OCR script,
core and model from the local server; external requests are rejected. Core JS and Wasm are separate
assets, and the English model is external. The first local measurements, with two concurrent Workers,
are:

| Profile | OCR core Wasm bytes | English model gzip bytes | First request ms | Reused request ms |
| --- | ---: | ---: | ---: | ---: |
| Legacy, OEM 0 | 3,456,075 | 10,923,060 | 774.9–775.0 | 28.2–29.0 |
| LSTM-only, OEM 1 | 2,862,266 | 2,952,873 | 719.9–720.0 | 42.0–42.4 |

First-request timing includes initialization, local asset loading, PNG encoding and OCR. These
single-fixture observations establish local request latency; score-image quality and full-engine
throughput need separate measurements. Both profiles provide line and word baselines. The optional
`tesseract-word-metadata.mjs` hook reads the recognized page's public `ResultIterator` API before
the normal JSON result is returned. Legacy OCR also supplies bold/italic/serif and other font flags
and point sizes; LSTM's unknown font attributes stay null. The same hook retains native floating
point word confidence: ordinary JSON rounds this to integer percent. Native font names are deliberately
unused, matching `TesseractOrder.getFont`. Word bounds and count must match the JSON word order;
misalignment fails explicitly and releases the iterator. The hook is idempotent and adds no OCR
Wasm code or model bytes. The Node factory shim and browser loader leave dependency sources
unchanged; browser tests also put the core assets in a directory separate from the Worker script.
The stock factory can still be injected without this enrichment.

`verifyNativeOcrBackend` also runs the application's original JavaCPP/Tesseract dependency
(native engine **5.5.2**) on the same image and decompressed model, with **OEM 0, DPI 70** and both
PSM 3 and 6. All raw results now match exactly: four words, 23 character boxes, confidence, line
and word baselines, and font flags/sizes. Before retaining native confidence, each mode had four
differences from JSON rounding. The test records every field difference and rejects regressions.
This comparison covers raw OCR on this fixture; original `TextWord` font fitting, score-page
cleanup and full TEXTS processing are outside this small test (see the separate TEXTS results).
The two OCR engine versions can still differ
on other inputs.

Install the locked test dependencies with `npm ci --ignore-scripts` in the capped WSL mirror, then
run `verifyTesseractJsOcrLegacy` and `verifyTesseractJsOcrLstm` with the audit init script.
`verifyTesseractJsOcr` selects the legacy profile. Summaries are in
`tesseract-js-ocr-results/{legacy,lstm}-word-metadata`; input images, result snapshots, local assets and package
notices are in `wsl-artifacts/wasm-components/tesseract-js-ocr/{legacy,lstm}-word-metadata`.
The earlier `{legacy,lstm}` results retain the stock-output comparison. Export each enriched profile
with `export_ocr_backend.py <repository> <mirror> <source-commit> <legacy|lstm>`.
For the legacy profile, append `--native` to archive the matching JVM comparison under `native/`.

## TEXTS recognition continuation

`WorkerTextsBridge` retains a sheet and an owned, already-cleaned GRAY8 buffer across the external
OCR request. A matching reply restores the image origin, builds original `TextWord` / `TextLine`
objects, performs the original ordinate sorting and font adjustment, then calls the original
`TextsStep.doSystem` for each system and its epilog, then marks TEXTS complete. Failures remain failures, cancellation rejects
late replies, and a continuation can be resumed only once. As in `TesseractOrder`, individual
words without font metadata are skipped; a backend with no font capability needs an explicit
fallback and cannot silently become an empty successful TEXTS result. Font capability describes
the backend, so an image with only unknown fonts still follows the original per-word filtering
when using the metadata-enabled legacy backend.

The small source boundary `TextFontGeometry` separates exact font measurements from recognition.
Its JVM default keeps Java2D visual bounds, layout bounds and advances. Font fitting retains the
original float arithmetic and rounding. `FontInfo`, `TextWord`, `WordInter`, `MusicWordInter` and
the recognition-side metronome construction delegate measurements through this boundary. Music
and ordinary text use separate entries. `HeadlessTextFontGeometry` accepts exact supplied values,
rejects missing/conflicting values and preserves the configured default font name. It is an
explicit resource boundary, not a portable font implementation or a Canvas approximation.
The original Tesseract confidence constant also has a scalar accessor so system recognition does
not need to instantiate an OCR service merely to read that setting.

**4,096 JVM checks** compare original text fitting and exact recorded replay. All **2,048 cases**
also match in two Node module instances and two independent Chromium Workers; missing measurements
are rejected. The font-only verification module is **167,763 bytes**. This verifies consumption of
exact supplied measurements, not a portable font-outline backend.

The strict TEXTS continuation entry compiles with **zero diagnostics**. The real-score comparison
covers all **5 accepted pages / 123 words / 107 sentences**, in two Node instances and two Workers,
including sentence roles, bounds, font attributes, graph relations and completion state. The
other **3 of 8 input pages** retain their pre-TEXTS low-resolution rejection. The checks compare
**2,381,628 numeric values**, with a maximum difference of **1.78e-15**, using **466 exact font
measurements**. Runtime-generated entity IDs differ between fresh sheets,
so recognition comparison uses content/geometry and relationships. The verification module is
**3,009,599 bytes**. These are recognition-continuation results with supplied native inputs.

Run `verifyTextGeometryProbe verifyTextsContinuationProbe` with the audit init script through
the capped launcher (one accepted score page by default; `-PtextsPages=0` selects all pages).
The score oracle compares original cleanup/OCR/TEXTS with the continuation, including imported
words and full text graph output. Exact native-cleaned rasters, OCR replies and font measurements
are explicitly supplied fixtures for Wasm checks. Browser-side TEXTS cleanup, general font
measurement remain separate work; live score OCR is verified separately below. The stage-25 all-method analysis
retains every earlier root and keeps the **494 / 203** diagnostic totals; generic and headless
regression probes also pass. CPU **8**, total
memory **8 GiB** and swap **0** remain in force.

Archive a committed run with `export_texts_continuation.py <repository> <mirror> <source-commit>`.
Input hashes bind the verification to its exact native fixtures, model, templates and sample
bytes. Summaries go to `texts-continuation-results`; runnable artifacts and native fixtures go to
`wsl-artifacts/wasm-components/texts-continuation`.

`compareScoreOcr` runs actual metadata-enabled legacy Tesseract.js on all five accepted,
native-cleaned score rasters, using one reused OCR worker in Node and in each of two independent
browser Workers. All **282 raw OCR words** (including native whitespace words) match between
Node and both Workers. The assets are local and hashed. The five results differ from the native
Tesseract 5.5.2 fixtures; the bundled JavaScript core reports 5.1.0-288-g2a9c1. These are observed
backend differences, not established native OCR parity. TODO: compare identical engine versions,
DPI/layout API sequences and adaptive-classifier state before attributing the differences or
changing OCR defaults. The tiny image's exact comparison alone does not establish score parity.

This real-score test exposed whitespace-only words omitted by `GetJSONText`. The metadata hook
now reads native symbols/word/line boundaries, retains those words and still checks alignment
of the JSON words. Native lines travel alongside the public JSON and are consumed by the adapter;
no whitespace geometry is invented or assigned to the next word. The adapter also supplies the
Tesseract.js error callback so a rejected recognition becomes a failure reply instead of an
uncaught worker error. Existing legacy/LSTM Node and two-Worker regressions, including the tiny
native comparison, still pass. TODO: profile the additional native/JSON payload before optimizing
serialization; retain the native character boxes, baselines, confidence and font flags.

`testLiveTextsJvm` constructs an independent JVM reference from these actual Tesseract.js replies,
using the original TEXTS system processing and epilog. `verifyLiveTextsProbe` then requests real
OCR from the Wasm continuation and compares its resulting recognition graph with that reference.
All **5 pages / 130 recognized words** match in **two Node instances and two browser Workers**:
**20 page comparisons / 2,382,096 numeric values**, maximum difference **1.78e-15**. The run uses
**519 exact supplied font measurements**; the strict module has zero diagnostics and remains
**3,009,599 bytes**. Each runtime invokes actual OCR, validates its complete reply against the
JVM oracle's input, restores the margin, resumes original system processing and marks TEXTS done.
These tasks reuse completed all-page native fixtures; first run the normal TEXTS oracle with
`-PtextsPages=0`. Native cleanup and exact font measurements remain explicit supplied inputs.
Live reports use a separate `live-ocr/` directory and do not overwrite the native-OCR comparison.
Archive a committed live verification with
`export_texts_continuation.py <repository> <mirror> <source-commit> --live`;
summaries go to `texts-live-ocr-results` and runnable inputs/assets to
`wsl-artifacts/wasm-components/texts-live-ocr`.

## Text font measurement investigation

`compareTextFontMetrics` compares the original `OmrFont.computeSize` and glyph visual bounds with
`OffscreenCanvas.measureText` in two independent Workers. Both sides load identical physical
Liberation font files: Sans/Serif/Mono, four styles, four sizes and eight strings (**384 cases**).
Browser kerning is disabled to match `createGlyphVector`; font and reference hashes are recorded.
Ordinary Canvas advance widths agree exactly, but all visual bounds differ (maximum **1.9375 px**)
and **236 fitted sizes differ**, by up to **2 points**. Measuring at 16× and 64× reduces the size
differences to **55** and **67**, respectively; both still differ by up to one point. The 1024×
experiment also changes advances at large sizes and is unsuitable for preserving these metrics.
Two Workers agree exactly with each other in all modes.

These are diagnostic results, not an engine replacement. Advance width cannot substitute for the
visual bounds used by the original font fitting. Keep the font-measurement boundary explicit and
investigate outline-based metrics or a compatible backend before integrating it into TEXTS.
The default font directory is `/usr/share/fonts/truetype/liberation`; override it with
`-PtextFontDirectory=...` containing the same named physical files. Summaries are in
`text-font-metrics-results`; raw reference/Worker results, fonts and their notice are under
`wsl-artifacts/wasm-components/text-font-metrics`. Archive a committed run with
`export_text_font_metrics.py <repository> <mirror> <source-commit>`.

`compareFontBoxMetrics` also tests the existing FontBox dependency on the same 384 physical-font
cases. Raw header/path bounds differ in every case, by at most **0.016602 px**; advances match
exactly. Rounding outline bounds to 1/64 px leaves **12 differing cases**, all involving the
accented-string fixture, with at most **0.015625 px** error. Header and path results agree for
these fonts. The original 1.15x target dimensions produce identical fitted sizes, but a sweep
of both fitting axes over 1..256 pixels (**196,608 targets per candidate**) exposes **226 size
differences** after quantization, by up to **one point** (5,827 before quantization).
Rounding the translated component offsets separately removes the remaining differences in these
fonts: both header/path candidates match all **384 bounds/advances** and all **196,608 fitted sizes**
exactly. This candidate explicitly rejects transformed or point-matched composite components.
The opt-in `verifyFontBoxMetricsProbe` also loads the actual physical TTF bytes in Wasm:
**384 cases each in two Node instances and two browser Workers / 1,536 checks**, with exact
bounds, advances and original fitted sizes. Strict compilation has zero diagnostics; the standalone
module with synthetic-italic support is **749,207 bytes / 265,472 gzip bytes**, with font data supplied separately. This does not
measure its incremental size inside the recognition engine or establish a speed advantage.
The profile `audiveris.compat.fontMetrics` avoids the unused GSUB cmap wrapper, disables native
mapped-file cleaning with an explicit unsupported handler, and delegates Commons Logging directly
to the existing headless logger. It is not enabled in the normal recognition/audit profiles.
TODO: broaden compound-glyph coverage, layout/shaping, physical/logical font mapping and music
fonts before integrating this backend, then measure real-page time and allocations.
Archive a committed run with `export_fontbox_metrics.py <repository> <mirror> <source-commit>`;
append `--probe` to include the matching Wasm/Worker verification.
summaries go to `fontbox-metrics-results` and raw cases/fonts/notices to
`wsl-artifacts/wasm-components/fontbox-metrics`.

`verifyRecordedTextFontProbe` additionally reproduces all **517 ordinary-text queries** recorded
by the five-page live TEXTS run (**516 visual bounds / one text layout**). The JVM resolves its
actual physical fonts once; four DejaVu TTF files suffice for these queries. The original
comparison differed on **88 synthetic-italic queries**, by up to **9.703125 px**. Applying the
[JDK 25 synthetic-italic matrix](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.desktop/share/native/libfontmanager/freetypeScaler.c)
to scaled outline points and evaluating quadratic extrema with fixed-point rounding removes
all differences. The bounding calculation follows the mathematical extrema and rounding used
by [FreeType](https://github.com/freetype/freetype/blob/VER-2-13-3/src/base/ftbbox.c).
Native comparison has zero differing/unsupported queries; two Node instances and two browser
Workers pass **2,068 checks** exactly, including bounds, advances and fitted sizes. The test also
keeps Java's saturated integer conversion for zero-ink strings, rather than treating infinity
as a fitted integer. JVM font discovery/reflection stays in the reference generator, outside Wasm.
The component comparison is separate from the font-backed TEXTS entry below; the **two music-font
queries** remain separate.
Archive a committed run with `export_recorded_text_fonts.py <repository> <mirror> <source-commit>`;
summaries go to `recorded-text-font-results`, while fonts, their notice, bindings and runnable
artifacts go to `wsl-artifacts/wasm-components/recorded-text-fonts`.

## TEXTS with physical text fonts

`TrueTypeFaceMetrics` and `TrueTypeGlyphBounds` provide the verified measurement in the engine's
compatibility code. `PortableTextGeometry` selects an explicit physical face and logical name
from font attributes. Font resources are installed once and sealed before recognition; attempts
to replace fonts after a page begins fail. Each module owns its parsed faces. Missing characters,
unconfigured styles, transformed/point-matched components and unsupported font formats remain
explicit failures. The initial layout path accepts ordinary ASCII; general shaping/fallback is
still separate work. This profile currently supports power-of-two TTF units per em and positive
integer sizes up to the JDK scaler's 16,384-point ceiling.

`verifyFontBackedTextsProbe` uses the same real OCR/JVM reference as the live continuation test,
but installs four physical fonts (**2,205,968 bytes**) and eight style bindings instead of a
per-string table. `FontBackedTextsProbe` rejects ordinary-text measurement entries, so those
fixtures cannot hide a missing font implementation. The only supplied `TextFontGeometry` entries
are the **two music-font measurements**. The font resource manifest contains face/style/name
bindings, with no recognition strings or point-size measurements. Native-cleaned rasters and
the existing CUE templates/symbol resources remain supplied inputs.

Strict compilation has zero diagnostics. This separate module is **3,271,357 bytes**, an increase
of **261,758 bytes** over the supplied-metrics continuation module; physical font files are
additional assets. The standalone font component after extracting the engine implementation is
**734,565 bytes / 260,051 gzip bytes**, and the 384-case plus 517-case comparisons still pass.
No real-page speed advantage is inferred from these sizes. The existing supplied-metrics entry
is retained as a comparison. The new profile is opt-in and does not change the stage-25 audit's
root policy or the ordinary headless compiler profile.

Two Node instances and two independent Chromium Workers now pass all five accepted pages
(**20 page comparisons / 130 recognized words**). All **2,382,096 numeric values** match the
same-OCR JVM reference within the existing tolerances; maximum difference is
**1.7763568394002505e-15**. Each instance performs **1,139 physical-font measurements** with
**zero supplied ordinary-text measurements**. Late font installation is rejected in every
instance. Three of the eight examined inputs are rejected before TEXTS, as in the JVM reference.
This still uses native-cleaned rasters and the two supplied music-font measurements; it does
not establish parity with the separate native Tesseract backend or general text shaping.

Run `verifyFontBackedTextsProbe` after generating all-page native/live references. Archive a
committed verification with `export_texts_continuation.py <repository> <mirror> <source-commit> --ttf`;
summaries go to `texts-font-backed-results` and assets to
`wsl-artifacts/wasm-components/texts-font-backed`.

The continuation verifier allows ten minutes each for Chromium launch and each Worker run;
`AUDIVERIS_BROWSER_LAUNCH_TIMEOUT_MS` (zero disables the launch timeout) and
`AUDIVERIS_TEXTS_WORKER_TIMEOUT_MS` override these limits. It writes `node-verification.json`
after the two Node instances pass. If browser startup fails, rerun the same verifier directly
with `AUDIVERIS_RESUME_TEXTS_NODE=1` to reuse that phase. Reuse requires the same verifier,
Node version, module and input hashes; it does not skip either browser Worker.

## TEXTS with engine image cleanup

`PageRaster` separates the cleaner's drawing operations from its recognition traversal.
The original `PageCleaner` constructor keeps its Graphics2D backend, including glyph and
music-symbol painting. The new constructor and `SheetScanner.cleanBuffer` use the same
inter selection, staff-core areas, template pixels, ledger widths, threshold and connected
glyph decisions with an explicit raster backend. `PackedPageRaster` combines integer
rectangles/run erasure, the thin-fill converter, normalized Marlin stroke outlines and the
new span converter. `PortableTextsCleanup` owns its byte buffer and binds the current sheet
while the original cleaner runs. Desktop subclasses retain their original graphics field.

`verifyCleanupTextsProbe` installs physical text fonts and runs that cleanup before the
external OCR suspension. Its page entry has **no cleaned-image parameter**. Native-cleaned
rasters are read by the verifier only to compare every OCR input pixel, including the white
margin; they are not passed to the engine. OCR, reply admission and full TEXTS system
processing then use the same reference as the live-OCR test.

The original and portable JVM traversals match all **43,474,400 pixels / five accepted pages**.
Two Node instances and two independent Chromium Workers pass all five pages (**20 comparisons**),
including exact OCR input pixels and TEXTS results. All **2,382,096 numeric values** match within
the existing tolerances; maximum difference is **1.7763568394002505e-15**. Each instance makes
**1,139 physical-font measurements**. Strict compilation has zero diagnostics.
The module is **3,441,767 bytes**, an increase
of **170,410 bytes** over the physical-font entry without cleanup. Verified thin-stroke support
adds **9,098 bytes** over the original cleanup entry. These sizes do not establish
actual recognition speed or total download size; fonts, classifier/templates and OCR assets
are separate resources.

Dashed thin-stroke drawing and music-symbol painting remain explicit unsupported paths in the packed
backend; neither occurs in the five accepted cleanup fixtures. Ordinary thin paths are verified
separately below. The two music-font measurement
resources remain supplied. General text shaping/fallback, other score inputs and parity with
the separate native OCR backend are not established. TODO: measure cleanup time, peak memory,
allocations and buffer copies before choosing further size/speed changes.

Run after generating the all-page native/live reference and physical font resources. Archive
a committed verification with `export_texts_continuation.py <repository> <mirror> <source-commit> --cleanup`;
summaries go to `texts-cleanup-results` and assets to `wsl-artifacts/wasm-components/texts-cleanup`.

## Pure Java path-fill component

`GrayPathRaster` calls the unchanged JDK `sun.java2d.loops.ProcessPath` scan converter, compiled
into Wasm, with an owned packed-byte destination and clipped white scanlines. There is no runtime
reflection or JVM/native call. Its small bridge lives under `src/jdk/java` and is compiled separately
with the required module export; the rest of the compatibility layer keeps its Java 17 target.
The native test records the JDK runtime, source hash and class hash in `jdk-reference.json`.

All **512 paths / 890,368 pixels** match Java2D's non-antialiased, thin-stroke `Path2D.Float` fill
operation exactly on the JVM. Cases include curves, holes, both winding rules, open/closed
subpaths and clipping. Two Wasm instances in Node compare the complete output bytes, and two
independent Chromium Workers compare SHA-256 and per-path hashes. The standalone probe is
**130,038 bytes**; no engine size or throughput conclusion is inferred from that test size.
Run `verifyGrayPathProbe` through the capped launcher with the audit init script.

This is deliberately a path-fill operation, not a complete Java2D replacement. Initial differential
tests exposed different rectangle fast-path rounding. Native Java2D also switches to a different
span-filling implementation when the current stroke is wide, even for `fill`. Those cases, stroke
outlines, symbol erasure and full TEXTS cleanup remain unverified and must be handled explicitly.
The component is not yet substituted into the recognition pipeline. TODO: measure cleanup time,
incremental Wasm size and allocations on actual pages before deciding whether another raster backend
is worthwhile. Reports are in `gray-path-results`; runnable artifacts are kept under
`wsl-artifacts/wasm-components/gray-path`.

## Pure Java span-fill component and actual TEXTS cleanup drawing

`GraySpanRaster` adapts OpenJDK 25's `ShapeSpanIterator.c` into Java-owned segments and a
packed grayscale sink. It keeps float subdivision, winding, endpoint normalization and
integer stepping; JNI and native memory management are absent. The original quadratic
normalization behavior is retained, including its control-point X adjustment. Attribution
and GPLv2 with Classpath Exception are in `third-party/openjdk`. Non-finite coordinates and
coordinates outside +/-4,194,304 fail explicitly.

`verifyGraySpanProbe` compares **1,024 cases / 1,780,736 pixels** against the native JDK span
iterator, covering both normalization modes, curves, holes, winding rules, open subpaths and
clipping. Two Node instances compare every byte; two independent Chromium Workers compare
the complete SHA-256 and per-path hashes. All agree exactly, the four input guards pass in
each instance, strict compilation has zero diagnostics, and the component is **140,964 bytes**.

`traceTextsCleanup` invokes the unchanged private `SheetScanner.TextsCleaner` on all eight
sample inputs and checks the five accepted pages against the saved native cleanup and CUE
prefix. The observed **836,628 drawing operations** comprise 835,344 rectangle erasures,
559 area fills, 511 area outlines and 214 ledger-line draws; no font drawing occurs in these
five pages. An independent native replay matches **43,474,400 pixels** exactly. Combining
integer rectangles, the existing thin-fill converter, normalized Marlin stroke geometry and
the new span converter also gives **zero differing pixels**, both before the final glyph
pass and in the final image, on all five pages.

For comparison, routing everything through the thin-fill converter leaves **6,535 pixels**
different before the glyph pass and **6,074** in the final images. Endpoint snapping alone
does not solve this. These are observations of drawing decisions from the original cleaner;
the drawing replay alone does not establish the engine's cleanup traversal. The separate
integration above now runs the original traversal with the packed backend. Glyph/symbol
painting, other inputs and actual cleanup throughput remain separate checks.

Archive a committed run with `export_span_cleanup.py <repository> <mirror> <source-commit>`.
The summary, source/input hashes and drawing observations go to `gray-span-results`; runnable
Wasm, dense native pixels, cleanup inputs and license notices go to
`wsl-artifacts/wasm-components/gray-span`.

## Pure Java thin-path drawing

`GrayThinRaster` uses the unchanged Java `ProcessPath` curve processing with an owned white
pixel sink. Its integer line stepping and extra endpoint clipping match the native JDK loops.
Initial tests exposed a difference between the Java and C `PROCESS_LINE` paths: the native
version clips integer endpoints again before drawing; the Java version delegates that work.
The sink restores that stage without modifying `ProcessPath` or using runtime reflection/JNI.
The source/attribution and license are covered by `third-party/openjdk`.

`verifyGrayThinProbe` compares **9,506 cases / 16,530,934 pixels** with native Graphics2D:
512 shape/curve fixtures, all 4,225 directed vectors in a 65-by-65 grid, and 16 large-coordinate
paths, each in default and pure stroke mode. Native widths are 0, 0.25, 0.5 and 1, with all three
cap and join values. Two Node instances compare all bytes; two independent Chromium Workers
compare complete/per-path hashes. All agree exactly. Four input guards pass per instance,
strict compilation has zero diagnostics and the standalone module is **137,549 bytes**.

`PackedPageRaster.draw` now selects this path for non-dashed widths at most one pixel. The
actual backend also matches **4,753 default-mode cases** against Graphics2D; custom strokes
and dashed thin strokes are explicitly rejected. A separate `verifyPackedThinProbe` repeats
those **4,753 cases / 8,265,467 pixels** through the actual backend in two Node instances and
two independent Workers; all bytes/hashes and the two rejection guards agree. Its separate
module is **292,619 bytes**, with zero strict diagnostics. Colors, arbitrary graphics transforms and
composites remain outside this white-raster API. Coordinates are finite and bounded to the
same +/-4,194,304 range as the span converter. General music-glyph painting remains separate.

Archive a committed run with `export_thin_raster.py <repository> <mirror> <source-commit>`;
summaries go to `gray-thin-results`, and dense native pixels, runnable Wasm and notices go to
`wsl-artifacts/wasm-components/gray-thin`.

## Pure Java stroke geometry component

`StrokePaths` invokes the JDK Marlin stroker directly, including line widths, caps, joins, miters,
dashes and non-antialiased coordinate normalization. The opt-in `audiveris.compat.strokePaths`
compiler profile keeps the original geometry algorithms. It changes the context provider's queue
to a Worker-local `ArrayDeque`, removes three unused native-raster allocations, routes diagnostics
to console output and explicitly rejects native raster/statistics entry points and non-null native
`Region` clipping. Source, class and JDK runtime hashes are recorded in `jdk-reference.json`.
The profile is not enabled for the existing recognition probes or broad audit.

The new strict component initially had **21 dependency diagnostics**, then one native Region
initializer, and now has zero. Runtime comparison also exposed numerical compatibility problems:
TeaVM 0.16 implements `cbrt` using `pow(x, 1/3)`, and its `ulp(double)` masks exponent/sign bits
differently from the JDK. The stroke profile selects the unchanged JDK FdLibm `Cbrt`/`Acos`
implementations and a small bit-based ULP helper. These changes remain scoped to stroke code.
TODO: check other engine callers before considering a wider numerical compatibility change.

There are **4,096 cases each** for cbrt, acos and ulp, including signed zero, subnormals, infinities
and NaN. Cbrt matches `StrictMath` exactly and stays within one ULP of the captured JVM `Math`
results; acos and ulp match their JVM references exactly. Repairing ULP restores identical path
segmentation and winding in all **256 widened / 256 normalized** fixtures. Normalized coordinates
match bit for bit. Widened double coordinates differ by at most **3.9689e-8 pixels**, below the
explicit **1e-7-pixel** bound. A further **512 outline fills / 890,368 pixels** match the native
Java2D path-fill output exactly. Node compares every output byte; two independent Chromium Workers
compare SHA-256, with **2,056,144 coordinate comparisons** across the runtime instances.

The standalone geometry/outline-fill probe is **280,957 bytes**. Native raster calls are verified
to fail explicitly. This does not implement wide-stroke span filling, glyph/symbol painting or
full TEXTS cleanup. Run `verifyStrokePathProbe` with the audit init script through the capped
launcher. Summaries are in `stroke-path-results`; dense references and runnable files are in
`wsl-artifacts/wasm-components/stroke-path`. CPU/memory limits remain shared across all development
processes at **8 CPU / 8 GiB / no swap**. TODO: measure actual cleanup throughput and incremental
engine size before choosing a final renderer backend.
The OCR exchange and path-fill regression tests also pass with identical Wasm hashes. Rebuilding
the CUE_BEAMS component changes only its embedded build revision (including removal of `-dirty`)
and the corresponding data-size metadata: every executable section is identical to the stage-24
verified module. `stroke-path-results/cue-regression.json` records that bounded binary comparison;
it does not claim an additional end-to-end sample run.

## Remaining work

### TODO: reconsider the XML properties parser

The user requested a performance/size review, then preferred kxml2. It is now the default;
the JDK handler remains a comparison backend. Neither path uses runtime reflection to load XML.
The first local Node 24/Wasm GC measurement uses separate processes and a small XML-only module:

| Measurement | JDK handler | kxml2 |
| --- | ---: | ---: |
| Wasm bytes | 160,377 | 132,349 |
| Gzip bytes | 56,093 | 48,829 |
| `ISO639-3.xml` (5,162 bytes), median parse | 0.154 ms | 0.141 ms |
| Synthetic 2,000 entries (117,912 bytes), median parse | 3.854 ms | 4.570 ms |

Output checksums match. kxml2 saves about 17.5% of the component Wasm size; these timings do not
establish a universal speed advantage. A follow-up in Chromium 158's dedicated Worker used the
same archived stage-3 modules (hashes in `results/xml-browser-benchmark.json`), a fresh browser per
backend and seven warm samples. Median times were **0.122 / 0.153 ms** for the real resource and
**2.700 / 4.260 ms** for the large synthetic document (JDK / kxml2). Checksums matched, but kxml2
was slower in this local Worker series. The current default prioritizes its smaller component;
it is not a speed optimization. These small local series do not establish engine-wide performance.
TODO: repeat with representative recognition inputs, measure incremental engine size and parser allocation separately from process RSS,
and reconsider if needed. `results/xml-benchmark.json` records first-load/cold/warm timings and
sampling details. Build with `buildXmlBenchmark -PxmlBackend=jdk` and `-PxmlBackend=kxml2`, then run
`node wasm-compat/tools/bench-xml.cjs build/wasm-compat/xml-bench-jdk build/wasm-compat/xml-bench-kxml2`
through the capped launcher. Build-time conversion of fixed resources and a browser DOM/SAX
adapter remain options; DOMParser itself is Window-only, so a Worker path needs a separate plan.
For the Worker comparison, use `node wasm-compat/tools/bench-xml-browser.cjs <jdk-directory>
<kxml2-directory>` through the same launcher. The reported component sizes above refer to the
archived stage-3 modules, not a fresh build of later compatibility layers.

The current headless audit has **494 diagnostics**, including reflection/classloading, thread APIs,
file/XML operations and other dependency runtime paths. None are located directly in selected engine
sources; the focused TEXTS runtime checks above establish narrower coverage than the full engine.
The generic-only audit still has 544 diagnostics, including three directly in selected engine
sources; the opt-in Worker substitutions address those three paths. Remaining work requires
resource, execution and platform adapters that preserve recognition behavior. The current reentry
report includes **76 classes / 251 class-excluded used input methods** (**320 methods across all exclusion
rules**), including the separate JVM raster backend. Raw distance-painting methods remain retained
for recognition. Choosing roots alone does not cut transitive dependencies.
