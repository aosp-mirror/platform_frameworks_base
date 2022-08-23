import argparse
import os
import sys

ANDROID_BUILD_TOP = os.environ.get("ANDROID_BUILD_TOP")
PATH_PREFIX = "out/soong/.intermediates"
PATH_SUFFIX = "android_common/lint"
FIX_DIR = "suggested-fixes"

parser = argparse.ArgumentParser(description="""
This is a python script that applies lint fixes to the platform:
1. Set up the environment, etc.
2. Build the lint and run it.
3. Unpack soong's intermediate zip containing source files modified by lint.
4. Copy the modified files back into the tree.

**Gotcha**: You must have run `source build/envsetup.sh` and `lunch` \
so that the `ANDROID_BUILD_TOP` environment variable has been set.
Alternatively, set it manually in your shell.
""", formatter_class=argparse.RawTextHelpFormatter)

parser.add_argument('build_path', metavar='build_path', type=str,
                    help='The build module to run '
                         '(e.g. frameworks/base/framework-minus-apex or '
                         'frameworks/base/services/core/services.core.unboosted)')

parser.add_argument('--check', metavar='check', type=str,
                    help='Which lint to run. Passed to the ANDROID_LINT_CHECK environment variable.')

parser.add_argument('--dry-run', dest='dry_run', action='store_true',
                    help='Just print the resulting shell script instead of running it.')

parser.add_argument('--no-fix', dest='no_fix', action='store_true',
                    help='Just build and run the lint, do NOT apply the fixes.')

args = parser.parse_args()

path = f"{PATH_PREFIX}/{args.build_path}/{PATH_SUFFIX}"
target = f"{path}/lint-report.html"

commands = []

if not args.dry_run:
    commands += [f"export ANDROID_BUILD_TOP={ANDROID_BUILD_TOP}"]

if args.check:
    commands += [f"export ANDROID_LINT_CHECK={args.check}"]

commands += [
    "cd $ANDROID_BUILD_TOP",
    "source build/envsetup.sh",
    f"rm {target}",  # remove the file first so soong doesn't think there is no work to do
    f"m {target}",
]

if not args.no_fix:
    commands += [
        f"cd {path}",
        f"unzip {FIX_DIR}.zip -d {FIX_DIR}",
        f"cd {FIX_DIR}",
        # Find all the java files in the fix directory, excluding the ./out subdirectory,
        # and copy them back into the same path within the tree.
        f"find . -path ./out -prune -o -name '*.java' -print | xargs -n 1 sh -c 'cp $1 $ANDROID_BUILD_TOP/$1' --",
        f"rm -rf {FIX_DIR}"
    ]

if args.dry_run:
    print("(\n" + ";\n".join(commands) + "\n)")
    sys.exit(0)

with_echo = []
for c in commands:
    with_echo.append(f'echo "{c}"')
    with_echo.append(c)

os.system("(\n" + ";\n".join(with_echo) + "\n)")
