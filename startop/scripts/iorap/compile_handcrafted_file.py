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
import asyncio
import csv
import itertools
import os
import re
import struct
import sys
import tempfile
import time
import zipfile
from typing import Any, Callable, Dict, Generic, Iterable, List, NamedTuple, TextIO, Tuple, TypeVar, Optional, Union

# Include generated protos.
dir_name = os.path.dirname(os.path.realpath(__file__))
sys.path.append(dir_name + "/generated")

from TraceFile_pb2 import *


def parse_options(argv: List[str] = None):
  """Parse command line arguments and return an argparse Namespace object."""
  parser = argparse.ArgumentParser(description="Compile a TraceFile.proto from a manual text file.")
  # argparse considers args starting with - and -- optional in --help, even though required=True.
  # by using a named argument group --help will clearly say that it's required instead of optional.
  required_named = parser.add_argument_group('required named arguments')

  # optional arguments
  # use a group here to get the required arguments to appear 'above' the optional arguments in help.
  optional_named = parser.add_argument_group('optional named arguments')
  optional_named.add_argument('-opb', '--output-proto-binary', dest='output_proto_binary', action='store', help='Write binary proto output to file.')
  optional_named.add_argument('-pm', '--pinlist-meta', dest='pinlist_meta', action='store', help='Path to pinlist.meta (default=none) binary file.')
  optional_named.add_argument('-pmp', '--pinlist-meta-parent', dest='pinlist_meta_parent', action='store', help='Device path that the pinlist.meta applies to (e.g. /data/.../somefile.apk)')
  optional_named.add_argument('-i', '--input', dest='input', action='store', help='Input text file (default stdin).')
  optional_named.add_argument('-zp', '--zip_path', dest='zip_path', action='append', help='Directory containing zip files.')
  optional_named.add_argument('-d', '--debug', dest='debug', action='store_true', help='Add extra debugging output')
  optional_named.add_argument('-ot', '--output-text', dest='output_text', action='store', help='Output text file (default stdout).')

  return parser.parse_args(argv)

# TODO: refactor this with a common library file with analyze_metrics.py
def _debug_print(*args, **kwargs):
  """Print the args to sys.stderr if the --debug/-d flag was passed in."""
  if _debug:
    print(*args, **kwargs, file=sys.stderr)

class BadInputError(Exception):
  pass

InputRecord = NamedTuple('InputRecord', [('filepath', str), ('offset', int), ('length', int), ('remark', str)])

def find_zip_in_paths(original_name, zip_paths):
  # /foo/bar/bax.zip -> bax.zip
  file_basename = os.path.split(original_name)[1]

  # the file must be located in one of the --zip-path arguments
  matched = None
  for zip_path in zip_paths:
    for dir_entry in os.listdir(zip_path):
      if dir_entry == file_basename:
        matched = os.path.join(zip_path, dir_entry)
        break
    if matched:
      break

  if not matched:
    raise ValueError("%s could not be found in any of the --zip_path specified." %(file_basename))

  _debug_print("found zip file ", file_basename, " in ", matched)

  if not zipfile.is_zipfile(matched):
    raise ValueError("%s is not a zip file" %(matched))

  return matched

def handle_zip_entry(input_record, zip_paths):

  res = re.match("([^!]+)[!](.*)", input_record.filepath)

  if not res:
    return input_record

                         # 'foo!bar'
  in_filepath = res[1]   # -> 'foo'
  in_zip_entry = res[2]  # -> 'bar'

  matched = find_zip_in_paths(in_filepath, zip_paths)

  zip = zipfile.ZipFile(matched)

  try:
    zip_info = zip.getinfo(in_zip_entry)
  except KeyError:
    raise ValueError("%s is not an item in the zip file %s" %(in_zip_entry, matched))

  # TODO: do we also need to add header size to this?
  in_offset = zip_info.header_offset

  # TODO: if a range is specified, use that instead.
  in_length = zip_info.compress_size

  return InputRecord(in_filepath, in_offset, in_length, 'zip entry (%s)' %(in_zip_entry))

def parse_input_file(input: Iterable[str], zip_paths: List[str]) -> Iterable[InputRecord]:
  for line in input:
    line = line.strip()

    _debug_print("Line = ", line)
    if not line:
      _debug_print("  skip empty line", line)
      continue
    elif line[0] == "#":
      _debug_print("  skip commented line", line)
      continue

    res = re.match("([^\s]+)\s+(\d+)\s+(\d+)", line)
    if not res:
      raise BadInputError("Expected input of form: <str:filepath> <int:offset> <int:length>")

    in_filepath = res[1]
    in_offset = int(res[2])
    in_length = int(res[3])

    yield handle_zip_entry(InputRecord(in_filepath, in_offset, in_length, 'regular file'), zip_paths)

# format:
#   (<big_endian(i32):file_offset> <big_endian(i32):range_length>)+
PIN_META_FORMAT = ">ii"
PIN_META_READ_SIZE = struct.calcsize(PIN_META_FORMAT)

def parse_pin_meta(pin_meta_file, pinlist_meta_parent, zip_paths):
  if not pin_meta_file:
    return ()

  global PIN_META_FORMAT
  global PIN_META_READ_SIZE

  # '/data/app/com.google.android.GoogleCamera-aNQhzSznf4h_bvJ_MRbweQ==/base.apk'
  #  -> 'com.google.android.GoogleCamera'
  package_name_match = re.match('/.*/(.*)-.*=/base.apk', pinlist_meta_parent)

  if not package_name_match:
    raise ValueError("%s did not contain the <packagename>.apk" %(pinlist_meta_parent))

  package_name = package_name_match[1]
  # "com.google.android.GoogleCamera" -> "GoogleCamera.apk"
  apk_name = package_name.split(".")[-1] + ".apk"

  path_to_zip_on_host = find_zip_in_paths(apk_name, zip_paths)
  apk_file_size = os.path.getsize(path_to_zip_on_host)
  _debug_print("APK path '%s' file size '%d'" %(path_to_zip_on_host, apk_file_size))

  while True:
    data = pin_meta_file.read(PIN_META_READ_SIZE)

    if not data:
      break

    (pin_offset, pin_length) = struct.unpack(PIN_META_FORMAT, data)  # (offset, length)

    remark = 'regular file (pinlist.meta)'

    remaining_size = apk_file_size - pin_offset
    if remaining_size < 0:
      print("WARNING: Clamp entry (%d, %d), offset too large (max file size = %d)" %(pin_offset, pin_length, apk_file_size))

      pin_length = pin_length + remaining_size
      pin_offset = pin_offset + remaining_size

      if pin_offset < 0:
        pin_offset = 0

      remark += '[clamped.offset]'

    pin_last_offset = pin_offset + pin_length
    remaining_size = apk_file_size - pin_last_offset

    if remaining_size < 0:
      print("WARNING: Clamp entry (%d, %d), length too large (max file size = %d)" %(pin_offset, pin_length, apk_file_size))
      pin_length = pin_length + remaining_size

      remark += '[clamped.length]'

    yield InputRecord(pinlist_meta_parent, pin_offset, pin_length, remark)

def write_text_file_output(input_records: Iterable[InputRecord], output_text_file):
  for rec in input_records:
    output_text_file.write("%s %d %d #%s\n" %(rec.filepath, rec.offset, rec.length, rec.remark))

def build_trace_file(input_records: Iterable[InputRecord]) -> TraceFile:
  trace_file = TraceFile()
  trace_file_index = trace_file.index

  file_id_counter = 0
  file_id_map = {} # filename -> id

  stats_length_total = 0
  filename_stats = {} # filename -> total size

  for rec in input_records:
    filename = rec.filepath

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
    file_entry.file_length = rec.length
    stats_length_total += file_entry.file_length
    file_entry.file_offset = rec.offset

    filename_stats[filename] = filename_stats.get(filename, 0) + file_entry.file_length

  return trace_file

def main():
  global _debug

  options= parse_options()
  _debug = options.debug
  _debug_print("parsed options: ", options)

  if not options.input:
    input_file = sys.stdin
    _debug_print("input = stdin")
  else:
    input_file = open(options.input)
    _debug_print("input = (file)", options.input)

  if not options.output_proto_binary:
    output_proto_file = None
  else:
    output_proto_file = open(options.output_proto_binary, 'wb')
  _debug_print("output_proto_binary = ", output_proto_file)

  pinlist_meta_parent = options.pinlist_meta_parent
  if options.pinlist_meta:
    pin_meta_file = open(options.pinlist_meta, 'rb')
  else:
    pin_meta_file = None

  if (pinlist_meta_parent == None) != (pin_meta_file == None):
    print("Options must be used together: --pinlist-meta and --pinlist-meta-path")
    return 1

  if not options.output_text:
    output_text_file = sys.stdout
    _debug_print("output = stdout")
  else:
    output_text_file = open(options.output_text, 'w')
    _debug_print("output = (file)", options.output_text)

  zip_paths = options.zip_path or []

  input_records = list(parse_pin_meta(pin_meta_file, pinlist_meta_parent, zip_paths))
  input_records = input_records + list(parse_input_file(input_file, zip_paths))

  for p in input_records:
    _debug_print(p)

  write_text_file_output(input_records, output_text_file)
  output_text_file.close()

  out_proto = build_trace_file(input_records)

  if output_proto_file:
    output_proto_file.write(out_proto.SerializeToString())
    output_proto_file.close()

  return 0

if __name__ == '__main__':
  sys.exit(main())
