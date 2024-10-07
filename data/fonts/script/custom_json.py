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

"""A custom json parser that additionally supports line comments."""

import json
import re

# RegEx of removing line comment line in JSON.
_LINE_COMMENT_RE = re.compile(r'\/\/[^\n\r]*[\n\r]')


def _load_json_with_comment(json_str: str):
  """Parse JSON string with accepting line comment."""
  raw_text = re.sub(_LINE_COMMENT_RE, '', json_str)
  return json.loads(raw_text)
