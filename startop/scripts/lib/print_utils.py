#!/usr/bin/env python3
#
# Copyright 2019, The Android Open Source Project
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

"""Helper util libraries for debug printing."""

import sys

DEBUG = False

def debug_print(*args, **kwargs):
  """Prints the args to sys.stderr if the DEBUG is set."""
  if DEBUG:
    print(*args, **kwargs, file=sys.stderr)

def error_print(*args, **kwargs):
  print('[ERROR]:', *args, file=sys.stderr, **kwargs)

def _expand_gen_repr(args):
  """Like repr but any generator-like object has its iterator consumed
  and then called repr on."""
  new_args_list = []
  for i in args:
    # detect iterable objects that do not have their own override of __str__
    if hasattr(i, '__iter__'):
      to_str = getattr(i, '__str__')
      if to_str.__objclass__ == object:
        # the repr for a generator is just type+address, expand it out instead.
        new_args_list.append([_expand_gen_repr([j])[0] for j in i])
        continue
    # normal case: uses the built-in to-string
    new_args_list.append(i)
  return new_args_list

def debug_print_gen(*args, **kwargs):
  """Like _debug_print but will turn any iterable args into a list."""
  if not DEBUG:
    return

  new_args_list = _expand_gen_repr(args)
  debug_print(*new_args_list, **kwargs)

def debug_print_nd(*args, **kwargs):
  """Like _debug_print but will turn any NamedTuple-type args into a string."""
  if not DEBUG:
    return

  new_args_list = []
  for i in args:
    if hasattr(i, '_field_types'):
      new_args_list.append("%s: %s" % (i.__name__, i._field_types))
    else:
      new_args_list.append(i)

  debug_print(*new_args_list, **kwargs)
