#!/usr/bin/env python3

#
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
#

from typing import Any, Callable, Dict, Generic, Iterable, List, NamedTuple, TextIO, Tuple, TypeVar, Optional, Union, TextIO

import re

class Inode2Filename:
  """
  Parses a text file of the format
     "uint(dev_t) uint(ino_t) int(file_size) string(filepath)\\n"*

  Lines not matching this format are ignored.
  """

  def __init__(self, inode_data_file: TextIO):
    """
    Create an Inode2Filename that reads cached inode from a file saved earlier
    (e.g. with pagecache.py -d or with inode2filename --format=textcache)

    :param inode_data_file: a file object (e.g. created with open or StringIO).

    Lifetime: inode_data_file is only used during the construction of the object.
    """
    self._inode_table = Inode2Filename.build_inode_lookup_table(inode_data_file)

  @classmethod
  def new_from_filename(cls, textcache_filename: str) -> 'Inode2Filename':
    """
    Create an Inode2Filename that reads cached inode from a file saved earlier
    (e.g. with pagecache.py -d or with inode2filename --format=textcache)

    :param textcache_filename: path to textcache
    """
    with open(textcache_filename) as inode_data_file:
      return cls(inode_data_file)

  @staticmethod
  def build_inode_lookup_table(inode_data_file: TextIO) -> Dict[Tuple[int, int], Tuple[str, str]]:
    """
    :return: map { (device_int, inode_int) -> (filename_str, size_str) }
    """
    inode2filename = {}
    for line in inode_data_file:
      # stat -c "%d %i %s %n
      # device number, inode number, total size in bytes, file name
      result = re.match('([0-9]+)d? ([0-9]+) -?([0-9]+) (.*)', line)
      if result:
        inode2filename[(int(result.group(1)), int(result.group(2)))] = \
            (result.group(4), result.group(3))

    return inode2filename

  def resolve(self, dev_t: int, ino_t: int) -> Optional[str]:
    """
    Return a filename (str) from a (dev_t, ino_t) inode pair.

    Returns None if the lookup fails.
    """
    maybe_result = self._inode_table.get((dev_t, ino_t))

    if not maybe_result:
      return None

    return maybe_result[0] # filename str

  def __len__(self) -> int:
    """
    :return: the number of inode entries parsed from the file.
    """
    return len(self._inode_table)

  def __repr__(self) -> str:
    """
    :return: string representation for debugging/test failures.
    """
    return "Inode2Filename%s" %(repr(self._inode_table))

  # end of class.
