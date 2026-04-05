#!/usr/bin/env python3
"""Unit tests for version_calc.py — pure logic only, no git calls."""

import unittest

from version_calc import (
    BumpLevel,
    CommitInfo,
    Version,
    calculate_bump,
    classify_commit,
    filter_releasable,
    next_version,
    parse_version,
    preflight_check,
    stamp_dev,
)


class TestParseVersion(unittest.TestCase):
    def test_with_v_prefix(self):
        self.assertEqual(parse_version("v0.1.0"), Version(0, 1, 0))

    def test_without_prefix(self):
        self.assertEqual(parse_version("1.2.3"), Version(1, 2, 3))

    def test_invalid_raises(self):
        with self.assertRaises(ValueError):
            parse_version("v1.0")

    def test_invalid_non_numeric_raises(self):
        with self.assertRaises(ValueError):
            parse_version("v1.0.x")

    def test_zero_version(self):
        self.assertEqual(parse_version("v0.0.0"), Version(0, 0, 0))

    def test_large_numbers(self):
        self.assertEqual(parse_version("v10.20.300"), Version(10, 20, 300))


class TestVersionBump(unittest.TestCase):
    def test_patch_bump(self):
        v = Version(0, 1, 0)
        self.assertEqual(v.bump(BumpLevel.PATCH), Version(0, 1, 1))

    def test_minor_bump_resets_patch(self):
        v = Version(0, 1, 5)
        self.assertEqual(v.bump(BumpLevel.MINOR), Version(0, 2, 0))

    def test_major_bump_resets_minor_and_patch(self):
        v = Version(0, 3, 7)
        self.assertEqual(v.bump(BumpLevel.MAJOR), Version(1, 0, 0))

    def test_none_bump_no_change(self):
        v = Version(0, 1, 0)
        self.assertEqual(v.bump(BumpLevel.NONE), Version(0, 1, 0))

    def test_str(self):
        self.assertEqual(str(Version(0, 2, 1)), "0.2.1")


class TestClassifyCommit(unittest.TestCase):
    def test_feat(self):
        c = classify_commit("feat: add replay command")
        self.assertEqual(c.commit_type, "feat")
        self.assertEqual(c.description, "add replay command")
        self.assertFalse(c.breaking)
        self.assertIsNone(c.scope)

    def test_fix_with_scope(self):
        c = classify_commit("fix(cli): handle null path")
        self.assertEqual(c.commit_type, "fix")
        self.assertEqual(c.scope, "cli")
        self.assertFalse(c.breaking)

    def test_breaking_bang(self):
        c = classify_commit("feat!: new API surface")
        self.assertEqual(c.commit_type, "feat")
        self.assertTrue(c.breaking)

    def test_breaking_bang_with_scope(self):
        c = classify_commit("refactor(core)!: rename Snapshot fields")
        self.assertEqual(c.commit_type, "refactor")
        self.assertEqual(c.scope, "core")
        self.assertTrue(c.breaking)

    def test_breaking_footer(self):
        msg = "feat: change output format\n\nBREAKING CHANGE: JSON output keys renamed"
        c = classify_commit(msg)
        self.assertTrue(c.breaking)

    def test_breaking_footer_hyphenated(self):
        msg = "feat: change output\n\nBREAKING-CHANGE: removed --verbose"
        c = classify_commit(msg)
        self.assertTrue(c.breaking)

    def test_non_conventional(self):
        c = classify_commit("Merge pull request #42 from feature/foo")
        self.assertEqual(c.commit_type, "other")
        self.assertFalse(c.breaking)

    def test_chore(self):
        c = classify_commit("chore: update dependencies")
        self.assertEqual(c.commit_type, "chore")
        self.assertFalse(c.breaking)

    def test_perf(self):
        c = classify_commit("perf: optimize file hashing")
        self.assertEqual(c.commit_type, "perf")

    def test_revert(self):
        c = classify_commit("revert: undo bad change")
        self.assertEqual(c.commit_type, "revert")

    def test_multiline_only_parses_first_line(self):
        msg = "fix: handle edge case\n\nThis fixes a tricky bug where..."
        c = classify_commit(msg)
        self.assertEqual(c.commit_type, "fix")
        self.assertEqual(c.description, "handle edge case")


class TestCalculateBump(unittest.TestCase):
    def test_no_commits(self):
        self.assertEqual(calculate_bump([]), BumpLevel.NONE)

    def test_only_chore(self):
        commits = [classify_commit("chore: cleanup")]
        self.assertEqual(calculate_bump(commits), BumpLevel.NONE)

    def test_single_fix(self):
        commits = [classify_commit("fix: null pointer")]
        self.assertEqual(calculate_bump(commits), BumpLevel.PATCH)

    def test_single_feat(self):
        commits = [classify_commit("feat: new command")]
        self.assertEqual(calculate_bump(commits), BumpLevel.MINOR)

    def test_feat_overrides_fix(self):
        commits = [
            classify_commit("fix: small bug"),
            classify_commit("feat: big feature"),
            classify_commit("fix: another bug"),
        ]
        self.assertEqual(calculate_bump(commits), BumpLevel.MINOR)

    def test_breaking_overrides_everything(self):
        commits = [
            classify_commit("fix: bug"),
            classify_commit("feat: feature"),
            classify_commit("feat!: breaking change"),
        ]
        self.assertEqual(calculate_bump(commits), BumpLevel.MAJOR)

    def test_perf_is_patch(self):
        commits = [classify_commit("perf: faster hashing")]
        self.assertEqual(calculate_bump(commits), BumpLevel.PATCH)

    def test_revert_is_patch(self):
        commits = [classify_commit("revert: undo change")]
        self.assertEqual(calculate_bump(commits), BumpLevel.PATCH)

    def test_mixed_non_releasable(self):
        commits = [
            classify_commit("chore: deps"),
            classify_commit("docs: readme"),
            classify_commit("test: add tests"),
            classify_commit("ci: fix workflow"),
        ]
        self.assertEqual(calculate_bump(commits), BumpLevel.NONE)

    def test_merge_commits_ignored(self):
        commits = [
            classify_commit("Merge pull request #10"),
            classify_commit("feat: actual feature"),
        ]
        self.assertEqual(calculate_bump(commits), BumpLevel.MINOR)


class TestFilterReleasable(unittest.TestCase):
    def test_filters_releasable_only(self):
        commits = [
            classify_commit("feat: new thing"),
            classify_commit("fix: bug"),
            classify_commit("chore: cleanup"),
            classify_commit("docs: readme"),
            classify_commit("perf: faster"),
        ]
        result = filter_releasable(commits)
        self.assertEqual(len(result), 3)
        types = {c.commit_type for c in result}
        self.assertEqual(types, {"feat", "fix", "perf"})

    def test_includes_breaking(self):
        commits = [
            classify_commit("refactor!: breaking change"),
            classify_commit("chore: nothing"),
        ]
        result = filter_releasable(commits)
        self.assertEqual(len(result), 1)
        self.assertTrue(result[0].breaking)

    def test_empty_list(self):
        self.assertEqual(filter_releasable([]), [])


class TestNextVersion(unittest.TestCase):
    def test_feat_bumps_minor(self):
        current = Version(0, 1, 0)
        commits = [classify_commit("feat: new thing")]
        self.assertEqual(next_version(current, commits), Version(0, 2, 0))

    def test_fix_bumps_patch(self):
        current = Version(0, 1, 0)
        commits = [classify_commit("fix: bug")]
        self.assertEqual(next_version(current, commits), Version(0, 1, 1))

    def test_breaking_bumps_major(self):
        current = Version(0, 5, 3)
        commits = [classify_commit("feat!: new API")]
        self.assertEqual(next_version(current, commits), Version(1, 0, 0))

    def test_no_releasable_stays_same(self):
        current = Version(0, 1, 0)
        commits = [classify_commit("chore: cleanup")]
        self.assertEqual(next_version(current, commits), Version(0, 1, 0))

    def test_multiple_feats_still_one_minor(self):
        current = Version(0, 1, 0)
        commits = [
            classify_commit("feat: a"),
            classify_commit("feat: b"),
            classify_commit("feat: c"),
        ]
        self.assertEqual(next_version(current, commits), Version(0, 2, 0))

    def test_three_fixes_and_one_feat(self):
        current = Version(0, 1, 0)
        commits = [
            classify_commit("fix: a"),
            classify_commit("fix: b"),
            classify_commit("feat: c"),
            classify_commit("fix: d"),
        ]
        self.assertEqual(next_version(current, commits), Version(0, 2, 0))


class TestStampDev(unittest.TestCase):
    def test_basic_stamp(self):
        current = Version(0, 1, 0)
        commits = [classify_commit("feat: new thing")]
        result = stamp_dev(current, commits, 5)
        self.assertEqual(result, "v0.2.0-dev.5")

    def test_stamp_with_fix(self):
        current = Version(0, 1, 0)
        commits = [classify_commit("fix: bug")]
        result = stamp_dev(current, commits, 3)
        self.assertEqual(result, "v0.1.1-dev.3")

    def test_stamp_no_releasable_falls_back_to_patch(self):
        current = Version(0, 1, 0)
        commits = [classify_commit("chore: cleanup")]
        result = stamp_dev(current, commits, 2)
        self.assertEqual(result, "v0.1.1-dev.2")

    def test_stamp_breaking(self):
        current = Version(0, 3, 0)
        commits = [classify_commit("feat!: breaking")]
        result = stamp_dev(current, commits, 10)
        self.assertEqual(result, "v1.0.0-dev.10")

    def test_stamp_empty_commits(self):
        current = Version(0, 1, 0)
        result = stamp_dev(current, [], 0)
        self.assertEqual(result, "v0.1.1-dev.0")


class TestPreflightCheck(unittest.TestCase):
    def test_clean_feat(self):
        current = Version(0, 1, 0)
        commits = [classify_commit("feat: add feature")]
        result = preflight_check(current, commits, 1)
        self.assertEqual(result.next_ver, Version(0, 2, 0))
        self.assertEqual(result.bump_level, BumpLevel.MINOR)
        self.assertEqual(len(result.releasable_commits), 1)
        self.assertFalse(result.has_warnings)

    def test_breaking_warns(self):
        current = Version(0, 1, 0)
        commits = [classify_commit("feat!: new API")]
        result = preflight_check(current, commits, 1)
        self.assertEqual(result.next_ver, Version(1, 0, 0))
        self.assertTrue(result.has_warnings)
        self.assertEqual(len(result.breaking_commits), 1)
        self.assertTrue(any("BREAKING" in w for w in result.warnings))

    def test_no_releasable_warns(self):
        current = Version(0, 1, 0)
        commits = [classify_commit("chore: cleanup"), classify_commit("docs: readme")]
        result = preflight_check(current, commits, 2)
        self.assertTrue(result.has_warnings)
        self.assertTrue(any("No releasable" in w for w in result.warnings))

    def test_mixed_commits(self):
        current = Version(0, 1, 0)
        commits = [
            classify_commit("chore: deps"),
            classify_commit("feat: new command"),
            classify_commit("fix: bug"),
            classify_commit("test: add tests"),
        ]
        result = preflight_check(current, commits, 4)
        self.assertEqual(result.bump_level, BumpLevel.MINOR)
        self.assertEqual(len(result.releasable_commits), 2)  # feat + fix
        self.assertFalse(result.has_warnings)

    def test_commit_count_passed_through(self):
        current = Version(0, 1, 0)
        commits = [classify_commit("fix: thing")]
        result = preflight_check(current, commits, 42)
        self.assertEqual(result.commit_count, 42)


if __name__ == "__main__":
    unittest.main()
