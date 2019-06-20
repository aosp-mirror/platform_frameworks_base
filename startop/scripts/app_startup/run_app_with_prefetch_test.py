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
# global imports
from contextlib import contextmanager

# pip imports
import pytest
# local imports
import run_app_with_prefetch as run
from mock import Mock, call, patch

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

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
  return vars(run.parse_options(shlex.split(args)))

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
  opts = run.parse_options(shlex.split(args))

  result = run.run_test(opts)
  assert result == [('TotalTime', '123')]

def test_set_up_adb_env():
  with patch('lib.cmd_utils.run_shell_command',
             new_callable=Mock) as mock_run_shell_command:
    mock_run_shell_command.return_value = (True, '')
    run.set_up_adb_env()

    calls = [call('adb root'),
             call('adb shell "getenforce"'),
             call('adb shell "setenforce 0"'),
             call('adb shell "stop"'),
             call('adb shell "start"'),
             call('adb wait-for-device')]
    mock_run_shell_command.assert_has_calls(calls)

def test_set_up_adb_env_with_permissive():
  with patch('lib.cmd_utils.run_shell_command',
             new_callable=Mock) as mock_run_shell_command:
    mock_run_shell_command.return_value = (True, 'Permissive')
    run.set_up_adb_env()

    calls = [call('adb root'), call('adb shell "getenforce"')]
    mock_run_shell_command.assert_has_calls(calls)

def test_configure_compiler_filter():
  with patch('lib.cmd_utils.run_shell_command',
             new_callable=Mock) as mock_run_shell_command:
    mock_run_shell_command.return_value = (True, 'speed arm64 kUpToDate')
    run.configure_compiler_filter('speed', 'music', 'MainActivity')

    calls = [call(os.path.join(run.DIR, 'query_compiler_filter.py') +
                  ' --package music')]
    mock_run_shell_command.assert_has_calls(calls)

def test_parse_metrics_output():
  input = 'a1=b1\nc1=d1\ne1=f1'
  ret = run.parse_metrics_output(input)

  assert ret == [('a1', 'b1'), ('c1', 'd1'), ('e1', 'f1')]

def _mocked_run_shell_command(*args, **kwargs):
  if args[0] == 'adb shell "date -u +\'%Y-%m-%d %H:%M:%S.%N\'"':
    return (True, "123:123")
  elif args[0] == 'adb shell ps | grep "music" | awk \'{print $2;}\'':
    return (True, '9999')
  else:
    return (True, 'a1=b1\nc1=d1=d2\ne1=f1')

def test_run_no_vm_cache_drop():
  with patch('lib.cmd_utils.run_shell_command',
             new_callable=Mock) as mock_run_shell_command:
    mock_run_shell_command.side_effect = _mocked_run_shell_command
    run.run('warm',
            'music',
            'MainActivity',
            timeout=10,
            simulate=False,
            debug=False)

    calls = [call('adb shell "date -u +\'%Y-%m-%d %H:%M:%S.%N\'"'),
             call(
               'timeout {timeout} "{DIR}/launch_application" "{package}" "{activity}" | '
               '"{DIR}/parse_metrics" --package {package} --activity {activity} '
               '--timestamp "{timestamp}"'
                 .format(timeout=10,
                         DIR=run.DIR,
                         package='music',
                         activity='MainActivity',
                         timestamp='123:123')),
             call('adb shell ps | grep "music" | awk \'{print $2;}\''),
             call('adb shell "kill 9999"')]
    mock_run_shell_command.assert_has_calls(calls)

def test_run_with_vm_cache_drop_and_post_launch_cleanup():
  with patch('lib.cmd_utils.run_shell_command',
             new_callable=Mock) as mock_run_shell_command:
    mock_run_shell_command.side_effect = _mocked_run_shell_command
    run.run('fadvise',
            'music',
            'MainActivity',
            timeout=10,
            simulate=False,
            debug=False)

    calls = [call('adb shell "echo 3 > /proc/sys/vm/drop_caches"'),
             call('adb shell "date -u +\'%Y-%m-%d %H:%M:%S.%N\'"'),
             call(
               'timeout {timeout} "{DIR}/launch_application" "{package}" "{activity}" | '
               '"{DIR}/parse_metrics" --package {package} --activity {activity} '
               '--timestamp "{timestamp}"'
                 .format(timeout=10,
                         DIR=run.DIR,
                         package='music',
                         activity='MainActivity',
                         timestamp='123:123')),
             call(
               'bash -c "source {script_path}; '
               'iorapd_readahead_wait_until_finished '
               '\'{package}\' \'{activity}\' \'{timestamp}\' \'{timeout}\'"'.
                 format(timeout=10,
                        package='music',
                        activity='MainActivity',
                        timestamp='123:123',
                        script_path=run.IORAP_COMMON_BASH_SCRIPT)),
             call('adb shell ps | grep "music" | awk \'{print $2;}\''),
             call('adb shell "kill 9999"')]
  mock_run_shell_command.assert_has_calls(calls)

if __name__ == '__main__':
  pytest.main()
