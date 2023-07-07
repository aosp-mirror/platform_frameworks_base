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
import json
import os
import shutil
import subprocess
import sys
import zipfile

ANDROID_BUILD_TOP = os.environ.get("ANDROID_BUILD_TOP")
ANDROID_PRODUCT_OUT = os.environ.get("ANDROID_PRODUCT_OUT")
PRODUCT_OUT = ANDROID_PRODUCT_OUT.removeprefix(f"{ANDROID_BUILD_TOP}/")

SOONG_UI = "build/soong/soong_ui.bash"
PATH_PREFIX = "out/soong/.intermediates"
PATH_SUFFIX = "android_common/lint"
FIX_ZIP = "suggested-fixes.zip"

class SoongLintFix:
    """
    This class creates a command line tool that will
    apply lint fixes to the platform via the necessary
    combination of soong and shell commands.

    It breaks up these operations into a few "private" methods
    that are intentionally exposed so experimental code can tweak behavior.

    The entry point, `run`, will apply lint fixes using the
    intermediate `suggested-fixes` directory that soong creates during its
    invocation of lint.

    Basic usage:
    ```
    from soong_lint_fix import SoongLintFix

    SoongLintFix().run()
    ```
    """
    def __init__(self):
        self._parser = _setup_parser()
        self._args = None
        self._kwargs = None
        self._path = None
        self._target = None


    def run(self, additional_setup=None, custom_fix=None):
        """
        Run the script
        """
        self._setup()
        self._find_module()
        self._lint()

        if not self._args.no_fix:
            self._fix()

        if self._args.print:
            self._print()

    def _setup(self):
        self._args = self._parser.parse_args()
        env = os.environ.copy()
        if self._args.check:
            env["ANDROID_LINT_CHECK"] = self._args.check
        if self._args.lint_module:
            env["ANDROID_LINT_CHECK_EXTRA_MODULES"] = self._args.lint_module

        self._kwargs = {
            "env": env,
            "executable": "/bin/bash",
            "shell": True,
        }

        os.chdir(ANDROID_BUILD_TOP)


    def _find_module(self):
        print("Refreshing soong modules...")
        try:
            os.mkdir(ANDROID_PRODUCT_OUT)
        except OSError:
            pass
        subprocess.call(f"{SOONG_UI} --make-mode {PRODUCT_OUT}/module-info.json", **self._kwargs)
        print("done.")

        with open(f"{ANDROID_PRODUCT_OUT}/module-info.json") as f:
            module_info = json.load(f)

        if self._args.module not in module_info:
            sys.exit(f"Module {self._args.module} not found!")

        module_path = module_info[self._args.module]["path"][0]
        print(f"Found module {module_path}/{self._args.module}.")

        self._path = f"{PATH_PREFIX}/{module_path}/{self._args.module}/{PATH_SUFFIX}"
        self._target = f"{self._path}/lint-report.txt"


    def _lint(self):
        print("Cleaning up any old lint results...")
        try:
            os.remove(f"{self._target}")
            os.remove(f"{self._path}/{FIX_ZIP}")
        except FileNotFoundError:
            pass
        print("done.")

        print(f"Generating {self._target}")
        subprocess.call(f"{SOONG_UI} --make-mode {self._target}", **self._kwargs)
        print("done.")


    def _fix(self):
        print("Copying suggested fixes to the tree...")
        with zipfile.ZipFile(f"{self._path}/{FIX_ZIP}") as zip:
            for name in zip.namelist():
                if name.startswith("out") or not name.endswith(".java"):
                    continue
                with zip.open(name) as src, open(f"{ANDROID_BUILD_TOP}/{name}", "wb") as dst:
                    shutil.copyfileobj(src, dst)
            print("done.")


    def _print(self):
        print("### lint-report.txt ###", end="\n\n")
        with open(self._target, "r") as f:
            print(f.read())


def _setup_parser():
    parser = argparse.ArgumentParser(description="""
        This is a python script that applies lint fixes to the platform:
        1. Set up the environment, etc.
        2. Run lint on the specified target.
        3. Copy the modified files, from soong's intermediate directory, back into the tree.

        **Gotcha**: You must have run `source build/envsetup.sh` and `lunch` first.
        """, formatter_class=argparse.RawTextHelpFormatter)

    parser.add_argument('module',
                        help='The soong build module to run '
                             '(e.g. framework-minus-apex or services.core.unboosted)')

    parser.add_argument('--check',
                        help='Which lint to run. Passed to the ANDROID_LINT_CHECK environment variable.')

    parser.add_argument('--lint-module',
                            help='Specific lint module to run. Passed to the ANDROID_LINT_CHECK_EXTRA_MODULES environment variable.')

    parser.add_argument('--no-fix', action='store_true',
                        help='Just build and run the lint, do NOT apply the fixes.')

    parser.add_argument('--print', action='store_true',
                        help='Print the contents of the generated lint-report.txt at the end.')

    return parser

if __name__ == "__main__":
    SoongLintFix().run()