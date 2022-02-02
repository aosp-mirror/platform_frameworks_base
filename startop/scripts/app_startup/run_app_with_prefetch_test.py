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
"""Unit tests for the run_app_with_prefetch_test.py script.

Install:
  $> sudo apt-get install python3-pytest   ##  OR
  $> pip install -U pytest
See also https://docs.pytest.org/en/latest/getting-started.html

Usage:
  $> ./run_app_with_prefetch_test.py
  $> pytest run_app_with_prefetch_test.py
  $> python -m pytest run_app_with_prefetch_test.py

See also https://docs.pytest.org/en/latest/usage.html
"""

import io
import os
import shlex
import sys
import tempfile
# global imports
from contextlib import contextmanager

# pip imports
import pytest
# local imports
import run_app_with_prefetch as runner
from mock import call, patch, Mock

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app_startup.lib.app_runner import AppRunner
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
  """Asserts that a SystemExit is raised when executing this context.

  If the assertion fails, print the message 'msg'.
  """
  with pytest.raises(SystemExit, message=msg):
    with ignore_stdout_stderr():
      yield

def assert_bad_argument(args, msg):
  """Asserts that the command line arguments in 'args' are malformed.

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
  return vars(runner.parse_options(shlex.split(args)))

def default_dict_for_parsed_args(**kwargs):
  """Combines it with all of the "optional" parameters' default values."""
  d = {
    'readahead': 'cold',
    'simulate': None,
    'simulate': False,
    'debug': False,
    'input': 'TraceFile.pb',
    'timeout': 10,
    'compiler_filter': None,
    'activity': None
  }
  d.update(kwargs)
  return d

def default_mock_dict_for_parsed_args(include_optional=True, **kwargs):
  """Combines default dict with all optional parameters with some mock required
    parameters.
    """
  d = {'package': 'com.fake.package'}
  if include_optional:
    d.update(default_dict_for_parsed_args())
  d.update(kwargs)
  return d

def parse_optional_args(str):
  """
    Parses an argument string which already includes all the required arguments
    in default_mock_dict_for_parsed_args.
  """
  req = '--package com.fake.package'
  return parse_args('%s %s' % (req, str))

def test_argparse():
  # missing arguments
  assert_bad_argument('', '-p are required')

  # required arguments are parsed correctly
  ad = default_dict_for_parsed_args  # assert dict
  assert parse_args('--package xyz') == ad(package='xyz')

  assert parse_args('-p xyz') == ad(package='xyz')

  assert parse_args('-p xyz -s') == ad(package='xyz', simulate=True)
  assert parse_args('-p xyz --simulate') == ad(package='xyz', simulate=True)

  # optional arguments are parsed correctly.
  mad = default_mock_dict_for_parsed_args  # mock assert dict
  assert parse_optional_args('--input trace.pb') == mad(input='trace.pb')

  assert parse_optional_args('--compiler-filter speed') == \
         mad(compiler_filter='speed')

  assert parse_optional_args('-d') == mad(debug=True)
  assert parse_optional_args('--debug') == mad(debug=True)

  assert parse_optional_args('--timeout 123') == mad(timeout=123)
  assert parse_optional_args('-t 456') == mad(timeout=456)

  assert parse_optional_args('-r warm') == mad(readahead='warm')
  assert parse_optional_args('--readahead warm') == mad(readahead='warm')

  assert parse_optional_args('-a act') == mad(activity='act')
  assert parse_optional_args('--activity act') == mad(activity='act')

def test_main():
  args = '--package com.fake.package --activity act -s'
  opts = runner.parse_options(shlex.split(args))
  result = runner.PrefetchAppRunner(**vars(opts)).run()
  assert result == [('TotalTime', '123')]

def _mocked_run_shell_command(*args, **kwargs):
  if args[0] == 'adb shell ps | grep "music" | awk \'{print $2;}\'':
    return (True, '9999')
  else:
    return (True, '')

def test_preprocess_no_cache_drop():
  with patch('lib.cmd_utils.run_shell_command',
             new_callable=Mock) as mock_run_shell_command:
    mock_run_shell_command.side_effect = _mocked_run_shell_command
    prefetch_app_runner = runner.PrefetchAppRunner(package='music',
                                                   activity='MainActivity',
                                                   readahead='warm',
                                                   compiler_filter=None,
                                                   timeout=None,
                                                   simulate=False,
                                                   debug=False,
                                                   input=None)

    prefetch_app_runner.preprocess()

    calls = [call('adb root'),
             call('adb shell "getenforce"'),
             call('adb shell "setenforce 0"'),
             call('adb shell "stop"'),
             call('adb shell "start"'),
             call('adb wait-for-device'),
             call('adb shell ps | grep "music" | awk \'{print $2;}\''),
             call('adb shell "kill 9999"')]
    mock_run_shell_command.assert_has_calls(calls)

def test_preprocess_with_cache_drop():
  with patch('lib.cmd_utils.run_shell_command',
             new_callable=Mock) as mock_run_shell_command:
    mock_run_shell_command.side_effect = _mocked_run_shell_command
    prefetch_app_runner = runner.PrefetchAppRunner(package='music',
                                                   activity='MainActivity',
                                                   readahead='cold',
                                                   compiler_filter=None,
                                                   timeout=None,
                                                   simulate=False,
                                                   debug=False,
                                                   input=None)

    prefetch_app_runner.preprocess()

    calls = [call('adb root'),
             call('adb shell "getenforce"'),
             call('adb shell "setenforce 0"'),
             call('adb shell "stop"'),
             call('adb shell "start"'),
             call('adb wait-for-device'),
             call('adb shell ps | grep "music" | awk \'{print $2;}\''),
             call('adb shell "kill 9999"'),
             call('adb shell "echo 3 > /proc/sys/vm/drop_caches"')]
    mock_run_shell_command.assert_has_calls(calls)

def test_preprocess_with_cache_drop_and_iorapd_enabled():
  with patch('lib.cmd_utils.run_shell_command',
             new_callable=Mock) as mock_run_shell_command:
    mock_run_shell_command.side_effect = _mocked_run_shell_command

    with tempfile.NamedTemporaryFile() as input:
      prefetch_app_runner = runner.PrefetchAppRunner(package='music',
                                                     activity='MainActivity',
                                                     readahead='fadvise',
                                                     compiler_filter=None,
                                                     timeout=None,
                                                     simulate=False,
                                                     debug=False,
                                                     input=input.name)

      prefetch_app_runner.preprocess()

      calls = [call('adb root'),
               call('adb shell "getenforce"'),
               call('adb shell "setenforce 0"'),
               call('adb shell "stop"'),
               call('adb shell "start"'),
               call('adb wait-for-device'),
               call(
                   'adb shell ps | grep "music" | awk \'{print $2;}\''),
               call('adb shell "kill 9999"'),
               call('adb shell "echo 3 > /proc/sys/vm/drop_caches"'),
               call('bash -c "source {}; iorapd_readahead_enable"'.
                    format(AppRunner.IORAP_COMMON_BASH_SCRIPT))]
      mock_run_shell_command.assert_has_calls(calls)

@patch('lib.adb_utils.blocking_wait_for_logcat_displayed_time')
@patch('lib.cmd_utils.run_shell_command')
def test_postprocess_with_launch_cleanup(
    mock_run_shell_command,
    mock_blocking_wait_for_logcat_displayed_time):
  mock_run_shell_command.side_effect = _mocked_run_shell_command
  mock_blocking_wait_for_logcat_displayed_time.return_value = 123

  with tempfile.NamedTemporaryFile() as input:
    prefetch_app_runner = runner.PrefetchAppRunner(package='music',
                                                   activity='MainActivity',
                                                   readahead='fadvise',
                                                   compiler_filter=None,
                                                   timeout=10,
                                                   simulate=False,
                                                   debug=False,
                                                   input=input.name)

    prefetch_app_runner.postprocess('2019-07-02 23:20:06.972674825')

    calls = [
        call('bash -c "source {script_path}; '
             'iorapd_readahead_wait_until_finished '
             '\'{package}\' \'{activity}\' \'{timestamp}\' \'{timeout}\'"'.
                 format(timeout=10,
                        package='music',
                        activity='MainActivity',
                        timestamp='2019-07-02 23:20:06.972674825',
                        script_path=AppRunner.IORAP_COMMON_BASH_SCRIPT)),
        call('bash -c "source {}; iorapd_readahead_disable"'.
             format(AppRunner.IORAP_COMMON_BASH_SCRIPT)),
        call('adb shell ps | grep "music" | awk \'{print $2;}\''),
        call('adb shell "kill 9999"')]
    mock_run_shell_command.assert_has_calls(calls)

if __name__ == '__main__':
  pytest.main()
