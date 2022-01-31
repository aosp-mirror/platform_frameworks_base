#!/usr/bin/env python3
#
# Copyright 2018, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
#
# Query the current compiler filter for an application by its package name.
# (By parsing the results of the 'adb shell dumpsys package $package' command).
# The output is a string "$compilation_filter $compilation_reason $isa".
#
# See --help for more details.
#
# -----------------------------------
#
# Sample usage:
#
# $> ./query_compiler_filter.py --package com.google.android.calculator
# speed-profile unknown arm64
#

import argparse
import os
import re
import sys

# TODO: refactor this with a common library file with analyze_metrics.py
DIR = os.path.abspath(os.path.dirname(__file__))
sys.path.append(os.path.dirname(DIR))
import lib.cmd_utils as cmd_utils
import lib.print_utils as print_utils

from typing import List, NamedTuple, Iterable

_DEBUG_FORCE = None  # Ignore -d/--debug if this is not none.

def parse_options(argv: List[str] = None):
  """Parse command line arguments and return an argparse Namespace object."""
  parser = argparse.ArgumentParser(description="Query the compiler filter for a package.")
  # argparse considers args starting with - and -- optional in --help, even though required=True.
  # by using a named argument group --help will clearly say that it's required instead of optional.
  required_named = parser.add_argument_group('required named arguments')
  required_named.add_argument('-p', '--package', action='store', dest='package', help='package of the application', required=True)

  # optional arguments
  # use a group here to get the required arguments to appear 'above' the optional arguments in help.
  optional_named = parser.add_argument_group('optional named arguments')
  optional_named.add_argument('-i', '--isa', '--instruction-set', action='store', dest='instruction_set', help='which instruction set to select. defaults to the first one available if not specified.', choices=('arm64', 'arm', 'x86_64', 'x86'))
  optional_named.add_argument('-s', '--simulate', dest='simulate', action='store_true', help='Print which commands will run, but don\'t run the apps')
  optional_named.add_argument('-d', '--debug', dest='debug', action='store_true', help='Add extra debugging output')

  return parser.parse_args(argv)

def remote_dumpsys_package(package: str, simulate: bool) -> str:
  # --simulate is used for interactive debugging/development, but also for the unit test.
  if simulate:
    return """
Dexopt state:
  [%s]
    path: /data/app/%s-D7s8PLidqqEq7Jc7UH_a5A==/base.apk
      arm64: [status=speed-profile] [reason=unknown]
    path: /data/app/%s-D7s8PLidqqEq7Jc7UH_a5A==/base.apk
      arm: [status=speed] [reason=first-boot]
    path: /data/app/%s-D7s8PLidqqEq7Jc7UH_a5A==/base.apk
      x86: [status=quicken] [reason=install]
""" %(package, package, package, package)

  code, res = cmd_utils.execute_arbitrary_command(['adb', 'shell', 'dumpsys',
                                                   'package', package],
                                                  simulate=False,
                                                  timeout=5,
                                                  shell=False)
  if code:
    return res
  else:
    raise AssertionError("Failed to dumpsys package, errors = %s", res)

ParseTree = NamedTuple('ParseTree', [('label', str), ('children', List['ParseTree'])])
DexoptState = ParseTree # With the Dexopt state: label
ParseResult = NamedTuple('ParseResult', [('remainder', List[str]), ('tree', ParseTree)])

def find_parse_subtree(parse_tree: ParseTree, match_regex: str) -> ParseTree:
  if re.match(match_regex, parse_tree.label):
    return parse_tree

  for node in parse_tree.children:
    res = find_parse_subtree(node, match_regex)
    if res:
      return res

  return None

def find_parse_children(parse_tree: ParseTree, match_regex: str) -> Iterable[ParseTree]:
  for node in parse_tree.children:
    if re.match(match_regex, node.label):
      yield node

def parse_tab_subtree(label: str, str_lines: List[str], separator=' ', indent=-1) -> ParseResult:
  children = []

  get_indent_level = lambda line: len(line) - len(line.lstrip())

  line_num = 0

  keep_going = True
  while keep_going:
    keep_going = False

    for line_num in range(len(str_lines)):
      line = str_lines[line_num]
      current_indent = get_indent_level(line)

      print_utils.debug_print("INDENT=%d, LINE=%s" %(current_indent, line))

      current_label = line.lstrip()

      # skip empty lines
      if line.lstrip() == "":
        continue

      if current_indent > indent:
        parse_result = parse_tab_subtree(current_label, str_lines[line_num+1::], separator, current_indent)
        str_lines = parse_result.remainder
        children.append(parse_result.tree)
        keep_going = True
      else:
        # current_indent <= indent
        keep_going = False

      break

  new_remainder = str_lines[line_num::]
  print_utils.debug_print("NEW REMAINDER: ", new_remainder)

  parse_tree = ParseTree(label, children)
  return ParseResult(new_remainder, parse_tree)

def parse_tab_tree(str_tree: str, separator=' ', indentation_level=-1) -> ParseTree:

  label = None
  lst = []

  line_num = 0
  line_lst = str_tree.split("\n")

  return parse_tab_subtree("", line_lst, separator, indentation_level).tree

def parse_dexopt_state(dumpsys_tree: ParseTree) -> DexoptState:
  res = find_parse_subtree(dumpsys_tree, "Dexopt(\s+)state[:]?")
  if not res:
    raise AssertionError("Could not find the Dexopt state")
  return res

def find_first_compiler_filter(dexopt_state: DexoptState, package: str, instruction_set: str) -> str:
  lst = find_all_compiler_filters(dexopt_state, package)

  print_utils.debug_print("all compiler filters: ", lst)

  for compiler_filter_info in lst:
    if not instruction_set:
      return compiler_filter_info

    if compiler_filter_info.isa == instruction_set:
      return compiler_filter_info

  return None

CompilerFilterInfo = NamedTuple('CompilerFilterInfo', [('isa', str), ('status', str), ('reason', str)])

def find_all_compiler_filters(dexopt_state: DexoptState, package: str) -> List[CompilerFilterInfo]:

  lst = []
  package_tree = find_parse_subtree(dexopt_state, re.escape("[%s]" %package))

  if not package_tree:
    raise AssertionError("Could not find any package subtree for package %s" %(package))

  print_utils.debug_print("package tree: ", package_tree)

  for path_tree in find_parse_children(package_tree, "path: "):
    print_utils.debug_print("path tree: ", path_tree)

    matchre = re.compile("([^:]+):\s+\[status=([^\]]+)\]\s+\[reason=([^\]]+)\]")

    for isa_node in find_parse_children(path_tree, matchre):

      matches = re.match(matchre, isa_node.label).groups()

      info = CompilerFilterInfo(*matches)
      lst.append(info)

  return lst

def main() -> int:
  opts = parse_options()
  cmd_utils._debug = opts.debug
  if _DEBUG_FORCE is not None:
    cmd_utils._debug = _DEBUG_FORCE
  print_utils.debug_print("parsed options: ", opts)

  # Note: This can often 'fail' if the package isn't actually installed.
  package_dumpsys = remote_dumpsys_package(opts.package, opts.simulate)
  print_utils.debug_print("package dumpsys: ", package_dumpsys)
  dumpsys_parse_tree = parse_tab_tree(package_dumpsys, package_dumpsys)
  print_utils.debug_print("parse tree: ", dumpsys_parse_tree)
  dexopt_state = parse_dexopt_state(dumpsys_parse_tree)

  filter = find_first_compiler_filter(dexopt_state, opts.package, opts.instruction_set)

  if filter:
    print(filter.status, end=' ')
    print(filter.reason, end=' ')
    print(filter.isa)
  else:
    print("ERROR: Could not find any compiler-filter for package %s, isa %s" %(opts.package, opts.instruction_set), file=sys.stderr)
    return 1

  return 0

if __name__ == '__main__':
  sys.exit(main())
