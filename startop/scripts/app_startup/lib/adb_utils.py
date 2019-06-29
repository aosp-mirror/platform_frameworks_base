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

import os
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(
  os.path.abspath(__file__)))))
import lib.cmd_utils as cmd_utils

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
  """Kills a process in device by its package name."""
  _, pids = cmd_utils.run_shell_command('adb shell ps | grep "{}" | '
                                        'awk \'{{print $2;}}\''.
                                          format(procname))

  for pid in pids.split('\n'):
    cmd_utils.run_adb_shell_command('kill {}'.format(pid))
