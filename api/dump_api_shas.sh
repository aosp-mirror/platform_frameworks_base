#!/bin/bash -e
# This script dumps the git SHAs of all commits inside api tracking directories.
# It can used by tools wanting to track API changes, and the primary original
# purpose is to verify verify all API change SHAs have been tracked by the
# server-side API-council tools.
#
# The only argument is used to specify a git commit range to filter by.
#
# Example invocation (API changes between O and P):
# frameworks/base/api/dump_api_shas.sh origin/oreo-dev..origin/pie-dev

set -o pipefail

eecho() { echo $@ >&2 ; }

if [[ $1 == *..* ]]; then
    exclude=${1/..*}
    include=${1/*..}
else
    eecho No range or invalid range specified, defaulting to all commits from HEAD.
    exclude=
    include=HEAD
fi

eecho -n building queryview...
{ source build/envsetup.sh && lunch aosp_arm && m queryview; } >/dev/null 2>&1 \
  || { eecho failed; exit 1; }
eecho "done"

# This finds the directories where the dependant java_sdk_libs are defined
bpdirs=$(
  bazel query --config=queryview --output=package \
    'kind(java_sdk_library, deps(//frameworks/base/api/..., 1))' 2>/dev/null
  echo frameworks/base/core/api # Not a java_sdk_library.
  echo frameworks/base/services/api # Not a java_sdk_library.
)

# Find relevant api subdirectories
apidirs=$(
  find $bpdirs -type f -name '*current.txt' -path '*/api/*' \
    | xargs realpath --relative-to=$(pwd) | xargs dirname | sort | uniq
)

# Dump sorted SHAs of commits in these directories
{ for d in $apidirs; do
    ( cd $d
      eecho inspecting $d
      exclude_arg=$(test -n "$exclude" && {
        git rev-parse -q --verify $exclude > /dev/null && echo "--not $exclude" \
          || eecho "$d has no revision $exclude, including all commits"; } || true)
      for f in $(find . -name '*current.txt'); do
        git --no-pager log --pretty=format:%H --no-merges --follow $include $exclude_arg -- $f
        echo # No trailing newline with --no-pager
      done
    )
done; } | sort | uniq
