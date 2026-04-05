# Module native

GraalVM shared library (`libqorche`) exposing the orchestration engine and built-in
runners via a C-compatible FFI interface. Enables embedding Qorche in non-JVM languages
(Python, Rust, Go, Node.js) through standard shared library loading.

## FFI Functions

| Function | Description |
|----------|-------------|
| `qorche_version()` | Returns the library version string |
| `qorche_validate_yaml(path)` | Validates a tasks.yaml file, returns JSON |
| `qorche_plan(path)` | Returns the execution plan as JSON |
| `qorche_run(yamlPath, workDir)` | Executes a task graph with runner support |
| `qorche_snapshot(workDir, desc)` | Creates a filesystem snapshot, returns JSON |
| `qorche_diff(workDir, id1, id2)` | Diffs two snapshots, returns JSON |
| `qorche_free(ptr)` | Frees memory allocated by any of the above |

All functions return UTF-8 JSON strings. Caller must free returned pointers via `qorche_free()`.

# Package io.qorche.ffi

GraalVM native-image entry points and FFI bindings for the shared library build.
