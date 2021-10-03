#!/usr/bin/env python3
#
# Copyright (C) 2021 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import io
import re
import unittest
import xml.etree.ElementTree as ET
import zipfile

import api_versions_trimmer


def create_in_memory_zip_file(files):
  f = io.BytesIO()
  with zipfile.ZipFile(f, "w") as z:
    for fname in files:
      with z.open(fname, mode="w") as class_file:
        class_file.write(b"")
  return f


def indent(elem, level=0):
  i = "\n" + level * "  "
  j = "\n" + (level - 1) * "  "
  if len(elem):
    if not elem.text or not elem.text.strip():
      elem.text = i + "  "
      if not elem.tail or not elem.tail.strip():
        elem.tail = i
        for subelem in elem:
          indent(subelem, level + 1)
        if not elem.tail or not elem.tail.strip():
          elem.tail = j
    else:
      if level and (not elem.tail or not elem.tail.strip()):
        elem.tail = j
    return elem


def pretty_print(s):
  tree = ET.parse(io.StringIO(s))
  el = indent(tree.getroot())
  res = ET.tostring(el).decode("utf-8")
  # remove empty lines inside the result because this still breaks some
  # comparisons
  return re.sub(r"\n\s*\n", "\n", res, re.MULTILINE)


class ApiVersionsTrimmerUnittests(unittest.TestCase):

  def setUp(self):
    # so it prints diffs in long strings (xml files)
    self.maxDiff = None

  def test_read_classes(self):
    f = create_in_memory_zip_file(
        ["a/b/C.class",
         "a/b/D.class",
        ]
    )
    res = api_versions_trimmer.read_classes(f)
    self.assertEqual({"a/b/C", "a/b/D"}, res)

  def test_read_classes_ignore_dex(self):
    f = create_in_memory_zip_file(
        ["a/b/C.class",
         "a/b/D.class",
         "a/b/E.dex",
         "f.dex",
        ]
    )
    res = api_versions_trimmer.read_classes(f)
    self.assertEqual({"a/b/C", "a/b/D"}, res)

  def test_read_classes_ignore_manifest(self):
    f = create_in_memory_zip_file(
        ["a/b/C.class",
         "a/b/D.class",
         "META-INFO/G.class"
        ]
    )
    res = api_versions_trimmer.read_classes(f)
    self.assertEqual({"a/b/C", "a/b/D"}, res)

  def test_filter_method_signature(self):
    xml = """
    <method name="dispatchGesture(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureResultCallback;Landroid/os/Handler;)Z" since="24"/>
    """
    method = ET.fromstring(xml)
    classes_to_remove = {"android/accessibilityservice/GestureDescription"}
    expected = "dispatchGesture(Ljava/lang/Object;Landroid/accessibilityservice/AccessibilityService$GestureResultCallback;Landroid/os/Handler;)Z"
    api_versions_trimmer.filter_method_tag(method, classes_to_remove)
    self.assertEqual(expected, method.get("name"))

  def test_filter_method_signature_with_L_in_method(self):
    xml = """
    <method name="dispatchLeftGesture(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureResultCallback;Landroid/os/Handler;)Z" since="24"/>
    """
    method = ET.fromstring(xml)
    classes_to_remove = {"android/accessibilityservice/GestureDescription"}
    expected = "dispatchLeftGesture(Ljava/lang/Object;Landroid/accessibilityservice/AccessibilityService$GestureResultCallback;Landroid/os/Handler;)Z"
    api_versions_trimmer.filter_method_tag(method, classes_to_remove)
    self.assertEqual(expected, method.get("name"))

  def test_filter_method_signature_with_L_in_class(self):
    xml = """
    <method name="dispatchGesture(Landroid/accessibilityservice/LeftGestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureResultCallback;Landroid/os/Handler;)Z" since="24"/>
    """
    method = ET.fromstring(xml)
    classes_to_remove = {"android/accessibilityservice/LeftGestureDescription"}
    expected = "dispatchGesture(Ljava/lang/Object;Landroid/accessibilityservice/AccessibilityService$GestureResultCallback;Landroid/os/Handler;)Z"
    api_versions_trimmer.filter_method_tag(method, classes_to_remove)
    self.assertEqual(expected, method.get("name"))

  def test_filter_method_signature_with_inner_class(self):
    xml = """
    <method name="dispatchGesture(Landroid/accessibilityservice/GestureDescription$Inner;Landroid/accessibilityservice/AccessibilityService$GestureResultCallback;Landroid/os/Handler;)Z" since="24"/>
    """
    method = ET.fromstring(xml)
    classes_to_remove = {"android/accessibilityservice/GestureDescription$Inner"}
    expected = "dispatchGesture(Ljava/lang/Object;Landroid/accessibilityservice/AccessibilityService$GestureResultCallback;Landroid/os/Handler;)Z"
    api_versions_trimmer.filter_method_tag(method, classes_to_remove)
    self.assertEqual(expected, method.get("name"))

  def _run_filter_db_test(self, database_str, expected):
    """Performs the pattern of testing the filter_lint_database method.

    Filters instances of the class "a/b/C" (hard-coded) from the database string
    and compares the result with the expected result (performs formatting of
    the xml of both inputs)

    Args:
      database_str: string, the contents of the lint database (api-versions.xml)
      expected: string, the expected result after filtering the original
    database
    """
    database = io.StringIO(database_str)
    classes_to_remove = {"a/b/C"}
    output = io.BytesIO()
    api_versions_trimmer.filter_lint_database(
        database,
        classes_to_remove,
        output
    )
    expected = pretty_print(expected)
    res = pretty_print(output.getvalue().decode("utf-8"))
    self.assertEqual(expected, res)

  def test_filter_lint_database_updates_method_signature_params(self):
    self._run_filter_db_test(
        database_str="""
    <api version="2">
      <!-- will be removed -->
      <class name="a/b/C" since="1">
        <extends name="java/lang/Object"/>
      </class>

      <class name="a/b/E" since="1">
        <!-- extends will be modified -->
        <extends name="a/b/C"/>
        <!-- first parameter will be modified -->
        <method name="dispatchGesture(La/b/C;Landroid/os/Handler;)Z" since="24"/>
        <!-- second should remain untouched -->
        <method name="dispatchGesture(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureRe
sultCallback;Landroid/os/Handler;)Z" since="24"/>
      </class>
    </api>
    """,
        expected="""
    <api version="2">
      <class name="a/b/E" since="1">
        <extends name="java/lang/Object"/>
        <method name="dispatchGesture(Ljava/lang/Object;Landroid/os/Handler;)Z" since="24"/>
        <method name="dispatchGesture(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureRe
sultCallback;Landroid/os/Handler;)Z" since="24"/>
      </class>
    </api>
    """)

  def test_filter_lint_database_updates_method_signature_return(self):
    self._run_filter_db_test(
        database_str="""
    <api version="2">
      <!-- will be removed -->
      <class name="a/b/C" since="1">
        <extends name="java/lang/Object"/>
      </class>

      <class name="a/b/E" since="1">
        <!-- extends will be modified -->
        <extends name="a/b/C"/>
        <!-- return type should be changed -->
        <method name="gestureIdToString(I)La/b/C;" since="24"/>
      </class>
    </api>
    """,
        expected="""
    <api version="2">
      <class name="a/b/E" since="1">

        <extends name="java/lang/Object"/>

        <method name="gestureIdToString(I)Ljava/lang/Object;" since="24"/>
      </class>
    </api>
    """)

  def test_filter_lint_database_removes_implements(self):
    self._run_filter_db_test(
        database_str="""
    <api version="2">
      <!-- will be removed -->
      <class name="a/b/C" since="1">
        <extends name="java/lang/Object"/>
      </class>

      <class name="a/b/D" since="1">
        <extends name="java/lang/Object"/>
        <implements name="a/b/C"/>
        <method name="dispatchGesture(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureRe
sultCallback;Landroid/os/Handler;)Z" since="24"/>
      </class>
    </api>
    """,
        expected="""
    <api version="2">

      <class name="a/b/D" since="1">
        <extends name="java/lang/Object"/>
        <method name="dispatchGesture(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureRe
sultCallback;Landroid/os/Handler;)Z" since="24"/>
      </class>
    </api>
    """)

  def test_filter_lint_database_updates_extends(self):
    self._run_filter_db_test(
        database_str="""
    <api version="2">
      <!-- will be removed -->
      <class name="a/b/C" since="1">
        <extends name="java/lang/Object"/>
      </class>

      <class name="a/b/E" since="1">
        <!-- extends will be modified -->
        <extends name="a/b/C"/>
        <method name="dispatchGesture(Ljava/lang/Object;Landroid/os/Handler;)Z" since="24"/>
        <method name="dispatchGesture(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureRe
sultCallback;Landroid/os/Handler;)Z" since="24"/>
      </class>
    </api>
    """,
        expected="""
    <api version="2">
      <class name="a/b/E" since="1">
        <extends name="java/lang/Object"/>
        <method name="dispatchGesture(Ljava/lang/Object;Landroid/os/Handler;)Z" since="24"/>
        <method name="dispatchGesture(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureRe
sultCallback;Landroid/os/Handler;)Z" since="24"/>
      </class>
    </api>
    """)

  def test_filter_lint_database_removes_class(self):
    self._run_filter_db_test(
        database_str="""
    <api version="2">
      <!-- will be removed -->
      <class name="a/b/C" since="1">
        <extends name="java/lang/Object"/>
      </class>

      <class name="a/b/D" since="1">
        <extends name="java/lang/Object"/>
        <method name="dispatchGesture(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureRe
sultCallback;Landroid/os/Handler;)Z" since="24"/>
      </class>
    </api>
    """,
        expected="""
    <api version="2">

      <class name="a/b/D" since="1">
        <extends name="java/lang/Object"/>
        <method name="dispatchGesture(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureRe
sultCallback;Landroid/os/Handler;)Z" since="24"/>
      </class>
    </api>
    """)


if __name__ == "__main__":
  unittest.main(verbosity=2)
