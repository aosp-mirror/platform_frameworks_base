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

# Libraries that constitute system_server.
# It is non-trivial to keep in sync with services/Android.bp as some
# module are post-processed (e.g, services.core).
TARGETS = [
        "services.core.unboosted",
        "services.accessibility",
        "services.appprediction",
        "services.appwidget",
        "services.autofill",
        "services.backup",
        "services.companion",
        "services.contentcapture",
        "services.contentsuggestions",
        "services.coverage",
        "services.devicepolicy",
        "services.midi",
        "services.musicsearch",
        "services.net",
        "services.people",
        "services.print",
        "services.profcollect",
        "services.restrictions",
        "services.searchui",
        "services.smartspace",
        "services.systemcaptions",
        "services.translation",
        "services.texttospeech",
        "services.usage",
        "services.usb",
        "services.voiceinteraction",
        "services.wallpapereffectsgeneration",
        "services.wifi",
]


class EnforcePermissionMigratedCounter:
    """Wrapper around lint_fix to count the number of AIDL methods annotated."""
    def run(self):
        opts = soong_lint_fix.SoongLintFixOptions()
        opts.check = "AnnotatedAidlCounter"
        opts.lint_module = "AndroidUtilsLintChecker"
        opts.no_fix = True
        opts.modules = TARGETS

        self.linter = soong_lint_fix.SoongLintFix(opts)
        self.linter.run()
        self.parse_lint_reports()

    def parse_lint_reports(self):
        counts = { "unannotated": 0, "enforced": 0, "notRequired": 0 }
        for module in self.linter._modules:
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
