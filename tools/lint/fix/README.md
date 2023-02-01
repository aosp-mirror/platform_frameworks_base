# Refactoring the platform with lint
Inspiration: go/refactor-the-platform-with-lint\
**Special Thanks: brufino@, azharaa@, for the prior work that made this all possible**

## What is this?

It's a python script that runs the framework linter,
and then copies modified files back into the source tree.\
Why python, you ask?  Because python is cool ¯\_(ツ)_/¯.

## Why?

Lint is not allowed to modify source files directly via lint's `--apply-suggestions` flag.
As a compromise, soong zips up the (potentially) modified sources and leaves them in an intermediate
directory.  This script runs the lint, unpacks those files, and copies them back into the tree.

## How do I run it?
**WARNING: You probably want to commit/stash any changes to your working tree before doing this...**

From this directory, run `python lint_fix.py -h`.
The script's help output explains things that are omitted here.

Alternatively, there is a python binary target you can build to make this
available anywhere in your tree:
```
m lint_fix
lint_fix -h
```

**Gotcha**: You must have run `source build/envsetup.sh` and `lunch` first.

Example: `lint_fix frameworks/base/services/core/services.core.unboosted UseEnforcePermissionAnnotation --dry-run`
```shell
(
export ANDROID_LINT_CHECK=UseEnforcePermissionAnnotation;
cd $ANDROID_BUILD_TOP;
source build/envsetup.sh;
rm out/soong/.intermediates/frameworks/base/services/core/services.core.unboosted/android_common/lint/lint-report.html;
m out/soong/.intermediates/frameworks/base/services/core/services.core.unboosted/android_common/lint/lint-report.html;
cd out/soong/.intermediates/frameworks/base/services/core/services.core.unboosted/android_common/lint;
unzip suggested-fixes.zip -d suggested-fixes;
cd suggested-fixes;
find . -path ./out -prune -o -name '*.java' -print | xargs -n 1 sh -c 'cp $1 $ANDROID_BUILD_TOP/$1' --;
rm -rf suggested-fixes
)
```
