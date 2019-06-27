#!/usr/bin/env python3

#
# Copyright (C) 2019 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

#
# Dependencies:
#
# $> sudo apt-get install python3-pip
# $> pip3 install --user protobuf sqlalchemy sqlite3
#

import collections
import optparse
import os
import re
import sys

from typing import Iterable

from lib.inode2filename import Inode2Filename
from generated.TraceFile_pb2 import *

parent_dir_name = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
sys.path.append(parent_dir_name + "/trace_analyzer")
from lib.trace2db import Trace2Db, MmFilemapAddToPageCache

_PAGE_SIZE = 4096 # adb shell getconf PAGESIZE ## size of a memory page in bytes.

class PageRun:
  """
  Intermediate representation for a run of one or more pages.
  """
  def __init__(self, device_number: int, inode: int, offset: int, length: int):
    self.device_number = device_number
    self.inode = inode
    self.offset = offset
    self.length = length

  def __str__(self):
    return "PageRun(device_number=%d, inode=%d, offset=%d, length=%d)" \
        %(self.device_number, self.inode, self.offset, self.length)

def debug_print(msg):
  #print(msg)
  pass

UNDER_LAUNCH = False

def page_cache_entries_to_runs(page_cache_entries: Iterable[MmFilemapAddToPageCache]):
  global _PAGE_SIZE

  runs = [
      PageRun(device_number=pg_entry.dev, inode=pg_entry.ino, offset=pg_entry.ofs,
              length=_PAGE_SIZE)
        for pg_entry in page_cache_entries
  ]

  for r in runs:
    debug_print(r)

  print("Stats: Page runs totaling byte length: %d" %(len(runs) * _PAGE_SIZE))

  return runs

def optimize_page_runs(page_runs):
  new_entries = []
  last_entry = None
  for pg_entry in page_runs:
    if last_entry:
      if pg_entry.device_number == last_entry.device_number and pg_entry.inode == last_entry.inode:
        # we are dealing with a run for the same exact file as a previous run.
        if pg_entry.offset == last_entry.offset + last_entry.length:
          # trivially contiguous entries. merge them together.
          last_entry.length += pg_entry.length
          continue
    # Default: Add the run without merging it to a previous run.
    last_entry = pg_entry
    new_entries.append(pg_entry)
  return new_entries

def is_filename_matching_filter(file_name, filters=[]):
  """
  Blacklist-style regular expression filters.

  :return: True iff file_name has an RE match in one of the filters.
  """
  for filt in filters:
    res = re.search(filt, file_name)
    if res:
      return True

  return False

def build_protobuf(page_runs, inode2filename, filters=[]):
  trace_file = TraceFile()
  trace_file_index = trace_file.index

  file_id_counter = 0
  file_id_map = {} # filename -> id

  stats_length_total = 0
  filename_stats = {} # filename -> total size

  skipped_inode_map = {}
  filtered_entry_map = {} # filename -> count

  for pg_entry in page_runs:
    fn = inode2filename.resolve(pg_entry.device_number, pg_entry.inode)
    if not fn:
      skipped_inode_map[pg_entry.inode] = skipped_inode_map.get(pg_entry.inode, 0) + 1
      continue

    filename = fn

    if filters and not is_filename_matching_filter(filename, filters):
      filtered_entry_map[filename] = filtered_entry_map.get(filename, 0) + 1
      continue

    file_id = file_id_map.get(filename)
    if not file_id:
      file_id = file_id_counter
      file_id_map[filename] = file_id_counter
      file_id_counter = file_id_counter + 1

      file_index_entry = trace_file_index.entries.add()
      file_index_entry.id = file_id
      file_index_entry.file_name = filename

    # already in the file index, add the file entry.
    file_entry = trace_file.list.entries.add()
    file_entry.index_id = file_id
    file_entry.file_length = pg_entry.length
    stats_length_total += file_entry.file_length
    file_entry.file_offset = pg_entry.offset

    filename_stats[filename] = filename_stats.get(filename, 0) + file_entry.file_length

  for inode, count in skipped_inode_map.items():
    print("WARNING: Skip inode %s because it's not in inode map (%d entries)" %(inode, count))

  print("Stats: Sum of lengths %d" %(stats_length_total))

  if filters:
    print("Filter: %d total files removed." %(len(filtered_entry_map)))

    for fn, count in filtered_entry_map.items():
      print("Filter: File '%s' removed '%d' entries." %(fn, count))

  for filename, file_size in filename_stats.items():
    print("%s,%s" %(filename, file_size))

  return trace_file

def query_add_to_page_cache(trace2db: Trace2Db):
  # SELECT * FROM tbl ORDER BY id;
  return trace2db.session.query(MmFilemapAddToPageCache).order_by(MmFilemapAddToPageCache.id).all()

def main(argv):
  parser = optparse.OptionParser(usage="Usage: %prog [options]", description="Compile systrace file into TraceFile.pb")
  parser.add_option('-i', dest='inode_data_file', metavar='FILE',
                    help='Read cached inode data from a file saved earlier with pagecache.py -d')
  parser.add_option('-t', dest='trace_file', metavar='FILE',
                    help='Path to systrace file (trace.html) that will be parsed')

  parser.add_option('--db', dest='sql_db', metavar='FILE',
                    help='Path to intermediate sqlite3 database [default: in-memory].')

  parser.add_option('-f', dest='filter', action="append", default=[],
                    help="Add file filter. All file entries not matching one of the filters are discarded.")

  parser.add_option('-l', dest='launch_lock', action="store_true", default=False,
                    help="Exclude all events not inside launch_lock")

  parser.add_option('-o', dest='output_file', metavar='FILE',
                    help='Output protobuf file')

  options, categories = parser.parse_args(argv[1:])

  # TODO: OptionParser should have some flags to make these mandatory.
  if not options.inode_data_file:
    parser.error("-i is required")
  if not options.trace_file:
    parser.error("-t is required")
  if not options.output_file:
    parser.error("-o is required")

  if options.launch_lock:
    print("INFO: Launch lock flag (-l) enabled; filtering all events not inside launch_lock.")


  inode_table = Inode2Filename.new_from_filename(options.inode_data_file)

  trace_file = open(options.trace_file)

  sql_db_path = ":memory:"
  if options.sql_db:
    sql_db_path = options.sql_db

  trace2db = Trace2Db(sql_db_path)
  # Speed optimization: Skip any entries that aren't mm_filemap_add_to_pagecache.
  trace2db.set_raw_ftrace_entry_filter(\
      lambda entry: entry['function'] == 'mm_filemap_add_to_page_cache')
  # TODO: parse multiple trace files here.
  parse_count = trace2db.parse_file_into_db(options.trace_file)

  mm_filemap_add_to_page_cache_rows = query_add_to_page_cache(trace2db)
  print("DONE. Parsed %d entries into sql db." %(len(mm_filemap_add_to_page_cache_rows)))

  page_runs = page_cache_entries_to_runs(mm_filemap_add_to_page_cache_rows)
  print("DONE. Converted %d entries" %(len(page_runs)))

  # TODO: flags to select optimizations.
  optimized_page_runs = optimize_page_runs(page_runs)
  print("DONE. Optimized down to %d entries" %(len(optimized_page_runs)))

  print("Build protobuf...")
  trace_file = build_protobuf(optimized_page_runs, inode_table, options.filter)

  print("Write protobuf to file...")
  output_file = open(options.output_file, 'wb')
  output_file.write(trace_file.SerializeToString())
  output_file.close()

  print("DONE")

  # TODO: Silent running mode [no output except on error] for build runs.

  return 0

sys.exit(main(sys.argv))
