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
"""Unit tests for the logcat_utils.py script."""

import asyncio
import datetime
import re

import logcat_utils
from mock import MagicMock, patch

def test_parse_logcat_datatime():
  # Act
  result = logcat_utils.parse_logcat_datetime('2019-07-01 16:13:55.221')

  # Assert
  assert result == datetime.datetime(2019, 7, 1, 16, 13, 55, 221000)

class AsyncMock(MagicMock):
  async def __call__(self, *args, **kwargs):
    return super(AsyncMock, self).__call__(*args, **kwargs)

def _async_return():
  f = asyncio.Future()
  f.set_result(
      b'2019-07-01 15:51:53.290 27365 27392 I ActivityTaskManager: '
      b'Displayed com.google.android.music/com.android.music.activitymanagement.'
      b'TopLevelActivity: +1s7ms')
  return f

def test_parse_displayed_time_succeed():
  # Act
  with patch('asyncio.create_subprocess_exec',
             new_callable=AsyncMock) as asyncio_mock:
    asyncio_mock.return_value.stdout.readline = _async_return
    timestamp = datetime.datetime(datetime.datetime.now().year, 7, 1, 16, 13,
                                  55, 221000)
    timeout_dt = timestamp + datetime.timedelta(0, 10)
    pattern = re.compile('.*ActivityTaskManager: Displayed '
                         'com.google.android.music/com.android.music.*')
    result = logcat_utils.blocking_wait_for_logcat_pattern(timestamp,
                                                           pattern,
                                                           timeout_dt)

    # Assert
    assert result == '2019-07-01 15:51:53.290 27365 27392 I ' \
                     'ActivityTaskManager: ' \
                     'Displayed com.google.android.music/com.android.music.' \
                     'activitymanagement.TopLevelActivity: +1s7ms'

def _async_timeout_return():
  f = asyncio.Future()
  f.set_result(
      b'2019-07-01 17:51:53.290 27365 27392 I ActivityTaskManager: '
      b'Displayed com.google.android.music/com.android.music.activitymanagement.'
      b'TopLevelActivity: +1s7ms')
  return f

def test_parse_displayed_time_timeout():
  # Act
  with patch('asyncio.create_subprocess_exec',
             new_callable=AsyncMock) as asyncio_mock:
    asyncio_mock.return_value.stdout.readline = _async_timeout_return
    timestamp = datetime.datetime(datetime.datetime.now().year,
                                  7, 1, 16, 13, 55, 221000)
    timeout_dt = timestamp + datetime.timedelta(0, 10)
    pattern = re.compile('.*ActivityTaskManager: Displayed '
                         'com.google.android.music/com.android.music.*')
    result = logcat_utils.blocking_wait_for_logcat_pattern(timestamp,
                                                           pattern,
                                                           timeout_dt)

    # Assert
    assert result == None
