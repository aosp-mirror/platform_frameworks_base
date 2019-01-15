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
# Stash away comments
C=( $(grep -E '^#' <<< "${A[*]}" || :) )
A=( $(grep -v -E '^#' <<< "${A[*]}" || :) )
# Sort entries
A=( $(LC_COLLATE=C sort -f <<< "${A[*]}") )
A=( $(uniq <<< "${A[*]}") )
# Concatenate comments and entries
A=( ${C[*]} ${A[*]} )
unset IFS
# Dump array back into the file
if [ ${#A[@]} -ne 0 ]; then
  printf '%s\n' "${A[@]}" > "$dest_list"
fi
