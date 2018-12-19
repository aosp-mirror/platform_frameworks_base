#!/usr/bin/env python

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

import unittest

import apilint

def cls(pkg, name):
    return apilint.Class(apilint.Package(999, "package %s {" % pkg, None), 999,
                  "public final class %s {" % name, None)

_ri = apilint._retry_iterator

c1 = cls("android.app", "ActivityManager")
c2 = cls("android.app", "Notification")
c3 = cls("android.app", "Notification.Action")
c4 = cls("android.graphics", "Bitmap")

class UtilTests(unittest.TestCase):
    def test_retry_iterator(self):
        it = apilint._retry_iterator([1, 2, 3, 4])
        self.assertEqual(it.next(), 1)
        self.assertEqual(it.next(), 2)
        self.assertEqual(it.next(), 3)
        it.send("retry")
        self.assertEqual(it.next(), 3)
        self.assertEqual(it.next(), 4)
        with self.assertRaises(StopIteration):
            it.next()

    def test_retry_iterator_one(self):
        it = apilint._retry_iterator([1])
        self.assertEqual(it.next(), 1)
        it.send("retry")
        self.assertEqual(it.next(), 1)
        with self.assertRaises(StopIteration):
            it.next()

    def test_retry_iterator_one(self):
        it = apilint._retry_iterator([1])
        self.assertEqual(it.next(), 1)
        it.send("retry")
        self.assertEqual(it.next(), 1)
        with self.assertRaises(StopIteration):
            it.next()

    def test_skip_to_matching_class_found(self):
        it = _ri([c1, c2, c3, c4])
        self.assertEquals(apilint._parse_to_matching_class(it, c3),
                          c3)
        self.assertEqual(it.next(), c4)

    def test_skip_to_matching_class_not_found(self):
        it = _ri([c1, c2, c3, c4])
        self.assertEquals(apilint._parse_to_matching_class(it, cls("android.content", "ContentProvider")),
                          None)
        self.assertEqual(it.next(), c4)

if __name__ == "__main__":
    unittest.main()