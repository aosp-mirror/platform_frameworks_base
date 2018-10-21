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
Merge mutliple CSV files, possibly with different columns, writing to stdout.
"""

import csv
import sys

csv_readers = [
    csv.DictReader(open(csv_file, 'rb'), delimiter=',', quotechar='|')
    for csv_file in sys.argv[1:]
]

# Build union of all columns from source files:
headers = set()
for reader in csv_readers:
  headers = headers.union(reader.fieldnames)

# Concatenate all files to output:
out = csv.DictWriter(sys.stdout, delimiter=',', quotechar='|', fieldnames = sorted(headers))
out.writeheader()
for reader in csv_readers:
  for row in reader:
    out.writerow(row)


