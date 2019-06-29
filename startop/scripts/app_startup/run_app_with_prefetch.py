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
from typing import List, Tuple
from pathlib import Path

# local imports
import lib.adb_utils as adb_utils

# global variables
DIR = os.path.abspath(os.path.dirname(__file__))
IORAP_COMMON_BASH_SCRIPT = os.path.realpath(os.path.join(DIR,
                                                         '../iorap/common'))

sys.path.append(os.path.dirname(DIR))
import lib.print_utils as print_utils
import lib.cmd_utils as cmd_utils
import iorap.lib.iorapd_utils as iorapd_utils

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

def validate_options(opts: argparse.Namespace) -> bool:
  """Validates the activity and trace file if needed.

  Returns:
    A bool indicates whether the activity is valid and trace file exists if
    necessary.
  """
  needs_trace_file = (opts.readahead != 'cold' and opts.readahead != 'warm')
  if needs_trace_file and (opts.input is None or
                           not os.path.exists(opts.input)):
    print_utils.error_print('--input not specified!')
    return False

  # Install necessary trace file.
  if needs_trace_file:
    passed = iorapd_utils.iorapd_compiler_install_trace_file(
      opts.package, opts.activity, opts.input)
    if not cmd_utils.SIMULATE and not passed:
      print_utils.error_print('Failed to install compiled TraceFile.pb for '
                              '"{}/{}"'.
                                format(opts.package, opts.activity))
      return False

  if opts.activity is not None:
    return True

  _, opts.activity = cmd_utils.run_shell_func(IORAP_COMMON_BASH_SCRIPT,
                                              'get_activity_name',
                                              [opts.package])

  if not opts.activity:
    print_utils.error_print('Activity name could not be found, '
                              'invalid package name?!')
    return False

  return True

def set_up_adb_env():
  """Sets up adb environment."""
  adb_utils.root()
  adb_utils.disable_selinux()
  time.sleep(1)

def configure_compiler_filter(compiler_filter: str, package: str,
                              activity: str) -> bool:
  """Configures compiler filter (e.g. speed).

  Returns:
    A bool indicates whether configure of compiler filer succeeds or not.
  """
  if not compiler_filter:
    print_utils.debug_print('No --compiler-filter specified, don\'t'
                            ' need to force it.')
    return True

  passed, current_compiler_filter_info = \
    cmd_utils.run_shell_command(
      '{} --package {}'.format(os.path.join(DIR, 'query_compiler_filter.py'),
                               package))

  if passed != 0:
    return passed

  # TODO: call query_compiler_filter directly as a python function instead of
  #  these shell calls.
  current_compiler_filter, current_reason, current_isa = current_compiler_filter_info.split(' ')
  print_utils.debug_print('Compiler Filter={} Reason={} Isa={}'.format(
    current_compiler_filter, current_reason, current_isa))

  # Don't trust reasons that aren't 'unknown' because that means
  #  we didn't manually force the compilation filter.
  # (e.g. if any automatic system-triggered compilations are not unknown).
  if current_reason != 'unknown' or current_compiler_filter != compiler_filter:
    passed, _ = adb_utils.run_shell_command('{}/force_compiler_filter '
                                            '--compiler-filter "{}" '
                                            '--package "{}"'
                                            ' --activity "{}'.
                                              format(DIR, compiler_filter,
                                                     package, activity))
  else:
    adb_utils.debug_print('Queried compiler-filter matched requested '
                          'compiler-filter, skip forcing.')
    passed = False
  return passed

def parse_metrics_output(input: str,
                         simulate: bool = False) -> List[Tuple[str, str, str]]:
  """Parses ouput of app startup to metrics and corresponding values.

  It converts 'a=b\nc=d\ne=f\n...' into '[(a,b,''),(c,d,''),(e,f,'')]'

  Returns:
    A list of tuples that including metric name, metric value and rest info.
  """
  if simulate:
    return [('TotalTime', '123')]

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

def run(readahead: str,
        package: str,
        activity: str,
        timeout: int,
        simulate: bool,
        debug: bool) -> List[Tuple[str, str]]:
  """Runs app startup test.

  Returns:
    A list of tuples that including metric name, metric value and rest info.
  """
  print_utils.debug_print('==========================================')
  print_utils.debug_print('=====             START              =====')
  print_utils.debug_print('==========================================')

  if readahead != 'warm':
    print_utils.debug_print('Drop caches for non-warm start.')
    # Drop all caches to get cold starts.
    adb_utils.vm_drop_cache()

  print_utils.debug_print('Running with timeout {}'.format(timeout))

  pre_launch_timestamp = adb_utils.logcat_save_timestamp()

  passed, output = cmd_utils.run_shell_command('timeout {timeout} '
                                               '"{DIR}/launch_application" '
                                               '"{package}" '
                                               '"{activity}" | '
                                               '"{DIR}/parse_metrics" '
                                               '--package {package} '
                                               '--activity {activity} '
                                               '--timestamp "{timestamp}"'
                                                 .format(timeout=timeout,
                                                         DIR=DIR,
                                                         package=package,
                                                         activity=activity,
                                                         timestamp=pre_launch_timestamp))

  if not output and not simulate:
    return None

  results = parse_metrics_output(output, simulate)

  passed = perform_post_launch_cleanup(
    readahead, package, activity, timeout, debug, pre_launch_timestamp)
  if not passed and not simulate:
    print_utils.error_print('Cannot perform post launch cleanup!')
    return None

  adb_utils.pkill(package)
  return results

def perform_post_launch_cleanup(readahead: str,
                                package: str,
                                activity: str,
                                timeout: int,
                                debug: bool,
                                logcat_timestamp: str) -> bool:
  """Performs cleanup at the end of each loop iteration.

  Returns:
    A bool indicates whether the cleanup succeeds or not.
  """
  if readahead != 'warm' and readahead != 'cold':
    return iorapd_utils.wait_for_iorapd_finish(package,
                                               activity,
                                               timeout,
                                               debug,
                                               logcat_timestamp)
    return passed
  # Don't need to do anything for warm or cold.
  return True

def run_test(opts: argparse.Namespace) -> List[Tuple[str, str]]:
  """Runs one test using given options.

  Returns:
    A list of tuples that including metric name, metric value and anything left.
  """
  print_utils.DEBUG = opts.debug
  cmd_utils.SIMULATE = opts.simulate

  passed = validate_options(opts)
  if not passed:
    return None

  set_up_adb_env()

  # Ensure the APK is currently compiled with whatever we passed in
  # via --compiler-filter.
  # No-op if this option was not passed in.
  if not configure_compiler_filter(opts.compiler_filter, opts.package,
                                   opts.activity):
    return None

  return run(opts.readahead, opts.package, opts.activity, opts.timeout,
             opts.simulate, opts.debug)

def main():
  args = parse_options()
  result = run_test(args)

  if result is None:
    return 1

  print(result)
  return 0

if __name__ == '__main__':
  sys.exit(main())
