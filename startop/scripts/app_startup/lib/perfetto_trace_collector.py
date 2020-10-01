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

"""Class to collector perfetto trace."""
import datetime
import os
import re
import sys
import time
from datetime import timedelta
from typing import Optional, List, Tuple

# global variables
DIR = os.path.abspath(os.path.dirname(__file__))

sys.path.append(os.path.dirname(os.path.dirname(DIR)))

import app_startup.lib.adb_utils as adb_utils
from app_startup.lib.app_runner import AppRunner, AppRunnerListener
import lib.print_utils as print_utils
import lib.logcat_utils as logcat_utils
import iorap.lib.iorapd_utils as iorapd_utils

class PerfettoTraceCollector(AppRunnerListener):
  """ Class to collect perfetto trace.

      To set trace duration of perfetto, change the 'trace_duration_ms'.
      To pull the generated perfetto trace on device, set the 'output'.
  """
  TRACE_FILE_SUFFIX = 'perfetto_trace.pb'
  TRACE_DURATION_PROP = 'iorapd.perfetto.trace_duration_ms'
  MS_PER_SEC  = 1000
  DEFAULT_TRACE_DURATION = timedelta(milliseconds=5000) # 5 seconds
  _COLLECTOR_TIMEOUT_MULTIPLIER = 10  # take the regular timeout and multiply

  def __init__(self,
               package: str,
               activity: Optional[str],
               compiler_filter: Optional[str],
               timeout: Optional[int],
               simulate: bool,
               trace_duration: timedelta = DEFAULT_TRACE_DURATION,
               save_destination_file_path: Optional[str] = None):
    """ Initialize the perfetto trace collector. """
    self.app_runner = AppRunner(package,
                                activity,
                                compiler_filter,
                                timeout,
                                simulate)
    self.app_runner.add_callbacks(self)

    self.trace_duration = trace_duration
    self.save_destination_file_path = save_destination_file_path

  def purge_file(self, suffix: str) -> None:
    print_utils.debug_print('iorapd-perfetto: purge file in ' +
                            self._get_remote_path())
    adb_utils.delete_file_on_device(self._get_remote_path())

  def run(self) -> Optional[List[Tuple[str]]]:
    """Runs an app.

    Returns:
      A list of (metric, value) tuples.
    """
    return self.app_runner.run()

  def preprocess(self):
    # Sets up adb environment.
    adb_utils.root()
    adb_utils.disable_selinux()
    time.sleep(1)

    # Kill any existing process of this app
    adb_utils.pkill(self.app_runner.package)

    # Remove existing trace and compiler files
    self.purge_file(PerfettoTraceCollector.TRACE_FILE_SUFFIX)

    # Set perfetto trace duration prop to milliseconds.
    adb_utils.set_prop(PerfettoTraceCollector.TRACE_DURATION_PROP,
                       int(self.trace_duration.total_seconds()*
                           PerfettoTraceCollector.MS_PER_SEC))

    if not iorapd_utils.stop_iorapd():
      raise RuntimeError('Cannot stop iorapd!')

    if not iorapd_utils.enable_iorapd_perfetto():
      raise RuntimeError('Cannot enable perfetto!')

    if not iorapd_utils.disable_iorapd_readahead():
      raise RuntimeError('Cannot disable readahead!')

    if not iorapd_utils.start_iorapd():
      raise RuntimeError('Cannot start iorapd!')

    # Drop all caches to get cold starts.
    adb_utils.vm_drop_cache()

  def postprocess(self, pre_launch_timestamp: str):
    # Kill any existing process of this app
    adb_utils.pkill(self.app_runner.package)

    iorapd_utils.disable_iorapd_perfetto()

    if self.save_destination_file_path is not None:
      adb_utils.pull_file(self._get_remote_path(),
                          self.save_destination_file_path)

  def metrics_selector(self, am_start_output: str,
                       pre_launch_timestamp: str) -> str:
    """Parses the metric after app startup by reading from logcat in a blocking
    manner until all metrics have been found".

    Returns:
      An empty string because the metric needs no further parsing.
    """
    if not self._wait_for_perfetto_trace(pre_launch_timestamp):
      raise RuntimeError('Could not save perfetto app trace file!')

    return ''

  def _wait_for_perfetto_trace(self, pre_launch_timestamp) -> Optional[str]:
    """ Waits for the perfetto trace being saved to file.

    The string is in the format of r".*Perfetto TraceBuffer saved to file:
    <file path>.*"

    Returns:
      the string what the program waits for. If the string doesn't show up,
      return None.
    """
    pattern = re.compile(r'.*Perfetto TraceBuffer saved to file: {}.*'.
                         format(self._get_remote_path()))

    # The pre_launch_timestamp is longer than what the datetime can parse. Trim
    # last three digits to make them align. For example:
    # 2019-07-02 23:20:06.972674825999 -> 2019-07-02 23:20:06.972674825
    assert len(pre_launch_timestamp) == len('2019-07-02 23:20:06.972674825')
    timestamp = datetime.datetime.strptime(pre_launch_timestamp[:-3],
                                           '%Y-%m-%d %H:%M:%S.%f')

    # The timeout of perfetto trace is longer than the normal app run timeout.
    timeout_dt = self.app_runner.timeout * PerfettoTraceCollector._COLLECTOR_TIMEOUT_MULTIPLIER
    timeout_end = timestamp + datetime.timedelta(seconds=timeout_dt)

    return logcat_utils.blocking_wait_for_logcat_pattern(timestamp,
                                                         pattern,
                                                         timeout_end)

  def _get_remote_path(self):
    # For example: android.music%2Fmusic.TopLevelActivity.perfetto_trace.pb
    return iorapd_utils._iorapd_path_to_data_file(self.app_runner.package,
                                                  self.app_runner.activity,
                                                  PerfettoTraceCollector.TRACE_FILE_SUFFIX)
