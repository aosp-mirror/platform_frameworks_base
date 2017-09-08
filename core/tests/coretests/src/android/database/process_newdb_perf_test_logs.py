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
    # If WAL was enabled for the test
    wal_enabled = False
    # Number of bytes which test process caused to be sent to the storage layer.
    # Reported as max value across all runs.
    max_write_bytes = 0
    for line in all_lines:
        if "NewDatabasePerformanceTests: Testing with WAL enabled" in line:
            wal_enabled = True
            continue

        regex = r"NewDatabasePerformanceTests: Test (\w+) took (\d+) ms"
        matches = re.search(regex, line)
        if matches:
            test_name = matches.group(1)
            duration = int(matches.group(2))
            if not test_name in timings:
                timings[test_name] = []
            timings[test_name].append(duration)
            running_sum += duration
            continue

        if ("TestRunner: run finished:" in line) and (running_sum > 0):
            test_name = ('*** TOTAL ALL TESTS [WAL] (ms) ***' if wal_enabled
                         else '*** TOTAL ALL TESTS (ms) ***')
            if not test_name in timings:
                timings[test_name] = []
            timings[test_name].append(running_sum)
            running_sum=0
            continue

        # Determine max from all reported totalWriteBytes
        regex = r"Test .* totalWriteBytes=(\d+)"
        matches = re.search(regex, line)
        if matches:
            max_write_bytes = max(max_write_bytes, int(matches.group(1)))
            continue

    for k in sorted(timings):
        timings_ar = timings[k]
        print "%s: %s avg: %s" % (k, timings_ar, sum(timings_ar) / float(len(timings_ar)) )


    print "\nAdditional stats: "
    print "    max write_bytes: %d" % max_write_bytes

if __name__ == '__main__':
    main()
