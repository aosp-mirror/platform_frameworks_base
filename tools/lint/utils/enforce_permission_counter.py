#  Copyright (C) 2023 The Android Open Source Project
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

import re

import soong_lint_fix

CHECK = "AnnotatedAidlCounter"
LINT_MODULE = "AndroidUtilsLintChecker"

class EnforcePermissionMigratedCounter(soong_lint_fix.SoongLintWrapper):
    """Wrapper around lint_fix to count the number of AIDL methods annotated."""

    def __init__(self):
        super().__init__(check=CHECK, lint_module=LINT_MODULE)

    def run(self):
        self._setup()

        # Analyze the dependencies of the "services" module and the module
        # "services.core.unboosted".
        service_module = self._find_module("services")
        dep_modules = self._find_module_java_deps(service_module) + \
                      [self._find_module("services.core.unboosted")]

        # Skip dependencies that are not services. Skip the "services.core"
        # module which is analyzed via "services.core.unboosted".
        modules = []
        for module in dep_modules:
            if "frameworks/base/services" not in module.path:
                continue
            if module.name == "services.core":
                continue
            modules.append(module)

        self._lint(modules)

        counts = { "unannotated": 0, "enforced": 0, "notRequired": 0 }
        for module in modules:
            with open(module.lint_report, "r") as f:
                content = f.read()
                keys = dict(re.findall(r'(\w+)=(\d+)', content))
                for key in keys:
                    counts[key] += int(keys[key])
        print(counts)
        total = sum(counts.values())
        annotated_percent = (1 - (counts["unannotated"] / total)) * 100
        print("Annotated methods = %.2f%%" % (annotated_percent))


if __name__ == "__main__":
    EnforcePermissionMigratedCounter().run()
