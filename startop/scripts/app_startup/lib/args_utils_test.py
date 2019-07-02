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

"""Unit tests for the args_utils.py script."""

import typing

import args_utils

def generate_run_combinations(*args):
  # expand out the generator values so that assert x == y works properly.
  return [i for i in args_utils.generate_run_combinations(*args)]

def test_generate_run_combinations():
  blank_nd = typing.NamedTuple('Blank')
  assert generate_run_combinations(blank_nd, {}, 1) == [()], "empty"
  assert generate_run_combinations(blank_nd, {'a': ['a1', 'a2']}) == [
    ()], "empty filter"
  a_nd = typing.NamedTuple('A', [('a', str)])
  assert generate_run_combinations(a_nd, {'a': None}) == [(None,)], "None"
  assert generate_run_combinations(a_nd, {'a': ['a1', 'a2']}) == [('a1',), (
    'a2',)], "one item"
  assert generate_run_combinations(a_nd,
                                   {'a': ['a1', 'a2'], 'b': ['b1', 'b2']}) == [
           ('a1',), ('a2',)], \
    "one item filter"
  assert generate_run_combinations(a_nd, {'a': ['a1', 'a2']}, 2) == [('a1',), (
    'a2',), ('a1',), ('a2',)], "one item"
  ab_nd = typing.NamedTuple('AB', [('a', str), ('b', str)])
  assert generate_run_combinations(ab_nd,
                                   {'a': ['a1', 'a2'],
                                    'b': ['b1', 'b2']}) == [ab_nd('a1', 'b1'),
                                                            ab_nd('a1', 'b2'),
                                                            ab_nd('a2', 'b1'),
                                                            ab_nd('a2', 'b2')], \
    "two items"

  assert generate_run_combinations(ab_nd,
                                   {'as': ['a1', 'a2'],
                                    'bs': ['b1', 'b2']}) == [ab_nd('a1', 'b1'),
                                                             ab_nd('a1', 'b2'),
                                                             ab_nd('a2', 'b1'),
                                                             ab_nd('a2', 'b2')], \
    "two items plural"
