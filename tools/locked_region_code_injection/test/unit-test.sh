#! /bin/bash
#

# Copyright (C) 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied. See the License for the specific language governing permissions and limitations under
# the License.

# This script runs the tests for the lockedregioninjectioncode.  See
# TestMain.java for the invocation.  The script expects that a full build has
# already been done and artifacts are in $TOP/out.

# Compute the default top of the workspace.  The following code copies the
# strategy of croot.  (croot cannot be usd directly because it is a function and
# functions are not carried over into subshells.)  This gives the correct answer
# if run from inside a workspace.  If run from outside a workspace, supply TOP
# on the command line.
TOPFILE=build/make/core/envsetup.mk
TOP=$(dirname $(realpath $0))
while [[ ! $TOP = / && ! -f $TOP/$TOPFILE ]]; do
  TOP=$(dirname $TOP)
done
# TOP is "/" if this script is located outside a workspace.

# If the user supplied a top directory, use it instead
if [[ -n $1 ]]; then
  TOP=$1
  shift
fi
if [[ -z $TOP || $TOP = / ]]; then
  echo "usage: $0 <workspace-root>"
  exit 1
elif [[ ! -d $TOP ]]; then
  echo "$TOP is not a directory"
  exit 1
elif [[ ! -d $TOP/prebuilts/misc/common ]]; then
  echo "$TOP does not look like w workspace"
  exit 1
fi
echo "Using workspace $TOP"

# Pick up the current java compiler.  The lunch target is not very important,
# since most, if not all, will use the same host binaries.
pushd $TOP > /dev/null
. build/envsetup.sh > /dev/null 2>&1
lunch redfin-userdebug > /dev/null 2>&1
popd > /dev/null

# Bail on any error
set -o pipefail
trap 'exit 1' ERR

# Create the two sources
pushd $TOP > /dev/null
m lockedregioncodeinjection
m lockedregioncodeinjection_input
popd > /dev/null

# Create a temporary directory outside of the workspace.
OUT=$TOP/out/host/test/lockedregioncodeinjection
echo

# Clean the directory
if [[ -d $OUT ]]; then rm -r $OUT; fi
mkdir -p $OUT

ROOT=$TOP/out/host/linux-x86
EXE=$ROOT/bin/lockedregioncodeinjection
INP=$ROOT/framework/lockedregioncodeinjection_input.jar

# Run tool on unit tests.
$EXE \
    -i $INP -o $OUT/test_output.jar \
    --targets 'Llockedregioncodeinjection/TestTarget;' \
    --pre     'lockedregioncodeinjection/TestTarget.boost' \
    --post    'lockedregioncodeinjection/TestTarget.unboost' \
    --scoped  'Llockedregioncodeinjection/TestScopedLock;,monitorEnter,monitorExit'

# Run unit tests.
java -ea -cp $OUT/test_output.jar \
    org.junit.runner.JUnitCore lockedregioncodeinjection.TestMain

# Extract the class files and decompile them for possible post-analysis.
pushd $OUT > /dev/null
jar -x --file test_output.jar lockedregioncodeinjection
for class in lockedregioncodeinjection/*.class; do
  javap -c -v $class > ${class%.class}.asm
done
popd > /dev/null

echo "artifacts are in $OUT"
