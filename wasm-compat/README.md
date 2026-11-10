# TeaVM API compatibility layer

The generic layer absorbs reusable platform API differences without editing Audiveris sources.
The opt-in headless layer also uses the small source boundaries documented below. Compatibility
is opt-in (`-Pcompat=true`) in the audit harness and targets TeaVM 0.16.0 / Wasm GC.
It does not yet make the complete recognition engine executable in a browser.

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
correctness; Jackson's normal fast paths remain in its own shipped implementations. Engine-level
size and throughput still need measurement with a runnable recognition path. Test sources use
JDK 25 so they can call recent API names directly; compatibility implementations target Java 17.

## Reproduction

### Worker platform boundaries

`-Pheadless=true -Pcompat=true` enables application/platform substitutions in addition to the
generic API compatibility layer. The default audit remains unchanged. This mode rewrites TeaVM's
class model. Root selection and dependency JARs remain unchanged. Stages 1–3 used unchanged
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
  '-Pcompat=true', '-Pheadless=true', '--console=plain')
```

The same **9,001 roots / 8,588 bodies** are analyzed. Generic compatibility has 544 diagnostics;
the initial Worker boundary stage has 525 and the current presentation-boundary stage has **524**
(216 unique messages). No diagnostics are located directly in selected engine sources.

| Residual exclusion candidates | Initial Worker boundary | Current presentation boundary |
| --- | ---: | ---: |
| Linked classes | 441 | **76** |
| Used input methods inside excluded classes | 1,681 | **242** |
| Used input methods under all exclusion rules, including method-only rules | 1,863 | **313** |

Of the 76 classes, **55 have used methods and 21 are type-only**. These candidates include enums,
rendering/output helpers and non-UI host dependencies, as well as remaining presentation paths;
they are not all removable widgets. The exclusion guard still fails. Fast analysis is conservative,
not a runtime trace. Some inferred witness paths can disagree with the transformed method model.
The full audit separately checks that `FilamentIndex.publish` has no entity-service read or
selection-event construction after transformation. Original-reference crossing tables include original
UI branches even after substitution; inspect modified methods and distinguish inferred edges.

Presentation-only entry points (display windows, check boards, trackers, UI-task impact overloads,
manual link/task construction and plot windows) explicitly reject desktop execution. The recognition
overload `RhythmsStep.impact(Collection)`, ledger geometry, grid recognition, MusicXML building and
image-difference processing remain rooted. The three configuration source files above are the only
application changes from the baseline; `buildSrc` remains unchanged.

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
Use `dev/teavm-audit/export_headless.py` with the Windows checkout, Linux build directory and tool SHA
to export the comparison against the archived stage-5 generic audit. Add `--stage 2` to compare
against headless stage 1; `--stage 4` compares against headless stage 3. CPU usage remains capped at eight logical CPUs and memory at 8 GiB,
with no swap. These changes do not yet produce a complete runnable recognition engine.

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

The original root manifests match exactly: **9,001 methods**, including **8,588 method bodies**.
With generic compatibility and the opt-in Worker/presentation substitutions enabled, diagnostics
fall from **1,276 to 524** (752 fewer); unique messages fall from **336 to 216**.
The stages are **1,276 → 789 → 702 → 609 → 545 → 544 → 525 → 524**. The 544/220 result is the
generic compatibility layer alone; 525/217 is the initial Worker boundary and **524/216** is the
current headless configuration boundary (the diagnostic totals are unchanged from stage 3).
Remaining exclusion candidates are **76 classes / 242 used input methods inside those classes**,
or **313 methods** when method-only exclusion rules are also included.
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
- The expanded probe Wasm is **768,016 bytes**, including XML/resources and Jackson's large-number parsers.
  This is a compatibility test program, not an engine
  size estimate. No engine throughput claim is made from these tests.
- The generic stage-5 snapshot used unchanged `app` and `buildSrc` sources from baseline
  `04697c74fae431ef8436a9429386957a5cb25993`. Current headless stage 4 modifies only `Constant.java`,
  `ConstantSet.java` and `BookManager.java`; their hashes are recorded in headless provenance. The capped runs recorded
  no OOM or hard memory-limit hits.

Reviewable snapshots are in [results](results); bulky audit graphs and runnable probe artifacts
are kept outside Git in `wsl-artifacts/wasm-compat/stage5`. The original stage's artifacts remain
in `wsl-artifacts/wasm-compat/audit` and `probe`; its comparison is retained in
`results/stage1-comparison.json`. Each `results/stageN-comparison.json` compares that stage directly
with its predecessor. `dev/teavm-audit/export_compat.py --stage N` packages reports and provenance.

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

The current headless audit has **524 diagnostics**, including reflection/classloading, thread APIs,
file/XML operations and other dependency runtime paths. None are located directly in selected engine
sources; this does not establish that OCR/native or other platform boundaries are implemented.
The generic-only audit still has 544 diagnostics, including three directly in selected engine
sources; the opt-in Worker substitutions address those three paths. Remaining work requires
resource, execution and platform adapters that preserve recognition behavior. The current reentry
report includes **76 classes / 242 class-excluded used input methods** (**313 methods across all exclusion
rules**); choosing roots alone does not cut transitive dependencies.
