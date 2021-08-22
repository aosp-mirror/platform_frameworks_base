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

"""Script to remove mainline APIs from the api-versions.xml."""

import argparse
import re
import xml.etree.ElementTree as ET
import zipfile


def read_classes(stubs):
  """Read classes from the stubs file.

  Args:
    stubs: argument can be a path to a file (a string), a file-like object or a
    path-like object

  Returns:
    a set of the classes found in the file (set of strings)
  """
  classes = set()
  with zipfile.ZipFile(stubs) as z:
    for info in z.infolist():
      if (not info.is_dir()
          and info.filename.endswith(".class")
          and not info.filename.startswith("META-INF")):
        # drop ".class" extension
        classes.add(info.filename[:-6])
  return classes


def filter_method_tag(method, classes_to_remove):
  """Updates the signature of this method by calling filter_method_signature.

  Updates the method passed into this function.

  Args:
    method: xml element that represents a method
    classes_to_remove: set of classes you to remove
  """
  filtered = filter_method_signature(method.get("name"), classes_to_remove)
  method.set("name", filtered)


def filter_method_signature(signature, classes_to_remove):
  """Removes mentions of certain classes from this method signature.

  Replaces any existing classes that need to be removed, with java/lang/Object

  Args:
    signature: string that is a java representation of a method signature
    classes_to_remove: set of classes you to remove
  """
  regex = re.compile("L.*?;")
  start = signature.find("(")
  matches = set(regex.findall(signature[start:]))
  for m in matches:
    # m[1:-1] to drop the leading `L` and `;` ending
    if m[1:-1] in classes_to_remove:
      signature = signature.replace(m, "Ljava/lang/Object;")
  return signature


def filter_lint_database(database, classes_to_remove, output):
  """Reads a lint database and writes a filtered version without some classes.

  Reads database from api-versions.xml and removes any references to classes
  in the second argument. Writes the result (another xml with the same format
  of the database) to output.

  Args:
    database: path to xml with lint database to read
    classes_to_remove: iterable (ideally a set or similar for quick
    lookups) that enumerates the classes that should be removed
    output: path to write the filtered database
  """
  xml = ET.parse(database)
  root = xml.getroot()
  for c in xml.findall("class"):
    cname = c.get("name")
    if cname in classes_to_remove:
      root.remove(c)
    else:
      # find the <extends /> tag inside this class to see if the parent
      # has been removed from the known classes (attribute called name)
      super_classes = c.findall("extends")
      for super_class in super_classes:
        super_class_name = super_class.get("name")
        if super_class_name in classes_to_remove:
          super_class.set("name", "java/lang/Object")
      interfaces = c.findall("implements")
      for interface in interfaces:
        interface_name = interface.get("name")
        if interface_name in classes_to_remove:
          c.remove(interface)
      for method in c.findall("method"):
        filter_method_tag(method, classes_to_remove)
  xml.write(output)


def main():
  """Run the program."""
  parser = argparse.ArgumentParser(
      description=
      ("Read a lint database (api-versions.xml) and many stubs jar files. "
       "Produce another database file that doesn't include the classes present "
       "in the stubs file(s)."))
  parser.add_argument("output", help="Destination of the result (xml file).")
  parser.add_argument(
      "api_versions",
      help="The lint database (api-versions.xml file) to read data from"
  )
  parser.add_argument("stubs", nargs="+", help="The stubs jar file(s)")
  parsed = parser.parse_args()
  classes = set()
  for stub in parsed.stubs:
    classes.update(read_classes(stub))
  filter_lint_database(parsed.api_versions, classes, parsed.output)


if __name__ == "__main__":
  main()
