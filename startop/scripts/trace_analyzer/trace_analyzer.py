#!/usr/bin/python3
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

import re
import sys
import argparse

from lib.trace2db import Trace2Db

# This script requires 'sqlalchemy' to access the sqlite3 database.
#
# $> sudo apt-get install python3-pip
# $> pip3 install --user sqlalchemy
#

def main(argv):
  parser = argparse.ArgumentParser(description='Convert ftrace/systrace file into sqlite3 db.')
  parser.add_argument('db_filename', metavar='sql_filename.db', type=str,
                      help='path to sqlite3 db filename')
  parser.add_argument('trace_filename', metavar='systrace.ftrace', type=str,
                      help='path to ftrace/systrace filename')
  parser.add_argument('--limit', type=int, help='limit the number of entries parsed [for debugging]')

  args = parser.parse_args()

  db_filename = args.db_filename
  trace_filename = args.trace_filename

  trace2db = Trace2Db(db_filename)
  print("SQL Alchemy db initialized")

  # parse 'raw_ftrace_entries' table
  count = trace2db.parse_file_into_db(trace_filename, limit=args.limit)
  print("Count was ", count)

  return 0

if __name__ == '__main__':
  main(sys.argv)
