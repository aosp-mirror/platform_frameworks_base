#!/bin/bash
set -e
LOCAL_DIR="$( dirname ${BASH_SOURCE} )"
git show --name-only --pretty=format: $1 | grep "config/hiddenapi-.*txt" | while read file; do
    diff <(git show $1:$file) <(git show $1:$file | $LOCAL_DIR/sort_api.sh )  || {
      echo -e "\e[1m\e[31m$file $1 is not sorted or contains duplicates. To sort it correctly:\e[0m"
      echo -e "\e[33m${LOCAL_DIR}/sort_api.sh $2/frameworks/base/$file\e[0m"
      exit 1
    }
done
