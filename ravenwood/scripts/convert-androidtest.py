#!/usr/bin/python3
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

# This script converts a legacy test class (using AndroidTestCase, TestCase or
# InstrumentationTestCase to a modern style test class, in a best-effort manner.
#
# Usage:
#  convert-androidtest.py TARGET-FILE [TARGET-FILE ...]
#
# Caveats:
#   - It adds all the extra imports, even if they're not needed.
#   - It won't sort imports.
#   - It also always adds getContext() and getTestContext().
#

import sys
import fileinput
import re
import subprocess

# Print message on console
def log(msg):
    print(msg, file=sys.stderr)


# Matches `extends AndroidTestCase` (or another similar base class)
re_extends = re.compile(
    r''' \b extends \s+ (AndroidTestCase|TestCase|InstrumentationTestCase) \s* ''',
    re.S + re.X)


# Look into given files and return the files that have `re_extends`.
def find_target_files(files):
    ret = []

    for file in files:
        try:
            with open(file, 'r') as f:
                data = f.read()

                if re_extends.search(data):
                    ret.append(file)

        except FileNotFoundError as e:
            log(f'Failed to open file {file}: {e}')

    return ret


def main(args):
    files = args

    # Find the files that should be processed.
    files = find_target_files(files)

    if len(files) == 0:
        log("No target files found.")
        return 0

    # Process the files.
    with fileinput.input(files=(files), inplace = True, backup = '.bak') as f:
        import_seen = False
        carry_over = ''
        class_body_started = False
        class_seen = False

        def on_file_start():
            nonlocal import_seen, carry_over, class_body_started, class_seen
            import_seen = False
            carry_over = ''
            class_body_started = False
            class_seen = False

        for line in f:
            if (fileinput.filelineno() == 1):
                log(f"Processing: {fileinput.filename()}")
                on_file_start()

            line = line.rstrip('\n')

            # Carry over a certain line to the next line.
            if re.search(r'''@Override\b''', line):
                carry_over = carry_over + line + '\n'
                continue

            if carry_over:
                line = carry_over + line
                carry_over = ''


            # Remove the base class from the class definition.
            line = re_extends.sub('', line)

            # Add a @RunWith.
            if not class_seen and re.search(r'''\b class \b''', line, re.X):
                class_seen = True
                print("@RunWith(AndroidJUnit4.class)")


            # Inject extra imports.
            if not import_seen and re.search(r'''^import\b''', line):
                import_seen = True
                print("""\
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertSame;
import static junit.framework.TestCase.assertNotSame;
import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.Test;

import androidx.test.ext.junit.runners.AndroidJUnit4;
""")

            # Add @Test to the test methods.
            if re.search(r'''^ \s* public \s* void \s* test''', line, re.X):
                print("    @Test")

            # Convert setUp/tearDown to @Before/@After.
            if re.search(r''' ^\s+ ( \@Override \s+ ) ? (public|protected) \s+ void \s+ (setUp|tearDown) ''',
                        line, re.X):
                if re.search('setUp', line):
                    print('    @Before')
                else:
                    print('    @After')

                line = re.sub(r''' \s* \@Override \s* \n ''', '', line, 0, re.X)
                line = re.sub(r'''protected''', 'public', line, 0, re.X)

            # Remove the super setUp / tearDown call.
            if re.search(r''' \b super \. (setUp|tearDown) \b ''', line, re.X):
                continue

            # Convert mContext to getContext().
            line = re.sub(r'''\b mContext \b ''', 'getContext()', line, 0, re.X)

            # Print the processed line.
            print(line)

            # Add getContext() / getTestContext() at the beginning of the class.
            if not class_body_started and re.search(r'''\{''', line):
                class_body_started = True
                print("""\
    private Context getContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private Context getTestContext() {
        return InstrumentationRegistry.getInstrumentation().getContext();
    }
""")


    # Run diff
    for file in files:
        subprocess.call(["diff", "-u", "--color=auto", f"{file}.bak", file])

    log(f'{len(files)} file(s) converted.')

    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
