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
