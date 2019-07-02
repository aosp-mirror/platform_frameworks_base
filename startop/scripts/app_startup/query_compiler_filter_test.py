#!/usr/bin/env python3
#
# Copyright 2018, The Android Open Source Project
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
Unit tests for the query_compiler_filter.py script.

Install:
  $> sudo apt-get install python3-pytest   ##  OR
  $> pip install -U pytest
See also https://docs.pytest.org/en/latest/getting-started.html

Usage:
  $> ./query_compiler_filter.py
  $> pytest query_compiler_filter.py
  $> python -m pytest query_compiler_filter.py

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
import query_compiler_filter as qcf

@contextmanager
def redirect_stdout_stderr():
  """Redirect stdout/stderr to a new StringIO for duration of context."""
  old_stdout = sys.stdout
  old_stderr = sys.stderr
  new_stdout = io.StringIO()
  sys.stdout = new_stdout
  new_stderr = io.StringIO()
  sys.stderr = new_stderr
  try:
    yield (new_stdout, new_stderr)
  finally:
    sys.stdout = old_stdout
    sys.stderr = old_stderr
    # Seek back to the beginning so we can read whatever was written into it.
    new_stdout.seek(0)
    new_stderr.seek(0)

@contextmanager
def replace_argv(argv):
  """ Temporarily replace argv for duration of this context."""
  old_argv = sys.argv
  sys.argv = [sys.argv[0]] + argv
  try:
    yield
  finally:
    sys.argv = old_argv

def exec_main(argv):
  """Run the query_compiler_filter main function with the provided arguments.

  Returns the stdout result when successful, assertion failure otherwise.
  """
  try:
    with redirect_stdout_stderr() as (the_stdout, the_stderr):
      with replace_argv(argv):
        code = qcf.main()
    assert 0 == code, the_stderr.readlines()

    all_lines = the_stdout.readlines()
    return "".join(all_lines)
  finally:
    the_stdout.close()
    the_stderr.close()

def test_query_compiler_filter():
  # no --instruction-set specified: provide whatever was the 'first' filter.
  assert exec_main(['--simulate',
                    '--package', 'com.google.android.apps.maps']) == \
      "speed-profile unknown arm64\n"

  # specifying an instruction set finds the exact compiler filter match.
  assert exec_main(['--simulate',
                    '--package', 'com.google.android.apps.maps',
                    '--instruction-set', 'arm64']) == \
      "speed-profile unknown arm64\n"

  assert exec_main(['--simulate',
                    '--package', 'com.google.android.apps.maps',
                    '--instruction-set', 'arm']) == \
      "speed first-boot arm\n"

  assert exec_main(['--simulate',
                    '--debug',
                    '--package', 'com.google.android.apps.maps',
                    '--instruction-set', 'x86']) == \
      "quicken install x86\n"

if __name__ == '__main__':
  pytest.main()
