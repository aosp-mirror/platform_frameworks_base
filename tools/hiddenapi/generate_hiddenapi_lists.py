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
"""
Generate API lists for non-SDK API enforcement.

usage: generate-hiddenapi-lists.py [-h]
                                   --input-public INPUT_PUBLIC
                                   --input-private INPUT_PRIVATE
                                   [--input-whitelists [INPUT_WHITELISTS [INPUT_WHITELISTS ...]]]
                                   [--input-greylists [INPUT_GREYLISTS [INPUT_GREYLISTS ...]]]
                                   [--input-blacklists [INPUT_BLACKLISTS [INPUT_BLACKLISTS ...]]]
                                   --output-whitelist OUTPUT_WHITELIST
                                   --output-light-greylist OUTPUT_LIGHT_GREYLIST
                                   --output-dark-greylist OUTPUT_DARK_GREYLIST
                                   --output-blacklist OUTPUT_BLACKLIST
"""
import argparse
import os
import sys
import re

def get_args():
    """Parses command line arguments.

    Returns:
        Namespace: dictionary of parsed arguments
    """
    parser = argparse.ArgumentParser()
    parser.add_argument('--input-public', required=True, help='List of all public members')
    parser.add_argument('--input-private', required=True, help='List of all private members')
    parser.add_argument(
        '--input-whitelists', nargs='*',
        help='Lists of members to force on whitelist')
    parser.add_argument(
        '--input-light-greylists', nargs='*',
        help='Lists of members to force on light greylist')
    parser.add_argument(
        '--input-dark-greylists', nargs='*',
        help='Lists of members to force on dark greylist')
    parser.add_argument(
        '--input-blacklists', nargs='*',
        help='Lists of members to force on blacklist')
    parser.add_argument('--output-whitelist', required=True)
    parser.add_argument('--output-light-greylist', required=True)
    parser.add_argument('--output-dark-greylist', required=True)
    parser.add_argument('--output-blacklist', required=True)
    return parser.parse_args()

def read_lines(filename):
    """Reads entire file and return it as a list of lines.

    Lines which begin with a hash are ignored.

    Args:
        filename (string): Path to the file to read from.

    Returns:
        list: Lines of the loaded file as a list of strings.
    """
    with open(filename, 'r') as f:
        return filter(lambda line: not line.startswith('#'), f.readlines())

def write_lines(filename, lines):
    """Writes list of lines into a file, overwriting the file it it exists.

    Args:
        filename (string): Path to the file to be writting into.
        lines (list): List of strings to write into the file.
    """
    with open(filename, 'w') as f:
        f.writelines(lines)

def move_between_sets(subset, src, dst, source = "<unknown>", ignoreMissing = False):
    """Removes a subset of elements from one set and add it to another.

    Args:
        subset (set): The subset of `src` to be moved from `src` to `dst`.
        src (set): Source set. Must be a superset of `subset`.
        dst (set): Destination set. Must be disjoint with `subset`.
        source (string): Name of the data source.
        ignoreMissing (bool): If true, do not check whether `src` is a superset of `subset`.
    """
    if ignoreMissing:
        # Some entries in `subset` may not be in `src`. Remove such entries first.
        subset.intersection_update(src)
    else:
        assert src.issuperset(subset), (
            "Error processing: {}\n"
            "The following entries were not found:\n"
            "{}"
            "Please visit go/hiddenapi for more information.").format(
                source, "".join(map(lambda x: "  " + str(x), subset.difference(src))))
        assert dst.isdisjoint(subset)
    # Order matters if `src` and `subset` are the same object.
    dst.update(subset)
    src.difference_update(subset)

def move_all(src, dst):
    """Moves all elements of one set to another.

    Args:
        src (set): Source set. Will become empty.
        dst (set): Destination set. Will contain all elements of `src`.
    """
    move_between_sets(src, src, dst)

def move_from_files(filenames, src, dst, ignoreMissing = False):
    """Loads member signatures from a list of files and moves them to a given set.

    Opens files in `filenames`, reads all their lines and moves those from `src`
    set to `dst` set.

    Args:
        filenames (list): List of paths to files to be loaded.
        src (set): Set that loaded lines should be moved from.
        dst (set): Set that loaded lines should be moved to.
    """
    if filenames:
        for filename in filenames:
            move_between_sets(set(read_lines(filename)), src, dst, filename, ignoreMissing)

def move_serialization(src, dst):
    """Moves all members matching serialization API signatures between given sets.

    Args:
        src (set): Set that will be searched for serialization API and that API
                   will be removed from it.
        dst (set): Set that serialization API will be moved to.
    """
    serialization_patterns = [
        r'readObject\(Ljava/io/ObjectInputStream;\)V',
        r'readObjectNoData\(\)V',
        r'readResolve\(\)Ljava/lang/Object;',
        r'serialVersionUID:J',
        r'serialPersistentFields:\[Ljava/io/ObjectStreamField;',
        r'writeObject\(Ljava/io/ObjectOutputStream;\)V',
        r'writeReplace\(\)Ljava/lang/Object;',
    ]
    regex = re.compile(r'.*->(' + '|'.join(serialization_patterns) + r')$')
    move_between_sets(filter(lambda api: regex.match(api), src), src, dst)

def main(argv):
    args = get_args()

    # Initialize API sets by loading lists of public and private API. Public API
    # are all members resolvable from SDK API stubs, other members are private.
    # As an optimization, skip the step of moving public API from a full set of
    # members and start with a populated whitelist.
    whitelist = set(read_lines(args.input_public))
    uncategorized = set(read_lines(args.input_private))
    light_greylist = set()
    dark_greylist = set()
    blacklist = set()

    # Assert that there is no overlap between public and private API.
    assert whitelist.isdisjoint(uncategorized)
    num_all_api = len(whitelist) + len(uncategorized)

    # Read all files which manually assign members to specific lists.
    move_from_files(args.input_whitelists, uncategorized, whitelist)
    move_from_files(args.input_light_greylists, uncategorized, light_greylist)
    move_from_files(args.input_blacklists, uncategorized, blacklist)

    # Iterate over all uncategorized members and move serialization API to light greylist.
    move_serialization(uncategorized, light_greylist)

    # Read dark greylist inputs and move all entries to dark greylist.
    # Note that the input is the list from P, so we will ignore missing entries.
    move_from_files(args.input_dark_greylists, uncategorized, dark_greylist, ignoreMissing=True)

    # Assign all uncategorized members to the blacklist.
    move_all(uncategorized, blacklist)

    # Assert we have not missed anything.
    assert whitelist.isdisjoint(light_greylist)
    assert whitelist.isdisjoint(dark_greylist)
    assert whitelist.isdisjoint(blacklist)
    assert light_greylist.isdisjoint(dark_greylist)
    assert light_greylist.isdisjoint(blacklist)
    assert dark_greylist.isdisjoint(blacklist)
    assert num_all_api == len(whitelist) + len(light_greylist) + len(dark_greylist) + len(blacklist)

    # Write final lists to disk.
    write_lines(args.output_whitelist, whitelist)
    write_lines(args.output_light_greylist, light_greylist)
    write_lines(args.output_dark_greylist, dark_greylist)
    write_lines(args.output_blacklist, blacklist)

if __name__ == "__main__":
    main(sys.argv)
