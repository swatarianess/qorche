# Module native

GraalVM shared library (`libqorche`) exposing the orchestration engine and built-in
runners via a C-compatible FFI interface. Enables embedding Qorche in non-JVM languages
(Python, Rust, Go, Node.js) through standard shared library loading.

## FFI Functions

| Function | Description |
|----------|-------------|
| `qorche_version()` | Returns the library version string |
| `qorche_parse_yaml(path)` | Parses a tasks.yaml file, returns full TaskProject as JSON |
| `qorche_validate_yaml(path)` | Validates a tasks.yaml file, returns summary JSON |
| `qorche_plan(path)` | Returns the execution plan as JSON |
| `qorche_schema()` | Returns the JSON Schema for tasks.yaml |
| `qorche_snapshot(workDir, desc)` | Creates a filesystem snapshot, returns JSON |
| `qorche_diff(workDir, id1, id2)` | Diffs two snapshots, returns JSON |
| `qorche_list_snapshots(workDir)` | Lists all stored snapshots as JSON array |
| `qorche_wal_entries(workDir)` | Returns all WAL entries as JSON array |
| `qorche_run(yamlPath, workDir)` | Executes a task graph with runner support |
| `qorche_clean(workDir, optionsJson)` | Removes stored data (snapshots, logs, WAL) |
| `qorche_free(ptr)` | Frees memory allocated by any of the above |

All functions return UTF-8 JSON strings. Caller must free returned pointers via `qorche_free()`.

# Package io.qorche.ffi

GraalVM native-image entry points and FFI bindings for the shared library build.
