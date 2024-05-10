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
import functools
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
MODULE_JAVA_DEPS = "out/soong/module_bp_java_deps.json"


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

    def find_java_deps(self, module_java_deps):
        """Finds the dependencies of a Java module in the loaded module_bp_java_deps.json.

        Returns:
            A list of module names.
        """
        if self._name not in module_java_deps:
            raise Exception(f"Module {self._name} not found!")

        return module_java_deps[self._name]["dependencies"]

    @property
    def name(self):
        return self._name

    @property
    def path(self):
        return self._path

    @property
    def lint_report(self):
        return f"{self._path}/lint-report.txt"

    @property
    def suggested_fixes(self):
        return f"{self._path}/{FIX_ZIP}"


class SoongLintWrapper:
    """
    This class wraps the necessary calls to Soong and/or shell commands to lint
    platform modules and apply suggested fixes if desired.

    It breaks up these operations into a few methods that are available to
    sub-classes (see SoongLintFix for an example).
    """
    def __init__(self, check=None, lint_module=None):
        self._check = check
        self._lint_module = lint_module
        self._kwargs = None

    def _setup(self):
        env = os.environ.copy()
        if self._check:
            env["ANDROID_LINT_CHECK"] = self._check
        if self._lint_module:
            env["ANDROID_LINT_CHECK_EXTRA_MODULES"] = self._lint_module

        self._kwargs = {
            "env": env,
            "executable": "/bin/bash",
            "shell": True,
        }

        os.chdir(ANDROID_BUILD_TOP)

    @functools.cached_property
    def _module_info(self):
        """Returns the JSON content of module-info.json."""
        print("Refreshing Soong modules...")
        try:
            os.mkdir(ANDROID_PRODUCT_OUT)
        except OSError:
            pass
        subprocess.call(f"{SOONG_UI} --make-mode {PRODUCT_OUT}/module-info.json", **self._kwargs)
        print("done.")

        with open(f"{ANDROID_PRODUCT_OUT}/module-info.json") as f:
            return json.load(f)

    def _find_module(self, module_name):
        """Returns a SoongModule from a module name.

        Ensures that the module is known to Soong.
        """
        module = SoongModule(module_name)
        module.find(self._module_info)
        return module

    def _find_modules(self, module_names):
        modules = []
        for module_name in module_names:
            modules.append(self._find_module(module_name))
        return modules

    @functools.cached_property
    def _module_java_deps(self):
        """Returns the JSON content of module_bp_java_deps.json."""
        print("Refreshing Soong Java deps...")
        subprocess.call(f"{SOONG_UI} --make-mode {MODULE_JAVA_DEPS}", **self._kwargs)
        print("done.")

        with open(f"{MODULE_JAVA_DEPS}") as f:
            return json.load(f)

    def _find_module_java_deps(self, module):
        """Returns a list a dependencies for a module.

        Args:
            module: A SoongModule.

        Returns:
            A list of SoongModule.
        """
        deps = []
        dep_names = module.find_java_deps(self._module_java_deps)
        for dep_name in dep_names:
            dep = SoongModule(dep_name)
            dep.find(self._module_info)
            deps.append(dep)
        return deps

    def _lint(self, modules):
        print("Cleaning up any old lint results...")
        for module in modules:
            try:
                os.remove(f"{module.lint_report}")
                os.remove(f"{module.suggested_fixes}")
            except FileNotFoundError:
                pass
        print("done.")

        target = " ".join([ module.lint_report for module in modules ])
        print(f"Generating {target}")
        subprocess.call(f"{SOONG_UI} --make-mode {target}", **self._kwargs)
        print("done.")

    def _fix(self, modules):
        for module in modules:
            print(f"Copying suggested fixes for {module.name} to the tree...")
            with zipfile.ZipFile(f"{module.suggested_fixes}") as zip:
                for name in zip.namelist():
                    if name.startswith("out") or not name.endswith(".java"):
                        continue
                    with zip.open(name) as src, open(f"{ANDROID_BUILD_TOP}/{name}", "wb") as dst:
                        shutil.copyfileobj(src, dst)
            print("done.")

    def _print(self, modules):
        for module in modules:
            print(f"### lint-report.txt {module.name} ###", end="\n\n")
            with open(module.lint_report, "r") as f:
                print(f.read())


class SoongLintFix(SoongLintWrapper):
    """
    Basic usage:
    ```
    from soong_lint_fix import SoongLintFix

    opts = SoongLintFixOptions()
    opts.parse_args()
    SoongLintFix(opts).run()
    ```
    """
    def __init__(self, opts):
        super().__init__(check=opts.check, lint_module=opts.lint_module)
        self._opts = opts

    def run(self):
        self._setup()
        modules = self._find_modules(self._opts.modules)
        self._lint(modules)

        if not self._opts.no_fix:
            self._fix(modules)

        if self._opts.print:
            self._print(modules)


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
    opts.parse_args()
    SoongLintFix(opts).run()
