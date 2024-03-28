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
Tool switch the deprecated jetpack test runner to the correct one.

Typical usage:
$ RAVENWOOD_OPTIONAL_VALIDATION=1 atest MyTestsRavenwood # Prepend RAVENWOOD_RUN_DISABLED_TESTS=1 as needed
$ cd /path/to/tests/root
$ python bulk_enable.py /path/to/atest/output/host_log.txt
"""

import collections
import os
import re
import subprocess
import sys

re_result = re.compile("I/ModuleListener.+?null-device-0 (.+?)#(.+?) ([A-Z_]+)(.*)$")

OLD_RUNNER = "androidx.test.runner.AndroidJUnit4"
NEW_RUNNER = "androidx.test.ext.junit.runners.AndroidJUnit4"
SED_ARG = r"s/%s/%s/g" % (OLD_RUNNER, NEW_RUNNER)

target = collections.defaultdict()

with open(sys.argv[1]) as f:
    for line in f.readlines():
        result = re_result.search(line)
        if result:
            clazz, method, state, msg = result.groups()
            if NEW_RUNNER in msg:
                target[clazz] = 1

if len(target) == 0:
    print("No tests need updating.")
    sys.exit(0)

num_fixed = 0
for clazz in target.keys():
    print("Fixing test runner", clazz)
    clazz_match = re.compile("%s\.(kt|java)" % (clazz.split(".")[-1]))
    found = False
    for root, dirs, files in os.walk("."):
        for f in files:
            if clazz_match.match(f):
                found = True
                num_fixed += 1
                path = os.path.join(root, f)
                subprocess.run(["sed", "-i", "-E", SED_ARG, path])
    if not found:
        print(f"  Warining: tests {clazz} not found")


print("Tests fixed", num_fixed)
