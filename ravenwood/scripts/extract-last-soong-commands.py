#!/usr/bin/env python3
# Copyright (C) 2024 The Android Open Source Project
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


# This script extracts all the commands executed in the last soong run,
# and write them into a script file, and print the filename.
#
# All the commands are commented out. Uncomment what you want to execute as
# needed before running it.

import datetime
import gzip
import os
import re
import shlex
import sys

re_command = re.compile(r''' ^\[.*?\]  \s*  (.*) ''', re.X)

HEADER = r'''#!/bin/bash

set -e # Stop on a failed command
set -x # Print command line before executing
cd "${ANDROID_BUILD_TOP:?}"

'''

OUT_SCRIPT_DIR = "/tmp/"
OUT_SCRIPT_FORMAT = "soong-rerun-%Y-%m-%d_%H-%M-%S.sh"

def main(args):
    log = os.environ["ANDROID_BUILD_TOP"] + "/out/verbose.log.gz"
    outdir = "/tmp/"
    outfile = outdir + datetime.datetime.now().strftime(OUT_SCRIPT_FORMAT)

    with open(outfile, "w") as out:
        out.write(HEADER)

        count = 0
        with gzip.open(log) as f:
            for line in f:
                s = line.decode("utf-8")

                if s.startswith("verbose"):
                    continue
                if re.match('^\[.*bootstrap blueprint', s):
                    continue

                s = s.rstrip()

                m = re_command.search(s)
                if m:
                    command = m.groups()[0]

                    count += 1
                    out.write(f'### Command {count} ========\n\n')
                    out.write('#' + command + '\n\n')

                    continue

                if s.startswith("FAILED:"):
                    break

    os.chmod(outfile, 0o755)
    print(outfile)

    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
