#!/usr/bin/env python3
#
# Copyright (C) 2024 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Tool to bulk-enable tests that are now passing on Ravenwood.

Currently only offers to include classes which are fully passing; ignores
classes that have partial success.

Typical usage:
$ ENABLE_PROBE_IGNORED=1 atest MyTestsRavenwood
$ cd /path/to/tests/root
$ python bulk_enable.py /path/to/atest/output/host_log.txt
"""

import collections
import os
import re
import subprocess
import sys

re_result = re.compile("I/ModuleListener.+?null-device-0 (.+?)#(.+?) ([A-Z_]+)(.*)$")

ANNOTATION = "@android.platform.test.annotations.EnabledOnRavenwood"
SED_ARG = "s/^((public )?class )/%s\\n\\1/g" % (ANNOTATION)

STATE_PASSED = "PASSED"
STATE_FAILURE = "FAILURE"
STATE_ASSUMPTION_FAILURE = "ASSUMPTION_FAILURE"
STATE_CANDIDATE = "CANDIDATE"

stats_total = collections.defaultdict(int)
stats_class = collections.defaultdict(lambda: collections.defaultdict(int))
stats_method = collections.defaultdict()

with open(sys.argv[1]) as f:
    for line in f.readlines():
        result = re_result.search(line)
        if result:
            clazz, method, state, msg = result.groups()
            if state == STATE_FAILURE and "actually passed under Ravenwood" in msg:
                state = STATE_CANDIDATE
            stats_total[state] += 1
            stats_class[clazz][state] += 1
            stats_method[(clazz, method)] = state

# Find classes who are fully "candidates" (would be entirely green if enabled)
num_enabled = 0
for clazz in stats_class.keys():
    stats = stats_class[clazz]
    if STATE_CANDIDATE in stats and len(stats) == 1:
        num_enabled += stats[STATE_CANDIDATE]
        print("Enabling fully-passing class", clazz)
        clazz_match = re.compile("%s\.(kt|java)" % (clazz.split(".")[-1]))
        for root, dirs, files in os.walk("."):
            for f in files:
                if clazz_match.match(f):
                    path = os.path.join(root, f)
                    subprocess.run(["sed", "-i", "-E", SED_ARG, path])

print("Overall stats", stats_total)
print("Candidates actually enabled", num_enabled)
