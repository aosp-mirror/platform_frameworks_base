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

# global imports
from contextlib import contextmanager
import io
import shlex
import sys
import typing

# pip imports
import pytest

# local imports
import app_startup_runner as asr

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
  d = {'compiler_filters': None, 'simulate': False, 'debug': False, 'output': None, 'timeout': None, 'loop_count': 1, 'inodes': None}
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
  return parse_args("%s %s" %(req, str))

def test_argparse():
  # missing arguments
  assert_bad_argument("", "-p and -r are required")
  assert_bad_argument("-r warm", "-p is required")
  assert_bad_argument("--readahead warm", "-p is required")
  assert_bad_argument("-p com.fake.package", "-r is required")
  assert_bad_argument("--package com.fake.package", "-r is required")

  # required arguments are parsed correctly
  ad = default_dict_for_parsed_args  # assert dict

  assert parse_args("--package xyz --readahead warm") == ad(packages=['xyz'], readaheads=['warm'])
  assert parse_args("-p xyz -r warm") == ad(packages=['xyz'], readaheads=['warm'])

  assert parse_args("-p xyz -r warm -s") == ad(packages=['xyz'], readaheads=['warm'], simulate=True)
  assert parse_args("-p xyz -r warm --simulate") == ad(packages=['xyz'], readaheads=['warm'], simulate=True)

  # optional arguments are parsed correctly.
  mad = default_mock_dict_for_parsed_args  # mock assert dict
  assert parse_optional_args("--output filename.csv") == mad(output='filename.csv')
  assert parse_optional_args("-o filename.csv") == mad(output='filename.csv')

  assert parse_optional_args("--timeout 123") == mad(timeout=123)
  assert parse_optional_args("-t 456") == mad(timeout=456)

  assert parse_optional_args("--loop-count 123") == mad(loop_count=123)
  assert parse_optional_args("-lc 456") == mad(loop_count=456)

  assert parse_optional_args("--inodes bar") == mad(inodes="bar")
  assert parse_optional_args("-in baz") == mad(inodes="baz")


def generate_run_combinations(*args):
  # expand out the generator values so that assert x == y works properly.
  return [i for i in asr.generate_run_combinations(*args)]

def test_generate_run_combinations():
  blank_nd = typing.NamedTuple('Blank')
  assert generate_run_combinations(blank_nd, {}) == [()], "empty"
  assert generate_run_combinations(blank_nd, {'a' : ['a1', 'a2']}) == [()], "empty filter"
  a_nd = typing.NamedTuple('A', [('a', str)])
  assert generate_run_combinations(a_nd, {'a': None}) == [(None,)], "None"
  assert generate_run_combinations(a_nd, {'a': ['a1', 'a2']}) == [('a1',), ('a2',)], "one item"
  assert generate_run_combinations(a_nd,
                                   {'a' : ['a1', 'a2'], 'b': ['b1', 'b2']}) == [('a1',), ('a2',)],\
      "one item filter"
  ab_nd = typing.NamedTuple('AB', [('a', str), ('b', str)])
  assert generate_run_combinations(ab_nd,
                                   {'a': ['a1', 'a2'],
                                    'b': ['b1', 'b2']}) == [ab_nd('a1', 'b1'),
                                                            ab_nd('a1', 'b2'),
                                                            ab_nd('a2', 'b1'),
                                                            ab_nd('a2', 'b2')],\
      "two items"

  assert generate_run_combinations(ab_nd,
                                   {'as': ['a1', 'a2'],
                                    'bs': ['b1', 'b2']}) == [ab_nd('a1', 'b1'),
                                                             ab_nd('a1', 'b2'),
                                                             ab_nd('a2', 'b1'),
                                                             ab_nd('a2', 'b2')],\
      "two items plural"

def test_key_to_cmdline_flag():
  assert asr.key_to_cmdline_flag("abc") == "--abc"
  assert asr.key_to_cmdline_flag("foos") == "--foo"
  assert asr.key_to_cmdline_flag("ba_r") == "--ba-r"
  assert asr.key_to_cmdline_flag("ba_zs") == "--ba-z"


def test_make_script_command_with_temp_output():
  cmd_str, tmp_file = asr.make_script_command_with_temp_output("fake_script", args=[], count=1)
  with tmp_file:
    assert cmd_str == ["fake_script", "--count", "1", "--output", tmp_file.name]

  cmd_str, tmp_file = asr.make_script_command_with_temp_output("fake_script", args=['a', 'b'], count=2)
  with tmp_file:
    assert cmd_str == ["fake_script", "a", "b", "--count", "2", "--output", tmp_file.name]

def test_parse_run_script_csv_file():
  # empty file -> empty list
  f = io.StringIO("")
  assert asr.parse_run_script_csv_file(f) == []

  # common case
  f = io.StringIO("1,2,3")
  assert asr.parse_run_script_csv_file(f) == [1,2,3]

  # ignore trailing comma
  f = io.StringIO("1,2,3,4,5,")
  assert asr.parse_run_script_csv_file(f) == [1,2,3,4,5]


if __name__ == '__main__':
  pytest.main()
