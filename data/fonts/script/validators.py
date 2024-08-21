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

"""Validators commonly used."""


def check_str_or_none(d, key: str) -> str | None:
  value = d.get(key)
  assert value is None or isinstance(value, str), (
      "%s type must be str or None." % key
  )
  return value


def check_str(d, key: str) -> str:
  value = d.get(key)
  assert isinstance(value, str), "%s type must be str." % key
  return value


def check_int_or_none(d, key: str) -> int | None:
  """Chcek if the given value of key in dict is int or None."""
  value = d.get(key)
  if value is None:
    return None
  elif isinstance(value, int):
    return value
  elif isinstance(value, str):
    try:
      return int(value)
    except ValueError as e:
      raise AssertionError() from e
  else:
    raise AssertionError("%s type must be int or str or None." % key)


def check_float(d, key: str) -> float:
  """Chcek if the given value of key in dict is float."""
  value = d.get(key)
  if isinstance(value, float):
    return value
  elif isinstance(value, int):
    return float(value)
  elif isinstance(value, str):
    try:
      return float(value)
    except ValueError as e:
      raise AssertionError() from e
  else:
    raise AssertionError("Float value is expeted but it is %s" % key)


def check_weight_or_none(d, key: str) -> int | None:
  value = check_int_or_none(d, key)

  assert value is None or (
      value >= 0 and value <= 1000
  ), "weight must be larger than 0 and lower than 1000."
  return value


def check_priority_or_none(d, key: str) -> int | None:
  value = check_int_or_none(d, key)

  assert value is None or (
      value >= -100 and value <= 100
  ), "priority must be between -100 (highest) to 100 (lowest)"
  return value


def check_enum_or_none(d, key: str, enum: [str]) -> str | None:
  value = check_str_or_none(d, key)

  assert value is None or value in enum, "%s must be None or one of %s" % (
      key,
      enum,
  )
  return value


def check_tag(value) -> str:
  if len(value) != 4 or not value.isascii():
    raise AssertionError("OpenType tag must be 4 ASCII letters: %s" % value)
  return value
