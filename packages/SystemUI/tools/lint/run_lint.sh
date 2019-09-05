#! /bin/bash

if [[ $# -ne 2 ]]; then
    echo "Error: Incorrect number of arguments" >&2
    echo "Usage: ./run_lint.sh <repo_root> <CL_SHA>" >&2
    exit 100
fi

git show --name-only --pretty=format: $2 | grep packages/SystemUI/ > /dev/null
exitcode=$?
if [[ exitcode -eq 1 ]];  then
    exit 0
fi

if [[ -z $ANDROID_BUILD_TOP ]]; then
    echo "Error: ANDROID_BUILD_TOP must be set" >&2
    echo "Try setting up your environment first:" >&2
    echo "    source build/envsetup.sh && lunch <target>" >&2
    exit 101
fi

# TODO: Run lint as part of the build so we can specify the dependency properly
systemuijarpath="out/soong/.intermediates/frameworks/base/packages/SystemUI/SystemUI-core/android_common/combined/SystemUI-core.jar"
if [[ ! -f $ANDROID_BUILD_TOP/$systemuijarpath ]]; then
    echo "Error: Classes.jar file not found" >&2
    echo "Try building that jar file manually:" >&2
    echo "    m -j16 out/soong/.intermediates/frameworks/base/packages/SystemUI/SystemUI-core/android_common/combined/SystemUI-core.jar" >&2
    exit 102
fi

REPO_ROOT=$1
${REPO_ROOT}/prebuilts/devtools/tools/lint \
       . \
       --exitcode \
       -Werror \
       --config ${REPO_ROOT}/frameworks/base/packages/SystemUI/tools/lint/lint.xml \
       --html ${REPO_ROOT}/out/lint_output.html \
       --baseline ${REPO_ROOT}/frameworks/base/packages/SystemUI/tools/lint/baseline.xml \
       --remove-fixed
exitcode=$?
if [[ exitcode -eq 1 ]];  then
    cat >&2 <<EOF

Please check the HTML results file and fix the errors.
If the error cannot be fixed immediately, there are 3 possible resolutions:
1. Use tools:ignore or @SuppressLint annotation. This is preferred
   for cases where the lint violation is intended, so that reviewers
   can review whether the suppression is appropriate.
2. Use tools/lint.xml to ignore a lint check which we don't care
   about for any file, or checks that are not actionable by the
   CL author (e.g. translation issues)
3. If there are lint errors that should be fixed, but cannot be done
   immediately for some reason, run ./tools/lint/update_baseline.sh to
   add them to baseline.xml.

EOF
fi

exit $exitcode
