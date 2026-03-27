# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
## [Unreleased]
### Fixed

- **core**: add .qorignore support and expand default ignore patterns

### Chore

- **core**: serializable API, Result error handling, and tests (#28)

## [0.8.3] - 2026-03-26
### Fixed

- **ci**: remove pull_request:closed trigger from release workflow (#27)

## [0.8.2] - 2026-03-26
### Fixed

- **ci**: use semantic-release-action for proper output capture (#26)

## [0.8.1] - 2026-03-26
### Fixed

- **ci**: add dual trigger for release workflow (#25)
- **core**: harden error handling and add missing tests (#24)

## [0.8.0] - 2026-03-26
### Added

- **core**: add KDocs to public API (#21)
- **cli**: add JSON output, exit codes, and scope overlap warnings (P0) (#18)

### Fixed

- **ci**: use push trigger for release workflow (#23)
- **ci**: trigger release on PR merge (#22)
- **ci**: pin to gradle/actions@v4 to avoid proprietary caching license (#20)
- **ci**: auto-trigger release from CI after tests pass on main (#19)

## [0.7.4] - 2026-03-26
### Fixed

- **ci**: disable UPX on Windows, GraalVM PE binaries incompatible (#17)

## [0.7.3] - 2026-03-26
### Fixed

- **ci**: fix Windows native build, UPX only on Linux (#16)

## [0.7.2] - 2026-03-25
### Fixed

- **core**: add per-task log files and cold-start benchmark (#15)

## [0.7.1] - 2026-03-25
### Fixed

- **ci**: merge native build into release workflow (#14)

## [0.7.0] - 2026-03-25
### Added

- **agent**: simplify ClaudeCodeAdapter, fix snapshot cache, dogfood KDoc (#12)
- **ci**: add GraalVM native-image build with cross-platform CI (#9)

### Fixed

- **ci**: add workflow_dispatch to release workflow (#13)
- **ci**: generate changelog in PR, remove flaky timing assertion (#11)

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


