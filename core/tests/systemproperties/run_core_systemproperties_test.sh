#!/bin/bash

while [[ $# -gt 0 ]]; do
  case "$1" in
  --rebuild ) echo Rebuild && rebuild=true;;
  * ) com_opts+=($1);;
  esac
  shift
done

if [[ -z $ANDROID_PRODUCT_OUT && $rebuilld == true ]]; then
  echo You must lunch before running this test.
  exit 0
fi

if [[ $rebuild == true ]]; then
  make -j4 FrameworksCoreSystemPropertiesTests
  TESTAPP=${ANDROID_PRODUCT_OUT}/data/app/FrameworksCoreSystemPropertiesTests/FrameworksCoreSystemPropertiesTests.apk
  COMMAND="adb install -r $TESTAPP"
  echo $COMMAND
  $COMMAND
fi

adb shell am instrument -w -e class android.os.SystemPropertiesTest com.android.frameworks.coretests.systemproperties/android.test.InstrumentationTestRunner
