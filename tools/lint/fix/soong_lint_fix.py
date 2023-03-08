#  Copyright (C) 2022 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

import argparse
import os
import subprocess
import sys

ANDROID_BUILD_TOP = os.environ.get("ANDROID_BUILD_TOP")
PATH_PREFIX = "out/soong/.intermediates"
PATH_SUFFIX = "android_common/lint"
FIX_DIR = "suggested-fixes"

class SoongLintFix:
    """
    This class creates a command line tool that will
    apply lint fixes to the platform via the necessary
    combination of soong and shell commands.

    It provides some basic hooks for experimental code
    to tweak the generation of the resulting shell script.

    By default, it will apply lint fixes using the intermediate `suggested-fixes`
    directory that soong creates during its invocation of lint.

    The default argument parser configures a number of command line arguments to
    facilitate running lint via soong.

    Basic usage:
    ```
    from soong_lint_fix import SoongLintFix

    SoongLintFix().run()
    ```
    """
    def __init__(self):
        self._commands = None
        self._args = None
        self._path = None
        self._target = None
        self._parser = _setup_parser()


    def add_argument(self, *args, **kwargs):
        """
        If necessary, add arguments to the underlying argparse.ArgumentParser before running
        """
        self._parser.add_argument(*args, **kwargs)


    def run(self, add_setup_commands=None, override_fix_commands=None):
        """
        Run the script
        :param add_setup_commands: OPTIONAL function to add additional setup commands
            passed the command line arguments, path, and build target
            must return a list of strings (the additional commands)
        :param override_fix_commands: OPTIONAL function to override the fix commands
            passed the command line arguments, path, and build target
            must return a list of strings (the fix commands)
        """
        self._setup()
        if add_setup_commands:
            self._commands += add_setup_commands(self._args, self._path, self._target)

        self._add_lint_report_commands()

        if not self._args.no_fix:
            if override_fix_commands:
                self._commands += override_fix_commands(self._args, self._path, self._target)
            else:
                self._commands += [
                    f"cd {self._path}",
                    f"unzip {FIX_DIR}.zip -d {FIX_DIR}",
                    f"cd {FIX_DIR}",
                    # Find all the java files in the fix directory, excluding the ./out subdirectory,
                    # and copy them back into the same path within the tree.
                    f"find . -path ./out -prune -o -name '*.java' -print | xargs -n 1 sh -c 'cp $1 $ANDROID_BUILD_TOP/$1 || exit 255' --",
                    f"rm -rf {FIX_DIR}"
                ]


        if self._args.dry_run:
            print(self._get_commands_str())
        else:
            self._execute()


    def _setup(self):
        self._args = self._parser.parse_args()
        self._commands = []
        self._path = f"{PATH_PREFIX}/{self._args.build_path}/{PATH_SUFFIX}"
        self._target = f"{self._path}/lint-report.html"

        if not self._args.dry_run:
            self._commands += [f"export ANDROID_BUILD_TOP={ANDROID_BUILD_TOP}"]

        if self._args.check:
            self._commands += [f"export ANDROID_LINT_CHECK={self._args.check}"]


    def _add_lint_report_commands(self):
        self._commands += [
            "cd $ANDROID_BUILD_TOP",
            "source build/envsetup.sh",
            # remove the file first so soong doesn't think there is no work to do
            f"rm {self._target}",
            # remove in case there are fixes from a prior run,
            # that we don't want applied if this run fails
            f"rm {self._path}/{FIX_DIR}.zip",
            f"m {self._target}",
        ]


    def _get_commands_str(self):
        prefix = "(\n"
        delimiter = ";\n"
        suffix = "\n)"
        return f"{prefix}{delimiter.join(self._commands)}{suffix}"


    def _execute(self, with_echo=True):
        if with_echo:
            exec_commands = []
            for c in self._commands:
                exec_commands.append(f'echo "{c}"')
                exec_commands.append(c)
            self._commands = exec_commands

        subprocess.call(self._get_commands_str(), executable='/bin/bash', shell=True)


def _setup_parser():
    parser = argparse.ArgumentParser(description="""
        This is a python script that applies lint fixes to the platform:
        1. Set up the environment, etc.
        2. Run lint on the specified target.
        3. Copy the modified files, from soong's intermediate directory, back into the tree.

        **Gotcha**: You must have run `source build/envsetup.sh` and `lunch`
        so that the `ANDROID_BUILD_TOP` environment variable has been set.
        Alternatively, set it manually in your shell.
        """, formatter_class=argparse.RawTextHelpFormatter)

    parser.add_argument('build_path', metavar='build_path', type=str,
                        help='The build module to run '
                             '(e.g. frameworks/base/framework-minus-apex or '
                             'frameworks/base/services/core/services.core.unboosted)')

    parser.add_argument('--check', metavar='check', type=str,
                        help='Which lint to run. Passed to the ANDROID_LINT_CHECK environment variable.')

    parser.add_argument('--dry-run', dest='dry_run', action='store_true',
                        help='Just print the resulting shell script instead of running it.')

    parser.add_argument('--no-fix', dest='no_fix', action='store_true',
                        help='Just build and run the lint, do NOT apply the fixes.')

    return parser
