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

"""Helper util libraries for parsing logcat logs."""

import asyncio
import re
from datetime import datetime
from typing import Optional, Pattern

# local import
import lib.print_utils as print_utils

def parse_logcat_datetime(timestamp: str) -> Optional[datetime]:
  """Parses the timestamp of logcat.

  Params:
    timestamp: for example "2019-07-01 16:13:55.221".

  Returns:
    a datetime of timestamp with the year now.
  """
  try:
    # Match the format of logcat. For example: "2019-07-01 16:13:55.221",
    # because it doesn't have year, set current year to it.
    timestamp = datetime.strptime(timestamp,
                                  '%Y-%m-%d %H:%M:%S.%f')
    return timestamp
  except ValueError as ve:
    print_utils.debug_print('Invalid line: ' + timestamp)
    return None

def _is_time_out(timeout: datetime, line: str) -> bool:
  """Checks if the timestamp of this line exceeds the timeout.

  Returns:
    true if the timestamp exceeds the timeout.
  """
  # Get the timestampe string.
  cur_timestamp_str = ' '.join(re.split(r'\s+', line)[0:2])
  timestamp = parse_logcat_datetime(cur_timestamp_str)
  if not timestamp:
    return False

  return timestamp > timeout

async def _blocking_wait_for_logcat_pattern(timestamp: datetime,
                                            pattern: Pattern,
                                            timeout: datetime) -> Optional[str]:
  # Show the year in the timestampe.
  logcat_cmd = 'adb logcat -v UTC -v year -v threadtime -T'.split()
  logcat_cmd.append(str(timestamp))
  print_utils.debug_print('[LOGCAT]:' + ' '.join(logcat_cmd))

  # Create subprocess
  process = await asyncio.create_subprocess_exec(
      *logcat_cmd,
      # stdout must a pipe to be accessible as process.stdout
      stdout=asyncio.subprocess.PIPE)

  while (True):
    # Read one line of output.
    data = await process.stdout.readline()
    line = data.decode('utf-8').rstrip()

    # 2019-07-01 14:54:21.946 27365 27392 I ActivityTaskManager: Displayed
    # com.android.settings/.Settings: +927ms
    # TODO: Detect timeouts even when there is no logcat output.
    if _is_time_out(timeout, line):
      print_utils.debug_print('DID TIMEOUT BEFORE SEEING ANYTHING ('
                              'timeout={timeout} seconds << {pattern} '
                              '>>'.format(timeout=timeout, pattern=pattern))
      return None

    if pattern.match(line):
      print_utils.debug_print(
          'WE DID SEE PATTERN << "{}" >>.'.format(pattern))
      return line

def blocking_wait_for_logcat_pattern(timestamp: datetime,
                                     pattern: Pattern,
                                     timeout: datetime) -> Optional[str]:
  """Selects the line that matches the pattern and within the timeout.

  Returns:
    the line that matches the pattern and within the timeout.
  """
  loop = asyncio.get_event_loop()
  result = loop.run_until_complete(
      _blocking_wait_for_logcat_pattern(timestamp, pattern, timeout))
  return result
