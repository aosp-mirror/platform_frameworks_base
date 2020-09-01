#!/usr/bin/env python3
#
# Copyright 2020, The Android Open Source Project
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

import argparse
import os
import sys
from typing import Dict, List, NamedTuple, Tuple

DIR = os.path.abspath(os.path.dirname(__file__))
sys.path.append(os.path.dirname(DIR))  # framework/base/startop/script
import lib.print_utils as print_utils

# Include generated protos.
dir_name = os.path.dirname(os.path.realpath(__file__))
sys.path.append(dir_name + "/generated")

from TraceFile_pb2 import *

def parse_options(argv: List[str] = None):
  """Parses command line arguments and returns an argparse Namespace object."""
  parser = argparse.ArgumentParser(description="Analyze compiled_trace iorap protos.")
  required_named = parser.add_argument_group('required named arguments')

  required_named.add_argument('-i', dest='input', metavar='FILE',
                              help='Read protobuf file as input')

  optional_named = parser.add_argument_group('optional named arguments')

  optional_named.add_argument('-up', dest='upper_percent', type=float,
                              default=95.0,
                              help='Only show the top-most entries up to this value.')

  optional_named.add_argument('-r', dest='raw', action='store_true',
                              help='Output entire raw file.')
  optional_named.add_argument('-o', dest='output',
                              help='The results are stored into the output file')
  optional_named.add_argument('-d', dest='debug', action='store_true'
                              , help='Activity of the app to be compiled')

  return parser.parse_args(argv)

def open_iorap_prefetch_file(file_path: str) -> TraceFile:
  with open(file_path, "rb") as f:
    tf = TraceFile()
    tf.ParseFromString(f.read())
    return tf

def print_stats_summary(trace_file: TraceFile, upper_percent):
  tf_dict = convert_to_dict(trace_file)
  print_utils.debug_print(tf_dict)

  total_length = 0
  summaries = []
  for name, entries_list in tf_dict.items():
    summary = entries_sum(entries_list)
    summaries.append(summary)

    total_length += summary.length

  # Sort by length
  summaries.sort(reverse=True, key=lambda s: s.length)

  percent_sum = 0.0
  skipped_entries = 0

  print("===========================================")
  print("Total length: {:,} bytes".format(total_length))
  print("Displayed upper percent: {:0.2f}%".format(upper_percent))
  print("===========================================")
  print("")
  print("name,length,percent_of_total,upper_percent")
  for sum in summaries:
    percent_of_total = (sum.length * 1.0) / (total_length * 1.0) * 100.0

    percent_sum += percent_of_total

    if percent_sum > upper_percent:
      skipped_entries = skipped_entries + 1
      continue

    #print("%s,%d,%.2f%%" %(sum.name, sum.length, percent_of_total))
    print("{:s},{:d},{:0.2f}%,{:0.2f}%".format(sum.name, sum.length, percent_of_total, percent_sum))

  if skipped_entries > 0:
    print("[WARNING] Skipped {:d} entries, use -up=100 to show everything".format(skipped_entries))

  pass

class FileEntry(NamedTuple):
  id: int
  name: str
  offset: int
  length: int

class FileEntrySummary(NamedTuple):
  name: str
  length: int

def entries_sum(entries: List[FileEntry]) -> FileEntrySummary:
  if not entries:
    return None

  summary = FileEntrySummary(name=entries[0].name, length=0)
  for entry in entries:
    summary = FileEntrySummary(summary.name, summary.length + entry.length)

  return summary

def convert_to_dict(trace_file: TraceFile) -> Dict[str, FileEntry]:
  trace_file_index = trace_file.index

  # entries.id -> entry.file_name
  entries_map = {}

  index_entries = trace_file_index.entries
  for entry in index_entries:
    entries_map[entry.id] = entry.file_name

  final_map = {}

  file_entries_map = {}
  file_entries = trace_file.list.entries
  for entry in file_entries:
    print_utils.debug_print(entry)

    lst = file_entries_map.get(entry.index_id, [])
    file_entries_map[entry.index_id] = lst

    file_name = entries_map[entry.index_id]
    file_entry = \
        FileEntry(id=entry.index_id, name=file_name, offset=entry.file_offset, length=entry.file_length)

    lst.append(file_entry)

    final_map[file_name] = lst

  return final_map

def main(argv: List[str]) -> int:
  opts = parse_options(argv[1:])
  if opts.debug:
    print_utils.DEBUG = opts.debug
  print_utils.debug_print(opts)

  prefetch_file = open_iorap_prefetch_file(opts.input)

  if opts.raw:
    print(prefetch_file)

  print_stats_summary(prefetch_file, opts.upper_percent)

  return 0

if __name__ == '__main__':
  sys.exit(main(sys.argv))
