# Refactoring the platform with lint
Inspiration: go/refactor-the-platform-with-lint\
**Special Thanks: brufino@, azharaa@, for the prior work that made this all possible**

## What is this?

It's a python script that runs the framework linter,
and then (optionally) copies modified files back into the source tree.\
Why python, you ask? Because python is cool ¯\\\_(ツ)\_/¯.

Incidentally, this exposes a much simpler way to run individual lint checks
against individual modules, so it's useful beyond applying fixes.

## Why?

Lint is not allowed to modify source files directly via lint's `--apply-suggestions` flag.
As a compromise, soong zips up the (potentially) modified sources and leaves them in an intermediate
directory. This script runs the lint, unpacks those files, and copies them back into the tree.

## How do I run it?
**WARNING: You probably want to commit/stash any changes to your working tree before doing this...**

```
source build/envsetup.sh
lunch cf_x86_64_phone-userdebug # or any lunch target
m lint_fix
lint_fix -h
```

The script's help output explains things that are omitted here.
