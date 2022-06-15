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

"""Class to run an app."""
import os
import sys
from typing import Optional, List, Tuple

# local import
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

import app_startup.lib.adb_utils as adb_utils
import lib.cmd_utils as cmd_utils
import lib.print_utils as print_utils

class AppRunnerListener(object):
  """Interface for lisenter of AppRunner. """

  def preprocess(self) -> None:
    """Preprocess callback to initialized before the app is running. """
    pass

  def postprocess(self, pre_launch_timestamp: str) -> None:
    """Postprocess callback to cleanup after the app is running.

      param:
        'pre_launch_timestamp': indicates the timestamp when the app is
        launching.. """
    pass

  def metrics_selector(self, am_start_output: str,
                       pre_launch_timestamp: str) -> None:
    """A metrics selection callback that waits for the desired metrics to
      show up in logcat.
      params:
        'am_start_output': indicates the output of app startup.
        'pre_launch_timestamp': indicates the timestamp when the app is
                        launching.
      returns:
        a string in the format of "<metric>=<value>\n<metric>=<value>\n..."
        for further parsing. For example "TotalTime=123\nDisplayedTime=121".
        Return an empty string if no metrics need to be parsed further.
        """
    pass

class AppRunner(object):
  """ Class to run an app. """
  # static variables
  DIR = os.path.abspath(os.path.dirname(__file__))
  APP_STARTUP_DIR = os.path.dirname(DIR)
  IORAP_COMMON_BASH_SCRIPT = os.path.realpath(os.path.join(DIR,
                                                           '../../iorap/common'))
  DEFAULT_TIMEOUT = 30 # seconds

  def __init__(self,
               package: str,
               activity: Optional[str],
               compiler_filter: Optional[str],
               timeout: Optional[int],
               simulate: bool):
    self.package = package
    self.simulate = simulate

    # If the argument activity is None, try to set it.
    self.activity = activity
    if self.simulate:
      self.activity = 'act'
    if self.activity is None:
      self.activity = AppRunner.get_activity(self.package)

    self.compiler_filter = compiler_filter
    self.timeout = timeout if timeout else AppRunner.DEFAULT_TIMEOUT

    self.listeners = []

  def add_callbacks(self, listener: AppRunnerListener):
    self.listeners.append(listener)

  def remove_callbacks(self, listener: AppRunnerListener):
    self.listeners.remove(listener)

  @staticmethod
  def get_activity(package: str) -> str:
    """ Tries to set the activity based on the package. """
    passed, activity = cmd_utils.run_shell_func(
        AppRunner.IORAP_COMMON_BASH_SCRIPT,
        'get_activity_name',
        [package])

    if not passed or not activity:
      raise ValueError(
          'Activity name could not be found, invalid package name?!')

    return activity

  def configure_compiler_filter(self) -> bool:
    """Configures compiler filter (e.g. speed).

    Returns:
      A bool indicates whether configure of compiler filer succeeds or not.
    """
    if not self.compiler_filter:
      print_utils.debug_print('No --compiler-filter specified, don\'t'
                              ' need to force it.')
      return True

    passed, current_compiler_filter_info = \
      cmd_utils.run_shell_command(
          '{} --package {}'.format(os.path.join(AppRunner.APP_STARTUP_DIR,
                                                'query_compiler_filter.py'),
                                   self.package))

    if passed != 0:
      return passed

    # TODO: call query_compiler_filter directly as a python function instead of
    #  these shell calls.
    current_compiler_filter, current_reason, current_isa = \
      current_compiler_filter_info.split(' ')
    print_utils.debug_print('Compiler Filter={} Reason={} Isa={}'.format(
        current_compiler_filter, current_reason, current_isa))

    # Don't trust reasons that aren't 'unknown' because that means
    #  we didn't manually force the compilation filter.
    # (e.g. if any automatic system-triggered compilations are not unknown).
    if current_reason != 'unknown' or \
        current_compiler_filter != self.compiler_filter:
      passed, _ = adb_utils.run_shell_command('{}/force_compiler_filter '
                                              '--compiler-filter "{}" '
                                              '--package "{}"'
                                              ' --activity "{}'.
                                                format(AppRunner.APP_STARTUP_DIR,
                                                       self.compiler_filter,
                                                       self.package,
                                                       self.activity))
    else:
      adb_utils.debug_print('Queried compiler-filter matched requested '
                            'compiler-filter, skip forcing.')
      passed = False
    return passed

  def run(self) -> Optional[List[Tuple[str]]]:
    """Runs an app.

    Returns:
      A list of (metric, value) tuples.
    """
    print_utils.debug_print('==========================================')
    print_utils.debug_print('=====             START              =====')
    print_utils.debug_print('==========================================')
    # Run the preprocess.
    for listener in self.listeners:
      listener.preprocess()

    # Ensure the APK is currently compiled with whatever we passed in
    # via --compiler-filter.
    # No-op if this option was not passed in.
    if not self.configure_compiler_filter():
      print_utils.error_print('Compiler filter configuration failed!')
      return None

    pre_launch_timestamp = adb_utils.logcat_save_timestamp()
    # Launch the app.
    results = self.launch_app(pre_launch_timestamp)

    # Run the postprocess.
    for listener in self.listeners:
      listener.postprocess(pre_launch_timestamp)

    return results

  def launch_app(self, pre_launch_timestamp: str) -> Optional[List[Tuple[str]]]:
    """ Launches the app.

        Returns:
          A list of (metric, value) tuples.
    """
    print_utils.debug_print('Running with timeout {}'.format(self.timeout))

    passed, am_start_output = cmd_utils.run_shell_command('timeout {timeout} '
                                                 '"{DIR}/launch_application" '
                                                 '"{package}" '
                                                 '"{activity}"'.
                                                   format(timeout=self.timeout,
                                                          DIR=AppRunner.APP_STARTUP_DIR,
                                                          package=self.package,
                                                          activity=self.activity))
    if not passed and not self.simulate:
      return None

    return self.wait_for_app_finish(pre_launch_timestamp, am_start_output)

  def wait_for_app_finish(self,
                          pre_launch_timestamp: str,
                          am_start_output:  str) -> Optional[List[Tuple[str]]]:
    """ Wait for app finish and all metrics are shown in logcat.

    Returns:
      A list of (metric, value) tuples.
    """
    if self.simulate:
      return [('TotalTime', '123')]

    ret = []
    for listener in self.listeners:
      output = listener.metrics_selector(am_start_output,
                                         pre_launch_timestamp)
      ret = ret + AppRunner.parse_metrics_output(output)

    return ret

  @staticmethod
  def parse_metrics_output(input: str) -> List[
    Tuple[str, str, str]]:
    """Parses output of app startup to metrics and corresponding values.

    It converts 'a=b\nc=d\ne=f\n...' into '[(a,b,''),(c,d,''),(e,f,'')]'

    Returns:
      A list of tuples that including metric name, metric value and rest info.
    """
    all_metrics = []
    for line in input.split('\n'):
      if not line:
        continue
      splits = line.split('=')
      if len(splits) < 2:
        print_utils.error_print('Bad line "{}"'.format(line))
        continue
      metric_name = splits[0]
      metric_value = splits[1]
      rest = splits[2] if len(splits) > 2 else ''
      if rest:
        print_utils.error_print('Corrupt line "{}"'.format(line))
      print_utils.debug_print('metric: "{metric_name}", '
                              'value: "{metric_value}" '.
                                format(metric_name=metric_name,
                                     metric_value=metric_value))

      all_metrics.append((metric_name, metric_value))
    return all_metrics

  @staticmethod
  def parse_total_time( am_start_output: str) -> Optional[str]:
    """Parses the total time from 'adb shell am start pkg' output.

    Returns:
      the total time of app startup.
    """
    for line in am_start_output.split('\n'):
      if 'TotalTime:' in line:
        return line[len('TotalTime:'):].strip()
    return None

