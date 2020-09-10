#!/usr/bin/env python3
#
# Copyright 2019, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""
Unit tests for inode2filename module.

Install:
  $> sudo apt-get install python3-pytest   ##  OR
  $> pip install -U pytest
See also https://docs.pytest.org/en/latest/getting-started.html

Usage:
  $> ./inode2filename_test.py
  $> pytest inode2filename_test.py
  $> python -m pytest inode2filename_test.py

See also https://docs.pytest.org/en/latest/usage.html
"""

# global imports
from contextlib import contextmanager
import io
import shlex
import sys
import typing

# pip imports
import pytest

# local imports
from inode2filename import *

def create_inode2filename(*contents):
  buf = io.StringIO()

  for c in contents:
    buf.write(c)
    buf.write("\n")

  buf.seek(0)

  i2f = Inode2Filename(buf)

  buf.close()

  return i2f

def test_inode2filename():
  a = create_inode2filename("")
  assert len(a) == 0
  assert a.resolve(1, 2) == None

  a = create_inode2filename("1 2 3 foo.bar")
  assert len(a) == 1
  assert a.resolve(1, 2) == "foo.bar"
  assert a.resolve(4, 5) == None

  a = create_inode2filename("1 2 3 foo.bar", "4 5 6 bar.baz")
  assert len(a) == 2
  assert a.resolve(1, 2) == "foo.bar"
  assert a.resolve(4, 5) == "bar.baz"

  a = create_inode2filename("1567d 8910 -1 /a/b/c/", "4 5 6 bar.baz")
  assert len(a) == 2
  assert a.resolve(1567, 8910) == "/a/b/c/"
  assert a.resolve(4, 5) == "bar.baz"

if __name__ == '__main__':
  pytest.main()
