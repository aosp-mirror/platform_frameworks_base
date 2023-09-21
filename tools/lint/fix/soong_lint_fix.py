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


class SoongModule:
    """A Soong module to lint.

    The constructor takes the name of the module (for example,
    "framework-minus-apex"). find() must be called to extract the intermediate
    module path from Soong's module-info.json
    """
    def __init__(self, name):
        self._name = name

    def find(self, module_info):
        """Finds the module in the loaded module_info.json."""
        if self._name not in module_info:
            raise Exception(f"Module {self._name} not found!")

        partial_path = module_info[self._name]["path"][0]
        print(f"Found module {partial_path}/{self._name}.")
        self._path = f"{PATH_PREFIX}/{partial_path}/{self._name}/{PATH_SUFFIX}"

    @property
    def name(self):
        return self._name

    @property
    def lint_report(self):
        return f"{self._path}/lint-report.txt"

    @property
    def suggested_fixes(self):
        return f"{self._path}/{FIX_ZIP}"


class SoongLintFix:
    """
    This class creates a command line tool that will apply lint fixes to the
    platform via the necessary combination of soong and shell commands.

    It breaks up these operations into a few "private" methods that are
    intentionally exposed so experimental code can tweak behavior.

    The entry point, `run`, will apply lint fixes using the intermediate
    `suggested-fixes` directory that soong creates during its invocation of
    lint.

    Basic usage:
    ```
    from soong_lint_fix import SoongLintFix

    opts = SoongLintFixOptions()
    opts.parse_args(sys.argv)
    SoongLintFix(opts).run()
    ```
    """
    def __init__(self, opts):
        self._opts = opts
        self._kwargs = None
        self._modules = []

    def run(self):
        """
        Run the script
        """
        self._setup()
        self._find_modules()
        self._lint()

        if not self._opts.no_fix:
            self._fix()

        if self._opts.print:
            self._print()

    def _setup(self):
        env = os.environ.copy()
        if self._opts.check:
            env["ANDROID_LINT_CHECK"] = self._opts.check
        if self._opts.lint_module:
            env["ANDROID_LINT_CHECK_EXTRA_MODULES"] = self._opts.lint_module

        self._kwargs = {
            "env": env,
            "executable": "/bin/bash",
            "shell": True,
        }

        os.chdir(ANDROID_BUILD_TOP)

        print("Refreshing soong modules...")
        try:
            os.mkdir(ANDROID_PRODUCT_OUT)
        except OSError:
            pass
        subprocess.call(f"{SOONG_UI} --make-mode {PRODUCT_OUT}/module-info.json", **self._kwargs)
        print("done.")


    def _find_modules(self):
        with open(f"{ANDROID_PRODUCT_OUT}/module-info.json") as f:
            module_info = json.load(f)

        for module_name in self._opts.modules:
            module = SoongModule(module_name)
            module.find(module_info)
            self._modules.append(module)

    def _lint(self):
        print("Cleaning up any old lint results...")
        for module in self._modules:
            try:
                os.remove(f"{module.lint_report}")
                os.remove(f"{module.suggested_fixes}")
            except FileNotFoundError:
                pass
        print("done.")

        target = " ".join([ module.lint_report for module in self._modules ])
        print(f"Generating {target}")
        subprocess.call(f"{SOONG_UI} --make-mode {target}", **self._kwargs)
        print("done.")

    def _fix(self):
        for module in self._modules:
            print(f"Copying suggested fixes for {module.name} to the tree...")
            with zipfile.ZipFile(f"{module.suggested_fixes}") as zip:
                for name in zip.namelist():
                    if name.startswith("out") or not name.endswith(".java"):
                        continue
                    with zip.open(name) as src, open(f"{ANDROID_BUILD_TOP}/{name}", "wb") as dst:
                        shutil.copyfileobj(src, dst)
            print("done.")

    def _print(self):
        for module in self._modules:
            print(f"### lint-report.txt {module.name} ###", end="\n\n")
            with open(module.lint_report, "r") as f:
                print(f.read())


class SoongLintFixOptions:
    """Options for SoongLintFix"""

    def __init__(self):
        self.modules = []
        self.check = None
        self.lint_module = None
        self.no_fix = False
        self.print = False

    def parse_args(self, args=None):
        _setup_parser().parse_args(args, self)


def _setup_parser():
    parser = argparse.ArgumentParser(description="""
        This is a python script that applies lint fixes to the platform:
        1. Set up the environment, etc.
        2. Run lint on the specified target.
        3. Copy the modified files, from soong's intermediate directory, back into the tree.

        **Gotcha**: You must have run `source build/envsetup.sh` and `lunch` first.
        """, formatter_class=argparse.RawTextHelpFormatter)

    parser.add_argument('modules',
                        nargs='+',
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
    opts = SoongLintFixOptions()
    opts.parse_args(sys.argv)
    SoongLintFix(opts).run()
