#!/usr/bin/python3
# Copyright (C) 2019 The Android Open Source Project
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

import re
import sys

from sqlalchemy import create_engine
from sqlalchemy import Column, Date, Integer, Float, String, ForeignKey
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import relationship

from sqlalchemy.orm import sessionmaker

import sqlalchemy

from typing import Optional, Tuple

_DEBUG = False        # print sql commands to console
_FLUSH_LIMIT = 10000  # how many entries are parsed before flushing to DB from memory

Base = declarative_base()

class RawFtraceEntry(Base):
  __tablename__ = 'raw_ftrace_entries'

  id = Column(Integer, primary_key=True)
  task_name = Column(String, nullable=True) # <...> -> None.
  task_pid = Column(String, nullable=False)
  tgid = Column(Integer, nullable=True)     # ----- -> None.
  cpu = Column(Integer, nullable=False)
  timestamp = Column(Float, nullable=False)
  function = Column(String, nullable=False)
  function_args = Column(String, nullable=False)

  # 1:1 relation with MmFilemapAddToPageCache.
  mm_filemap_add_to_page_cache = relationship("MmFilemapAddToPageCache",
                                              back_populates="raw_ftrace_entry")

  @staticmethod
  def parse_dict(line):
    # '           <...>-5521  (-----) [003] ...1 17148.446877: tracing_mark_write: trace_event_clock_sync: parent_ts=17148.447266'
    m = re.match('\s*(.*)-(\d+)\s+\(([^\)]+)\)\s+\[(\d+)\]\s+([\w.]{4})\s+(\d+[.]\d+):\s+(\w+):\s+(.*)', line)
    if not m:
      return None

    groups = m.groups()
    # groups example:
    # ('<...>',
    #  '5521',
    #  '-----',
    #  '003',
    #  '...1',
    #  '17148.446877',
    #  'tracing_mark_write',
    #  'trace_event_clock_sync: parent_ts=17148.447266')
    task_name = groups[0]
    if task_name == '<...>':
      task_name = None

    task_pid = int(groups[1])
    tgid = groups[2]
    if tgid == '-----':
      tgid = None

    cpu = int(groups[3])
    # irq_flags = groups[4]
    timestamp = float(groups[5])
    function = groups[6]
    function_args = groups[7]

    return {'task_name': task_name, 'task_pid': task_pid, 'tgid': tgid, 'cpu': cpu, 'timestamp': timestamp, 'function': function, 'function_args': function_args}

class SchedSwitch(Base):
  __tablename__ = 'sched_switches'

  id = Column(Integer, ForeignKey('raw_ftrace_entries.id'), primary_key=True)

  prev_comm = Column(String, nullable=False)
  prev_pid = Column(Integer, nullable=False)
  prev_prio = Column(Integer, nullable=False)
  prev_state = Column(String, nullable=False)

  next_comm = Column(String, nullable=False)
  next_pid = Column(Integer, nullable=False)
  next_prio = Column(Integer, nullable=False)

  @staticmethod
  def parse_dict(function_args, id = None):
    # 'prev_comm=kworker/u16:5 prev_pid=13971 prev_prio=120 prev_state=S ==> next_comm=swapper/4 next_pid=0 next_prio=120'
    m = re.match("prev_comm=(.*) prev_pid=(\d+) prev_prio=(\d+) prev_state=(.*) ==> next_comm=(.*) next_pid=(\d+) next_prio=(\d+) ?", function_args)
    if not m:
      return None

    groups = m.groups()
    # ('kworker/u16:5', '13971', '120', 'S', 'swapper/4', '0', '120')
    d = {}
    if id is not None:
      d['id'] = id
    d['prev_comm'] = groups[0]
    d['prev_pid'] = int(groups[1])
    d['prev_prio'] = int(groups[2])
    d['prev_state'] = groups[3]
    d['next_comm'] = groups[4]
    d['next_pid'] = int(groups[5])
    d['next_prio'] = int(groups[6])

    return d

class SchedBlockedReason(Base):
  __tablename__ = 'sched_blocked_reasons'

  id = Column(Integer, ForeignKey('raw_ftrace_entries.id'), primary_key=True)

  pid = Column(Integer, nullable=False)
  iowait = Column(Integer, nullable=False)
  caller = Column(String, nullable=False)

  @staticmethod
  def parse_dict(function_args, id = None):
    # 'pid=2289 iowait=1 caller=wait_on_page_bit_common+0x2a8/0x5f'
    m = re.match("pid=(\d+) iowait=(\d+) caller=(.*) ?", function_args)
    if not m:
      return None

    groups = m.groups()
    # ('2289', '1', 'wait_on_page_bit_common+0x2a8/0x5f8')
    d = {}
    if id is not None:
      d['id'] = id
    d['pid'] = int(groups[0])
    d['iowait'] = int(groups[1])
    d['caller'] = groups[2]

    return d

class MmFilemapAddToPageCache(Base):
  __tablename__ = 'mm_filemap_add_to_page_caches'

  id = Column(Integer, ForeignKey('raw_ftrace_entries.id'), primary_key=True)

  dev = Column(Integer, nullable=False)        # decoded from ${major}:${minor} syntax.
  dev_major = Column(Integer, nullable=False)  # original ${major} value.
  dev_minor = Column(Integer, nullable=False)  # original ${minor} value.

  ino = Column(Integer, nullable=False)  # decoded from hex to base 10
  page = Column(Integer, nullable=False) # decoded from hex to base 10

  pfn = Column(Integer, nullable=False)
  ofs = Column(Integer, nullable=False)

  # 1:1 relation with RawFtraceEntry.
  raw_ftrace_entry = relationship("RawFtraceEntry", uselist=False)

  @staticmethod
  def parse_dict(function_args, id = None):
    # dev 253:6 ino b2c7 page=00000000ec787cd9 pfn=1478539 ofs=4096
    m = re.match("dev (\d+):(\d+) ino ([0-9a-fA-F]+) page=([0-9a-fA-F]+) pfn=(\d+) ofs=(\d+)", function_args)
    if not m:
      return None

    groups = m.groups()
    # ('253', '6', 'b2c7', '00000000ec787cd9', '1478539', '4096')
    d = {}
    if id is not None:
      d['id'] = id

    device_major = d['dev_major'] = int(groups[0])
    device_minor = d['dev_minor'] = int(groups[1])
    d['dev'] = device_major << 8 | device_minor
    d['ino'] = int(groups[2], 16)
    d['page'] = int(groups[3], 16)
    d['pfn'] = int(groups[4])
    d['ofs'] = int(groups[5])

    return d

class Trace2Db:
  def __init__(self, db_filename: str):
    (s, e) = self._init_sqlalchemy(db_filename)
    self._session = s
    self._engine = e
    self._raw_ftrace_entry_filter = lambda x: True

  def set_raw_ftrace_entry_filter(self, flt):
    """
    Install a function dict(RawFtraceEntry) -> bool

    If this returns 'false', then we skip adding the RawFtraceEntry to the database.
    """
    self._raw_ftrace_entry_filter = flt

  @staticmethod
  def _init_sqlalchemy(db_filename: str) -> Tuple[object, object]:
    global _DEBUG
    engine = create_engine('sqlite:///' + db_filename, echo=_DEBUG)

    # CREATE ... (tables)
    Base.metadata.create_all(engine)

    Session = sessionmaker(bind=engine)
    session = Session()
    return (session, engine)

  def parse_file_into_db(self, filename: str, limit: Optional[int] = None):
    """
    Parse the ftrace/systrace at 'filename',
    inserting the values into the current sqlite database.

    :return: number of RawFtraceEntry inserted.
    """
    return parse_file(filename, self._session, self._engine, self._raw_ftrace_entry_filter, limit)

  def parse_file_buf_into_db(self, file_buf, limit: Optional[int] = None):
    """
    Parse the ftrace/systrace at 'filename',
    inserting the values into the current sqlite database.

    :return: number of RawFtraceEntry inserted.
    """
    return parse_file_buf(file_buf, self._session, self._engine, self._raw_ftrace_entry_filter, limit)


  @property
  def session(self):
    return self._session

def insert_pending_entries(engine, kls, lst):
  if len(lst) > 0:
    # for some reason, it tries to generate an empty INSERT statement with len=0,
    # which of course violates the first non-null constraint.
    try:
      # Performance-sensitive parsing according to:
      # https://docs.sqlalchemy.org/en/13/faq/performance.html#i-m-inserting-400-000-rows-with-the-orm-and-it-s-really-slow
      engine.execute(kls.__table__.insert(), lst)
      lst.clear()
    except sqlalchemy.exc.IntegrityError as err:
      # possibly violating some SQL constraint, print data here.
      print(err)
      print(lst)
      raise

def parse_file(filename: str, *args, **kwargs) -> int:
  # use explicit encoding to avoid UnicodeDecodeError.
  with open(filename, encoding="ISO-8859-1") as f:
    return parse_file_buf(f, *args, **kwargs)

def parse_file_buf(filebuf, session, engine, raw_ftrace_entry_filter, limit=None) -> int:
  global _FLUSH_LIMIT
  count = 0
  # count and id are not equal, because count still increases for invalid lines.
  id = 0

  pending_entries = []
  pending_sched_switch = []
  pending_sched_blocked_reasons = []
  pending_mm_filemap_add_to_pagecaches = []

  def insert_all_pending_entries():
    insert_pending_entries(engine, RawFtraceEntry, pending_entries)
    insert_pending_entries(engine, SchedSwitch, pending_sched_switch)
    insert_pending_entries(engine, SchedBlockedReason, pending_sched_blocked_reasons)
    insert_pending_entries(engine, MmFilemapAddToPageCache, pending_mm_filemap_add_to_pagecaches)

  # for trace.html files produced by systrace,
  # the actual ftrace is in the 'second' trace-data script class.
  parsing_trace_data = 0
  parsing_systrace_file = False

  f = filebuf
  for l in f:
    if parsing_trace_data == 0 and l == "<!DOCTYPE html>\n":
      parsing_systrace_file = True
      continue
    if parsing_trace_data != 2 and parsing_systrace_file:
      if l == '  <script class="trace-data" type="application/text">\n':
        parsing_trace_data = parsing_trace_data + 1
      continue

    if parsing_systrace_file and parsing_trace_data != 2:
      continue
    elif parsing_systrace_file and parsing_trace_data == 2 and l == "  </script>\n":
      # the rest of this file is just random html
      break

    # now parsing the ftrace data.
    if len(l) > 1 and l[0] == '#':
      continue

    count = count + 1

    if limit and count >= limit:
      break

    raw_ftrace_entry = RawFtraceEntry.parse_dict(l)
    if not raw_ftrace_entry:
      print("WARNING: Failed to parse raw ftrace entry: " + l)
      continue

    if not raw_ftrace_entry_filter(raw_ftrace_entry):
      # Skip processing raw ftrace entries that don't match a filter.
      # This is an optimization for when Trace2Db is used programatically
      # to avoid having an overly large database.
      continue

    pending_entries.append(raw_ftrace_entry)
    id = id + 1

    if raw_ftrace_entry['function'] == 'sched_switch':
      sched_switch = SchedSwitch.parse_dict(raw_ftrace_entry['function_args'], id)

      if not sched_switch:
        print("WARNING: Failed to parse sched_switch: " + l)
      else:
        pending_sched_switch.append(sched_switch)

    elif raw_ftrace_entry['function'] == 'sched_blocked_reason':
      sbr = SchedBlockedReason.parse_dict(raw_ftrace_entry['function_args'], id)

      if not sbr:
        print("WARNING: Failed to parse sched_blocked_reason: " + l)
      else:
        pending_sched_blocked_reasons.append(sbr)

    elif raw_ftrace_entry['function'] == 'mm_filemap_add_to_page_cache':
      d = MmFilemapAddToPageCache.parse_dict(raw_ftrace_entry['function_args'],
                                             id)
      if not d:
        print("WARNING: Failed to parse mm_filemap_add_to_page_cache: " + l)
      else:
        pending_mm_filemap_add_to_pagecaches.append(d)

    # Objects are cached in python memory, not yet sent to SQL database.

    # Send INSERT/UPDATE/etc statements to the underlying SQL database.
    if count % _FLUSH_LIMIT == 0:
      insert_all_pending_entries()

  insert_all_pending_entries()

  # Ensure underlying database commits changes from memory to disk.
  session.commit()

  return count
