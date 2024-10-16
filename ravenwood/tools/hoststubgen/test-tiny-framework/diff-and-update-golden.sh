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

help() {
  cat <<'EOF'

  diff-and-update-golden.sh [OPTIONS]

    Compare the generated jar files from tiny-framework to the "golden" files.

  OPTIONS:
    -u: Update the golden files.

    -3: Run `meld` to compare original, stub and impl jar files in 3-way diff.
        This is useful to visualize the exact differences between 3 jar files.

    -2: Run `meld` to compare original <-> impl, and impl <-> stub as two different diffs.
EOF
}

source "${0%/*}"/../../common.sh

SCRIPT_NAME="${0##*/}"

GOLDEN_DIR=golden-output
mkdir -p $GOLDEN_DIR

DIFF_CMD=${DIFF:-diff -u --ignore-blank-lines --ignore-space-change}

update=0
three_way=0
two_way=0
while getopts "u32" opt; do
case "$opt" in
    u)
        update=1
        ;;
    3)
        three_way=1
        ;;
    2)
        two_way=1
        ;;
    '?')
        help
        exit 1
        ;;
esac
done
shift $(($OPTIND - 1))


# Build the dump files, which are the input of this test.
run m  dump-jar tiny-framework-dump-test


# Get the path to the generate text files. (not the golden files.)
# We get them from $OUT/module-info.json

files=(
$(python3 -c '
import sys
import os
import json

with open(sys.argv[1], "r") as f:
    data = json.load(f)

    # Equivalent to: jq -r '.["tiny-framework-dump-test"]["installed"][]'
    for path in data["tiny-framework-dump-test"]["installed"]:

      if "golden-output" in path:
        continue
      if path.endswith(".txt"):
        print(os.getenv("ANDROID_BUILD_TOP") + "/" + path)
' $OUT/module-info.json)
)

# Next, compare each file and update them in $GOLDEN_DIR

any_file_changed=0

for file in ${files[*]} ; do
  name=$(basename $file)
  echo "# Checking $name ..."

  file_changed=0
  if run $DIFF_CMD $GOLDEN_DIR/$name $file; then
    : # No diff
  else
    file_changed=1
    any_file_changed=1
  fi

  if (( $update && $file_changed )) ; then
    echo "# Updating $name ..."
    run cp $file $GOLDEN_DIR/$name
  fi
done

if (( $three_way )) ; then
  echo "# Running 3-way diff with meld..."
  run meld ${files[0]} ${files[1]} ${files[2]} &
fi

if (( $two_way )) ; then
  echo "# Running meld..."
  run meld --diff ${files[0]} ${files[1]} --diff ${files[1]} ${files[2]} --diff ${files[0]} ${files[2]}
fi

if (( $any_file_changed == 0 )) ; then
  echo "$SCRIPT_NAME: Success: no changes detected."
  exit 0
else
  if (( $update )) ; then
    echo "$SCRIPT_NAME: Warning: golden files have been updated."
    exit 2
  else
    echo "$SCRIPT_NAME: Failure: changes detected. See above diff for the details."
    exit 3
  fi
fi
