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

GOLDEN_DIR = 'golden-output'

# Run diff.
def run_diff(file1, file2):
    command = ['diff', '-u', '--ignore-blank-lines', '--ignore-space-change', file1, file2]
    print(' '.join(command))
    result = subprocess.run(command, stderr = sys.stdout)

    success = result.returncode == 0

    if success:
        print(f'No diff found.')
    else:
        print(f'Fail: {file1} and {file2} are different.')

    return success


# Check one golden file.
def check_one_file(filename):
    print(f'= Checking file: {filename}')
    return run_diff(os.path.join(GOLDEN_DIR, filename), filename)

class TestWithGoldenOutput(unittest.TestCase):

    # Test to check the generated jar files to the golden output.
    @unittest.skip("Disabled until JDK 21 is merged and the golden files updated")
    def test_compare_to_golden(self):
        files = os.listdir(GOLDEN_DIR)
        files.sort()

        print(f"Golden files: {files}")
        success = True

        for file in files:
            if not check_one_file(file):
                success = False

        if not success:
            self.fail('Some files are different. See stdout log for more details.')

if __name__ == "__main__":
    unittest.main(verbosity=2)
