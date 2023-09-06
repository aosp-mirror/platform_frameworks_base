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

set -e # Exit at failure
shopt -s globstar # Enable double-star wildcards (**)

cd "${0%/*}" # Move to the script dir

fail() {
  echo "Error: $*" 1>&2
  exit 1
}

# Print the arguments and then execute.
run() {
  echo "Running: $*" 1>&2
  "$@"
}

# Concatenate the second and subsequent args with the first arg as a separator.
# e.g. `join : a b c` -> prints `a:b:c`
join() {
  local IFS="$1"
  shift
  echo "$*"
}

abspath() {
  for name in "${@}"; do
    readlink -f $name
  done
}

m() {
  if (( $SKIP_BUILD )) ; then
    echo "Skipping build: $*" 1>&2
    return 0
  fi
  run ${ANDROID_BUILD_TOP}/build/soong/soong_ui.bash --make-mode "$@"
}

# Extract given jar files
extract() {
  for f in "${@}"; do
    local out=$f.ext
    run rm -fr $out
    run mkdir -p $out

    # It's too noisy, so only show the first few lines.
    {
      # Hmm unzipping kotlin jar files may produce a warning? Let's just add `|| true`...
      run unzip $f -d $out || true
    } |& sed -e '5,$d'
    echo '  (omitting remaining output)'

  done
}

# Find all *.java files in $1, and print them as Java class names.
# For example, if there's a file `src/com/android/test/Test.java`, and you run
# `list_all_classes_under_dir src`, then it'll print `com.android.test.Test`.
list_all_classes_under_dir() {
  local dir="$1"
  ( # Use a subshell, so we won't change the current directory on the caller side.
    cd "$dir"

    # List the java files, but replace the slashes with dots, and remove the `.java` suffix.
    ls **/*.java | sed -e 's!/!.!g' -e 's!.java$!!'
  )
}

checkenv() {
  # Make sure $ANDROID_BUILD_TOP is set.
  : ${ANDROID_BUILD_TOP:?}

  # Make sure ANDROID_BUILD_TOP doesn't contain whitespace.
  set ${ANDROID_BUILD_TOP}
  if [[ $# != 1 ]] ; then
    fail "\$ANDROID_BUILD_TOP cannot contain whitespace."
  fi
}

checkenv

JAVAC=${JAVAC:-javac}
JAVA=${JAVA:-java}
JAR=${JAR:-jar}

JAVAC_OPTS=${JAVAC_OPTS:--Xmaxerrs 99999 -Xlint:none}

SOONG_INT=$ANDROID_BUILD_TOP/out/soong/.intermediates

JUNIT_TEST_MAIN_CLASS=com.android.hoststubgen.hosthelper.HostTestSuite

run_junit_test_jar() {
  local jar="$1"
  echo "Starting test: $jar ..."
  run cd "${jar%/*}"

  run $JAVA $JAVA_OPTS \
      -cp $jar \
      org.junit.runner.JUnitCore \
      $main_class || return 1
  return 0
}
