#!/bin/bash
git show --name-only --pretty=format: $1 | grep values/strings.xml | while read file; do
    python $ANDROID_BUILD_TOP/frameworks/base/tools/stringslint/stringslint.py <(git show $1:$file) <(git show $1^:$file)
done
