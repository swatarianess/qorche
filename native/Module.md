# Module native

GraalVM shared library (`libqorche`) exposing the orchestration engine via a C-compatible
FFI interface. Enables embedding Qorche in non-JVM languages (Python, Rust, Go, Node.js)
through standard shared library loading.

# Package io.qorche.native

GraalVM native-image entry points and FFI bindings for the shared library build.
