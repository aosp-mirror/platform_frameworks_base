#!/usr/bin/env python

#
# Copyright (C) 2024 The Android Open Source Project
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
#

"""Build commandline arguments."""

import argparse
import dataclasses
from typing import Callable

from alias_builder import Alias
from alias_builder import parse_aliases_from_json
from fallback_builder import FallbackEntry
from fallback_builder import parse_fallback_from_json
from family_builder import Family
from family_builder import parse_families_from_json


@dataclasses.dataclass
class CommandlineArgs:
  outfile: str
  fallback: [FallbackEntry]
  aliases: [Alias]
  families: [Family]


def _create_argument_parser() -> argparse.ArgumentParser:
  """Create argument parser."""
  parser = argparse.ArgumentParser()
  parser.add_argument('-o', '--output')
  parser.add_argument('--alias')
  parser.add_argument('--fallback')
  return parser


def _fileread(path: str) -> str:
  with open(path, 'r') as f:
    return f.read()


def parse_commandline(
    args: [str], fileread: Callable[str, str] = _fileread
) -> CommandlineArgs:
  """Parses command line arguments and returns CommandlineArg."""
  parser = _create_argument_parser()
  args, inputs = parser.parse_known_args(args)

  families = []
  for i in inputs:
    families = families + parse_families_from_json(fileread(i))

  return CommandlineArgs(
      outfile=args.output,
      fallback=parse_fallback_from_json(fileread(args.fallback)),
      aliases=parse_aliases_from_json(fileread(args.alias)),
      families=families,
  )
