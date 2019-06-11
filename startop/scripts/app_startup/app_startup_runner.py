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
import asyncio
import csv
import itertools
import os
import sys
import tempfile
import time
from typing import Any, Callable, Dict, Generic, Iterable, List, NamedTuple, TextIO, Tuple, TypeVar, Optional, Union

# The following command line options participate in the combinatorial generation.
# All other arguments have a global effect.
_COMBINATORIAL_OPTIONS=['packages', 'readaheads', 'compiler_filters']
_TRACING_READAHEADS=['mlock', 'fadvise']
_FORWARD_OPTIONS={'loop_count': '--count'}
_RUN_SCRIPT=os.path.join(os.path.dirname(os.path.realpath(__file__)), 'run_app_with_prefetch')

RunCommandArgs = NamedTuple('RunCommandArgs', [('package', str), ('readahead', str), ('compiler_filter', Optional[str])])
CollectorPackageInfo = NamedTuple('CollectorPackageInfo', [('package', str), ('compiler_filter', str)])
_COLLECTOR_SCRIPT=os.path.join(os.path.dirname(os.path.realpath(__file__)), '../iorap/collector')
_COLLECTOR_TIMEOUT_MULTIPLIER = 2 # take the regular --timeout and multiply by 2; systrace starts up slowly.

_UNLOCK_SCREEN_SCRIPT=os.path.join(os.path.dirname(os.path.realpath(__file__)), 'unlock_screen')

# This must be the only mutable global variable. All other global variables are constants to avoid magic literals.
_debug = False  # See -d/--debug flag.
_DEBUG_FORCE = None  # Ignore -d/--debug if this is not none.

# Type hinting names.
T = TypeVar('T')
NamedTupleMeta = Callable[..., T]  # approximation of a (S : NamedTuple<T> where S() == T) metatype.

def parse_options(argv: List[str] = None):
  """Parse command line arguments and return an argparse Namespace object."""
  parser = argparse.ArgumentParser(description="Run one or more Android applications under various settings in order to measure startup time.")
  # argparse considers args starting with - and -- optional in --help, even though required=True.
  # by using a named argument group --help will clearly say that it's required instead of optional.
  required_named = parser.add_argument_group('required named arguments')
  required_named.add_argument('-p', '--package', action='append', dest='packages', help='package of the application', required=True)
  required_named.add_argument('-r', '--readahead', action='append', dest='readaheads', help='which readahead mode to use', choices=('warm', 'cold', 'mlock', 'fadvise'), required=True)

  # optional arguments
  # use a group here to get the required arguments to appear 'above' the optional arguments in help.
  optional_named = parser.add_argument_group('optional named arguments')
  optional_named.add_argument('-c', '--compiler-filter', action='append', dest='compiler_filters', help='which compiler filter to use. if omitted it does not enforce the app\'s compiler filter', choices=('speed', 'speed-profile', 'quicken'))
  optional_named.add_argument('-s', '--simulate', dest='simulate', action='store_true', help='Print which commands will run, but don\'t run the apps')
  optional_named.add_argument('-d', '--debug', dest='debug', action='store_true', help='Add extra debugging output')
  optional_named.add_argument('-o', '--output', dest='output', action='store', help='Write CSV output to file.')
  optional_named.add_argument('-t', '--timeout', dest='timeout', action='store', type=int, help='Timeout after this many seconds when executing a single run.')
  optional_named.add_argument('-lc', '--loop-count', dest='loop_count', default=1, type=int, action='store', help='How many times to loop a single run.')
  optional_named.add_argument('-in', '--inodes', dest='inodes', type=str, action='store', help='Path to inodes file (system/extras/pagecache/pagecache.py -d inodes)')

  return parser.parse_args(argv)

# TODO: refactor this with a common library file with analyze_metrics.py
def _debug_print(*args, **kwargs):
  """Print the args to sys.stderr if the --debug/-d flag was passed in."""
  if _debug:
    print(*args, **kwargs, file=sys.stderr)

def _expand_gen_repr(args):
  """Like repr but any generator-like object has its iterator consumed
  and then called repr on."""
  new_args_list = []
  for i in args:
    # detect iterable objects that do not have their own override of __str__
    if hasattr(i, '__iter__'):
      to_str = getattr(i, '__str__')
      if to_str.__objclass__ == object:
        # the repr for a generator is just type+address, expand it out instead.
        new_args_list.append([_expand_gen_repr([j])[0] for j in i])
        continue
    # normal case: uses the built-in to-string
    new_args_list.append(i)
  return new_args_list

def _debug_print_gen(*args, **kwargs):
  """Like _debug_print but will turn any iterable args into a list."""
  if not _debug:
    return

  new_args_list = _expand_gen_repr(args)
  _debug_print(*new_args_list, **kwargs)

def _debug_print_nd(*args, **kwargs):
  """Like _debug_print but will turn any NamedTuple-type args into a string."""
  if not _debug:
    return

  new_args_list = []
  for i in args:
    if hasattr(i, '_field_types'):
      new_args_list.append("%s: %s" %(i.__name__, i._field_types))
    else:
      new_args_list.append(i)

  _debug_print(*new_args_list, **kwargs)

def dict_lookup_any_key(dictionary: dict, *keys: List[Any]):
  for k in keys:
    if k in dictionary:
      return dictionary[k]
  raise KeyError("None of the keys %s were in the dictionary" %(keys))

def generate_run_combinations(named_tuple: NamedTupleMeta[T], opts_dict: Dict[str, List[Optional[str]]])\
    -> Iterable[T]:
  """
  Create all possible combinations given the values in opts_dict[named_tuple._fields].

  :type T: type annotation for the named_tuple type.
  :param named_tuple: named tuple type, whose fields are used to make combinations for
  :param opts_dict: dictionary of keys to value list. keys correspond to the named_tuple fields.
  :return: an iterable over named_tuple instances.
  """
  combinations_list = []
  for k in named_tuple._fields:
    # the key can be either singular or plural , e.g. 'package' or 'packages'
    val = dict_lookup_any_key(opts_dict, k, k + "s")

    # treat {'x': None} key value pairs as if it was [None]
    # otherwise itertools.product throws an exception about not being able to iterate None.
    combinations_list.append(val or [None])

  _debug_print("opts_dict: ", opts_dict)
  _debug_print_nd("named_tuple: ", named_tuple)
  _debug_print("combinations_list: ", combinations_list)

  for combo in itertools.product(*combinations_list):
    yield named_tuple(*combo)

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

def generate_group_run_combinations(run_combinations: Iterable[NamedTuple], dst_nt: NamedTupleMeta[T])\
    -> Iterable[Tuple[T, Iterable[NamedTuple]]]:

  def group_by_keys(src_nt):
    src_d = src_nt._asdict()
    # now remove the keys that aren't legal in dst.
    for illegal_key in set(src_d.keys()) - set(dst_nt._fields):
      if illegal_key in src_d:
        del src_d[illegal_key]

    return dst_nt(**src_d)

  for args_list_it in itertools.groupby(run_combinations, group_by_keys):
    (group_key_value, args_it) = args_list_it
    yield (group_key_value, args_it)

class DataFrame:
  """Table-like class for storing a 2D cells table with named columns."""
  def __init__(self, data: Dict[str, List[object]] = {}):
    """
    Create a new DataFrame from a dictionary (keys = headers,
    values = columns).
    """
    self._headers = [i for i in data.keys()]
    self._rows = []

    row_num = 0

    def get_data_row(idx):
      r = {}
      for header, header_data in data.items():

        if not len(header_data) > idx:
          continue

        r[header] = header_data[idx]

      return r

    while True:
      row_dict = get_data_row(row_num)
      if len(row_dict) == 0:
        break

      self._append_row(row_dict.keys(), row_dict.values())
      row_num = row_num + 1

  def concat_rows(self, other: 'DataFrame') -> None:
    """
    In-place concatenate rows of other into the rows of the
    current DataFrame.

    None is added in pre-existing cells if new headers
    are introduced.
    """
    other_datas = other._data_only()

    other_headers = other.headers

    for d in other_datas:
      self._append_row(other_headers, d)

  def _append_row(self, headers: List[str], data: List[object]):
    new_row = {k:v for k,v in zip(headers, data)}
    self._rows.append(new_row)

    for header in headers:
      if not header in self._headers:
        self._headers.append(header)

  def __repr__(self):
#     return repr(self._rows)
    repr = ""

    header_list = self._headers_only()

    row_format = u""
    for header in header_list:
      row_format = row_format + u"{:>%d}" %(len(header) + 1)

    repr = row_format.format(*header_list) + "\n"

    for v in self._data_only():
      repr = repr + row_format.format(*v) + "\n"

    return repr

  def __eq__(self, other):
    if isinstance(other, self.__class__):
      return self.headers == other.headers and self.data_table == other.data_table
    else:
      print("wrong instance", other.__class__)
      return False

  @property
  def headers(self) -> List[str]:
    return [i for i in self._headers_only()]

  @property
  def data_table(self) -> List[List[object]]:
    return list(self._data_only())

  @property
  def data_table_transposed(self) -> List[List[object]]:
    return list(self._transposed_data())

  @property
  def data_row_len(self) -> int:
    return len(self._rows)

  def data_row_at(self, idx) -> List[object]:
    """
    Return a single data row at the specified index (0th based).

    Accepts negative indices, e.g. -1 is last row.
    """
    row_dict = self._rows[idx]
    l = []

    for h in self._headers_only():
      l.append(row_dict.get(h)) # Adds None in blank spots.

    return l

  def copy(self) -> 'DataFrame':
    """
    Shallow copy of this DataFrame.
    """
    return self.repeat(count=0)

  def repeat(self, count: int) -> 'DataFrame':
    """
    Returns a new DataFrame where each row of this dataframe is repeated count times.
    A repeat of a row is adjacent to other repeats of that same row.
    """
    df = DataFrame()
    df._headers = self._headers.copy()

    rows = []
    for row in self._rows:
      for i in range(count):
        rows.append(row.copy())

    df._rows = rows

    return df

  def merge_data_columns(self, other: 'DataFrame'):
    """
    Merge self and another DataFrame by adding the data from other column-wise.
    For any headers that are the same, data from 'other' is preferred.
    """
    for h in other._headers:
      if not h in self._headers:
        self._headers.append(h)

    append_rows = []

    for self_dict, other_dict in itertools.zip_longest(self._rows, other._rows):
      if not self_dict:
        d = {}
        append_rows.append(d)
      else:
        d = self_dict

      d_other = other_dict
      if d_other:
        for k,v in d_other.items():
          d[k] = v

    for r in append_rows:
      self._rows.append(r)

  def data_row_reduce(self, fnc) -> 'DataFrame':
    """
    Reduces the data row-wise by applying the fnc to each row (column-wise).
    Empty cells are skipped.

    fnc(Iterable[object]) -> object
    fnc is applied over every non-empty cell in that column (descending row-wise).

    Example:
      DataFrame({'a':[1,2,3]}).data_row_reduce(sum) == DataFrame({'a':[6]})

    Returns a new single-row DataFrame.
    """
    df = DataFrame()
    df._headers = self._headers.copy()

    def yield_by_column(header_key):
      for row_dict in self._rows:
        val = row_dict.get(header_key)
        if val:
          yield val

    new_row_dict = {}
    for h in df._headers:
      cell_value = fnc(yield_by_column(h))
      new_row_dict[h] = cell_value

    df._rows = [new_row_dict]
    return df

  def _headers_only(self):
    return self._headers

  def _data_only(self):
    row_len = len(self._rows)

    for i in range(row_len):
      yield self.data_row_at(i)

  def _transposed_data(self):
    return zip(*self._data_only())

def parse_run_script_csv_file_flat(csv_file: TextIO) -> List[int]:
  """Parse a CSV file full of integers into a flat int list."""
  csv_reader = csv.reader(csv_file)
  arr = []
  for row in csv_reader:
    for i in row:
      if i:
        arr.append(int(i))
  return arr

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

def make_script_command_with_temp_output(script: str, args: List[str], **kwargs)\
    -> Tuple[str, TextIO]:
  """
  Create a command to run a script given the args.
  Appends --count <loop_count> --output <tmp-file-name>.
  Returns a tuple (cmd, tmp_file)
  """
  tmp_output_file = tempfile.NamedTemporaryFile(mode='r')
  cmd = [script] + args
  for key, value in kwargs.items():
    cmd += ['--%s' %(key), "%s" %(value)]
  if _debug:
    cmd += ['--verbose']
  cmd = cmd + ["--output", tmp_output_file.name]
  return cmd, tmp_output_file

async def _run_command(*args : List[str], timeout: Optional[int] = None) -> Tuple[int, bytes]:
  # start child process
  # NOTE: universal_newlines parameter is not supported
  process = await asyncio.create_subprocess_exec(*args,
      stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)

  script_output = b""

  _debug_print("[PID]", process.pid)

#hack
#  stdout, stderr = await process.communicate()
#  return (process.returncode, stdout)

  timeout_remaining = timeout
  time_started = time.time()

  # read line (sequence of bytes ending with b'\n') asynchronously
  while True:
    try:
      line = await asyncio.wait_for(process.stdout.readline(), timeout_remaining)
      _debug_print("[STDOUT]", line)
      script_output += line

      if timeout_remaining:
        time_elapsed = time.time() - time_started
        timeout_remaining = timeout - time_elapsed
    except asyncio.TimeoutError:
      _debug_print("[TIMEDOUT] Process ", process.pid)

#      if process.returncode is not None:
#        #_debug_print("[WTF] can-write-eof?", process.stdout.can_write_eof())
#
#        _debug_print("[TIMEDOUT] Process already terminated")
#        (remaining_stdout, remaining_stderr) = await process.communicate()
#        script_output += remaining_stdout
#
#        code = await process.wait()
#        return (code, script_output)

      _debug_print("[TIMEDOUT] Sending SIGTERM.")
      process.terminate()

      # 1 second timeout for process to handle SIGTERM nicely.
      try:
       (remaining_stdout, remaining_stderr) = await asyncio.wait_for(process.communicate(), 5)
       script_output += remaining_stdout
      except asyncio.TimeoutError:
        _debug_print("[TIMEDOUT] Sending SIGKILL.")
        process.kill()

      # 1 second timeout to finish with SIGKILL.
      try:
        (remaining_stdout, remaining_stderr) = await asyncio.wait_for(process.communicate(), 5)
        script_output += remaining_stdout
      except asyncio.TimeoutError:
        # give up, this will leave a zombie process.
        _debug_print("[TIMEDOUT] SIGKILL failed for process ", process.pid)
        time.sleep(100)
        #await process.wait()

      return (-1, script_output)
    else:
      if not line: # EOF
        break

      #if process.returncode is not None:
      #  _debug_print("[WTF] can-write-eof?", process.stdout.can_write_eof())
      #  process.stdout.write_eof()

      #if process.stdout.at_eof():
      #  break

  code = await process.wait() # wait for child process to exit
  return (code, script_output)

def execute_arbitrary_command(cmd: List[str], simulate: bool, timeout: Optional[int]) -> Tuple[bool, str]:
  if simulate:
    print(" ".join(cmd))
    return (True, "")
  else:
    _debug_print("[EXECUTE]", cmd)

    # block until either command finishes or the timeout occurs.
    loop = asyncio.get_event_loop()
    (return_code, script_output) = loop.run_until_complete(_run_command(*cmd, timeout=timeout))

    script_output = script_output.decode() # convert bytes to str

    passed = (return_code == 0)
    _debug_print("[$?]", return_code)
    if not passed:
      print("[FAILED, code:%s]" %(return_code), script_output, file=sys.stderr)

    return (passed, script_output)

def execute_run_combos(grouped_run_combos: Iterable[Tuple[CollectorPackageInfo, Iterable[RunCommandArgs]]], simulate: bool, inodes_path: str, timeout: int, loop_count: int, need_trace: bool):
  # nothing will work if the screen isn't unlocked first.
  execute_arbitrary_command([_UNLOCK_SCREEN_SCRIPT], simulate, timeout)

  for collector_info, run_combos in grouped_run_combos:
    #collector_args = ["--package", package_name]
    collector_args = as_run_command(collector_info)
    # TODO: forward --wait_time for how long systrace runs?
    # TODO: forward --trace_buffer_size for size of systrace buffer size?
    collector_cmd, collector_tmp_output_file = make_script_command_with_temp_output(_COLLECTOR_SCRIPT, collector_args, inodes=inodes_path)

    with collector_tmp_output_file:
      collector_passed = True
      if need_trace:
        collector_timeout = timeout and _COLLECTOR_TIMEOUT_MULTIPLIER * timeout
        (collector_passed, collector_script_output) = execute_arbitrary_command(collector_cmd, simulate, collector_timeout)
        # TODO: consider to print a ; collector wrote file to <...> into the CSV file so we know it was ran.

      for combos in run_combos:
        args = as_run_command(combos)

        cmd, tmp_output_file = make_script_command_with_temp_output(_RUN_SCRIPT, args, count=loop_count, input=collector_tmp_output_file.name)
        with tmp_output_file:
          (passed, script_output) = execute_arbitrary_command(cmd, simulate, timeout)
          parsed_output = simulate and DataFrame({'fake_ms':[1,2,3]}) or parse_run_script_csv_file(tmp_output_file)
          yield (passed, script_output, parsed_output)

def gather_results(commands: Iterable[Tuple[bool, str, DataFrame]], key_list: List[str], value_list: List[Tuple[str, ...]]):
  _debug_print("gather_results: key_list = ", key_list)
#  yield key_list + ["time(ms)"]

  stringify_none = lambda s: s is None and "<none>" or s

  for ((passed, script_output, run_result_list), values) in itertools.zip_longest(commands, value_list):
    _debug_print("run_result_list = ", run_result_list)
    _debug_print("values = ", values)
    if not passed:
      continue

    # RunCommandArgs(package='com.whatever', readahead='warm', compiler_filter=None)
    # -> {'package':['com.whatever'], 'readahead':['warm'], 'compiler_filter':[None]}
    values_dict = {k:[v] for k,v in values._asdict().items()}

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
      csv_writer.writerow(data_row)

    output.flush() # see the output live.

def main():
  global _debug

  opts = parse_options()
  _debug = opts.debug
  if _DEBUG_FORCE is not None:
    _debug = _DEBUG_FORCE
  _debug_print("parsed options: ", opts)
  need_trace = not not set(opts.readaheads).intersection(set(_TRACING_READAHEADS))
  if need_trace and not opts.inodes:
    print("Error: Missing -in/--inodes, required when using a readahead of %s" %(_TRACING_READAHEADS), file=sys.stderr)
    return 1

  output_file = opts.output and open(opts.output, 'w') or sys.stdout

  combos = lambda: generate_run_combinations(RunCommandArgs, vars(opts))
  _debug_print_gen("run combinations: ", combos())

  grouped_combos = lambda: generate_group_run_combinations(combos(), CollectorPackageInfo)
  _debug_print_gen("grouped run combinations: ", grouped_combos())

  exec = execute_run_combos(grouped_combos(), opts.simulate, opts.inodes, opts.timeout, opts.loop_count, need_trace)
  results = gather_results(exec, _COMBINATORIAL_OPTIONS, combos())
  eval_and_save_to_csv(output_file, results)

  return 0


if __name__ == '__main__':
  sys.exit(main())
