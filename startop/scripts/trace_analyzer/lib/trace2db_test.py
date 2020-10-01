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
Unit tests for inode2filename module.

Install:
  $> sudo apt-get install python3-pytest   ##  OR
  $> pip install -U pytest
See also https://docs.pytest.org/en/latest/getting-started.html

Usage:
  $> ./inode2filename_test.py
  $> pytest inode2filename_test.py
  $> python -m pytest inode2filename_test.py

See also https://docs.pytest.org/en/latest/usage.html
"""

# global imports
import io
from copy import deepcopy

# pip imports
# local imports
from trace2db import *

# This pretty-prints the raw dictionary of the sqlalchemy object if it fails.
class EqualsSqlAlchemyObject:
  # For convenience to write shorter tests, we also add 'ignore_fields' which allow us to specify
  # which fields to ignore when doing the comparison.
  def __init__(self_, self, ignore_fields=[]):
    self_.self = self
    self_.ignore_fields = ignore_fields

  # Do field-by-field comparison.
  # It seems that SQLAlchemy does not implement __eq__ itself so we have to do it ourselves.
  def __eq__(self_, other):
    if isinstance(other, EqualsSqlAlchemyObject):
      other = other.self

    self = self_.self

    classes_match = isinstance(other, self.__class__)
    a, b = deepcopy(self.__dict__), deepcopy(other.__dict__)

    #compare based on equality our attributes, ignoring SQLAlchemy internal stuff

    a.pop('_sa_instance_state', None)
    b.pop('_sa_instance_state', None)

    for f in self_.ignore_fields:
      a.pop(f, None)
      b.pop(f, None)

    attrs_match = (a == b)
    return classes_match and attrs_match

  def __repr__(self):
    return repr(self.self.__dict__)


def assert_eq_ignore_id(left, right):
  # This pretty-prints the raw dictionary of the sqlalchemy object if it fails.
  # It does field-by-field comparison, but ignores the 'id' field.
  assert EqualsSqlAlchemyObject(left, ignore_fields=['id']) == EqualsSqlAlchemyObject(right)

def parse_trace_file_to_db(*contents):
  """
  Make temporary in-memory sqlite3 database by parsing the string contents as a trace.

  :return: Trace2Db instance
  """
  buf = io.StringIO()

  for c in contents:
    buf.write(c)
    buf.write("\n")

  buf.seek(0)

  t2d = Trace2Db(":memory:")
  t2d.parse_file_buf_into_db(buf)

  buf.close()

  return t2d

def test_ftrace_mm_filemap_add_to_pagecache():
  test_contents = """
MediaStoreImpor-27212 (27176) [000] .... 16136.595194: mm_filemap_add_to_page_cache: dev 253:6 ino 7580 page=0000000060e990c7 pfn=677646 ofs=159744
MediaStoreImpor-27212 (27176) [000] .... 16136.595920: mm_filemap_add_to_page_cache: dev 253:6 ino 7580 page=0000000048e2e156 pfn=677645 ofs=126976
MediaStoreImpor-27212 (27176) [000] .... 16136.597793: mm_filemap_add_to_page_cache: dev 253:6 ino 7580 page=0000000051eabfb2 pfn=677644 ofs=122880
MediaStoreImpor-27212 (27176) [000] .... 16136.597815: mm_filemap_add_to_page_cache: dev 253:6 ino 7580 page=00000000ce7cd606 pfn=677643 ofs=131072
MediaStoreImpor-27212 (27176) [000] .... 16136.603732: mm_filemap_add_to_page_cache: dev 253:6 ino 1 page=000000008ffd3030 pfn=730119 ofs=186482688
MediaStoreImpor-27212 (27176) [000] .... 16136.604126: mm_filemap_add_to_page_cache: dev 253:6 ino b1d8 page=0000000098d4d2e2 pfn=829676 ofs=0
          <...>-27197 (-----) [002] .... 16136.613471: mm_filemap_add_to_page_cache: dev 253:6 ino 7580 page=00000000aca88a97 pfn=743346 ofs=241664
          <...>-27197 (-----) [002] .... 16136.615979: mm_filemap_add_to_page_cache: dev 253:6 ino 7580 page=00000000351f2bc1 pfn=777799 ofs=106496
          <...>-27224 (-----) [006] .... 16137.400090: mm_filemap_add_to_page_cache: dev 253:6 ino 712d page=000000006ff7ffdb pfn=754861 ofs=0
          <...>-1396  (-----) [000] .... 16137.451660: mm_filemap_add_to_page_cache: dev 253:6 ino 1 page=00000000ba0cbb34 pfn=769173 ofs=187191296
          <...>-1396  (-----) [000] .... 16137.453020: mm_filemap_add_to_page_cache: dev 253:6 ino b285 page=00000000f6ef038e pfn=820291 ofs=0
          <...>-1396  (-----) [000] .... 16137.453067: mm_filemap_add_to_page_cache: dev 253:6 ino b285 page=0000000083ebc446 pfn=956463 ofs=4096
          <...>-1396  (-----) [000] .... 16137.453101: mm_filemap_add_to_page_cache: dev 253:6 ino b285 page=000000009dc2cd25 pfn=822813 ofs=8192
          <...>-1396  (-----) [000] .... 16137.453113: mm_filemap_add_to_page_cache: dev 253:6 ino b285 page=00000000a11167fb pfn=928650 ofs=12288
          <...>-1396  (-----) [000] .... 16137.453126: mm_filemap_add_to_page_cache: dev 253:6 ino b285 page=00000000c1c3311b pfn=621110 ofs=16384
          <...>-1396  (-----) [000] .... 16137.453139: mm_filemap_add_to_page_cache: dev 253:6 ino b285 page=000000009aa78342 pfn=689370 ofs=20480
          <...>-1396  (-----) [000] .... 16137.453151: mm_filemap_add_to_page_cache: dev 253:6 ino b285 page=0000000082cddcd6 pfn=755584 ofs=24576
          <...>-1396  (-----) [000] .... 16137.453162: mm_filemap_add_to_page_cache: dev 253:6 ino b285 page=00000000b0249bc7 pfn=691431 ofs=28672
          <...>-1396  (-----) [000] .... 16137.453183: mm_filemap_add_to_page_cache: dev 253:6 ino b285 page=000000006a776ff0 pfn=795084 ofs=32768
          <...>-1396  (-----) [000] .... 16137.453203: mm_filemap_add_to_page_cache: dev 253:6 ino b285 page=000000001a4918a7 pfn=806998 ofs=36864
          <...>-2578  (-----) [002] .... 16137.561871: mm_filemap_add_to_page_cache: dev 253:6 ino 1 page=00000000d65af9d2 pfn=719246 ofs=187015168
          <...>-2578  (-----) [002] .... 16137.562846: mm_filemap_add_to_page_cache: dev 253:6 ino b25a page=000000002f6ba74f pfn=864982 ofs=0
          <...>-2578  (-----) [000] .... 16138.104500: mm_filemap_add_to_page_cache: dev 253:6 ino 1 page=00000000f888d0f6 pfn=805812 ofs=192794624
          <...>-2578  (-----) [000] .... 16138.105836: mm_filemap_add_to_page_cache: dev 253:6 ino b7dd page=000000003749523b pfn=977196 ofs=0
          <...>-27215 (-----) [001] .... 16138.256881: mm_filemap_add_to_page_cache: dev 253:6 ino 758f page=000000001b375de1 pfn=755928 ofs=0
          <...>-27215 (-----) [001] .... 16138.257526: mm_filemap_add_to_page_cache: dev 253:6 ino 7591 page=000000004e039481 pfn=841534 ofs=0
 NonUserFacing6-5246  ( 1322) [005] .... 16138.356491: mm_filemap_add_to_page_cache: dev 253:6 ino 1 page=00000000d65af9d2 pfn=719246 ofs=161890304
 NonUserFacing6-5246  ( 1322) [005] .... 16138.357538: mm_filemap_add_to_page_cache: dev 253:6 ino 9a64 page=000000002f6ba74f pfn=864982 ofs=0
 NonUserFacing6-5246  ( 1322) [005] .... 16138.357581: mm_filemap_add_to_page_cache: dev 253:6 ino 9a64 page=000000006e0f8322 pfn=797894 ofs=4096
          <...>-27197 (-----) [005] .... 16140.143224: mm_filemap_add_to_page_cache: dev 253:6 ino 7580 page=00000000a42527c6 pfn=1076669 ofs=32768
  """

  t2d = parse_trace_file_to_db(test_contents)
  session = t2d.session

  first_row = session.query(MmFilemapAddToPageCache).order_by(MmFilemapAddToPageCache.id).first()

  #dev 253:6 ino 7580 page=0000000060e990c7 pfn=677646 ofs=159744
  assert_eq_ignore_id(MmFilemapAddToPageCache(dev=64774, dev_major=253, dev_minor=6,
      ino=0x7580, page=0x0000000060e990c7, pfn=677646, ofs=159744), first_row)

  second_to_last_row = session.query(MmFilemapAddToPageCache).filter(MmFilemapAddToPageCache.page.in_([0x000000006e0f8322])).first()

  # dev 253:6 ino 9a64 page=000000006e0f8322 pfn=797894 ofs=4096
  assert_eq_ignore_id(MmFilemapAddToPageCache(dev=64774, dev_major=253, dev_minor=6,
      ino=0x9a64, page=0x000000006e0f8322, pfn=797894, ofs=4096), second_to_last_row)

def test_systrace_mm_filemap_add_to_pagecache():
  test_contents = """
<!DOCTYPE html>
<html>
<head i18n-values="dir:textdirection;">
<meta http-equiv="Content-Type" content="text/html; charset=utf-8">
<meta charset="utf-8"/>
<title>Android System Trace</title>
  <script class="trace-data" type="application/text">
PROCESS DUMP
USER           PID  PPID     VSZ    RSS WCHAN  PC S NAME                        COMM
root             1     0   62148   5976 0       0 S init                        [init]
root             2     0       0      0 0       0 S [kthreadd]                  [kthreadd]
  </script>

  <script class="trace-data" type="application/text">
MediaStoreImpor-27212 (27176) [000] .... 16136.595194: mm_filemap_add_to_page_cache: dev 253:6 ino 7580 page=0000000060e990c7 pfn=677646 ofs=159744
NonUserFacing6-5246  ( 1322) [005] .... 16138.357581: mm_filemap_add_to_page_cache: dev 253:6 ino 9a64 page=000000006e0f8322 pfn=797894 ofs=4096
  </script>

  <script class="trace-data" type="application/text">
{"traceEvents": [{"category": "process_argv", "name": "process_argv", "args": {"argv": ["/mnt/ssd3/workspace/master/external/chromium-trace/systrace.py", "-t", "5", "pagecache"]}, "pid": 160383, "ts": 1037300940509.7991, "tid": 139628672526080, "ph": "M"}, {"category": "python", "name": "clock_sync", "args": {"issue_ts": 1037307346185.212, "sync_id": "9a7e4fe3-89ad-441f-8226-8fe533fe973e"}, "pid": 160383, "ts": 1037307351643.906, "tid": 139628726089536, "ph": "c"}], "metadata": {"clock-domain": "SYSTRACE"}}
  </script>
<!-- END TRACE -->
  """

  t2d = parse_trace_file_to_db(test_contents)
  session = t2d.session

  first_row = session.query(MmFilemapAddToPageCache).order_by(MmFilemapAddToPageCache.id).first()

  #dev 253:6 ino 7580 page=0000000060e990c7 pfn=677646 ofs=159744
  assert_eq_ignore_id(MmFilemapAddToPageCache(dev=64774, dev_major=253, dev_minor=6,
      ino=0x7580, page=0x0000000060e990c7, pfn=677646, ofs=159744), first_row)

  second_to_last_row = session.query(MmFilemapAddToPageCache).filter(MmFilemapAddToPageCache.page.in_([0x000000006e0f8322])).first()

  # dev 253:6 ino 9a64 page=000000006e0f8322 pfn=797894 ofs=4096
  assert_eq_ignore_id(MmFilemapAddToPageCache(dev=64774, dev_major=253, dev_minor=6,
      ino=0x9a64, page=0x000000006e0f8322, pfn=797894, ofs=4096), second_to_last_row)

def test_timestamp_filter():
  test_contents = """
    MediaStoreImpor-27212 (27176) [000] .... 16136.595194: mm_filemap_add_to_page_cache: dev 253:6 ino 7580 page=0000000060e990c7 pfn=677646 ofs=159744
    NonUserFacing6-5246  ( 1322) [005] .... 16139.357581: mm_filemap_add_to_page_cache: dev 253:6 ino 9a64 page=000000006e0f8322 pfn=797894 ofs=4096
    MediaStoreImpor-27212 (27176) [000] .... 16136.604126: mm_filemap_add_to_page_cache: dev 253:6 ino b1d8 page=0000000098d4d2e2 pfn=829676 ofs=0
  """

  t2d = parse_trace_file_to_db(test_contents)
  session = t2d.session

  end_time = 16137.0

  results = session.query(MmFilemapAddToPageCache).join(
      MmFilemapAddToPageCache.raw_ftrace_entry).filter(
      RawFtraceEntry.timestamp <= end_time).order_by(
      MmFilemapAddToPageCache.id).all()

  assert len(results) == 2
  assert_eq_ignore_id(
      MmFilemapAddToPageCache(dev=64774, dev_major=253, dev_minor=6,
                              ino=0x7580, page=0x0000000060e990c7, pfn=677646,
                              ofs=159744), results[0])
  assert_eq_ignore_id(
      MmFilemapAddToPageCache(dev=64774, dev_major=253, dev_minor=6,
                              ino=0xb1d8, page=0x0000000098d4d2e2, pfn=829676,
                              ofs=0), results[1])


if __name__ == '__main__':
  pytest.main()
