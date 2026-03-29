"""
Smoke test for libqorche shared library via Python ctypes.

Usage:
    python test_libqorche.py

Requires libqorche.dll (Windows), libqorche.so (Linux), or libqorche.dylib (macOS)
to be in the same directory or on the system library path.
"""
import ctypes
import json
import os
import sys
import tempfile

# ── Load library ────────────────────────────────────────────────

script_dir = os.path.dirname(os.path.abspath(__file__))
build_dir = os.path.join(script_dir, "..", "build", "native", "nativeCompile")

if sys.platform == "win32":
    lib_name = "libqorche.dll"
elif sys.platform == "darwin":
    lib_name = "libqorche.dylib"
else:
    lib_name = "libqorche.so"

lib_path = os.path.join(build_dir, lib_name)
if not os.path.exists(lib_path):
    print(f"ERROR: {lib_path} not found. Run: ./gradlew :native:nativeCompile")
    sys.exit(1)

lib = ctypes.CDLL(lib_path)

# ── GraalVM isolate setup ──────────────────────────────────────

# graal_create_isolate(params, isolate_out, thread_out) -> int
lib.graal_create_isolate.restype = ctypes.c_int
lib.graal_create_isolate.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_void_p)]

# graal_tear_down_isolate(thread) -> int
lib.graal_tear_down_isolate.restype = ctypes.c_int
lib.graal_tear_down_isolate.argtypes = [ctypes.c_void_p]

isolate = ctypes.c_void_p()
thread = ctypes.c_void_p()
rc = lib.graal_create_isolate(None, ctypes.byref(isolate), ctypes.byref(thread))
if rc != 0:
    print(f"ERROR: graal_create_isolate failed with code {rc}")
    sys.exit(1)

# ── Configure function signatures ──────────────────────────────

lib.qorche_version.restype = ctypes.c_char_p
lib.qorche_version.argtypes = [ctypes.c_void_p]

lib.qorche_validate_yaml.restype = ctypes.c_char_p
lib.qorche_validate_yaml.argtypes = [ctypes.c_void_p, ctypes.c_char_p]

lib.qorche_plan.restype = ctypes.c_char_p
lib.qorche_plan.argtypes = [ctypes.c_void_p, ctypes.c_char_p]

lib.qorche_snapshot.restype = ctypes.c_char_p
lib.qorche_snapshot.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]

lib.qorche_free.restype = None
lib.qorche_free.argtypes = [ctypes.c_void_p, ctypes.c_char_p]

# ── Tests ──────────────────────────────────────────────────────

passed = 0
failed = 0

def test(name, fn):
    global passed, failed
    try:
        fn()
        print(f"  PASS  {name}")
        passed += 1
    except Exception as e:
        print(f"  FAIL  {name}: {e}")
        failed += 1

def test_version():
    result = lib.qorche_version(thread)
    version = result.decode("utf-8")
    assert len(version) > 0, f"Expected non-empty version, got: {version}"
    assert "error" not in version.lower(), f"Got error: {version}"

def test_validate_yaml():
    # Create a temp YAML file
    yaml_content = """
project: test-project
tasks:
  - id: task-a
    instruction: "First task"
  - id: task-b
    instruction: "Second task"
    depends_on: [task-a]
""".strip()
    with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
        f.write(yaml_content)
        f.flush()
        yaml_path = f.name

    try:
        result = lib.qorche_validate_yaml(thread, yaml_path.encode("utf-8"))
        data = json.loads(result.decode("utf-8"))
        assert data["valid"] == True, f"Expected valid=true, got: {data}"
        assert data["project"] == "test-project"
        assert data["task_count"] == 2
        assert data["dependency_count"] == 1
    finally:
        os.unlink(yaml_path)

def test_validate_invalid_yaml():
    with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
        f.write("not: valid: yaml: [[[")
        f.flush()
        yaml_path = f.name

    try:
        result = lib.qorche_validate_yaml(thread, yaml_path.encode("utf-8"))
        data = json.loads(result.decode("utf-8"))
        assert data["valid"] == False, f"Expected valid=false, got: {data}"
        assert "error" in data
    finally:
        os.unlink(yaml_path)

def test_plan():
    yaml_content = """
project: plan-test
tasks:
  - id: lint
    instruction: "Lint code"
  - id: test
    instruction: "Run tests"
  - id: build
    instruction: "Build"
    depends_on: [lint, test]
""".strip()
    with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
        f.write(yaml_content)
        f.flush()
        yaml_path = f.name

    try:
        result = lib.qorche_plan(thread, yaml_path.encode("utf-8"))
        data = json.loads(result.decode("utf-8"))
        assert data["project"] == "plan-test"
        assert data["task_count"] == 3
    finally:
        os.unlink(yaml_path)

def test_snapshot():
    with tempfile.TemporaryDirectory() as tmpdir:
        # Create a test file
        test_file = os.path.join(tmpdir, "hello.txt")
        with open(test_file, "w") as f:
            f.write("hello world\n")

        result = lib.qorche_snapshot(thread, tmpdir.encode("utf-8"), b"test snapshot")
        data = json.loads(result.decode("utf-8"))
        assert "id" in data, f"Expected snapshot with 'id', got: {data}"
        assert "fileHashes" in data or "file_hashes" in data, f"Expected file hashes in: {data.keys()}"

# ── Run ────────────────────────────────────────────────────────

print(f"\nlibqorche smoke test ({lib_path})\n")

test("qorche_version", test_version)
test("qorche_validate_yaml (valid)", test_validate_yaml)
test("qorche_validate_yaml (invalid)", test_validate_invalid_yaml)
test("qorche_plan", test_plan)
test("qorche_snapshot", test_snapshot)

# ── Teardown ───────────────────────────────────────────────────

lib.graal_tear_down_isolate(thread)

print(f"\n{passed} passed, {failed} failed\n")
sys.exit(1 if failed > 0 else 0)
