#!/usr/bin/env bash
# Runs the tests in this apk
adb install $OUT/data/app/HierarchyViewerTest/HierarchyViewerTest.apk
adb shell am instrument -w com.android.test.hierarchyviewer/android.test.InstrumentationTestRunner
