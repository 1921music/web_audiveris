# TeaVM API compatibility layer

This module absorbs reusable platform API differences without editing the original Audiveris
sources. It is opt-in (`-Pcompat=true`) in the audit harness and targets TeaVM 0.16.0 / Wasm GC.
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
Diagnostics fall from **1,276 to 702** (574 fewer); unique messages fall from **336 to 254**.
The second compatibility stage reduces the previous **789 to 702**, a net improvement of 87
diagnostics. There are no remaining missing-API messages for the APIs targeted above. The broad
fast analysis newly exposes eight unique messages (13 occurrences) in JDK virtual-thread internals, including
interrupt fields and thread-container/state accessors. They remain unsupported and are recorded
in `results/new-diagnostics.tsv`, rather than supplied with inert implementations. These are
dependency-analysis results, not a successful full-engine
Wasm build or proof that every selected method executes correctly.

- JVM oracle: **582,229 checks**, comparing the replacements with the standard JDK, including
  randomized and edge-case inputs.
- A probe compiled from code that imports the original standard Java API names executes under
  Wasm GC in Node and Chromium **158.0.8127.18**. Its 4,096 numeric values match the JVM exactly,
  along with sorted-set, string and path result arrays; 75 standard-API cases, 640 byte-array cases,
  322 numeric-parser cases and captured stack output also match. The JVM fixture uses Jackson's
  original multi-release JAR; Wasm uses the selected unchanged base entries.
- The expanded probe Wasm is **535,024 bytes**, now including Jackson's large-number parsers.
  This is a compatibility test program, not an engine
  size estimate. No engine throughput claim is made from these tests.
- `app` and `buildSrc` remain unchanged from upstream baseline
  `04697c74fae431ef8436a9429386957a5cb25993`. The capped runs recorded no OOM or hard memory-limit hits.

Reviewable snapshots are in [results](results); bulky audit graphs and runnable probe artifacts
are kept outside Git in `wsl-artifacts/wasm-compat/stage2`. The original stage's artifacts remain
in `wsl-artifacts/wasm-compat/audit` and `probe`; its comparison is retained in
`results/stage1-comparison.json`. `results/stage2-comparison.json` compares the second stage
directly with the first. `dev/teavm-audit/export_compat.py` packages these reports and provenance.

## Remaining work

The remaining 702 diagnostics include reflection/classloading, thread APIs, file/XML operations,
OCR/native dependencies and transitive UI code. The five diagnostics located directly in selected
engine sources concern `Properties.loadFromXML`, `ClassLoader.getResource`,
`Class.getProtectionDomain`, `Thread.getThreadGroup` and a `Thread` constructor taking a group.
Those need decisions about resources, execution and platform boundaries; replacing them with
successful no-ops would not preserve recognition behavior. The existing excluded-class reentry
report still applies: choosing roots alone does not cut transitive dependencies.
