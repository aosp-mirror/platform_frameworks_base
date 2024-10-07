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

"""Build Font instance with validating JSON contents."""

import dataclasses

from custom_json import _load_json_with_comment
from validators import check_enum_or_none
from validators import check_float
from validators import check_int_or_none
from validators import check_str
from validators import check_str_or_none
from validators import check_tag
from validators import check_weight_or_none


@dataclasses.dataclass
class Font:
  file: str
  weight: int | None
  style: str | None
  index: int | None
  supported_axes: str | None
  post_script_name: str | None
  axes: dict[str | float]


_FONT_KEYS = set([
    "file",
    "weight",
    "style",
    "index",
    "supportedAxes",
    "postScriptName",
    "axes",
])


def _check_axes(axes) -> dict[str | float] | None:
  """Sanitize the variation axes."""
  if axes is None:
    return None
  assert isinstance(axes, dict), "axes must be dict"

  sanitized = {}
  for key in axes.keys():
    sanitized[check_tag(key)] = check_float(axes, key)

  return sanitized


def _parse_font(obj, for_sanitization_test=False) -> Font:
  """Convert given dict object to Font instance."""
  unknown_keys = obj.keys() - _FONT_KEYS
  assert not unknown_keys, "Unknown keys found: %s" % unknown_keys
  font = Font(
      file=check_str(obj, "file"),
      weight=check_weight_or_none(obj, "weight"),
      style=check_enum_or_none(obj, "style", ["normal", "italic"]),
      index=check_int_or_none(obj, "index"),
      supported_axes=check_enum_or_none(
          obj, "supportedAxes", ["wght", "wght,ital"]
      ),
      post_script_name=check_str_or_none(obj, "postScriptName"),
      axes=_check_axes(obj.get("axes")),
  )

  if not for_sanitization_test:
    assert font.file, "file must be specified"
    if not font.supported_axes:
      assert font.weight, (
          "If supported_axes is not specified, weight should be specified: %s"
          % obj
      )
      assert font.style, (
          "If supported_axes is not specified, style should be specified: %s"
          % obj
      )

  return font


def parse_fonts(objs) -> Font:
  assert isinstance(objs, list), "fonts must be list: %s" % (objs)
  assert objs, "At least one font should be added."
  return [_parse_font(obj) for obj in objs]


def parse_font_from_json_for_sanitization_test(json_str: str) -> Font:
  """For testing purposes."""
  return _parse_font(
      _load_json_with_comment(json_str), for_sanitization_test=False
  )


def parse_fonts_from_json_for_validation_test(json_str: str) -> [Font]:
  """For testing purposes."""
  return parse_fonts(_load_json_with_comment(json_str))
