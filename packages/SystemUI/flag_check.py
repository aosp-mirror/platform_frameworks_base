#! /usr/bin/env python3

import sys
import re
import argparse

# partially copied from tools/repohooks/rh/hooks.py

TEST_MSG = """Commit message is missing a "Flag:" line.  It must match one of the
following case-sensitive regex:

    %s

The Flag: stanza is regex matched and should describe whether your change is behind a flag or flags.

As a CL author, you'll have a consistent place to describe the risk of the proposed change by explicitly calling out the name of the
flag in addition to its state (ENABLED|DISABLED|DEVELOPMENT|STAGING|TEAMFOOD|TRUNKFOOD|NEXTFOOD).

Some examples below:

Flag: NONE
Flag: NA
Flag: LEGACY ENABLE_ONE_SEARCH DISABLED
Flag: ACONFIG com.android.launcher3.enable_twoline_allapps DEVELOPMENT
Flag: ACONFIG com.android.launcher3.enable_twoline_allapps TRUNKFOOD

Check the git history for more examples. It's a regex matched field.
"""

def main():
    """Check the commit message for a 'Flag:' line."""
    parser = argparse.ArgumentParser(
        description='Check the commit message for a Flag: line.')
    parser.add_argument('--msg',
                        metavar='msg',
                        type=str,
                        nargs='?',
                        default='HEAD',
                        help='commit message to process.')
    parser.add_argument(
        '--files',
        metavar='files',
        nargs='?',
        default='',
        help=
        'PREUPLOAD_FILES in repo upload to determine whether the check should run for the files.')
    parser.add_argument(
        '--project',
        metavar='project',
        type=str,
        nargs='?',
        default='',
        help=
        'REPO_PROJECT in repo upload to determine whether the check should run for this project.')

    # Parse the arguments
    args = parser.parse_args()
    desc = args.msg
    files = args.files
    project = args.project

    if not should_run_path(project, files):
        return

    field = 'Flag'
    none = '(NONE|NA|N\/A)' # NONE|NA|N/A

    typeExpression = '\s*(LEGACY|ACONFIG)' # [type:LEGACY|ACONFIG]

    # legacyFlagName contains only uppercase alphabets with '_' - Ex: ENABLE_ONE_SEARCH
    # Aconfig Flag name format = "packageName"."flagName"
    # package name - Contains only lowercase alphabets + digits + '.' - Ex: com.android.launcher3
    # For now alphabets, digits, "_", "." characters are allowed in flag name and not adding stricter format check.
    #common_typos_disable
    flagName = '([a-zA-z0-9_.])+'

    #[state:ENABLED|DISABLED|DEVELOPMENT|TEAM*(TEAMFOOD)|STAGING|TRUNK*(TRUNK_STAGING, TRUNK_FOOD)|NEXT*(NEXTFOOD)]
    stateExpression = '\s*(ENABLED|DISABLED|DEVELOPMENT|TEAM[a-zA-z]*|STAGING|TRUNK[a-zA-z]*|NEXT[a-zA-z]*)'
    #common_typos_enable

    readableRegexMsg = '\n\tFlag: (NONE|NA)\n\tFlag: LEGACY|ACONFIG FlagName|packageName.flagName ENABLED|DISABLED|DEVELOPMENT|TEAMFOOD|STAGING|TRUNKFOOD|NEXTFOOD'

    flagRegex = fr'^{field}: .*$'
    check_flag = re.compile(flagRegex) #Flag:

    # Ignore case for flag name format.
    flagNameRegex = fr'(?i)^{field}:\s*({none}|{typeExpression}\s*{flagName}\s*{stateExpression})\s*'
    check_flagName = re.compile(flagNameRegex) #Flag: <flag name format>

    flagError = False
    foundFlag = []
    # Check for multiple "Flag:" lines and all lines should match this format
    for line in desc.splitlines():
        if check_flag.match(line):
            if not check_flagName.match(line):
                flagError = True
                break
            foundFlag.append(line)

    # Throw error if
    # 1. No "Flag:" line is found
    # 2. "Flag:" doesn't follow right format.
    if (not foundFlag) or (flagError):
        error = TEST_MSG % (readableRegexMsg)
        print(error)
        sys.exit(1)

    sys.exit(0)


def should_run_path(project, files):
    """Returns a boolean if this check should run with these paths.
    If you want to check for a particular subdirectory under the path,
    add a check here, call should_run_files and check for a specific sub dir path in should_run_files.
    """
    if not project:
        return False
    if project == 'platform/frameworks/base':
        return should_run_files(files)
    # Default case, run for all other projects which calls this script.
    return True


def should_run_files(files):
    """Returns a boolean if this check should run with these files."""
    if not files:
        return False
    if 'packages/SystemUI' in files:
        return True
    return False


if __name__ == '__main__':
    main()
