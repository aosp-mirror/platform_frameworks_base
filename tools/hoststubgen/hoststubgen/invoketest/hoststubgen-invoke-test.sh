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

set -e # Exit when any command files

# This script runs HostStubGen directly with various arguments and make sure
# the tool behaves in the expected way.


echo "# Listing files in the test environment"
ls -lR

echo "# Dumping the environment variables"
env

# Set up the constants and variables

# Bazel sets $TEST_TMPDIR.
export TEMP=$TEST_TMPDIR

if [[ "$TEMP" == "" ]] ; then
  TEMP=./tmp
  mkdir -p $TEMP
fi

JAR=hoststubgen-test-tiny-framework.jar
STUB=$TEMP/stub.jar
IMPL=$TEMP/impl.jar

ANNOTATION_FILTER=$TEMP/annotation-filter.txt

HOSTSTUBGEN_OUT=$TEMP/output.txt

# Because of `set -e`, we can't return non-zero from functions, so we store
# HostStubGen result in it.
HOSTSTUBGEN_RC=0

# Define the functions to


# Note, because the build rule will only install hoststubgen.jar, but not the wrapper script,
# we need to execute it manually with the java command.
hoststubgen() {
  java -jar ./hoststubgen.jar "$@"
}

run_hoststubgen() {
  local test_name="$1"
  local annotation_filter="$2"

  echo "# Test: $test_name"

  rm -f $HOSTSTUBGEN_OUT

  local filter_arg=""

  if [[ "$annotation_filter" != "" ]] ; then
    echo "$annotation_filter" > $ANNOTATION_FILTER
    filter_arg="--annotation-allowed-classes-file $ANNOTATION_FILTER"
    echo "=== filter ==="
    cat $ANNOTATION_FILTER
  fi

  hoststubgen \
      --debug \
      --in-jar $JAR \
      --out-stub-jar $STUB \
      --out-impl-jar $IMPL \
      --stub-annotation \
          android.hosttest.annotation.HostSideTestStub \
      --keep-annotation \
          android.hosttest.annotation.HostSideTestKeep \
      --stub-class-annotation \
          android.hosttest.annotation.HostSideTestWholeClassStub \
      --keep-class-annotation \
          android.hosttest.annotation.HostSideTestWholeClassKeep \
      --throw-annotation \
          android.hosttest.annotation.HostSideTestThrow \
      --remove-annotation \
          android.hosttest.annotation.HostSideTestRemove \
      --substitute-annotation \
          android.hosttest.annotation.HostSideTestSubstitute \
      --native-substitute-annotation \
          android.hosttest.annotation.HostSideTestNativeSubstitutionClass \
      --class-load-hook-annotation \
          android.hosttest.annotation.HostSideTestClassLoadHook \
      --stub-static-initializer-annotation \
          android.hosttest.annotation.HostSideTestStaticInitializerStub \
      $filter_arg \
      |& tee $HOSTSTUBGEN_OUT
  HOSTSTUBGEN_RC=${PIPESTATUS[0]}
  echo "HostStubGen exited with $HOSTSTUBGEN_RC"
  return 0
}

run_hoststubgen_for_success() {
  run_hoststubgen "$@"

  if (( $HOSTSTUBGEN_RC != 0 )) ; then
    echo "HostStubGen expected to finish successfully, but failed with $rc"
    return 1
  fi
}

run_hoststubgen_for_failure() {
  local test_name="$1"
  local expected_error_message="$2"
  shift 2

  run_hoststubgen "$test_name" "$@"

  if (( $HOSTSTUBGEN_RC == 0 )) ; then
    echo "HostStubGen expected to fail, but it didn't fail"
    return 1
  fi

  # The output should contain the expected message. (note we se fgrep here.)
  grep -Fq "$expected_error_message" $HOSTSTUBGEN_OUT
}

# Start the tests...

# Pass "" as a filter to _not_ add `--annotation-allowed-classes-file`.
run_hoststubgen_for_success "No annotation filter" ""

# Now, we use " ", so we do add `--annotation-allowed-classes-file`.
run_hoststubgen_for_failure "No classes are allowed to have annotations" \
    "not allowed to have Ravenwood annotations" \
    " "

run_hoststubgen_for_success "All classes allowed (wildcard)" \
    "
* # Allow all classes
"

run_hoststubgen_for_failure "All classes disallowed (wildcard)" \
    "not allowed to have Ravenwood annotations" \
    "
!* # Disallow all classes
"

run_hoststubgen_for_failure "Some classes not allowed (1)" \
    "not allowed to have Ravenwood annotations" \
    "
android.hosttest.*
com.android.hoststubgen.*
com.supported.*
"

run_hoststubgen_for_failure "Some classes not allowed (2)" \
    "not allowed to have Ravenwood annotations" \
    "
android.hosttest.*
com.android.hoststubgen.*
com.unsupported.*
"

run_hoststubgen_for_success "All classes allowed (package wildcard)" \
    "
android.hosttest.*
com.android.hoststubgen.*
com.supported.*
com.unsupported.*
"


run_hoststubgen_for_failure "One specific class disallowed" \
    "TinyFrameworkClassAnnotations is not allowed to have Ravenwood annotations" \
    "
!com.android.hoststubgen.test.tinyframework.TinyFrameworkClassAnnotations
* # All other classes allowed
"

run_hoststubgen_for_success "One specific class disallowed, but it doesn't use annotations" \
    "
!com.android.hoststubgen.test.tinyframework.TinyFrameworkForTextPolicy
* # All other classes allowed
"



echo "All tests passed"
exit 0