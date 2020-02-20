#!/usr/bin/env python
#
# Copyright (C) 2018 The Android Open Source Project
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
"""
Merge multiple CSV files, possibly with different columns.
"""

import argparse
import csv
import io

from zipfile import ZipFile

args_parser = argparse.ArgumentParser(description='Merge given CSV files into a single one.')
args_parser.add_argument('--header', help='Comma separated field names; '
                                          'if missing determines the header from input files.')
args_parser.add_argument('--zip_input', help='ZIP archive with all CSV files to merge.')
args_parser.add_argument('--output', help='Output file for merged CSV.',
                         default='-', type=argparse.FileType('w'))
args_parser.add_argument('files', nargs=argparse.REMAINDER)
args = args_parser.parse_args()


def dict_reader(input):
    return csv.DictReader(input, delimiter=',', quotechar='|')


if args.zip_input and len(args.files) > 0:
    raise ValueError('Expecting either a single ZIP with CSV files'
                     ' or a list of CSV files as input; not both.')

csv_readers = []
if len(args.files) > 0:
    for file in args.files:
        csv_readers.append(dict_reader(open(file, 'r')))
elif args.zip_input:
    with ZipFile(args.zip_input) as zip:
        for entry in zip.namelist():
            if entry.endswith('.uau'):
                csv_readers.append(dict_reader(io.TextIOWrapper(zip.open(entry, 'r'))))

headers = set()
if args.header:
    fieldnames = args.header.split(',')
else:
    # Build union of all columns from source files:
    for reader in csv_readers:
        headers = headers.union(reader.fieldnames)
    fieldnames = sorted(headers)

# Concatenate all files to output:
writer = csv.DictWriter(args.output, delimiter=',', quotechar='|', quoting=csv.QUOTE_MINIMAL,
                        dialect='unix', fieldnames=fieldnames)
writer.writeheader()
for reader in csv_readers:
    for row in reader:
        writer.writerow(row)
