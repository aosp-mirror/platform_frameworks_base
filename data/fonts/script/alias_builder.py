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

"""Build Alias instance with validating JSON contents."""

import dataclasses

from custom_json import _load_json_with_comment
from validators import check_str
from validators import check_weight_or_none


@dataclasses.dataclass
class Alias:
  name: str
  to: str
  weight: int | None


_ALIAS_KEYS = set(["name", "to", "weight"])


def parse_alias(obj) -> Alias:
  """Convert given dict object to Alias instance."""
  unknown_keys = obj.keys() - _ALIAS_KEYS
  assert not unknown_keys, "Unknown keys found: %s" % unknown_keys
  alias = Alias(
      name=check_str(obj, "name"),
      to=check_str(obj, "to"),
      weight=check_weight_or_none(obj, "weight"),
  )

  assert alias.name != alias.to, "name and to must not be equal"

  return alias


def parse_alias_from_json(json_str) -> Alias:
  """For testing purposes."""
  return parse_alias(_load_json_with_comment(json_str))


def parse_aliases(objs) -> [Alias]:
  assert isinstance(objs, list), "aliases must be list"
  return [parse_alias(obj) for obj in objs]


def parse_aliases_from_json(json_str) -> [Alias]:
  return parse_aliases(_load_json_with_comment(json_str))
