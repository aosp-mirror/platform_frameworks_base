#!/usr/bin/env python

# Copyright (C) 2016 The Android Open Source Project
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

import os
import subprocess
import sys

INSTRUMENTED_PACKAGE_RUNNER = ('com.android.frameworks.servicestests/'
                               'androidx.test.runner.AndroidJUnitRunner')

PACKAGE_WHITELIST = (
    "com.android.server",
)

COLOR_RED = '\033[0;31m'
COLOR_NONE ='\033[0m'

def run(shell_command, echo=True):
    if echo:
        print '%s + %s%s' % (
                COLOR_RED,
                echo if isinstance(echo, str) else shell_command,
                COLOR_NONE)
    return subprocess.check_call(shell_command, shell=True)

# usage:
#   ${ANDROID_BUILD_TOP}/frameworks/base/services/tests/runtests.py : run tests in com.android.server
#   ${ANDROID_BUILD_TOP}/frameworks/base/services/tests/runtests.py -e package [package name, e.g. com.android.server]
#   ${ANDROID_BUILD_TOP}/frameworks/base/services/tests/runtests.py -e class [class name, e.g. com.android.server.MountServiceTests]
#
#   The available INSTRUMENTED_PACKAGE_RUNNER may differ in different environments.
#   In this case, use "adb shell pm list instrumentation" to query available runners
#   and use the environment variable INSTRUMENTED_PACKAGE_RUNNER to overwrite
#   the default one, e.g.,
#   INSTRUMENTED_PACKAGE_RUNNER=com.android.frameworks.servicestests/androidx.test.runner.AndroidJUnitRunner \
#       ${ANDROID_BUILD_TOP}/frameworks/base/services/tests/runtests.py
#
def main():
    build_top = os.environ.get('ANDROID_BUILD_TOP', None)
    out_dir = os.environ.get('OUT', None)
    runner = os.environ.get('INSTRUMENTED_PACKAGE_RUNNER', None)
    if build_top is None or out_dir is None:
        print 'You need to source and lunch before you can use this script'
        return 1
    if runner is None:
        runner = INSTRUMENTED_PACKAGE_RUNNER
    print 'Building tests...'
    run('make -j32 -C %s -f build/core/main.mk '
        'MODULES-IN-frameworks-base-services-tests-servicestests' % build_top,
        echo='mmma -j32 %s/frameworks/base/services/tests/servicestests' %
             build_top)

    print 'Installing tests...'
    run('adb root')
    run('adb wait-for-device')
    apk_path = (
            '%s/data/app/FrameworksServicesTests/FrameworksServicesTests.apk' %
            out_dir)
    run('adb install -t -r -g "%s"' % apk_path)

    print 'Running tests...'
    if len(sys.argv) != 1:
        run('adb shell am instrument -w %s "%s"' %
            (' '.join(sys.argv[1:]), runner))
        return 0

    # It would be nice if the activity manager accepted a list of packages, but
    # in lieu of that...
    for package in PACKAGE_WHITELIST:
        run('adb shell am instrument -w -e package %s %s' %
            (package, runner))

    return 0


if __name__ == '__main__':
    sys.exit(main())
