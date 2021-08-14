#!/bin/bash
LOCAL_DIR="$( dirname "${BASH_SOURCE}" )"

if git branch -vv | grep -q -P "^\*[^\[]+\[aosp/"; then
    # Change appears to be in AOSP
    exit 0
elif git log -n 1 --format='%B' $1 | grep -q -E "^Ignore-AOSP-First: .+" ; then
    # Change is explicitly marked as ok to skip AOSP
    exit 0
else
    # Change appears to be non-AOSP.

    # If this is a cherry-pick, then allow it.
    cherrypick=0
    while read -r line ; do
      if [[ $line =~ cherry\ picked\ from  ]] ; then
        (( cherrypick++ ))
      fi
    done < <(git show $1)
    if (( cherrypick != 0 )); then
      # This is a cherry-pick, so allow it.
      exit 0
    fi

    # See if any files are affected.
    count=0
    while read -r file ; do
        if (( count == 0 )); then
            echo
        fi
        echo -e "\033[0;31;47mThe source of truth for '$file' is in AOSP.\033[0m"
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
