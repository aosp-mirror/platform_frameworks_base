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

"""Unit tests for the data_frame.py script."""
import os
import sys
from pathlib import Path
from datetime import timedelta

from mock import call, patch
from perfetto_trace_collector import PerfettoTraceCollector

sys.path.append(Path(os.path.realpath(__file__)).parents[2])
from app_startup.lib.app_runner import AppRunner

RUNNER = PerfettoTraceCollector(package='music',
                                activity='MainActivity',
                                compiler_filter=None,
                                timeout=10,
                                simulate=False,
                                trace_duration = timedelta(milliseconds=1000),
                                # No actual file will be created. Just to
                                # check the command.
                                save_destination_file_path='/tmp/trace.pb')

def _mocked_run_shell_command(*args, **kwargs):
  if args[0] == 'adb shell ps | grep "music" | awk \'{print $2;}\'':
    return (True, '9999')
  else:
    return (True, '')

@patch('lib.logcat_utils.blocking_wait_for_logcat_pattern')
@patch('lib.cmd_utils.run_shell_command')
def test_perfetto_trace_collector_preprocess(mock_run_shell_command,
                                             mock_blocking_wait_for_logcat_pattern):
  mock_run_shell_command.side_effect = _mocked_run_shell_command
  mock_blocking_wait_for_logcat_pattern.return_value = "Succeed!"

  RUNNER.preprocess()

  calls = [call('adb root'),
           call('adb shell "getenforce"'),
           call('adb shell "setenforce 0"'),
           call('adb shell "stop"'),
           call('adb shell "start"'),
           call('adb wait-for-device'),
           call('adb shell ps | grep "music" | awk \'{print $2;}\''),
           call('adb shell "kill 9999"'),
           call(
               'adb shell "[[ -f \'/data/misc/iorapd/music%2FMainActivity.perfetto_trace.pb\' ]] '
               '&& rm -f \'/data/misc/iorapd/music%2FMainActivity.perfetto_trace.pb\' || exit 0"'),
           call('adb shell "setprop "iorapd.perfetto.trace_duration_ms" "1000""'),
           call(
               'bash -c "source {}; iorapd_stop"'.format(
                   AppRunner.IORAP_COMMON_BASH_SCRIPT)),
           call(
               'bash -c "source {}; iorapd_perfetto_enable"'.format(
                   AppRunner.IORAP_COMMON_BASH_SCRIPT)),
           call(
               'bash -c "source {}; iorapd_readahead_disable"'.format(
                   AppRunner.IORAP_COMMON_BASH_SCRIPT)),
           call(
               'bash -c "source {}; iorapd_start"'.format(
                   AppRunner.IORAP_COMMON_BASH_SCRIPT)),
           call('adb shell "echo 3 > /proc/sys/vm/drop_caches"')]

  mock_run_shell_command.assert_has_calls(calls)

@patch('lib.logcat_utils.blocking_wait_for_logcat_pattern')
@patch('lib.cmd_utils.run_shell_command')
def test_perfetto_trace_collector_postprocess(mock_run_shell_command,
                                              mock_blocking_wait_for_logcat_pattern):
  mock_run_shell_command.side_effect = _mocked_run_shell_command
  mock_blocking_wait_for_logcat_pattern.return_value = "Succeed!"

  RUNNER.postprocess('2019-07-02 23:20:06.972674825')

  calls = [call('adb shell ps | grep "music" | awk \'{print $2;}\''),
           call('adb shell "kill 9999"'),
           call(
               'bash -c "source {}; iorapd_perfetto_disable"'.format(
                   AppRunner.IORAP_COMMON_BASH_SCRIPT)),
           call('adb pull '
                '"/data/misc/iorapd/music%2FMainActivity.perfetto_trace.pb" '
                '"/tmp/trace.pb"')]

  mock_run_shell_command.assert_has_calls(calls)
