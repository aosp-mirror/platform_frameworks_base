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
#

"""
Unit tests for trace_analyzer module.

Install:
  $> sudo apt-get install python3-pytest   ##  OR
  $> pip install -U pytest
See also https://docs.pytest.org/en/latest/getting-started.html

Usage:
  $> pytest trace_analyzer_test.py

See also https://docs.pytest.org/en/latest/usage.html
"""

# global imports
import os
import sys

DIR = os.path.abspath(os.path.dirname(__file__))

sys.path.append(os.path.dirname(DIR))
import lib.cmd_utils as cmd_utils

def test_trace_analyzer(tmpdir):
  # Setup
  bin = os.path.join(DIR, 'trace_analyzer')
  systrace = os.path.join(DIR, 'test_fixtures/common_systrace')
  db_file = tmpdir.mkdir('trace_analyzer').join('test.db')

  # Act
  passed, output = cmd_utils.execute_arbitrary_command([bin, systrace,
                                                        str(db_file)],
                                                       timeout=300,
                                                       shell=False,
                                                       simulate=False)

  # Assert
  assert passed
  assert output == """\
'blocked_iowait_duration_ms',\
'process_name',\
'launching_duration_ms',\
'launching_started_timestamp_ms',\
'launching_finished_timestamp_ms'
81.697999999960302375,\
'com.google.android.dialer',\
594.99400000095192808,\
14594219.85600000061,\
14594814.85000000149"""
