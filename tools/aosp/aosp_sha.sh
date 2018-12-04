#!/bin/bash
LOCAL_DIR="$( dirname "${BASH_SOURCE}" )"

if git branch -vv | grep -q -P "^\*[^\[]+\[aosp/"; then
    # Change appears to be in AOSP
    exit 0
else
    # Change appears to be non-AOSP; search for files
    count=0
    while read -r file ; do
        if (( count == 0 )); then
            echo
        fi
        echo -e "\033[0;31mThe source of truth for '$file' is in AOSP.\033[0m"
        (( count++ ))
    done < <(git show --name-only --pretty=format: $1 | grep -- "$2")
    if (( count != 0 )); then
        echo
        echo "If your change contains no confidential details (such as security fixes), please"
        echo "upload and merge this change at https://android-review.googlesource.com/."
        echo
        exit 1
    fi
fi
