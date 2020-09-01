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

"""Helper util libraries for iorapd related operations."""

import os
import sys

# up to two level
sys.path.append(os.path.join(os.path.abspath(__file__),'../..'))
import lib.cmd_utils as cmd_utils

IORAPID_LIB_DIR = os.path.abspath(os.path.dirname(__file__))
IORAPD_DATA_PATH = '/data/misc/iorapd'
IORAP_COMMON_BASH_SCRIPT = os.path.realpath(os.path.join(IORAPID_LIB_DIR,
                                                         '../common'))

def _iorapd_path_to_data_file(package: str, activity: str, suffix: str) -> str:
  """Gets conventional data filename.

   Returns:
     The path of iorapd data file.

  """
  # Match logic of 'AppComponentName' in iorap::compiler C++ code.
  return '{}/{}%2F{}.{}'.format(IORAPD_DATA_PATH, package, activity, suffix)

def compile_perfetto_trace_on_device(package: str, activity: str,
                                     inodes: str) -> bool:
  """Compiles the perfetto trace using on-device compiler."""
  passed, _ = cmd_utils.run_shell_func(IORAP_COMMON_BASH_SCRIPT,
                                       'iorapd_compiler_for_app_trace',
                                       [package, activity, inodes])
  return passed

def get_iorapd_compiler_trace(package: str, activity: str, dest: str) -> str:
  """Gets compiler trace to dest file."""
  src = _iorapd_path_to_data_file(package, activity, 'compiled_trace.pb')
  passed, _ = cmd_utils.run_shell_command('adb pull "{}" "{}"'.format(src, dest))
  if not passed:
    return False
  return True

def iorapd_compiler_install_trace_file(package: str, activity: str,
                                       input_file: str) -> bool:
  """Installs a compiled trace file.

  Returns:
    Whether the trace file is installed successful or not.
  """
  # remote path calculations
  compiled_path = _iorapd_path_to_data_file(package, activity,
                                            'compiled_trace.pb')

  if not os.path.exists(input_file):
    print('Error: File {} does not exist'.format(input_file))
    return False

  passed, _ = cmd_utils.run_adb_shell_command(
    'mkdir -p "$(dirname "{}")"'.format(compiled_path))
  if not passed:
    return False

  passed, _ = cmd_utils.run_shell_command('adb push "{}" "{}"'.format(
    input_file, compiled_path))

  return passed

def wait_for_iorapd_finish(package: str,
                           activity: str,
                           timeout: int,
                           debug: bool,
                           logcat_timestamp: str)->bool:
  """Waits for the finish of iorapd.

  Returns:
    A bool indicates whether the iorapd is done successfully or not.
  """
  # Set verbose for bash script based on debug flag.
  if debug:
    os.putenv('verbose', 'y')

  # Validate that readahead completes.
  # If this fails for some reason, then this will also discard the timing of
  # the run.
  passed, _ = cmd_utils.run_shell_func(IORAP_COMMON_BASH_SCRIPT,
                                       'iorapd_readahead_wait_until_finished',
                                       [package, activity, logcat_timestamp,
                                        str(timeout)])
  return passed


def enable_iorapd_readahead() -> bool:
  """
  Disable readahead. Subsequent launches of an application will be sped up
  by iorapd readahead prefetching.

  Returns:
    A bool indicates whether the enabling is done successfully or not.
  """
  passed, _ = cmd_utils.run_shell_func(IORAP_COMMON_BASH_SCRIPT,
                                       'iorapd_readahead_enable', [])
  return passed

def disable_iorapd_readahead() -> bool:
  """
  Disable readahead. Subsequent launches of an application will be not be sped
  up by iorapd readahead prefetching.

  Returns:
    A bool indicates whether the disabling is done successfully or not.
  """
  passed, _ = cmd_utils.run_shell_func(IORAP_COMMON_BASH_SCRIPT,
                                       'iorapd_readahead_disable', [])
  return passed

def enable_iorapd_perfetto() -> bool:
  """
  Enable Perfetto. Subsequent launches of an application will record a perfetto
  trace protobuf.

  Returns:
    A bool indicates whether the enabling is done successfully or not.
  """
  passed, _ = cmd_utils.run_shell_func(IORAP_COMMON_BASH_SCRIPT,
                                       'iorapd_perfetto_enable', [])
  return passed

def disable_iorapd_perfetto() -> bool:
  """
  Disable Perfetto. Subsequent launches of applications will no longer record
  perfetto trace protobufs.

  Returns:
    A bool indicates whether the disabling is done successfully or not.
  """
  passed, _ = cmd_utils.run_shell_func(IORAP_COMMON_BASH_SCRIPT,
                                       'iorapd_perfetto_disable', [])
  return passed

def start_iorapd() -> bool:
  """
  Starts iorapd.

  Returns:
    A bool indicates whether the starting is done successfully or not.
  """
  passed, _ = cmd_utils.run_shell_func(IORAP_COMMON_BASH_SCRIPT,
                                       'iorapd_start', [])
  return passed

def stop_iorapd() -> bool:
  """
  Stops iorapd.

  Returns:
    A bool indicates whether the stopping is done successfully or not.
  """
  passed, _ = cmd_utils.run_shell_func(IORAP_COMMON_BASH_SCRIPT,
                                       'iorapd_stop', [])
  return passed

