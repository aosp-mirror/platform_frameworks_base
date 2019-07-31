#!/usr/bin/env python3
#
# Copyright 2018, The Android Open Source Project
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
#
# Measure application start-up time by launching applications under various combinations.
# See --help for more details.
#
#
# Sample usage:
# $> ./app_startup_runner.py -p com.google.android.calculator -r warm -r cold -lc 10  -o out.csv
# $> ./analyze_metrics.py out.csv
#
#

import argparse
import csv
import itertools
import os
import sys
import tempfile
from typing import Any, Callable, Iterable, List, NamedTuple, TextIO, Tuple, \
    TypeVar, Union, Optional

# local import
DIR = os.path.abspath(os.path.dirname(__file__))
sys.path.append(os.path.dirname(DIR))
from app_startup.run_app_with_prefetch import PrefetchAppRunner
import app_startup.lib.args_utils as args_utils
from app_startup.lib.data_frame import DataFrame
import lib.cmd_utils as cmd_utils
import lib.print_utils as print_utils

# The following command line options participate in the combinatorial generation.
# All other arguments have a global effect.
_COMBINATORIAL_OPTIONS = ['package', 'readahead', 'compiler_filter', 'activity']
_TRACING_READAHEADS = ['mlock', 'fadvise']
_FORWARD_OPTIONS = {'loop_count': '--count'}
_RUN_SCRIPT = os.path.join(os.path.dirname(os.path.realpath(__file__)),
                           'run_app_with_prefetch.py')

CollectorPackageInfo = NamedTuple('CollectorPackageInfo',
                                  [('package', str), ('compiler_filter', str)])
_COLLECTOR_SCRIPT = os.path.join(os.path.dirname(os.path.realpath(__file__)),
                                 '../iorap/collector')
_COLLECTOR_TIMEOUT_MULTIPLIER = 10  # take the regular --timeout and multiply
# by 2; systrace starts up slowly.

_UNLOCK_SCREEN_SCRIPT = os.path.join(
    os.path.dirname(os.path.realpath(__file__)), 'unlock_screen')

RunCommandArgs = NamedTuple('RunCommandArgs',
                            [('package', str),
                             ('readahead', str),
                             ('activity', Optional[str]),
                             ('compiler_filter', Optional[str]),
                             ('timeout', Optional[int]),
                             ('debug', bool),
                             ('simulate', bool),
                             ('input', Optional[str])])

# This must be the only mutable global variable. All other global variables are constants to avoid magic literals.
_debug = False  # See -d/--debug flag.
_DEBUG_FORCE = None  # Ignore -d/--debug if this is not none.

# Type hinting names.
T = TypeVar('T')
NamedTupleMeta = Callable[
    ..., T]  # approximation of a (S : NamedTuple<T> where S() == T) metatype.

def parse_options(argv: List[str] = None):
  """Parse command line arguments and return an argparse Namespace object."""
  parser = argparse.ArgumentParser(description="Run one or more Android "
                                               "applications under various "
                                               "settings in order to measure "
                                               "startup time.")
  # argparse considers args starting with - and -- optional in --help, even though required=True.
  # by using a named argument group --help will clearly say that it's required instead of optional.
  required_named = parser.add_argument_group('required named arguments')
  required_named.add_argument('-p', '--package', action='append',
                              dest='packages',
                              help='package of the application', required=True)
  required_named.add_argument('-r', '--readahead', action='append',
                              dest='readaheads',
                              help='which readahead mode to use',
                              choices=('warm', 'cold', 'mlock', 'fadvise'),
                              required=True)

  # optional arguments
  # use a group here to get the required arguments to appear 'above' the optional arguments in help.
  optional_named = parser.add_argument_group('optional named arguments')
  optional_named.add_argument('-c', '--compiler-filter', action='append',
                              dest='compiler_filters',
                              help='which compiler filter to use. if omitted it does not enforce the app\'s compiler filter',
                              choices=('speed', 'speed-profile', 'quicken'))
  optional_named.add_argument('-s', '--simulate', dest='simulate',
                              action='store_true',
                              help='Print which commands will run, but don\'t run the apps')
  optional_named.add_argument('-d', '--debug', dest='debug',
                              action='store_true',
                              help='Add extra debugging output')
  optional_named.add_argument('-o', '--output', dest='output', action='store',
                              help='Write CSV output to file.')
  optional_named.add_argument('-t', '--timeout', dest='timeout', action='store',
                              type=int, default=10,
                              help='Timeout after this many seconds when executing a single run.')
  optional_named.add_argument('-lc', '--loop-count', dest='loop_count',
                              default=1, type=int, action='store',
                              help='How many times to loop a single run.')
  optional_named.add_argument('-in', '--inodes', dest='inodes', type=str,
                              action='store',
                              help='Path to inodes file (system/extras/pagecache/pagecache.py -d inodes)')

  return parser.parse_args(argv)

def make_script_command_with_temp_output(script: str,
                                         args: List[str],
                                         **kwargs) -> Tuple[str, TextIO]:
  """
  Create a command to run a script given the args.
  Appends --count <loop_count> --output <tmp-file-name>.
  Returns a tuple (cmd, tmp_file)
  """
  tmp_output_file = tempfile.NamedTemporaryFile(mode='r')
  cmd = [script] + args
  for key, value in kwargs.items():
    cmd += ['--%s' % (key), "%s" % (value)]
  if _debug:
    cmd += ['--verbose']
  cmd = cmd + ["--output", tmp_output_file.name]
  return cmd, tmp_output_file

def key_to_cmdline_flag(key: str) -> str:
  """Convert key into a command line flag, e.g. 'foo-bars' -> '--foo-bar' """
  if key.endswith("s"):
    key = key[:-1]
  return "--" + key.replace("_", "-")

def as_run_command(tpl: NamedTuple) -> List[Union[str, Any]]:
  """
  Convert a named tuple into a command-line compatible arguments list.

  Example: ABC(1, 2, 3) -> ['--a', 1, '--b', 2, '--c', 3]
  """
  args = []
  for key, value in tpl._asdict().items():
    if value is None:
      continue
    args.append(key_to_cmdline_flag(key))
    args.append(value)
  return args

def run_collector_script(collector_info: CollectorPackageInfo,
                         inodes_path: str,
                         timeout: int,
                         simulate: bool) -> Tuple[bool, TextIO]:
  """Run collector to collect prefetching trace. """
  # collector_args = ["--package", package_name]
  collector_args = as_run_command(collector_info)
  # TODO: forward --wait_time for how long systrace runs?
  # TODO: forward --trace_buffer_size for size of systrace buffer size?
  collector_cmd, collector_tmp_output_file = make_script_command_with_temp_output(
      _COLLECTOR_SCRIPT, collector_args, inodes=inodes_path)

  collector_timeout = timeout and _COLLECTOR_TIMEOUT_MULTIPLIER * timeout
  (collector_passed, collector_script_output) = \
    cmd_utils.execute_arbitrary_command(collector_cmd,
                                        collector_timeout,
                                        shell=False,
                                        simulate=simulate)

  return collector_passed, collector_tmp_output_file

def parse_run_script_csv_file(csv_file: TextIO) -> DataFrame:
  """Parse a CSV file full of integers into a DataFrame."""
  csv_reader = csv.reader(csv_file)

  try:
    header_list = next(csv_reader)
  except StopIteration:
    header_list = []

  if not header_list:
    return None

  headers = [i for i in header_list]

  d = {}
  for row in csv_reader:
    header_idx = 0

    for i in row:
      v = i
      if i:
        v = int(i)

      header_key = headers[header_idx]
      l = d.get(header_key, [])
      l.append(v)
      d[header_key] = l

      header_idx = header_idx + 1

  return DataFrame(d)

def execute_run_combos(
    grouped_run_combos: Iterable[Tuple[CollectorPackageInfo, Iterable[RunCommandArgs]]],
    simulate: bool,
    inodes_path: str,
    timeout: int):
  # nothing will work if the screen isn't unlocked first.
  cmd_utils.execute_arbitrary_command([_UNLOCK_SCREEN_SCRIPT],
                                      timeout,
                                      simulate=simulate,
                                      shell=False)

  for collector_info, run_combos in grouped_run_combos:
    for combos in run_combos:
      args = as_run_command(combos)
      if combos.readahead in _TRACING_READAHEADS:
        passed, collector_tmp_output_file = run_collector_script(collector_info,
                                                                 inodes_path,
                                                                 timeout,
                                                                 simulate)
        combos = combos._replace(input=collector_tmp_output_file.name)

      print_utils.debug_print(combos)
      output = PrefetchAppRunner(**combos._asdict()).run()

      yield DataFrame(dict((x, [y]) for x, y in output)) if output else None

def gather_results(commands: Iterable[Tuple[DataFrame]],
                   key_list: List[str], value_list: List[Tuple[str, ...]]):
  print_utils.debug_print("gather_results: key_list = ", key_list)
  stringify_none = lambda s: s is None and "<none>" or s
  #  yield key_list + ["time(ms)"]
  for (run_result_list, values) in itertools.zip_longest(commands, value_list):
    print_utils.debug_print("run_result_list = ", run_result_list)
    print_utils.debug_print("values = ", values)

    if not run_result_list:
      continue

    # RunCommandArgs(package='com.whatever', readahead='warm', compiler_filter=None)
    # -> {'package':['com.whatever'], 'readahead':['warm'], 'compiler_filter':[None]}
    values_dict = {}
    for k, v in values._asdict().items():
      if not k in key_list:
        continue
      values_dict[k] = [stringify_none(v)]

    values_df = DataFrame(values_dict)
    # project 'values_df' to be same number of rows as run_result_list.
    values_df = values_df.repeat(run_result_list.data_row_len)

    # the results are added as right-hand-side columns onto the existing labels for the table.
    values_df.merge_data_columns(run_result_list)

    yield values_df

def eval_and_save_to_csv(output, annotated_result_values):
  printed_header = False

  csv_writer = csv.writer(output)
  for row in annotated_result_values:
    if not printed_header:
      headers = row.headers
      csv_writer.writerow(headers)
      printed_header = True
      # TODO: what about when headers change?

    for data_row in row.data_table:
      data_row = [d for d in data_row]
      csv_writer.writerow(data_row)

    output.flush()  # see the output live.

def coerce_to_list(opts: dict):
  """Tranform values of the dictionary to list.
  For example:
  1 -> [1], None -> [None], [1,2,3] -> [1,2,3]
  [[1],[2]] -> [[1],[2]], {1:1, 2:2} -> [{1:1, 2:2}]
  """
  result = {}
  for key in opts:
    val = opts[key]
    result[key] = val if issubclass(type(val), list) else [val]
  return result

def main():
  global _debug

  opts = parse_options()
  _debug = opts.debug
  if _DEBUG_FORCE is not None:
    _debug = _DEBUG_FORCE

  print_utils.DEBUG = _debug
  cmd_utils.SIMULATE = opts.simulate

  print_utils.debug_print("parsed options: ", opts)

  output_file = opts.output and open(opts.output, 'w') or sys.stdout

  combos = lambda: args_utils.generate_run_combinations(
      RunCommandArgs,
      coerce_to_list(vars(opts)),
      opts.loop_count)
  print_utils.debug_print_gen("run combinations: ", combos())

  grouped_combos = lambda: args_utils.generate_group_run_combinations(combos(),
                                                                      CollectorPackageInfo)

  print_utils.debug_print_gen("grouped run combinations: ", grouped_combos())
  exec = execute_run_combos(grouped_combos(),
                            opts.simulate,
                            opts.inodes,
                            opts.timeout)

  results = gather_results(exec, _COMBINATORIAL_OPTIONS, combos())

  eval_and_save_to_csv(output_file, results)

  return 1

if __name__ == '__main__':
  sys.exit(main())
