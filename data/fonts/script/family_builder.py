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

"""Build Family instance with validating JSON contents."""

import dataclasses

from custom_json import _load_json_with_comment
from font_builder import Font
from font_builder import parse_fonts
from validators import check_enum_or_none
from validators import check_priority_or_none
from validators import check_str_or_none

_FAMILY_KEYS = set([
    "id",
    "lang",
    "name",
    "variant",
    "fallbackFor",
    "fonts",
    "target",
    "priority",
])


@dataclasses.dataclass
class Family:
  id: str | None
  lang: str | None
  name: str | None
  priority: int | None
  variant: str | None
  fallback_for: str | None
  target: str | None
  fonts: [Font]


def _validate_family(family):
  assert not family.lang or not family.name, (
      "If lang attribute is specified, name attribute must not be specified: %s"
      % family
  )

  if family.fallback_for:
    assert family.target, (
        "If fallbackFor is specified, must specify target: %s" % family
    )
  if family.target:
    assert family.fallback_for, (
        "If target is specified, must specify fallbackFor: %s" % family
    )


def _parse_family(obj, for_sanitization_test=False) -> Family:
  """Create Family object from dictionary."""
  unknown_keys = obj.keys() - _FAMILY_KEYS
  assert not unknown_keys, "Unknown keys found: %s in %s" % (unknown_keys, obj)

  if for_sanitization_test:
    fonts = []
  else:
    fonts = parse_fonts(obj.get("fonts"))

  family = Family(
      id=check_str_or_none(obj, "id"),
      lang=check_str_or_none(obj, "lang"),
      name=check_str_or_none(obj, "name"),
      priority=check_priority_or_none(obj, "priority"),
      variant=check_enum_or_none(obj, "variant", ["elegant", "compact"]),
      fallback_for=check_str_or_none(obj, "fallbackFor"),
      target=check_str_or_none(obj, "target"),
      fonts=fonts,
  )

  if not for_sanitization_test:
    _validate_family(family)
  return family


def parse_family_from_json_for_sanitization_test(json_str) -> Family:
  """For testing purposes."""
  return _parse_family(
      _load_json_with_comment(json_str), for_sanitization_test=True
  )


def parse_family_from_json(json_str) -> Family:
  """For testing purposes."""
  return _parse_family(_load_json_with_comment(json_str))


def parse_families_from_json(json_str) -> [Family]:
  objs = _load_json_with_comment(json_str)
  assert isinstance(objs, list), "families must be list"
  assert objs, "families must contains at least one family"
  return [_parse_family(obj) for obj in objs]
