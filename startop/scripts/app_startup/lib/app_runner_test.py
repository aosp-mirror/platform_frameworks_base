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

"""Unit tests for the AppRunner."""
import os
import sys
from pathlib import Path

from app_runner import AppRunner, AppRunnerListener
from mock import Mock, call, patch

# The path is "frameworks/base/startop/scripts/"
sys.path.append(Path(os.path.realpath(__file__)).parents[2])
import lib.cmd_utils as cmd_utils

class AppRunnerTestListener(AppRunnerListener):
  def preprocess(self) -> None:
    cmd_utils.run_shell_command('pre'),

  def postprocess(self, pre_launch_timestamp: str) -> None:
    cmd_utils.run_shell_command('post'),

  def metrics_selector(self, am_start_output: str,
                       pre_launch_timestamp: str) -> None:
    return 'TotalTime=123\n'

RUNNER = AppRunner(package='music',
                   activity='MainActivity',
                   compiler_filter='speed',
                   timeout=None,
                   simulate=False)



def test_configure_compiler_filter():
  with patch('lib.cmd_utils.run_shell_command',
             new_callable=Mock) as mock_run_shell_command:
    mock_run_shell_command.return_value = (True, 'speed arm64 kUpToDate')

    RUNNER.configure_compiler_filter()

    calls = [call(os.path.realpath(
        os.path.join(RUNNER.DIR,
                     '../query_compiler_filter.py')) + ' --package music')]
    mock_run_shell_command.assert_has_calls(calls)

def test_parse_metrics_output():
  input = 'a1=b1\nc1=d1\ne1=f1'
  ret = RUNNER.parse_metrics_output(input)

  assert ret == [('a1', 'b1'), ('c1', 'd1'), ('e1', 'f1')]

def _mocked_run_shell_command(*args, **kwargs):
  if args[0] == 'adb shell "date -u +\'%Y-%m-%d %H:%M:%S.%N\'"':
    return (True, "2019-07-02 23:20:06.972674825")
  elif args[0] == 'adb shell ps | grep "music" | awk \'{print $2;}\'':
    return (True, '9999')
  else:
    return (True, 'a1=b1\nc1=d1=d2\ne1=f1')

@patch('app_startup.lib.adb_utils.blocking_wait_for_logcat_displayed_time')
@patch('lib.cmd_utils.run_shell_command')
def test_run(mock_run_shell_command,
             mock_blocking_wait_for_logcat_displayed_time):
  mock_run_shell_command.side_effect = _mocked_run_shell_command
  mock_blocking_wait_for_logcat_displayed_time.return_value = 123

  test_listener = AppRunnerTestListener()
  RUNNER.add_callbacks(test_listener)

  result = RUNNER.run()

  RUNNER.remove_callbacks(test_listener)

  calls = [call('pre'),
           call(os.path.realpath(
               os.path.join(RUNNER.DIR,
                            '../query_compiler_filter.py')) +
                ' --package music'),
           call('adb shell "date -u +\'%Y-%m-%d %H:%M:%S.%N\'"'),
           call(
               'timeout {timeout} "{DIR}/launch_application" "{package}" "{activity}"'
                 .format(timeout=30,
                         DIR=os.path.realpath(os.path.dirname(RUNNER.DIR)),
                         package='music',
                         activity='MainActivity',
                         timestamp='2019-07-02 23:20:06.972674825')),
           call('post')
           ]
  mock_run_shell_command.assert_has_calls(calls)
  assert result == [('TotalTime', '123')]
  assert len(RUNNER.listeners) == 0