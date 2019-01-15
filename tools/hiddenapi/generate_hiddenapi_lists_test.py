#!/usr/bin/env python
#
# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Unit tests for Hidden API list generation."""
import unittest
from generate_hiddenapi_lists import *

class TestHiddenapiListGeneration(unittest.TestCase):
    def test_init(self):
        # Check empty lists
        flags = FlagsDict([], [])
        self.assertEquals(flags.generate_csv(), [])

        # Check valid input - two public and two private API signatures.
        flags = FlagsDict(['A', 'B'], ['C', 'D'])
        self.assertEquals(flags.generate_csv(),
                          [ 'A,' + FLAG_WHITELIST, 'B,' + FLAG_WHITELIST, 'C', 'D' ])

        # Check invalid input - overlapping public/private API signatures.
        with self.assertRaises(AssertionError):
            flags = FlagsDict(['A', 'B'], ['B', 'C', 'D'])

    def test_filter_apis(self):
        # Initialize flags so that A and B are put on the whitelist and
        # C, D, E are left unassigned. Try filtering for the unassigned ones.
        flags = FlagsDict(['A', 'B'], ['C', 'D', 'E'])
        filter_set = flags.filter_apis(lambda api, flags: not flags)
        self.assertTrue(isinstance(filter_set, set))
        self.assertEqual(filter_set, set([ 'C', 'D', 'E' ]))

    def test_get_valid_subset_of_unassigned_keys(self):
        # Create flags where only A is unassigned.
        flags = FlagsDict(['A'], ['B', 'C'])
        flags.assign_flag(FLAG_GREYLIST, set(['C']))
        self.assertEquals(flags.generate_csv(),
            [ 'A,' + FLAG_WHITELIST, 'B', 'C,' + FLAG_GREYLIST ])

        # Check three things:
        # (1) B is selected as valid unassigned
        # (2) A is not selected because it is assigned 'whitelist'
        # (3) D is not selected because it is not a valid key
        self.assertEqual(
            flags.get_valid_subset_of_unassigned_apis(set(['A', 'B', 'D'])), set([ 'B' ]))

    def test_parse_and_merge_csv(self):
        flags = FlagsDict(['A'], ['B'])
        self.assertEquals(flags.generate_csv(), [ 'A,' + FLAG_WHITELIST, 'B' ])

        # Test empty CSV entry.
        flags.parse_and_merge_csv(['B'])
        self.assertEquals(flags.generate_csv(), [ 'A,' + FLAG_WHITELIST, 'B' ])

        # Test assigning an already assigned flag.
        flags.parse_and_merge_csv(['A,' + FLAG_WHITELIST])
        self.assertEquals(flags.generate_csv(), [ 'A,' + FLAG_WHITELIST, 'B' ])

        # Test new additions.
        flags.parse_and_merge_csv([
            'A,' + FLAG_GREYLIST,
            'B,' + FLAG_BLACKLIST + ',' + FLAG_GREYLIST_MAX_O ])
        self.assertEqual(flags.generate_csv(),
            [ 'A,' + FLAG_GREYLIST + "," + FLAG_WHITELIST,
              'B,' + FLAG_BLACKLIST + "," + FLAG_GREYLIST_MAX_O ])

        # Test unknown API signature.
        with self.assertRaises(AssertionError):
            flags.parse_and_merge_csv([ 'C' ])

        # Test unknown flag.
        with self.assertRaises(AssertionError):
            flags.parse_and_merge_csv([ 'A,foo' ])

    def test_assign_flag(self):
        flags = FlagsDict(['A'], ['B'])
        self.assertEquals(flags.generate_csv(), [ 'A,' + FLAG_WHITELIST, 'B' ])

        # Test assigning an already assigned flag.
        flags.assign_flag(FLAG_WHITELIST, set([ 'A' ]))
        self.assertEquals(flags.generate_csv(), [ 'A,' + FLAG_WHITELIST, 'B' ])

        # Test new additions.
        flags.assign_flag(FLAG_GREYLIST, set([ 'A', 'B' ]))
        self.assertEquals(flags.generate_csv(),
            [ 'A,' + FLAG_GREYLIST + "," + FLAG_WHITELIST, 'B,' + FLAG_GREYLIST ])

        # Test invalid API signature.
        with self.assertRaises(AssertionError):
            flags.assign_flag(FLAG_WHITELIST, set([ 'C' ]))

        # Test invalid flag.
        with self.assertRaises(AssertionError):
            flags.assign_flag('foo', set([ 'A' ]))

if __name__ == '__main__':
    unittest.main()
