#!/bin/bash
LOCAL_DIR="$( dirname ${BASH_SOURCE} )"
git show --name-only --pretty=format: $1 | grep values/strings.xml | while read file; do
    python $LOCAL_DIR/stringslint.py <(git show $1:$file) <(git show $1^:$file)
done
