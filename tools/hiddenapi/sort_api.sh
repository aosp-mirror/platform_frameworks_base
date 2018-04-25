#!/bin/bash
set -e
if [ -z "$1" ]; then
  source_list=/dev/stdin
  dest_list=/dev/stdout
else
  source_list="$1"
  dest_list="$1"
fi
# Load the file
readarray A < "$source_list"
# Sort
IFS=$'\n'
A=( $(LC_COLLATE=C sort -f <<< "${A[*]}") )
A=( $(uniq <<< "${A[*]}") )
unset IFS
# Dump array back into the file
printf '%s\n' "${A[@]}" > "$dest_list"
