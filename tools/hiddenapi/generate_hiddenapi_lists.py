#!/usr/bin/env python
#
# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Generate API lists for non-SDK API enforcement."""
import argparse
from collections import defaultdict, namedtuple
import functools
import os
import re
import sys

# Names of flags recognized by the `hiddenapi` tool.
FLAG_SDK = 'sdk'
FLAG_UNSUPPORTED = 'unsupported'
FLAG_BLOCKED = 'blocked'
FLAG_MAX_TARGET_O = 'max-target-o'
FLAG_MAX_TARGET_P = 'max-target-p'
FLAG_MAX_TARGET_Q = 'max-target-q'
FLAG_MAX_TARGET_R = 'max-target-r'
FLAG_CORE_PLATFORM_API = 'core-platform-api'
FLAG_PUBLIC_API = 'public-api'
FLAG_SYSTEM_API = 'system-api'
FLAG_TEST_API = 'test-api'

# List of all known flags.
FLAGS_API_LIST = [
    FLAG_SDK,
    FLAG_UNSUPPORTED,
    FLAG_BLOCKED,
    FLAG_MAX_TARGET_O,
    FLAG_MAX_TARGET_P,
    FLAG_MAX_TARGET_Q,
    FLAG_MAX_TARGET_R,
]
ALL_FLAGS = FLAGS_API_LIST + [
    FLAG_CORE_PLATFORM_API,
    FLAG_PUBLIC_API,
    FLAG_SYSTEM_API,
    FLAG_TEST_API,
]

FLAGS_API_LIST_SET = set(FLAGS_API_LIST)
ALL_FLAGS_SET = set(ALL_FLAGS)

# Option specified after one of FLAGS_API_LIST to indicate that
# only known and otherwise unassigned entries should be assign the
# given flag.
# For example, the max-target-P list is checked in as it was in P,
# but signatures have changes since then. The flag instructs this
# script to skip any entries which do not exist any more.
FLAG_IGNORE_CONFLICTS = "ignore-conflicts"

# Option specified after one of FLAGS_API_LIST to express that all
# apis within a given set of packages should be assign the given flag.
FLAG_PACKAGES = "packages"

# Option specified after one of FLAGS_API_LIST to indicate an extra
# tag that should be added to the matching APIs.
FLAG_TAG = "tag"

# Regex patterns of fields/methods used in serialization. These are
# considered public API despite being hidden.
SERIALIZATION_PATTERNS = [
    r'readObject\(Ljava/io/ObjectInputStream;\)V',
    r'readObjectNoData\(\)V',
    r'readResolve\(\)Ljava/lang/Object;',
    r'serialVersionUID:J',
    r'serialPersistentFields:\[Ljava/io/ObjectStreamField;',
    r'writeObject\(Ljava/io/ObjectOutputStream;\)V',
    r'writeReplace\(\)Ljava/lang/Object;',
]

# Single regex used to match serialization API. It combines all the
# SERIALIZATION_PATTERNS into a single regular expression.
SERIALIZATION_REGEX = re.compile(r'.*->(' + '|'.join(SERIALIZATION_PATTERNS) + r')$')

# Predicates to be used with filter_apis.
HAS_NO_API_LIST_ASSIGNED = lambda api, flags: not FLAGS_API_LIST_SET.intersection(flags)
IS_SERIALIZATION = lambda api, flags: SERIALIZATION_REGEX.match(api)


class StoreOrderedOptions(argparse.Action):
    """An argparse action that stores a number of option arguments in the order that
    they were specified.
    """
    def __call__(self, parser, args, values, option_string = None):
        items = getattr(args, self.dest, None)
        if items is None:
            items = []
        items.append([option_string.lstrip('-'), values])
        setattr(args, self.dest, items)

def get_args():
    """Parses command line arguments.

    Returns:
        Namespace: dictionary of parsed arguments
    """
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--csv', nargs='*', default=[], metavar='CSV_FILE',
        help='CSV files to be merged into output')

    for flag in ALL_FLAGS:
        parser.add_argument('--' + flag, dest='ordered_flags', metavar='TXT_FILE',
            action=StoreOrderedOptions, help='lists of entries with flag "' + flag + '"')
    parser.add_argument('--' + FLAG_IGNORE_CONFLICTS, dest='ordered_flags', nargs=0,
        action=StoreOrderedOptions, help='Indicates that only known and otherwise unassigned '
        'entries should be assign the given flag. Must follow a list of entries and applies '
        'to the preceding such list.')
    parser.add_argument('--' + FLAG_PACKAGES, dest='ordered_flags', nargs=0,
        action=StoreOrderedOptions, help='Indicates that the previous list of entries '
        'is a list of packages. All members in those packages will be given the flag. '
        'Must follow a list of entries and applies to the preceding such list.')
    parser.add_argument('--' + FLAG_TAG, dest='ordered_flags', nargs=1,
        action=StoreOrderedOptions, help='Adds an extra tag to the previous list of entries. '
        'Must follow a list of entries and applies to the preceding such list.')

    return parser.parse_args()


def read_lines(filename):
    """Reads entire file and return it as a list of lines.

    Lines which begin with a hash are ignored.

    Args:
        filename (string): Path to the file to read from.

    Returns:
        Lines of the file as a list of string.
    """
    with open(filename, 'r') as f:
        lines = f.readlines();
    lines = filter(lambda line: not line.startswith('#'), lines)
    lines = map(lambda line: line.strip(), lines)
    return set(lines)


def write_lines(filename, lines):
    """Writes list of lines into a file, overwriting the file if it exists.

    Args:
        filename (string): Path to the file to be writing into.
        lines (list): List of strings to write into the file.
    """
    lines = map(lambda line: line + '\n', lines)
    with open(filename, 'w') as f:
        f.writelines(lines)


def extract_package(signature):
    """Extracts the package from a signature.

    Args:
        signature (string): JNI signature of a method or field.

    Returns:
        The package name of the class containing the field/method.
    """
    full_class_name = signature.split(";->")[0]
    # Example: Landroid/hardware/radio/V1_2/IRadio$Proxy
    if (full_class_name[0] != "L"):
        raise ValueError("Expected to start with 'L': %s" % full_class_name)
    full_class_name = full_class_name[1:]
    # If full_class_name doesn't contain '/', then package_name will be ''.
    package_name = full_class_name.rpartition("/")[0]
    return package_name.replace('/', '.')


class FlagsDict:
    def __init__(self):
        self._dict_keyset = set()
        self._dict = defaultdict(set)

    def _check_entries_set(self, keys_subset, source):
        assert isinstance(keys_subset, set)
        assert keys_subset.issubset(self._dict_keyset), (
            "Error: {} specifies signatures not present in code:\n"
            "{}"
            "Please visit go/hiddenapi for more information.").format(
                source, "".join(map(lambda x: "  " + str(x) + "\n", keys_subset - self._dict_keyset)))

    def _check_flags_set(self, flags_subset, source):
        assert isinstance(flags_subset, set)
        assert flags_subset.issubset(ALL_FLAGS_SET), (
            "Error processing: {}\n"
            "The following flags were not recognized: \n"
            "{}\n"
            "Please visit go/hiddenapi for more information.").format(
                source, "\n".join(flags_subset - ALL_FLAGS_SET))

    def filter_apis(self, filter_fn):
        """Returns APIs which match a given predicate.

        This is a helper function which allows to filter on both signatures (keys) and
        flags (values). The built-in filter() invokes the lambda only with dict's keys.

        Args:
            filter_fn : Function which takes two arguments (signature/flags) and returns a boolean.

        Returns:
            A set of APIs which match the predicate.
        """
        return set(filter(lambda x: filter_fn(x, self._dict[x]), self._dict_keyset))

    def get_valid_subset_of_unassigned_apis(self, api_subset):
        """Sanitizes a key set input to only include keys which exist in the dictionary
        and have not been assigned any API list flags.

        Args:
            entries_subset (set/list): Key set to be sanitized.

        Returns:
            Sanitized key set.
        """
        assert isinstance(api_subset, set)
        return api_subset.intersection(self.filter_apis(HAS_NO_API_LIST_ASSIGNED))

    def generate_csv(self):
        """Constructs CSV entries from a dictionary.

        Old versions of flags are used to generate the file.

        Returns:
            List of lines comprising a CSV file. See "parse_and_merge_csv" for format description.
        """
        lines = []
        for api in self._dict:
          flags = sorted(self._dict[api])
          lines.append(",".join([api] + flags))
        return sorted(lines)

    def parse_and_merge_csv(self, csv_lines, source = "<unknown>"):
        """Parses CSV entries and merges them into a given dictionary.

        The expected CSV format is:
            <api signature>,<flag1>,<flag2>,...,<flagN>

        Args:
            csv_lines (list of strings): Lines read from a CSV file.
            source (string): Origin of `csv_lines`. Will be printed in error messages.

        Throws:
            AssertionError if parsed flags are invalid.
        """
        # Split CSV lines into arrays of values.
        csv_values = [ line.split(',') for line in csv_lines ]

        # Update the full set of API signatures.
        self._dict_keyset.update([ csv[0] for csv in csv_values ])

        # Check that all flags are known.
        csv_flags = set()
        for csv in csv_values:
          csv_flags.update(csv[1:])
        self._check_flags_set(csv_flags, source)

        # Iterate over all CSV lines, find entry in dict and append flags to it.
        for csv in csv_values:
            flags = csv[1:]
            if (FLAG_PUBLIC_API in flags) or (FLAG_SYSTEM_API in flags):
                flags.append(FLAG_SDK)
            self._dict[csv[0]].update(flags)

    def assign_flag(self, flag, apis, source="<unknown>", tag = None):
        """Assigns a flag to given subset of entries.

        Args:
            flag (string): One of ALL_FLAGS.
            apis (set): Subset of APIs to receive the flag.
            source (string): Origin of `entries_subset`. Will be printed in error messages.

        Throws:
            AssertionError if parsed API signatures of flags are invalid.
        """
        # Check that all APIs exist in the dict.
        self._check_entries_set(apis, source)

        # Check that the flag is known.
        self._check_flags_set(set([ flag ]), source)

        # Iterate over the API subset, find each entry in dict and assign the flag to it.
        for api in apis:
            self._dict[api].add(flag)
            if tag:
                self._dict[api].add(tag)


FlagFile = namedtuple('FlagFile', ('flag', 'file', 'ignore_conflicts', 'packages', 'tag'))

def parse_ordered_flags(ordered_flags):
    r = []
    currentflag, file, ignore_conflicts, packages, tag = None, None, False, False, None
    for flag_value in ordered_flags:
        flag, value = flag_value[0], flag_value[1]
        if flag in ALL_FLAGS_SET:
            if currentflag:
                r.append(FlagFile(currentflag, file, ignore_conflicts, packages, tag))
                ignore_conflicts, packages, tag = False, False, None
            currentflag = flag
            file = value
        else:
            if currentflag is None:
                raise argparse.ArgumentError('--%s is only allowed after one of %s' % (
                    flag, ' '.join(['--%s' % f for f in ALL_FLAGS_SET])))
            if flag == FLAG_IGNORE_CONFLICTS:
                ignore_conflicts = True
            elif flag == FLAG_PACKAGES:
                packages = True
            elif flag == FLAG_TAG:
                tag = value[0]


    if currentflag:
        r.append(FlagFile(currentflag, file, ignore_conflicts, packages, tag))
    return r


def main(argv):
    # Parse arguments.
    args = vars(get_args())
    flagfiles = parse_ordered_flags(args['ordered_flags'])

    # Initialize API->flags dictionary.
    flags = FlagsDict()

    # Merge input CSV files into the dictionary.
    # Do this first because CSV files produced by parsing API stubs will
    # contain the full set of APIs. Subsequent additions from text files
    # will be able to detect invalid entries, and/or filter all as-yet
    # unassigned entries.
    for filename in args["csv"]:
        flags.parse_and_merge_csv(read_lines(filename), filename)

    # Combine inputs which do not require any particular order.
    # (1) Assign serialization API to SDK.
    flags.assign_flag(FLAG_SDK, flags.filter_apis(IS_SERIALIZATION))

    # (2) Merge text files with a known flag into the dictionary.
    for info in flagfiles:
        if (not info.ignore_conflicts) and (not info.packages):
            flags.assign_flag(info.flag, read_lines(info.file), info.file, info.tag)

    # Merge text files where conflicts should be ignored.
    # This will only assign the given flag if:
    # (a) the entry exists, and
    # (b) it has not been assigned any other flag.
    # Because of (b), this must run after all strict assignments have been performed.
    for info in flagfiles:
        if info.ignore_conflicts:
            valid_entries = flags.get_valid_subset_of_unassigned_apis(read_lines(info.file))
            flags.assign_flag(info.flag, valid_entries, filename, info.tag)

    # All members in the specified packages will be assigned the appropriate flag.
    for info in flagfiles:
        if info.packages:
            packages_needing_list = set(read_lines(info.file))
            should_add_signature_to_list = lambda sig,lists: extract_package(
                sig) in packages_needing_list and not lists
            valid_entries = flags.filter_apis(should_add_signature_to_list)
            flags.assign_flag(info.flag, valid_entries, info.file, info.tag)

    # Mark all remaining entries as blocked.
    flags.assign_flag(FLAG_BLOCKED, flags.filter_apis(HAS_NO_API_LIST_ASSIGNED))

    # Write output.
    write_lines(args["output"], flags.generate_csv())

if __name__ == "__main__":
    main(sys.argv)
