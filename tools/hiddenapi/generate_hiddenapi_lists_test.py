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

    def test_get_package_name(self):
        self.assertEqual(get_package_name("Ljava/lang/String;->clone()V"), "Ljava/lang/")

    def test_get_package_name_fail_no_arrow(self):
        with self.assertRaises(AssertionError) as ar:
            get_package_name("Ljava/lang/String;-clone()V")
        with self.assertRaises(AssertionError) as ar:
            get_package_name("Ljava/lang/String;>clone()V")
        with self.assertRaises(AssertionError) as ar:
            get_package_name("Ljava/lang/String;__clone()V")

    def test_get_package_name_fail_no_package(self):
        with self.assertRaises(AssertionError) as ar:
            get_package_name("LString;->clone()V")

    def test_all_package_names(self):
        self.assertEqual(all_package_names(), set())
        self.assertEqual(all_package_names(set(["Lfoo/Bar;->baz()V"])), set(["Lfoo/"]))
        self.assertEqual(
            all_package_names(set(["Lfoo/Bar;->baz()V", "Lfoo/BarX;->bazx()I"])),
            set(["Lfoo/"]))
        self.assertEqual(
            all_package_names(
                set(["Lfoo/Bar;->baz()V"]),
                set(["Lfoo/BarX;->bazx()I", "Labc/xyz/Mno;->ijk()J"])),
            set(["Lfoo/", "Labc/xyz/"]))

    def test_move_all(self):
        src = set([ "abc", "xyz" ])
        dst = set([ "def" ])
        move_all(src, dst)
        self.assertEqual(src, set())
        self.assertEqual(dst, set([ "abc", "def", "xyz" ]))

    def test_move_from_packages(self):
        src = set([ "Lfoo/bar/ClassA;->abc()J",        # will be moved
                    "Lfoo/bar/ClassA;->def()J",        # will be moved
                    "Lcom/pkg/example/ClassD;->ijk:J", # not moved: different package
                    "Lfoo/bar/xyz/ClassC;->xyz()Z" ])  # not moved: subpackage
        dst = set()
        packages = set([ "Lfoo/bar/" ])
        move_from_packages(packages, src, dst)
        self.assertEqual(
            src, set([ "Lfoo/bar/xyz/ClassC;->xyz()Z", "Lcom/pkg/example/ClassD;->ijk:J" ]))
        self.assertEqual(
            dst, set([ "Lfoo/bar/ClassA;->abc()J", "Lfoo/bar/ClassA;->def()J" ]))

    def test_move_serialization(self):
        # All the entries should be moved apart from the last one
        src = set([ "Lfoo/bar/ClassA;->readObject(Ljava/io/ObjectInputStream;)V",
                    "Lfoo/bar/ClassA;->readObjectNoData()V",
                    "Lfoo/bar/ClassA;->readResolve()Ljava/lang/Object;",
                    "Lfoo/bar/ClassA;->serialVersionUID:J",
                    "Lfoo/bar/ClassA;->serialPersistentFields:[Ljava/io/ObjectStreamField;",
                    "Lfoo/bar/ClassA;->writeObject(Ljava/io/ObjectOutputStream;)V",
                    "Lfoo/bar/ClassA;->writeReplace()Ljava/lang/Object;",
                    # Should not be moved as signature does not match
                    "Lfoo/bar/ClassA;->readObject(Ljava/io/ObjectInputStream;)I"])
        expectedToMove = len(src) - 1
        dst = set()
        packages = set([ "Lfoo/bar/" ])
        move_serialization(src, dst)
        self.assertEqual(len(src), 1)
        self.assertEqual(len(dst), expectedToMove)

if __name__ == '__main__':
    unittest.main()
