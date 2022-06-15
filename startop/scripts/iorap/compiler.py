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

import importlib
import os
import sys
import tempfile
from enum import Enum
from typing import TextIO, List

# local import
DIR = os.path.abspath(os.path.dirname(__file__))
sys.path.append(os.path.dirname(DIR))
import lib.print_utils as print_utils

# Type of compiler.
class CompilerType(Enum):
  HOST = 1  # iorap.cmd.compiler on host
  DEVICE = 2  # adb shell iorap.cmd.compiler
  RI = 3  # compiler.py

def compile_perfetto_trace_ri(
    argv: List[str],
    compiler) -> TextIO:
  print_utils.debug_print('Compile using RI compiler.')
  compiler_trace_file = tempfile.NamedTemporaryFile()
  argv.extend(['-o', compiler_trace_file.name])
  print_utils.debug_print(argv)
  compiler.main([''] + argv)
  return compiler_trace_file

def compile_perfetto_trace_device(inodes_path: str,
                                  package: str,
                                  activity: str,
                                  compiler) -> TextIO:
  print_utils.debug_print('Compile using on-device compiler.')
  compiler_trace_file = tempfile.NamedTemporaryFile()
  compiler.main(inodes_path, package, activity, compiler_trace_file.name)
  return compiler_trace_file

def compile(compiler_type: CompilerType,
            inodes_path: str,
            ri_compiler_argv,
            package: str,
            activity: str) -> TextIO:
  if compiler_type == CompilerType.RI:
    compiler = importlib.import_module('iorap.compiler_ri')
    compiler_trace_file = compile_perfetto_trace_ri(ri_compiler_argv,
                                                    compiler)
    return compiler_trace_file
  if compiler_type == CompilerType.DEVICE:
    compiler = importlib.import_module('iorap.compiler_device')
    compiler_trace_file = compile_perfetto_trace_device(inodes_path,
                                                        package,
                                                        activity,
                                                        compiler)
    return compiler_trace_file

  # Should not arrive here.
  raise ValueError('Unknown compiler type')
