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

"""
Perform statistical analysis on measurements produced by app_startup_runner.py

Install:
$> sudo apt-get install python3-scipy

Usage:
$> ./analyze_metrics.py <filename.csv> [<filename2.csv> ...]
$> ./analyze_metrics.py --help
"""

import argparse
import csv
import itertools
import os
import subprocess
import sys
import tempfile
from typing import Any, List, Dict, Iterable, TextIO, Tuple

from scipy import stats as sc
import numpy as np


# These CSV columns are considered labels. Everything after them in the same row are metrics.
_LABEL_COLUMNS=['packages', 'readaheads', 'compiler_filters']
# The metric series with the 'cold' readahead is the baseline.
# All others (warm, jit, etc) are the potential improvements.

#fixme: this should probably be an option
_BASELINE=('readaheads', 'cold')
# ignore this for some statistic calculations
_IGNORE_PAIR=('readaheads', 'warm')
_PLOT_SUBKEY='readaheads'
_PLOT_GROUPKEY='packages'
_PLOT_DATA_INDEX = 0
_DELTA=50
_DELTA2=100
_PVALUE_THRESHOLD=0.10
_debug = False  # See -d/--debug flag.

def parse_options(argv: List[str] = None):
  """Parse command line arguments and return an argparse Namespace object."""
  parser = argparse.ArgumentParser(description="Perform statistical analysis on measurements produced by app_start_runner.py.")
  parser.add_argument('input_files', metavar='file.csv', nargs='+', help='CSV file produced by app_startup_runner.py')

  parser.add_argument('-d', '--debug', dest='debug', action='store_true', help='Add extra debugging output')
  parser.add_argument('-os', '--output-samples', dest='output_samples', default='/dev/null', action='store', help='Store CSV for per-sample data')
  parser.add_argument('-oc', '--output-comparable', dest='output_comparable', default='/dev/null', action='store', help='Output CSV for comparable against baseline')
  parser.add_argument('-ocs', '--output-comparable-significant', dest='output_comparable_significant', default='/dev/null', action='store', help='Output CSV for comparable against baseline (significant only)')
  parser.add_argument('-pt', '--pvalue-threshold', dest='pvalue_threshold', type=float, default=_PVALUE_THRESHOLD, action='store')
  parser.add_argument('-dt', '--delta-threshold', dest='delta_threshold', type=int, default=_DELTA, action='store')

  return parser.parse_args(argv)

def _debug_print(*args, **kwargs):
  """Print the args to sys.stderr if the --debug/-d flag was passed in."""
  global _debug
  if _debug:
    print(*args, **kwargs, file=sys.stderr)

def _expand_gen_repr(args):
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

def read_headers(input_file: TextIO) -> Tuple[List[str], List[str]]:
  _debug_print("read_headers for file: ", input_file.name)
  csv_reader = csv.reader(input_file)

  label_num_columns = len(_LABEL_COLUMNS)

  try:
    header = next(csv_reader)
  except StopIteration:
    header = None
  _debug_print('header', header)

  if not header:
    return (None, None)

  labels = header[0:label_num_columns]
  data = header[label_num_columns:]

  return (labels, data)

def read_labels_and_data(input_file: TextIO) -> Iterable[Tuple[List[str], List[int]]]:
  _debug_print("print_analysis for file: ", input_file.name)
  csv_reader = csv.reader(input_file)

  # Skip the header because it doesn't contain any data.
  # To get the header see read_headers function.
  try:
    header = next(csv_reader)
  except StopIteration:
    header = None

  label_num_columns = len(_LABEL_COLUMNS)

  for row in csv_reader:
    if len(row) > 0 and row[0][0] == ';':
      _debug_print("skip comment line", row)
      continue

    labels = row[0:label_num_columns]
    data = [int(i) for i in row[label_num_columns:]]

#    _debug_print("labels:", labels)
#    _debug_print("data:", data)

    yield (labels, data)

def group_metrics_by_label(it: Iterable[Tuple[List[str], List[int]]]):
  prev_labels = None
  data_2d = []

  for label_list, data_list in it:
    if prev_labels != label_list:
      if prev_labels:
#        _debug_print("grouped labels:", prev_labels, "data_2d:", data_2d)
        yield (prev_labels, data_2d)
      data_2d = []

    data_2d.append(data_list)
    prev_labels = label_list

  if prev_labels:
#    _debug_print("grouped labels:", prev_labels, "data_2d:", data_2d)
    yield (prev_labels, data_2d)

def data_to_numpy(it: Iterable[Tuple[List[str], List[List[int]]]]) -> Iterable[Tuple[List[str], Any]]:
  for label_list, data_2d in it:
    yield (label_list, np.asarray(data_2d, dtype=int))

def iterate_columns(np_data_2d):
  for col in range(np_data_2d.shape[1]):
    col_as_array = np_data_2d[:, col]
    yield col_as_array

def confidence_interval(np_data_2d, percent=0.95):
  """
  Given some data [[a,b,c],[d,e,f,]...]

  We assume the same metric is in the column (e.g. [a,d])
  and that data in the rows (e.g. [b,e]) are separate metric values.

  We then calculate the CI for each metric individually returning it as a list of tuples.
  """
  arr = []
  for col_2d in iterate_columns(np_data_2d):
    mean = col_2d.mean()
    sigma = col_2d.std()

    ci = sc.norm.interval(percent, loc=mean, scale=sigma / np.sqrt(len(col_2d)))
    arr.append(ci)

  # TODO: This seems to be returning NaN when all the samples have the same exact value
  # (e.g. stddev=0, which can trivially happen when sample count = 1).

  return arr

def print_analysis(it, label_header: List[str], data_header: List[str], output_samples: str):
  print(label_header)

  with open(output_samples, "w") as output_file:

    csv_writer = csv.writer(output_file)
    csv_writer.writerow(label_header + ['mean', 'std', 'confidence_interval_a', 'confidence_interval_b'])

    for label_list, np_data_2d in it:
      print("**********************")
      print(label_list)
      print()
      print("      ", data_header)
      # aggregate computation column-wise
      print("Mean: ", np_data_2d.mean(axis=0))
      print("Std:  ", np_data_2d.std(axis=0))
      print("CI95%:", confidence_interval(np_data_2d))
      print("SEM:  ", stats_standard_error_one(np_data_2d, axis=0))

      #ci = confidence_interval(np_data_2d)[_PLOT_DATA_INDEX]
      sem = stats_standard_error_one(np_data_2d, axis=0)[_PLOT_DATA_INDEX]
      mean = np_data_2d.mean(axis=0)[_PLOT_DATA_INDEX]

      ci = (mean - sem, mean + sem)

      csv_writer.writerow(label_list + [mean, np_data_2d.std(axis=0)[_PLOT_DATA_INDEX], ci[0], ci[1]])

def from_file_group_by_labels(input_file):
  (label_header, data_header) = read_headers(input_file)
  label_data_iter = read_labels_and_data(input_file)
  grouped_iter = group_metrics_by_label(label_data_iter)
  grouped_numpy_iter = data_to_numpy(grouped_iter)

  return grouped_numpy_iter, label_header, data_header

def list_without_index(list, index):
  return list[:index] + list[index+1:]

def group_by_without_baseline_key(grouped_numpy_iter, label_header):
  """
  Data is considered comparable if the only difference is the baseline key
  (i.e. the readahead is different but the package, compilation filter, etc, are the same).

  Returns iterator that's grouped by the non-baseline labels to an iterator of
  (label_list, data_2d).
  """
  baseline_index = label_header.index(_BASELINE[0])

  def get_label_without_baseline(tpl):
    label_list, _ = tpl
    return list_without_index(label_list, baseline_index)
  # [['pkgname', 'compfilter', 'warm'], [data]]
  # [['pkgname', 'compfilter', 'cold'], [data2]]
  # [['pkgname2', 'compfilter', 'warm'], [data3]]
  #
  #   ->
  # ( [['pkgname', 'compfilter', 'warm'], [data]]      # ignore baseline label change.
  #   [['pkgname', 'compfilter', 'cold'], [data2]] ),  # split here because the pkgname changed.
  # ( [['pkgname2', 'compfilter', 'warm'], [data3]] )
  for group_info, it in itertools.groupby(grouped_numpy_iter, key = get_label_without_baseline):
    yield it

  # TODO: replace this messy manual iteration/grouping with pandas

def iterate_comparable_metrics(without_baseline_iter, label_header):
  baseline_index = label_header.index(_BASELINE[0])
  baseline_value = _BASELINE[1]

  _debug_print("iterate comparables")

  def is_baseline_fun(tp):
    ll, dat = tp
    return ll[baseline_index] == baseline_value

  # iterating here when everything but the baseline key is the same.
  for it in without_baseline_iter:
    it1, it2 = itertools.tee(it)

    # find all the baseline data.
    baseline_filter_it = filter(is_baseline_fun, it1)

    # find non-baseline data.
    nonbaseline_filter_it = itertools.filterfalse(is_baseline_fun, it2)

    yield itertools.product(baseline_filter_it, nonbaseline_filter_it)

def stats_standard_error_one(a, axis):
  a_std = a.std(axis=axis, ddof=0)
  a_len = a.shape[axis]

  return a_std / np.sqrt(a_len)

def stats_standard_error(a, b, axis):
  a_std = a.std(axis=axis, ddof=0)
  b_std = b.std(axis=axis, ddof=0)

  a_len = a.shape[axis]
  b_len = b.shape[axis]

  temp1 = a_std*a_std/a_len
  temp2 = b_std*b_std/b_len

  return np.sqrt(temp1 + temp2)

def stats_tvalue(a, b, axis, delta = 0):
  a_mean = a.mean(axis=axis)
  b_mean = b.mean(axis=axis)

  return (a_mean - b_mean - delta) / stats_standard_error(a, b, axis)

def stats_pvalue(a, b, axis, delta, left:bool = False):
  """
  Single-tailed 2-sample t-test.

  Returns p-value for the null hypothesis: mean(a) - mean(b) >= delta.
  :param a: numpy 2d array
  :param b: numpy 2d array
  :param axis: which axis to do the calculations across
  :param delta: test value of mean differences
  :param left: if true then use <= delta instead of >= delta
  :return: p-value
  """
  # implement our own pvalue calculation because the built-in t-test (t,p values)
  # only offer delta=0 , e.g. m1-m1 ? 0
  # we are however interested in m1-m2 ? delta
  t_value = stats_tvalue(a, b, axis, delta)

  # 2-sample degrees of freedom is using the array sizes - 2.
  dof = a.shape[axis] + b.shape[axis] - 2

  if left:
    # left tailed test. e.g. m1-m2 <= delta
    return sc.t.cdf(t_value, dof)
  else:
    # right tailed test. e.g. m1-m2 >= delta
    return sc.t.sf(t_value, dof)
  # a left+right tailed test is a 2-tail t-test and can be done using ttest_ind for delta=0

def print_comparable_analysis(comparable_metrics_iter, label_header, data_header, output_comparable: str, output_comparable_significant: str):
  baseline_value = _BASELINE[1]
  baseline_index = label_header.index(_BASELINE[0])

  old_baseline_label_list = None
  delta = _DELTA
  filter_value = _IGNORE_PAIR[1]
  filter_index = label_header.index(_IGNORE_PAIR[0])

  pvalue_threshold = _PVALUE_THRESHOLD
  ci_threshold = (1 - _PVALUE_THRESHOLD) * 100.0

  with open(output_comparable, "w") as output_file:

    csv_writer = csv.writer(output_file)
    csv_writer.writerow(label_header + ['mean', 'mean_diff', 'sem', 'pvalue_2tailed', 'pvalue_gt%d' %(_DELTA), 'pvalue_gt%d' %(_DELTA2)])

    print("------------------------------------------------------------------")
    print("Comparison against the baseline %s = %s" %(_BASELINE, baseline_value))
    print("--- Right-tailed t-test checks if the baseline >= current %s by at least %d" %(_BASELINE[0], delta))
    print()

    global_stats = {'better_than_delta': [], 'better_than_delta_p95': []}

    for nested_it in comparable_metrics_iter:
      print("************************")

      better_than_delta = []
      better_than_delta_p95 = []

      saw_baseline_once = False

      for ((baseline_label_list, baseline_np_data_2d), (rest_label_list, rest_np_data_2d)) in nested_it:
        _debug_print("baseline_label_list:", baseline_label_list)
        _debug_print("baseline_np_data_2d:", baseline_np_data_2d)
        _debug_print("rest_label_list:", rest_label_list)
        _debug_print("rest_np_data_2d:", rest_np_data_2d)

        mean_diff = baseline_np_data_2d.mean(axis=0) - rest_np_data_2d.mean(axis=0)
        # 2-sample 2-tailed t-test with delta=0
        # e.g. "Is it true that usually the two sample means are different?"
        t_statistic, t_pvalue = sc.ttest_ind(baseline_np_data_2d, rest_np_data_2d, axis=0)

        # 2-sample 1-tailed t-test with delta=50
        # e.g. "Is it true that usually the sample means better than 50ms?"
        t2 = stats_tvalue(baseline_np_data_2d, rest_np_data_2d, axis=0, delta=delta)
        p2 = stats_pvalue(baseline_np_data_2d, rest_np_data_2d, axis=0, delta=delta)

        t2_b = stats_tvalue(baseline_np_data_2d, rest_np_data_2d, axis=0, delta=_DELTA2)
        p2_b = stats_pvalue(baseline_np_data_2d, rest_np_data_2d, axis=0, delta=_DELTA2)

        print("%s vs %s" %(rest_label_list, baseline_value))
        print("                           ", data_header)
        print("Mean Difference:           ", mean_diff)
        print("T-test (2-tailed) != 0:      t=%s, p=%s" %(t_statistic, t_pvalue))
        print("T-test (right-tailed) >= %d: t=%s, p=%s" %(_DELTA, t2, p2))
        print("T-test (right-tailed) >= %d: t=%s, p=%s" %(_DELTA2, t2_b, p2_b))

        def write_out_values(label_list, *args):
          csv_writer.writerow(label_list + [i[_PLOT_DATA_INDEX] for i in args])

        sem = stats_standard_error(baseline_np_data_2d, rest_np_data_2d, axis=0)
        if saw_baseline_once == False:
          saw_baseline_once = True
          base_sem = stats_standard_error_one(baseline_np_data_2d, axis=0)
          write_out_values(baseline_label_list, baseline_np_data_2d.mean(axis=0), [0], base_sem, [None], [None], [None])
        write_out_values(rest_label_list, rest_np_data_2d.mean(axis=0), mean_diff, sem, t_pvalue, p2, p2_b)

        # now do the global statistics aggregation

        if rest_label_list[filter_index] == filter_value:
          continue

        if mean_diff > delta:
          better_than_delta.append((mean_diff, p2, rest_label_list))

          if p2 <= pvalue_threshold:
            better_than_delta_p95.append((mean_diff, rest_label_list))

      if better_than_delta:
        global_stats['better_than_delta'].append(better_than_delta)
      if better_than_delta_p95:
        global_stats['better_than_delta_p95'].append(better_than_delta_p95)

    print("------------------------")
    print("Global statistics:")
    print("//// Rows with %s=%s are ignored here." %_IGNORE_PAIR)
    print("- # of results with mean diff better than delta(%d)       = %d" %(delta, len(global_stats['better_than_delta'])))
    print("    > (meandiff, pvalue, labels)")
    for i in global_stats['better_than_delta']:
      print("    > %s" %i)
    print("- # of results with mean diff better than delta(%d) CI%d%% = %d" %(delta, ci_threshold, len(global_stats['better_than_delta_p95'])))
    print("    > (meandiff, labels)")
    for i in global_stats['better_than_delta_p95']:
      print("    > %s" %i)

def main():
  global _debug
  global _DELTA
  global _PVALUE_THRESHOLD

  opts = parse_options()
  _debug = opts.debug
  _debug_print("parsed options: ", opts)

  _PVALUE_THRESHOLD = opts.pvalue_threshold or _PVALUE_THRESHOLD

  for file_name in opts.input_files:
    with open(file_name, 'r') as input_file:
      (grouped_numpy_iter, label_header, data_header) = from_file_group_by_labels(input_file)
      print_analysis(grouped_numpy_iter, label_header, data_header, opts.output_samples)

    with open(file_name, 'r') as input_file:
      (grouped_numpy_iter, label_header, data_header) = from_file_group_by_labels(input_file)
      without_baseline_iter = group_by_without_baseline_key(grouped_numpy_iter, label_header)
      #_debug_print_gen(without_baseline_iter)

      comparable_metrics_iter = iterate_comparable_metrics(without_baseline_iter, label_header)
      print_comparable_analysis(comparable_metrics_iter, label_header, data_header, opts.output_comparable, opts.output_comparable_significant)

  return 0


if __name__ == '__main__':
  sys.exit(main())
