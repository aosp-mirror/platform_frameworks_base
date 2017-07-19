* Copyright (C) 2016 The Android Open Source Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.


INTRODUCTION

The Android platform core tests (APCT) consist of unit tests for core platform
functionality. These differ from CTS in that they are not necessarily testing
public APIs and are not guaranteed to work outside of AOSP builds.


INSTRUCTIONS

To run a test or set of tests, first build the FrameworksCoreTests package:

  make FrameworksCoreTests

Next, install the resulting APK and run tests as you would normal JUnit tests:

  adb install -r ${ANDROID_PRODUCT_OUT}/data/app/FrameworksCoreTests/FrameworksCoreTests.apk
  adb shell am instrument -w \
    com.android.frameworks.coretests/android.support.test.runner.AndroidJUnitRunner

To run a tests within a specific package, add the following argument AFTER -w:

    -e package android.content.pm

To run a specific test or method within a test:

    -e class android.content.pm.PackageParserTest
    -e class android.content.pm.PackageParserTest#testComputeMinSdkVersion

To run tests in debug mode:

    -e debug true

To uninstall the package:

  adb shell pm uninstall -k com.android.frameworks.coretests

For more arguments, see the guide to command=line testing:

  https://developer.android.com/studio/test/command-line.html
