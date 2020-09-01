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
Unit tests for the app_startup_runner.py script.

Install:
  $> sudo apt-get install python3-pytest   ##  OR
  $> pip install -U pytest
See also https://docs.pytest.org/en/latest/getting-started.html

Usage:
  $> ./app_startup_runner_test.py
  $> pytest app_startup_runner_test.py
  $> python -m pytest app_startup_runner_test.py

See also https://docs.pytest.org/en/latest/usage.html
"""

import io
import shlex
import sys
import typing
# global imports
from contextlib import contextmanager

# local imports
import app_startup_runner as asr
# pip imports
import pytest

#
# Argument Parsing Helpers
#

@contextmanager
def ignore_stdout_stderr():
  """Ignore stdout/stderr output for duration of this context."""
  old_stdout = sys.stdout
  old_stderr = sys.stderr
  sys.stdout = io.StringIO()
  sys.stderr = io.StringIO()
  try:
    yield
  finally:
    sys.stdout = old_stdout
    sys.stderr = old_stderr

@contextmanager
def argparse_bad_argument(msg):
  """
  Assert that a SystemExit is raised when executing this context.
  If the assertion fails, print the message 'msg'.
  """
  with pytest.raises(SystemExit, message=msg):
    with ignore_stdout_stderr():
      yield

def assert_bad_argument(args, msg):
  """
  Assert that the command line arguments in 'args' are malformed.
  Prints 'msg' if the assertion fails.
  """
  with argparse_bad_argument(msg):
    parse_args(args)

def parse_args(args):
  """
  :param args: command-line like arguments as a single string
  :return:  dictionary of parsed key/values
  """
  # "-a b -c d"    => ['-a', 'b', '-c', 'd']
  return vars(asr.parse_options(shlex.split(args)))

def default_dict_for_parsed_args(**kwargs):
  """
  # Combine it with all of the "optional" parameters' default values.
  """
  d = {'compiler_filters': None, 'simulate': False, 'debug': False,
       'output': None, 'timeout': 10, 'loop_count': 1, 'inodes': None,
       'trace_duration': None, 'compiler_type': asr.CompilerType.DEVICE}
  d.update(kwargs)
  return d

def default_mock_dict_for_parsed_args(include_optional=True, **kwargs):
  """
  Combine default dict with all optional parameters with some mock required parameters.
  """
  d = {'packages': ['com.fake.package'], 'readaheads': ['warm']}
  if include_optional:
    d.update(default_dict_for_parsed_args())
  d.update(kwargs)
  return d

def parse_optional_args(str):
  """
  Parse an argument string which already includes all the required arguments
  in default_mock_dict_for_parsed_args.
  """
  req = "--package com.fake.package --readahead warm"
  return parse_args("%s %s" % (req, str))

def test_argparse():
  # missing arguments
  assert_bad_argument("", "-p and -r are required")
  assert_bad_argument("-r warm", "-p is required")
  assert_bad_argument("--readahead warm", "-p is required")
  assert_bad_argument("-p com.fake.package", "-r is required")
  assert_bad_argument("--package com.fake.package", "-r is required")

  # required arguments are parsed correctly
  ad = default_dict_for_parsed_args  # assert dict

  assert parse_args("--package xyz --readahead warm") == ad(packages=['xyz'],
                                                            readaheads=['warm'])
  assert parse_args("-p xyz -r warm") == ad(packages=['xyz'],
                                            readaheads=['warm'])

  assert parse_args("-p xyz -r warm -s") == ad(packages=['xyz'],
                                               readaheads=['warm'],
                                               simulate=True)
  assert parse_args("-p xyz -r warm --simulate") == ad(packages=['xyz'],
                                                       readaheads=['warm'],
                                                       simulate=True)

  # optional arguments are parsed correctly.
  mad = default_mock_dict_for_parsed_args  # mock assert dict
  assert parse_optional_args("--output filename.csv") == mad(
    output='filename.csv')
  assert parse_optional_args("-o filename.csv") == mad(output='filename.csv')

  assert parse_optional_args("--timeout 123") == mad(timeout=123)
  assert parse_optional_args("-t 456") == mad(timeout=456)

  assert parse_optional_args("--loop-count 123") == mad(loop_count=123)
  assert parse_optional_args("-lc 456") == mad(loop_count=456)

  assert parse_optional_args("--inodes bar") == mad(inodes="bar")
  assert parse_optional_args("-in baz") == mad(inodes="baz")



def test_key_to_cmdline_flag():
  assert asr.key_to_cmdline_flag("abc") == "--abc"
  assert asr.key_to_cmdline_flag("foos") == "--foo"
  assert asr.key_to_cmdline_flag("ba_r") == "--ba-r"
  assert asr.key_to_cmdline_flag("ba_zs") == "--ba-z"

def test_parse_run_script_csv_file():
  # empty file -> empty list
  f = io.StringIO("")
  assert asr.parse_run_script_csv_file(f) == None

  # common case
  f = io.StringIO("TotalTime_ms,Displayed_ms\n1,2")
  df = asr.DataFrame({'TotalTime_ms': [1], 'Displayed_ms': [2]})

  pf = asr.parse_run_script_csv_file(f)
  assert pf == df

if __name__ == '__main__':
  pytest.main()
