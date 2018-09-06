#!/usr/bin/env python
#
# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Unit tests for Hidden API list generation."""
import unittest
from generate_hiddenapi_lists import *

class TestHiddenapiListGeneration(unittest.TestCase):

    def test_move_between_sets(self):
        A = set([1, 2, 3, 4])
        B = set([5, 6, 7, 8])
        move_between_sets(set([2, 4]), A, B)
        self.assertEqual(A, set([1, 3]))
        self.assertEqual(B, set([2, 4, 5, 6, 7, 8]))

    def test_move_between_sets_fail_not_superset(self):
        A = set([1, 2, 3, 4])
        B = set([5, 6, 7, 8])
        with self.assertRaises(AssertionError) as ar:
            move_between_sets(set([0, 2]), A, B)

    def test_move_between_sets_fail_not_disjoint(self):
        A = set([1, 2, 3, 4])
        B = set([4, 5, 6, 7, 8])
        with self.assertRaises(AssertionError) as ar:
            move_between_sets(set([1, 4]), A, B)

    def test_move_all(self):
        src = set([ "abc", "xyz" ])
        dst = set([ "def" ])
        move_all(src, dst)
        self.assertEqual(src, set())
        self.assertEqual(dst, set([ "abc", "def", "xyz" ]))

if __name__ == '__main__':
    unittest.main()
