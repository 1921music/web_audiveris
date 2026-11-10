import unittest
from compare_compat import validate_roots


class RootComparisonTest(unittest.TestCase):
    def test_additions_need_explicit_scope_change(self):
        old = {'recognize': ('Engine', 'true', 'false', 'engine')}
        new = {**old, 'new-input-api': ('Engine', 'true', 'false', 'engine')}
        with self.assertRaises(AssertionError):
            validate_roots(old, new)
        self.assertEqual(['new-input-api'], validate_roots(old, new, True))

    def test_opt_in_never_allows_removal_or_changed_original_coverage(self):
        old = {'recognize': ('Engine', 'true', 'false', 'engine')}
        for new in [{}, {'recognize': ('Engine', 'false', 'false', 'engine')},
                    {'recognize': ('Engine', 'true', 'false', 'ui')}]:
            with self.assertRaises(AssertionError):
                validate_roots(old, new, True)

    def test_unchanged_roots_keep_the_strict_comparison(self):
        self.assertEqual([], validate_roots({'a': 1}, {'a': 1}))
