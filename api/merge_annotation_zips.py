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

"""Script to merge annotation XML files (created by e.g. metalava)."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile


def validate_xml_assumptions(root):
  """Verify the format of the annotations XML matches expectations"""
  prevName = ""
  assert root.tag == 'root'
  for child in root:
    assert child.tag == 'item', 'unexpected tag: %s' % child.tag
    assert list(child.attrib.keys()) == ['name'], 'unexpected attribs: %s' % child.attrib.keys()
    assert prevName < child.get('name'), 'items unexpectedly not strictly sorted (possibly duplicate entries)'
    prevName = child.get('name')


def merge_xml(a, b):
  """Merge two annotation xml files"""
  for xml in [a, b]:
    validate_xml_assumptions(xml)
  a.extend(b[:])
  a[:] = sorted(a[:], key=lambda x: x.get('name'))
  validate_xml_assumptions(a)


def merge_zip_file(out_dir, zip_file):
  """Merge the content of the zip_file into out_dir"""
  for filename in zip_file.namelist():
    path = Path(out_dir, filename)
    if path.exists():
      existing_xml = ET.parse(path)
      with zip_file.open(filename) as other_file:
        other_xml = ET.parse(other_file)
      merge_xml(existing_xml.getroot(), other_xml.getroot())
      existing_xml.write(path, encoding='UTF-8', xml_declaration=True)
    else:
      zip_file.extract(filename, out_dir)


def main():
  out_dir = Path(sys.argv[1])
  zip_filenames = sys.argv[2:]

  assert not out_dir.exists()
  out_dir.mkdir()
  for zip_filename in zip_filenames:
    with zipfile.ZipFile(zip_filename) as zip_file:
      merge_zip_file(out_dir, zip_file)


if __name__ == "__main__":
  main()
