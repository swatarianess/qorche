#!/usr/bin/env python3
"""
Version calculator for Qorche.

Parses conventional commits since the last release tag and calculates
the next semantic version. Pure logic is separated from git I/O for
testability.

Usage:
    python version_calc.py --stamp          # Print dev version stamp (e.g. v0.2.0-dev.5)
    python version_calc.py --preflight      # PR check: warn about unexpected bumps
    python version_calc.py --dry-run        # Show what the next release version would be
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from enum import Enum
from typing import Optional


# ---------------------------------------------------------------------------
# Pure domain logic (no I/O, fully testable)
# ---------------------------------------------------------------------------

class BumpLevel(Enum):
    NONE = 0
    PATCH = 1
    MINOR = 2
    MAJOR = 3


@dataclass(frozen=True)
class Version:
    major: int
    minor: int
    patch: int

    def bump(self, level: BumpLevel) -> Version:
        if level == BumpLevel.MAJOR:
            return Version(self.major + 1, 0, 0)
        if level == BumpLevel.MINOR:
            return Version(self.major, self.minor + 1, 0)
        if level == BumpLevel.PATCH:
            return Version(self.major, self.minor, self.patch + 1)
        return self

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


@dataclass(frozen=True)
class CommitInfo:
    commit_type: str
    scope: Optional[str]
    breaking: bool
    description: str
    raw: str


# Regex for conventional commits:  type(scope)!: description
_CC_RE = re.compile(
    r"^(?P<type>[a-z]+)"
    r"(?:\((?P<scope>[^)]*)\))?"
    r"(?P<bang>!)?"
    r":\s*(?P<desc>.+)$",
    re.IGNORECASE,
)

# Types that trigger a release
_RELEASE_TYPES: dict[str, BumpLevel] = {
    "feat": BumpLevel.MINOR,
    "fix": BumpLevel.PATCH,
    "perf": BumpLevel.PATCH,
    "revert": BumpLevel.PATCH,
}


def parse_version(tag: str) -> Version:
    """Parse a version tag like 'v0.1.0' or '0.1.0' into a Version."""
    raw = tag.lstrip("v")
    parts = raw.split(".")
    if len(parts) != 3:
        raise ValueError(f"Invalid version tag: {tag}")
    return Version(int(parts[0]), int(parts[1]), int(parts[2]))


def classify_commit(message: str) -> CommitInfo:
    """Parse a single-line commit message into a CommitInfo."""
    first_line = message.strip().split("\n")[0]
    body = message.strip()

    match = _CC_RE.match(first_line)
    if not match:
        return CommitInfo(
            commit_type="other",
            scope=None,
            breaking=False,
            description=first_line,
            raw=message,
        )

    has_bang = match.group("bang") == "!"
    has_breaking_footer = "BREAKING CHANGE" in body or "BREAKING-CHANGE" in body

    return CommitInfo(
        commit_type=match.group("type").lower(),
        scope=match.group("scope"),
        breaking=has_bang or has_breaking_footer,
        description=match.group("desc"),
        raw=message,
    )


def calculate_bump(commits: list[CommitInfo]) -> BumpLevel:
    """Determine the highest bump level from a list of commits."""
    level = BumpLevel.NONE
    for c in commits:
        if c.breaking:
            return BumpLevel.MAJOR
        commit_level = _RELEASE_TYPES.get(c.commit_type, BumpLevel.NONE)
        if commit_level.value > level.value:
            level = commit_level
    return level


def next_version(current: Version, commits: list[CommitInfo]) -> Version:
    """Calculate the next version given a current version and commits."""
    return current.bump(calculate_bump(commits))


def filter_releasable(commits: list[CommitInfo]) -> list[CommitInfo]:
    """Return commits that would trigger a release."""
    return [c for c in commits if _RELEASE_TYPES.get(c.commit_type, BumpLevel.NONE) != BumpLevel.NONE or c.breaking]


def stamp_dev(current: Version, commits: list[CommitInfo], commit_count: int) -> str:
    """Generate a dev version stamp like 'v0.2.0-dev.5'."""
    nv = next_version(current, commits)
    if nv == current:
        # No releasable commits — use patch bump for dev stamp
        nv = current.bump(BumpLevel.PATCH)
    return f"v{nv}-dev.{commit_count}"


@dataclass
class PreflightResult:
    current_version: Version
    next_ver: Version
    bump_level: BumpLevel
    commit_count: int
    releasable_commits: list[CommitInfo]
    breaking_commits: list[CommitInfo]
    warnings: list[str]

    @property
    def has_warnings(self) -> bool:
        return len(self.warnings) > 0


def preflight_check(
    current: Version,
    commits: list[CommitInfo],
    commit_count: int,
) -> PreflightResult:
    """Run preflight checks for a PR and return structured results."""
    bump = calculate_bump(commits)
    nv = next_version(current, commits)
    releasable = filter_releasable(commits)
    breaking = [c for c in commits if c.breaking]
    warnings: list[str] = []

    if breaking:
        descriptions = [f"  - {c.raw.split(chr(10))[0]}" for c in breaking]
        warnings.append(
            f"BREAKING CHANGE detected — this will bump to v{nv} (major):\n"
            + "\n".join(descriptions)
        )

    if bump == BumpLevel.NONE:
        warnings.append(
            "No releasable commits found (fix:, feat:, perf:, revert:). "
            "This merge will not trigger a release."
        )

    return PreflightResult(
        current_version=current,
        next_ver=nv,
        bump_level=bump,
        commit_count=commit_count,
        releasable_commits=releasable,
        breaking_commits=breaking,
        warnings=warnings,
    )


# ---------------------------------------------------------------------------
# Git I/O (thin shell, not unit-tested)
# ---------------------------------------------------------------------------

def git_latest_semver_tag() -> str:
    """Find the latest semver tag reachable from HEAD, excluding prereleases."""
    result = subprocess.run(
        ["git", "tag", "--list", "v*", "--sort=-v:refname"],
        capture_output=True, text=True, check=True,
    )
    for line in result.stdout.strip().splitlines():
        tag = line.strip()
        # Skip prereleases like v1.0.0-beta.1
        if re.match(r"^v\d+\.\d+\.\d+$", tag):
            return tag
    raise RuntimeError("No semver tags found (expected v*.*.* format)")


def git_commits_since(tag: str) -> list[str]:
    """Get full commit messages since the given tag."""
    result = subprocess.run(
        ["git", "log", f"{tag}..HEAD", "--format=%B---COMMIT_SEP---"],
        capture_output=True, text=True, check=True,
    )
    raw = result.stdout.strip()
    if not raw:
        return []
    return [m.strip() for m in raw.split("---COMMIT_SEP---") if m.strip()]


def git_commit_count_since(tag: str) -> int:
    """Count commits since the given tag."""
    result = subprocess.run(
        ["git", "rev-list", "--count", f"{tag}..HEAD"],
        capture_output=True, text=True, check=True,
    )
    return int(result.stdout.strip())


def git_short_sha() -> str:
    """Get the short SHA of HEAD."""
    result = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"],
        capture_output=True, text=True, check=True,
    )
    return result.stdout.strip()


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Qorche version calculator")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--stamp", action="store_true", help="Print dev version stamp")
    group.add_argument("--preflight", action="store_true", help="Run PR preflight checks")
    group.add_argument("--dry-run", action="store_true", help="Show next release version")
    group.add_argument("--current", action="store_true", help="Show current version from latest tag")
    args = parser.parse_args(argv)

    try:
        tag = git_latest_semver_tag()
        messages = git_commits_since(tag)
        commits = [classify_commit(m) for m in messages]
        commit_count = git_commit_count_since(tag)
    except RuntimeError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as e:
        print(f"Git command failed: {e}", file=sys.stderr)
        return 1

    current = parse_version(tag)

    if args.current:
        print(f"v{current}")
        return 0

    if args.stamp:
        print(stamp_dev(current, commits, commit_count))
        return 0

    if args.dry_run:
        nv = next_version(current, commits)
        bump = calculate_bump(commits)
        print(f"Current: v{current}")
        print(f"Next:    v{nv}")
        print(f"Bump:    {bump.name.lower() if bump != BumpLevel.NONE else 'none'}")
        print(f"Commits: {commit_count} since {tag}")
        releasable = filter_releasable(commits)
        if releasable:
            print(f"Releasable commits ({len(releasable)}):")
            for c in releasable:
                prefix = "BREAKING " if c.breaking else ""
                print(f"  {prefix}{c.commit_type}: {c.description}")
        return 0

    if args.preflight:
        result = preflight_check(current, commits, commit_count)
        print(f"Current version: v{result.current_version}")
        print(f"Expected next:   v{result.next_ver}")
        print(f"Bump level:      {result.bump_level.name.lower()}")
        print(f"Commits since {tag}: {result.commit_count}")
        print(f"Releasable:      {len(result.releasable_commits)}")

        if result.warnings:
            print()
            for w in result.warnings:
                print(f"WARNING: {w}")
            return 1

        print("\nAll checks passed.")
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())
