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

"""Runner of one test given a setting.

Run app and gather the measurement in a certain configuration.
Print the result to stdout.
See --help for more details.

Sample usage:
  $> ./python run_app_with_prefetch.py  -p com.android.settings -a
     com.android.settings.Settings -r fadvise -i input

"""

import argparse
import os
import sys
import time
from typing import List, Tuple, Optional

# local imports
import lib.adb_utils as adb_utils
from lib.app_runner import AppRunner, AppRunnerListener

# global variables
DIR = os.path.abspath(os.path.dirname(__file__))

sys.path.append(os.path.dirname(DIR))
import lib.print_utils as print_utils
import lib.cmd_utils as cmd_utils
import iorap.lib.iorapd_utils as iorapd_utils

class PrefetchAppRunner(AppRunnerListener):
  def __init__(self,
               package: str,
               activity: Optional[str],
               readahead: str,
               compiler_filter: Optional[str],
               timeout: Optional[int],
               simulate: bool,
               debug: bool,
               input:Optional[str],
               **kwargs):
    self.app_runner = AppRunner(package,
                                activity,
                                compiler_filter,
                                timeout,
                                simulate)
    self.app_runner.add_callbacks(self)

    self.simulate = simulate
    self.readahead = readahead
    self.debug = debug
    self.input = input
    print_utils.DEBUG = self.debug
    cmd_utils.SIMULATE = self.simulate


  def run(self) -> Optional[List[Tuple[str]]]:
    """Runs an app.

    Returns:
      A list of (metric, value) tuples.
    """
    return self.app_runner.run()

  def preprocess(self):
    passed = self.validate_options()
    if not passed:
      return

    # Sets up adb environment.
    adb_utils.root()
    adb_utils.disable_selinux()
    time.sleep(1)

    # Kill any existing process of this app
    adb_utils.pkill(self.app_runner.package)

    if self.readahead != 'warm':
      print_utils.debug_print('Drop caches for non-warm start.')
      # Drop all caches to get cold starts.
      adb_utils.vm_drop_cache()

    if self.readahead != 'warm' and self.readahead != 'cold':
      iorapd_utils.enable_iorapd_readahead()

  def postprocess(self, pre_launch_timestamp: str):
    passed = self._perform_post_launch_cleanup(pre_launch_timestamp)
    if not passed and not self.app_runner.simulate:
      print_utils.error_print('Cannot perform post launch cleanup!')
      return None

    # Kill any existing process of this app
    adb_utils.pkill(self.app_runner.package)

  def _perform_post_launch_cleanup(self, logcat_timestamp: str) -> bool:
    """Performs cleanup at the end of each loop iteration.

    Returns:
      A bool indicates whether the cleanup succeeds or not.
    """
    if self.readahead != 'warm' and self.readahead != 'cold':
      passed = iorapd_utils.wait_for_iorapd_finish(self.app_runner.package,
                                                   self.app_runner.activity,
                                                   self.app_runner.timeout,
                                                   self.debug,
                                                   logcat_timestamp)

      if not passed:
        return passed

      return iorapd_utils.disable_iorapd_readahead()

    # Don't need to do anything for warm or cold.
    return True

  def metrics_selector(self, am_start_output: str,
                       pre_launch_timestamp: str) -> str:
    """Parses the metric after app startup by reading from logcat in a blocking
    manner until all metrics have been found".

    Returns:
      the total time and displayed time of app startup.
      For example: "TotalTime=123\nDisplayedTime=121
    """
    total_time = AppRunner.parse_total_time(am_start_output)
    displayed_time = adb_utils.blocking_wait_for_logcat_displayed_time(
        pre_launch_timestamp, self.app_runner.package, self.app_runner.timeout)

    return 'TotalTime={}\nDisplayedTime={}'.format(total_time, displayed_time)

  def validate_options(self) -> bool:
    """Validates the activity and trace file if needed.

    Returns:
      A bool indicates whether the activity is valid.
    """
    needs_trace_file = self.readahead != 'cold' and self.readahead != 'warm'
    if needs_trace_file and (self.input is None or
                             not os.path.exists(self.input)):
      print_utils.error_print('--input not specified!')
      return False

    # Install necessary trace file. This must be after the activity checking.
    if needs_trace_file:
      passed = iorapd_utils.iorapd_compiler_install_trace_file(
          self.app_runner.package, self.app_runner.activity, self.input)
      if not cmd_utils.SIMULATE and not passed:
        print_utils.error_print('Failed to install compiled TraceFile.pb for '
                                '"{}/{}"'.
                                    format(self.app_runner.package,
                                           self.app_runner.activity))
        return False

    return True



def parse_options(argv: List[str] = None):
  """Parses command line arguments and return an argparse Namespace object."""
  parser = argparse.ArgumentParser(
      description='Run an Android application once and measure startup time.'
  )

  required_named = parser.add_argument_group('required named arguments')
  required_named.add_argument('-p', '--package', action='store', dest='package',
                              help='package of the application', required=True)

  # optional arguments
  # use a group here to get the required arguments to appear 'above' the
  # optional arguments in help.
  optional_named = parser.add_argument_group('optional named arguments')
  optional_named.add_argument('-a', '--activity', action='store',
                              dest='activity',
                              help='launch activity of the application')
  optional_named.add_argument('-s', '--simulate', dest='simulate',
                              action='store_true',
                              help='simulate the process without executing '
                                   'any shell commands')
  optional_named.add_argument('-d', '--debug', dest='debug',
                              action='store_true',
                              help='Add extra debugging output')
  optional_named.add_argument('-i', '--input', action='store', dest='input',
                              help='perfetto trace file protobuf',
                              default='TraceFile.pb')
  optional_named.add_argument('-r', '--readahead', action='store',
                              dest='readahead',
                              help='which readahead mode to use',
                              default='cold',
                              choices=('warm', 'cold', 'mlock', 'fadvise'))
  optional_named.add_argument('-t', '--timeout', dest='timeout', action='store',
                              type=int,
                              help='Timeout after this many seconds when '
                                   'executing a single run.',
                              default=10)
  optional_named.add_argument('--compiler-filter', dest='compiler_filter',
                              action='store',
                              help='Which compiler filter to use.',
                              default=None)

  return parser.parse_args(argv)

def main():
  opts = parse_options()
  runner = PrefetchAppRunner(**vars(opts))
  result = runner.run()

  if result is None:
    return 1

  print(result)
  return 0

if __name__ == '__main__':
  sys.exit(main())
