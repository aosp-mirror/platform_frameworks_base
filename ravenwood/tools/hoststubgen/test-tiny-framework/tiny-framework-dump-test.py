#!/usr/bin/python3
# Copyright (C) 2023 The Android Open Source Project
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

# Compare the tiny-framework JAR dumps to the golden files.

import sys
import os
import unittest
import subprocess

GOLDEN_DIRS = [
    'golden-output',
    'golden-output.RELEASE_TARGET_JAVA_21',
]


# Run diff.
def run_diff(file1, file2):
    command = ['diff', '-u',
               '--ignore-blank-lines',
               '--ignore-space-change',

               # Ignore the class file version.
               '--ignore-matching-lines=^ *\(major\|minor\) version:$',

               # We shouldn't need `--ignore-matching-lines`, but somehow
               # the golden files were generated without these lines for b/388562869,
               # so let's just ignore them.
               '--ignore-matching-lines=^\(Constant.pool:\|{\)$',
               file1, file2]
    print(' '.join(command))
    result = subprocess.run(command, stderr=sys.stdout)

    success = result.returncode == 0

    if success:
        print('No diff found.')
    else:
        print(f'Fail: {file1} and {file2} are different.')

    return success


# Check one golden file.
def check_one_file(golden_dir, filename):
    print(f'= Checking file: {filename}')
    return run_diff(os.path.join(golden_dir, filename), filename)


class TestWithGoldenOutput(unittest.TestCase):

    # Test to check the generated jar files to the golden output.
    # Depending on build flags, the golden output may differ in expected ways.
    # So only expect the files to match one of the possible golden outputs.
    def test_compare_to_golden(self):
        success = False

        for golden_dir in GOLDEN_DIRS:
            if self.matches_golden(golden_dir):
                success = True
                print(f"Test passes for dir: {golden_dir}")
                break

        if not success:
            self.fail('Some files are different. ' +
                      'See stdout log for more details.')

    def matches_golden(self, golden_dir):
        files = os.listdir(golden_dir)
        files.sort()

        print(f"Golden files for {golden_dir}: {files}")
        match_success = True

        for file in files:
            if not check_one_file(golden_dir, file):
                match_success = False

        return match_success


if __name__ == "__main__":
    args = sys.argv

    # This script is used by diff-and-update-golden.sh too.
    if len(args) > 1 and args[1] == "run-diff":
        if run_diff(args[2], args[3]):
            sys.exit(0)
        else:
            sys.exit(1)

    unittest.main(verbosity=2)
