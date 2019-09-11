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

import argparse
import os
import sys
from typing import List

DIR = os.path.abspath(os.path.dirname(__file__))
sys.path.append(os.path.dirname(DIR))  # framework/base/startop/script
import lib.print_utils as print_utils
import iorap.lib.iorapd_utils as iorapd_utils
from app_startup.lib.app_runner import AppRunner

IORAP_COMMON_BASH_SCRIPT = os.path.join(DIR, 'common')

def parse_options(argv: List[str] = None):
  """Parses command line arguments and returns an argparse Namespace object."""
  parser = argparse.ArgumentParser(description="Compile perfetto trace file")
  required_named = parser.add_argument_group('required named arguments')

  required_named.add_argument('-i', dest='inodes', metavar='FILE',
                              help='Read cached inode data from a file saved '
                                   'earlier with pagecache.py -d')
  required_named.add_argument('-p', dest='package',
                              help='Package of the app to be compiled')

  optional_named = parser.add_argument_group('optional named arguments')
  optional_named.add_argument('-o', dest='output',
                              help='The compiled trace is stored into the output file')
  optional_named.add_argument('-a', dest='activity',
                              help='Activity of the app to be compiled')
  optional_named.add_argument('-d', dest='debug', action='store_true'
                              , help='Activity of the app to be compiled')

  return parser.parse_args(argv)

def main(inodes, package, activity, output, **kwargs) -> int:
  """Entries of the program."""
  if not activity:
    activity = AppRunner.get_activity(package)

  passed = iorapd_utils.compile_perfetto_trace_on_device(package, activity,
                                                         inodes)
  if passed and output:
    iorapd_utils.get_iorapd_compiler_trace(package, activity, output)

  return 0

if __name__ == '__main__':
  opts = parse_options()
  if opts.debug:
    print_utils.DEBUG = opts.debug
  print_utils.debug_print(opts)
  sys.exit(main(**(vars(opts))))
