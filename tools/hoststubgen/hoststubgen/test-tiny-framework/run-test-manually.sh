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


source "${0%/*}"/../../common.sh

# This scripts run the "tiny-framework" test, but does most stuff from the command line, using
# the native java and javac commands.

debug=0
while getopts "d" opt; do
case "$opt" in
    d) debug=1 ;;
esac
done
shift $(($OPTIND - 1))


out=out

rm -fr $out
mkdir -p $out

HOSTSTUBGEN=hoststubgen

# Rebuild the tool and the dependencies. These are the only things we build with the build system.
run m $HOSTSTUBGEN hoststubgen-annotations hoststubgen-helper-runtime truth junit


# Build tiny-framework

tiny_framework_classes=$out/tiny-framework/classes/
tiny_framework_jar=$out/tiny-framework.jar
tiny_framework_host_jar=$out/tiny-framework_host.jar

tiny_test_classes=$out/tiny-test/classes/
tiny_test_jar=$out/tiny-test.jar

framework_compile_classpaths=(
  $SOONG_INT/frameworks/base/tools/hoststubgen/hoststubgen/hoststubgen-annotations/android_common/javac/hoststubgen-annotations.jar
)

test_compile_classpaths=(
  $SOONG_INT/external/junit/junit/android_common/combined/junit.jar
  $SOONG_INT/external/truth/truth/android_common/combined/truth.jar
)

test_runtime_classpaths=(
  $SOONG_INT/frameworks/base/tools/hoststubgen/hoststubgen/hoststubgen-helper-runtime/linux_glibc_common/javac/hoststubgen-helper-runtime.jar
)

# This suite runs all tests in the JAR.
test_classes=(com.android.hoststubgen.hosthelper.HostTestSuite)

# Uncomment this to run a specific test.
# tests=(com.android.hoststubgen.test.tinyframework.TinyFrameworkBenchmark)


# Build tiny-framework.jar
echo "# Building tiny-framework..."
run $JAVAC \
    -cp $( \
        join : \
        ${framework_compile_classpaths[@]} \
        ) \
    -d $tiny_framework_classes \
    tiny-framework/src/**/*.java

run $JAR cvf $tiny_framework_jar \
    -C $tiny_framework_classes .

# Build stub/impl jars
echo "# Generating the stub and impl jars..."
run $HOSTSTUBGEN \
    @../hoststubgen-standard-options.txt \
    --in-jar $tiny_framework_jar \
    --out-jar $tiny_framework_host_jar \
    --policy-override-file policy-override-tiny-framework.txt \
    --gen-keep-all-file out/tiny-framework_keep_all.txt \
    --gen-input-dump-file out/tiny-framework_dump.txt \
    --package-redirect com.unsupported:com.supported \
    --annotation-allowed-classes-file annotation-allowed-classes-tiny-framework.txt \
    $HOSTSTUBGEN_OPTS

# Extract the jar files, so we can look into them.
extract $tiny_framework_host_jar

# Build the test
echo "# Building tiny-test..."
run $JAVAC \
    -cp $( \
        join : \
        $tiny_framework_jar \
        "${test_compile_classpaths[@]}" \
        ) \
    -d $tiny_test_classes \
    tiny-test/src/**/*.java

run $JAR cvf $tiny_test_jar \
    -C $tiny_test_classes .

if (( $debug )) ; then
  JAVA_OPTS="$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8700"
fi

# Run the test
echo "# Running tiny-test..."
run $JAVA \
    $JAVA_OPTS \
    -cp $( \
        join : \
        $tiny_test_jar \
        $tiny_framework_host_jar \
        "${test_compile_classpaths[@]}" \
        "${test_runtime_classpaths[@]}" \
        ) \
    org.junit.runner.JUnitCore \
    ${test_classes[@]}
