#!/bin/bash

# Copyright (C) 2009 The Android Open Source Project
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

iterations=150
failures=0
i=0
LOGDIR="$HOME/backup_tests"
LOGFILE="$LOGDIR/backup_stress.`date +%s`.log"
export BUGREPORT_DIR="$LOGDIR/bugreports"

# make sure that we have a place to put logs and bugreports
mkdir -p $LOGDIR $BUGREPORT_DIR

echo "logfile is $LOGFILE"

(while (sleep 10); do
    failed=0
    
    echo
    echo "Iteration $i at `date`"
    echo

    ./test_backup.sh "$@" 2>&1

    sleep 10
    echo "Restore at `date`"
    echo

    ./test_restore.sh "$@" 2>&1 || failed=1
    
    if [ "$failed" -ne 0 ]; then
        failures=$(($failures+1))
        # Long and verbose so it sticks out
        echo "FAILED iteration $i of $iterations; $failures failures so far"
        echo "FAILED iteration $i of $iterations; $failures failures so far" > /dev/stderr
    else
        printf "Iteration %d:\tPASS; remaining: %d\n" $i $(($iterations - $i - 1))
        printf "Iteration %d:\tPASS; remaining: %d\n" $i $(($iterations - $i - 1)) > /dev/stderr
    fi

    echo "End $i at `date`"
    
    i=$(($i+1))
    if [ $i -eq $iterations ]; then
        echo "DONE: $iterations iterations with $failures failures."
        echo "DONE: $iterations iterations with $failures failures." > /dev/stderr
        [ "$failures" -eq 0 ] && exit 0
        exit 1
    fi
done) > "$LOGFILE"

