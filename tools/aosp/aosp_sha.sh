#!/bin/bash
LOCAL_DIR="$( dirname ${BASH_SOURCE} )"

if git branch -vv | grep "^*" | grep "\[aosp/master" > /dev/null; then
    # Change appears to be in AOSP
    exit 0
else
    # Change appears to be non-AOSP; search for files
    git show --name-only --pretty=format: $1 | grep $2 | while read file; do
        echo
        echo -e "\033[0;31mThe source of truth for '$file' is in AOSP.\033[0m"
        echo
        echo "If your change contains no confidential details, please upload and merge"
        echo "this change at https://android-review.googlesource.com/."
        echo
        exit 77
    done
fi
