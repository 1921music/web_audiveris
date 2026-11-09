# TeaVM compilation audit

Investigation harness for the original Audiveris application. The default audit uses no substitutions.
Optional API compatibility implementations live separately in `wasm-compat` and are enabled only
with `-Pcompat=true`. No application-source transformations are performed. TeaVM is fixed at 0.16.0,
target Wasm GC.

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
Neither analysis mode rewrites the application. API replacements are controlled separately by `compat`.

## Selected engine candidates and exclusion paths

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
    'traceTeaVMExclusions', '-I', 'dev/teavm-audit/audit.init.gradle',
    '-PauditScope=selected', '--console=plain'
)
```

This first inventories every original method without running dependency analysis, classifies all
original classes, and applies `root-policy.json`. `root-plan.tsv` records a reason for every method:
`root`, `excluded`, or `dependency-only`. Only non-UI candidates in `keep`/`mixed` classes are forced.
Boundary classes (including recognition fonts), and metadata under review, are left for selected
callers to reach. Method rules cover training, debug tools, OMR persistence and manual-edit entry
points in otherwise retained classes. Lambda helpers of rejected methods follow the same rule.

All original application class files remain on the compiler classpath. No javac source exclusions,
TeaVM class filters, dead implementation stubs, or substitutions are used. Excluded methods may
be reached transitively; this investigation must expose those paths rather than suppress them.
The profile is a broad candidate-method inventory, not the final minimal engine API.

`inventory/source-references.tsv` contains original application-body call, construction,
initialization, field access and field-type references with source locations. This is separate from
the TeaVM call graph, whose virtual dispatch in fast mode is a conservative approximation.

Selected-run outputs under `build/teavm-audit/fast-selected` include:

- `root-plan.tsv`, `selected-roots.tsv`, `root-selection.json`: every decision, counts and input hashes.
- `reached-methods.tsv`, `reached-classes.txt`: methods marked used versus linked type metadata.
- `excluded-class-paths.tsv`: one representative shortest root path per excluded linked class, or an
  explicit type-only marker when no method is used. This is not a runtime execution trace.
- `excluded-method-paths.tsv`: root paths to unwanted UI/training/storage methods that re-enter.
- `entry-boundaries.tsv`: used original methods in retained/mixed/boundary classes that reference an
  unwanted class or method. Multiple records can refer to the same source line.
- `original-reference-paths.tsv`: paths made only of original application-declared references to
  those entry points, without substituting inferred virtual targets for source evidence.
- `exclusion-boundaries.tsv`: all such original-body references, including inside excluded code.
- `exclusion-summary.json`: re-entry counts, baseline diagnostic comparison, guard result and limits.

The Java harness rejects missing/stale plans, unknown actions, and forced UI/excluded-class roots.
`testTeaVMSelection` tests the policy and graph/guard behavior before selecting roots. Optional
`-PfailOnExcluded=true` fails the task if excluded classes/methods remain reachable. It is currently
expected to fail: the requested investigation identifies the boundaries but does not replace them.
Reports are written before this strict guard fails. Default mode reports the same findings without
treating the expected remaining dependencies as a failure of the investigation itself.

See [SELECTION.md](SELECTION.md) for the measured result. The initial all-method snapshot under
`results/` is preserved; selected-run results are stored separately under `selected-results/`.

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

## Optional API compatibility

`-Pcompat=true` places `wasm-compat` before the standard TeaVM class library on the compiler classpath
and enables its compiler-side policies. Results use the separate `fast-selected-compat` directory.
The original-class inventory and root selection always run without those policies, so the same
original-method roots can be compared. Compatibility audits also run the JVM oracle and Node/Wasm
probe before compiling the application roots. `compareWasmCompatibilityAudit` checks that the
original root manifests match exactly and reports resolved/new diagnostics.

See [wasm-compat/README](../../wasm-compat/README.md) for supported APIs, behavior tests and the remaining
platform boundaries. Existing all-method and selected audit snapshots are kept as baselines.

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
