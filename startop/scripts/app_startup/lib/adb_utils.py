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

"""Helper util libraries for calling adb command line."""

import datetime
import os
import re
import sys
import time
from typing import Optional

sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(
  os.path.abspath(__file__)))))
import lib.cmd_utils as cmd_utils
import lib.logcat_utils as logcat_utils


def logcat_save_timestamp() -> str:
  """Gets the current logcat timestamp.

  Returns:
    A string of timestamp.
  """
  _, output = cmd_utils.run_adb_shell_command(
    "date -u +\'%Y-%m-%d %H:%M:%S.%N\'")
  return output

def vm_drop_cache():
  """Free pagecache and slab object."""
  cmd_utils.run_adb_shell_command('echo 3 > /proc/sys/vm/drop_caches')
  # Sleep a little bit to provide enough time for cache cleanup.
  time.sleep(1)

def root():
  """Roots adb and successive adb commands will run under root."""
  cmd_utils.run_shell_command('adb root')

def disable_selinux():
  """Disables selinux setting."""
  _, output = cmd_utils.run_adb_shell_command('getenforce')
  if output == 'Permissive':
    return

  print('Disable selinux permissions and restart framework.')
  cmd_utils.run_adb_shell_command('setenforce 0')
  cmd_utils.run_adb_shell_command('stop')
  cmd_utils.run_adb_shell_command('start')
  cmd_utils.run_shell_command('adb wait-for-device')

def pkill(procname: str):
  """Kills a process on device specified by the substring pattern in procname"""
  _, pids = cmd_utils.run_shell_command('adb shell ps | grep "{}" | '
                                        'awk \'{{print $2;}}\''.
                                          format(procname))

  for pid in pids.split('\n'):
    pid = pid.strip()
    if pid:
      passed,_ = cmd_utils.run_adb_shell_command('kill {}'.format(pid))
      time.sleep(1)

def parse_time_to_milliseconds(time: str) -> int:
  """Parses the time string to milliseconds."""
  # Example: +1s56ms, +56ms
  regex = r'\+((?P<second>\d+?)s)?(?P<millisecond>\d+?)ms'
  result = re.search(regex, time)
  second = 0
  if result.group('second'):
    second = int(result.group('second'))
  ms = int(result.group('millisecond'))
  return second * 1000 + ms

def blocking_wait_for_logcat_displayed_time(timestamp: datetime.datetime,
                                            package: str,
                                            timeout: int) -> Optional[int]:
  """Parses the displayed time in the logcat.

  Returns:
    the displayed time.
  """
  pattern = re.compile('.*ActivityTaskManager: Displayed {}.*'.format(package))
  # 2019-07-02 22:28:34.469453349 -> 2019-07-02 22:28:34.469453
  timestamp = datetime.datetime.strptime(timestamp[:-3],
                                         '%Y-%m-%d %H:%M:%S.%f')
  timeout_dt = timestamp + datetime.timedelta(0, timeout)
  # 2019-07-01 14:54:21.946 27365 27392 I ActivityTaskManager:
  # Displayed com.android.settings/.Settings: +927ms
  result = logcat_utils.blocking_wait_for_logcat_pattern(timestamp,
                                                         pattern,
                                                         timeout_dt)
  if not result or not '+' in result:
    return None
  displayed_time = result[result.rfind('+'):]

  return parse_time_to_milliseconds(displayed_time)

def delete_file_on_device(file_path: str) -> None:
  """ Deletes a file on the device. """
  cmd_utils.run_adb_shell_command(
    "[[ -f '{file_path}' ]] && rm -f '{file_path}' || "
    "exit 0".format(file_path=file_path))

def set_prop(property: str, value: str) -> None:
  """ Sets property using adb shell. """
  cmd_utils.run_adb_shell_command('setprop "{property}" "{value}"'.format(
      property=property, value=value))

def pull_file(device_file_path: str, output_file_path: str) -> None:
  """ Pulls file from device to output """
  cmd_utils.run_shell_command('adb pull "{device_file_path}" "{output_file_path}"'.
      format(device_file_path=device_file_path,
             output_file_path=output_file_path))
