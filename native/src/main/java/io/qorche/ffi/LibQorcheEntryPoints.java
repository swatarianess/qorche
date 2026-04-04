package io.qorche.ffi;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

/**
 * C entry points for libqorche shared library.
 * <p>
 * This thin Java layer handles GraalVM Word-type constraints that prevent
 * Kotlin from being used directly with {@code @CEntryPoint}. All business
 * logic lives in {@link QorcheApi} (Kotlin).
 * <p>
 * Memory contract: every function returning {@code CCharPointer} allocates
 * via {@link UnmanagedMemory}. Caller MUST free via {@code qorche_free()}.
 */
public final class LibQorcheEntryPoints {

    private LibQorcheEntryPoints() {}

    private static CCharPointer toCString(String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CCharPointer ptr = UnmanagedMemory.malloc(WordFactory.unsigned(bytes.length + 1));
        for (int i = 0; i < bytes.length; i++) {
            ptr.write(i, bytes[i]);
        }
        ptr.write(bytes.length, (byte) 0);
        return ptr;
    }

    // ── Lifecycle ──────────────────────────────────────────────

    @CEntryPoint(name = "qorche_version")
    public static CCharPointer version(IsolateThread thread) {
        return toCString(QorcheApi.INSTANCE.version());
    }

    // ── Validation & Planning ──────────────────────────────────

    @CEntryPoint(name = "qorche_validate_yaml")
    public static CCharPointer validateYaml(IsolateThread thread, CCharPointer yamlPath) {
        String path = CTypeConversion.toJavaString(yamlPath);
        return toCString(QorcheApi.INSTANCE.validateYaml(path));
    }

    @CEntryPoint(name = "qorche_plan")
    public static CCharPointer plan(IsolateThread thread, CCharPointer yamlPath) {
        String path = CTypeConversion.toJavaString(yamlPath);
        return toCString(QorcheApi.INSTANCE.plan(path));
    }

    // ── Snapshots ──────────────────────────────────────────────

    @CEntryPoint(name = "qorche_snapshot")
    public static CCharPointer snapshot(IsolateThread thread, CCharPointer workDirPath, CCharPointer description) {
        String workDir = CTypeConversion.toJavaString(workDirPath);
        String desc = CTypeConversion.toJavaString(description);
        return toCString(QorcheApi.INSTANCE.snapshot(workDir, desc));
    }

    @CEntryPoint(name = "qorche_diff")
    public static CCharPointer diff(IsolateThread thread, CCharPointer workDirPath,
                                     CCharPointer snapshotId1, CCharPointer snapshotId2) {
        String workDir = CTypeConversion.toJavaString(workDirPath);
        String id1 = CTypeConversion.toJavaString(snapshotId1);
        String id2 = CTypeConversion.toJavaString(snapshotId2);
        return toCString(QorcheApi.INSTANCE.diff(workDir, id1, id2));
    }

    // ── Execution ──────────────────────────────────────────────

    @CEntryPoint(name = "qorche_run")
    public static CCharPointer run(IsolateThread thread, CCharPointer yamlPath, CCharPointer workDirPath) {
        String yaml = CTypeConversion.toJavaString(yamlPath);
        String workDir = CTypeConversion.toJavaString(workDirPath);
        return toCString(QorcheApi.INSTANCE.run(yaml, workDir));
    }

    // ── Memory ─────────────────────────────────────────────────

    @CEntryPoint(name = "qorche_free")
    public static void free(IsolateThread thread, CCharPointer ptr) {
        if (ptr.isNonNull()) {
            UnmanagedMemory.free(ptr);
        }
    }
}
