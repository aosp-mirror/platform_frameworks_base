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

"""Build Fallback instance with validating JSON contents."""

import dataclasses

from custom_json import _load_json_with_comment
from validators import check_str_or_none


@dataclasses.dataclass
class FallbackEntry:
  lang: str | None
  id: str | None


_FALLBACK_KEYS = set(["lang", "id"])


def _parse_entry(obj) -> FallbackEntry:
  """Convert given dict object to FallbackEntry instance."""
  unknown_keys = obj.keys() - _FALLBACK_KEYS
  assert not unknown_keys, "Unknown keys found: %s" % unknown_keys
  entry = FallbackEntry(
      lang=check_str_or_none(obj, "lang"),
      id=check_str_or_none(obj, "id"),
  )

  assert entry.lang or entry.id, "lang or id must be specified."
  assert (
      not entry.lang or not entry.id
  ), "lang and id must not be specified at the same time"

  return entry


def parse_fallback(objs) -> [FallbackEntry]:
  assert isinstance(objs, list), "fallback must be list"
  assert objs, "at least one etnry must be specified"
  return [_parse_entry(obj) for obj in objs]


def parse_fallback_from_json(json_str) -> [FallbackEntry]:
  """For testing purposes."""
  return parse_fallback(_load_json_with_comment(json_str))
