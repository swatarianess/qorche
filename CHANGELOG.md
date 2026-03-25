# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
## [0.6.1] - 2026-03-25
### Fixed

- **core**: add loser rollback, stubborn retry test, default maxRetries=0 (#7)

## [0.6.0] - 2026-03-25
### Added

- **core**: add retry-on-conflict for parallel task execution (#5)

## [0.5.1] - 2026-03-25
### Fixed

- **core**: add timeout to ClaudeCodeAdapter, fix DiffCommand ID lookup, stabilize topological sort (#3)
- **ci**: use PR for changelog updates, improve format (#2)

## [0.5.0] - 2026-03-24
### Added

- **ci**: add semantic-release and git-cliff for automated versioning (#1)

### CI/CD

- add GitHub Actions workflow for test on push/PR

### Documentation

- add README and filter CI to code-only changes

### Fixed

- **ci**: add gradle-wrapper.jar and fix gitignore ordering
- **ci**: make gradlew executable for Linux runners

### Performance

- **ci**: exclude benchmark tests from default test task

## [0.4.0] - 2026-03-24
### Added

- **core**: implement M3 parallel execution with MVCC conflict detection

### Changed

- replace inline comments with KDoc, remove section markers

### Documentation

- mark M2 complete, add M3 and native library roadmap

## [0.3.0] - 2026-03-22
### Added

- **core**: implement M2 task graph execution with YAML parsing and ShellRunner

## [0.2.0] - 2026-03-22
### Added

- **core**: implement M1 snapshot system with parallel hashing and orchestrator

### Fixed

- **build**: resolve large-scale benchmark tag conflict and update changelog for v0.2.0

## [0.1.0] - 2026-03-22
### Added

- initialize project scaffold with MVCC core, agent adapters, and benchmarks


