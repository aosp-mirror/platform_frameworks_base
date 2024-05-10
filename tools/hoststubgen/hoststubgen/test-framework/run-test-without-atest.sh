#!/bin/bash
# Copyright (C) 2023 The Android Open Source Project
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

# Run HostStubGenTest-framework-test-host-test directly with JUnit.
# (without using atest.)

source "${0%/*}"/../../common.sh


# Options:
# -v enable verbose log
# -d enable debugger

verbose=0
debug=0
while getopts "vd" opt; do
  case "$opt" in
    v) verbose=1 ;;
    d) debug=1 ;;
  esac
done
shift $(($OPTIND - 1))


if (( $verbose )) ; then
  JAVA_OPTS="$JAVA_OPTS -verbose:class"
fi

if (( $debug )) ; then
  JAVA_OPTS="$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8700"
fi

#=======================================
module=HostStubGenTest-framework-all-test-host-test
module_jar=$ANDROID_BUILD_TOP/out/host/linux-x86/testcases/$module/$module.jar
run m $module

out=out

rm -fr $out
mkdir -p $out


# Copy and extract the relevant jar files so we can look into them.
run cp \
    $module_jar \
    $SOONG_INT/frameworks/base/tools/hoststubgen/hoststubgen/framework-all-hidden-api-host/linux_glibc_common/gen/*.jar \
    $out

run extract $out/*.jar

# Result is the number of failed tests.
result=0


# This suite runs all tests in the JAR.
tests=(com.android.hoststubgen.hosthelper.HostTestSuite)

# Uncomment this to run a specific test.
# tests=(com.android.hoststubgen.frameworktest.LogTest)


for class in ${tests[@]} ; do
  echo "Running $class ..."

  run cd "${module_jar%/*}"
  run $JAVA $JAVA_OPTS \
      -cp $module_jar \
      org.junit.runner.JUnitCore \
      $class || result=$(( $result + 1 ))
done

exit $result
