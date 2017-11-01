#!/usr/bin/env python

# Copyright (C) 2017 The Android Open Source Project
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

def main():
    args = sys.argv
    if len(args) <= 1:
        exit("Usage %s <log_file>" % args[0])
    with open(args[1], 'r') as f:
        all_lines = f.readlines()
    timings = {}
    running_sum = 0
    for line in all_lines:
        regex = r"NewDatabasePerformanceTests: Test (\w+) took (\d+) ms"
        matches = re.search(regex, line)
        if matches:
            test_name = matches.group(1)
            duration = int(matches.group(2))
            if not test_name in timings:
                timings[test_name] = []
            timings[test_name].append(duration)
            running_sum += duration
        if ("TestRunner: run finished:" in line) and (running_sum > 0):
            test_name = '*** TOTAL ALL TESTS (ms) ***'
            if not test_name in timings:
                timings[test_name] = []
            timings[test_name].append(running_sum)
            running_sum=0

    for k in sorted(timings):
        timings_ar = timings[k]
        print "%s: %s avg: %s" % (k, timings_ar, sum(timings_ar) / float(len(timings_ar)) )

if __name__ == '__main__':
    main()
