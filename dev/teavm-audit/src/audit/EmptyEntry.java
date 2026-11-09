package audit;

/** Audit-only entry: application methods are rooted by TeaVMAudit, never executed on the JVM. */
public final class EmptyEntry {
    public static void main(String[] args) { }
}
