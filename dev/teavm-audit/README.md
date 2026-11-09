# TeaVM compilation audit

Investigation harness for the original Audiveris application. It adds no engine port, platform stubs,
class substitutions, or application-source transformations. TeaVM is fixed at 0.16.0, target Wasm GC.

## Run

From the Windows repository root:

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
    'summarizeTeaVMAudit', '-I', 'dev/teavm-audit/audit.init.gradle', '--console=plain'
)
```

The shared cgroup restricts all jobs and descendants to three logical CPUs and 8 GiB of memory,
with swap disabled. The audit JVM has a 5 GiB heap limit. Gradle uses one worker and no persistent
daemon. The source of truth remains on Windows and is mirrored into WSL before building.

`-PauditScope=ui` or `-PauditScope=engine` restricts the method roots. The default is `all`.
`-PauditMode=precise` enables TeaVM's precise dependency analyzer; the default `fast` analyzer
uses coarser global type information to reduce the memory cost of this deliberately broad audit.
Neither mode rewrites the application or replaces missing APIs.

## Coverage and classification

The harness enumerates compiled application classes, including nested/anonymous classes, then calls
`DependencyAgent.linkMethod(...).use()` on every declared method. Private, synthetic, bridge,
constructor, class-initializer, and abstract methods are included. Reference parameters and receivers
are seeded into the type analysis. `roots.tsv` records each root and whether TeaVM marked it used.
This is stronger than `classesToPreserve` / `preservedClasses`, which preserves class types but does
not explicitly make every method body a root. It does not root every method of third-party libraries:
their dependencies are followed by TeaVM from the application roots.

UI roots are methods in `.ui.` packages, `LogPane`, and rendering/painting/component/editor/chart
methods outside UI packages, AWT component subclasses, and methods with Swing/Graphics/event types
in their signatures. Other application methods are labelled `engine`; this is a conservative
non-UI candidate set that also includes desktop startup, persistence, training and debugging helpers.
It is not the final browser-engine dependency closure. Individual root labels remain reviewable.

`summarize.py` propagates UI/engine provenance along the TeaVM call graph. Errors in dependencies can
therefore be UI-only, engine-side, shared, or unattributed. A UI implementation may still be needed by
engine code (for example, music-font measurements); package names alone do not prove it removable.
API families are an additional textual grouping, not a claim that each reported call must be ported.

## Output

Under the Linux mirror's `build/teavm-audit/<mode>-<scope>/`:

- `roots.tsv`: complete application-method inventory and rooting status.
- `diagnostics.tsv`: full TeaVM diagnostics with original source locations.
- `calls.tsv`: dependency call edges used for provenance.
- `summary.properties`: coverage, compiler exceptions, diagnostic counts and elapsed time.
- `compiler-exception.txt`: compiler exception, if any; such a run is incomplete.

Run `python3 dev/teavm-audit/summarize.py build/teavm-audit/fast-all` through the limited WSL runner
to produce `diagnostics-classified.tsv`, `apis.tsv` and `classified-summary.json`.
Compilation errors are expected, so the Gradle task reports investigation completion separately
from application-compilation success. A successful Gradle task does not imply a working Wasm file.

This audit finds static compilation obstacles. It cannot prove resource availability, OCR adapter
behavior, rasterization/font equivalence, asynchronous behavior, or recognition-result equivalence.

## Class inclusion decisions

```bash
python3 dev/teavm-audit/classify_classes.py app/build/classes/java/main app/src/main/java build/teavm-audit/fast-all
python3 dev/teavm-audit/summarize.py build/teavm-audit/fast-all
```

`classify_classes.py` reads the original class-file constant pools, class hierarchy and original source
descriptions. It writes a complete inventory including classes with no declared methods. The explicit
rules distinguish `keep` (recognition/data logic), `mixed` (needed class with optional responsibilities),
`boundary` (needed role with platform-dependent implementation), `exclude` (outside initial scope), and
`review` (unresolved metadata or role). These are scope/role decisions, not a proven minimal dependency
closure. `keep` does not mean every method is needed, or that the class already compiles successfully.
`exclude` does not mean its current references can be deleted without adaptation.

Every row of `class-decisions.tsv` includes a reason, source, superclass, method categories and inbound
references from kept/mixed classes. `class-decisions.json` also retains all constant-pool references.
`kept-to-excluded.tsv` enumerates remaining references that need to be cut, made unreachable, or
absorbed by an adapter. It includes fields/signatures/optional methods; a class-level reference does
not establish a required runtime call. The summarizer joins class decisions onto each diagnostic.

Font/symbol classes under `.ui.symbol.` are treated as boundary candidates, not simply excluded.
The dataset `OmrShape` enum is retained because recognition code directly uses its values; dataset
export containers and standalone dataset tools are excluded. Training methods inside an inference
class are optional even when the class itself must remain. No source/class deletion is performed.
