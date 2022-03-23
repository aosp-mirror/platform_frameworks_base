#!/usr/bin/env python3
#
# Copyright 2018, The Android Open Source Project
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

"""Unit tests for the data_frame.py script."""

from data_frame import DataFrame

def test_data_frame():
  # trivial empty data frame
  df = DataFrame()
  assert df.headers == []
  assert df.data_table == []
  assert df.data_table_transposed == []

  # common case, same number of values in each place.
  df = DataFrame({'TotalTime_ms': [1, 2, 3], 'Displayed_ms': [4, 5, 6]})
  assert df.headers == ['TotalTime_ms', 'Displayed_ms']
  assert df.data_table == [[1, 4], [2, 5], [3, 6]]
  assert df.data_table_transposed == [(1, 2, 3), (4, 5, 6)]

  # varying num values.
  df = DataFrame({'many': [1, 2], 'none': []})
  assert df.headers == ['many', 'none']
  assert df.data_table == [[1, None], [2, None]]
  assert df.data_table_transposed == [(1, 2), (None, None)]

  df = DataFrame({'many': [], 'none': [1, 2]})
  assert df.headers == ['many', 'none']
  assert df.data_table == [[None, 1], [None, 2]]
  assert df.data_table_transposed == [(None, None), (1, 2)]

  # merge multiple data frames
  df = DataFrame()
  df.concat_rows(DataFrame())
  assert df.headers == []
  assert df.data_table == []
  assert df.data_table_transposed == []

  df = DataFrame()
  df2 = DataFrame({'TotalTime_ms': [1, 2, 3], 'Displayed_ms': [4, 5, 6]})

  df.concat_rows(df2)
  assert df.headers == ['TotalTime_ms', 'Displayed_ms']
  assert df.data_table == [[1, 4], [2, 5], [3, 6]]
  assert df.data_table_transposed == [(1, 2, 3), (4, 5, 6)]

  df = DataFrame({'TotalTime_ms': [1, 2]})
  df2 = DataFrame({'Displayed_ms': [4, 5]})

  df.concat_rows(df2)
  assert df.headers == ['TotalTime_ms', 'Displayed_ms']
  assert df.data_table == [[1, None], [2, None], [None, 4], [None, 5]]

  df = DataFrame({'TotalTime_ms': [1, 2]})
  df2 = DataFrame({'TotalTime_ms': [3, 4], 'Displayed_ms': [5, 6]})

  df.concat_rows(df2)
  assert df.headers == ['TotalTime_ms', 'Displayed_ms']
  assert df.data_table == [[1, None], [2, None], [3, 5], [4, 6]]

  # data_row_at
  df = DataFrame({'TotalTime_ms': [1, 2, 3], 'Displayed_ms': [4, 5, 6]})
  assert df.data_row_at(-1) == [3, 6]
  assert df.data_row_at(2) == [3, 6]
  assert df.data_row_at(1) == [2, 5]

  # repeat
  df = DataFrame({'TotalTime_ms': [1], 'Displayed_ms': [4]})
  df2 = DataFrame({'TotalTime_ms': [1, 1, 1], 'Displayed_ms': [4, 4, 4]})
  assert df.repeat(3) == df2

  # repeat
  df = DataFrame({'TotalTime_ms': [1, 1, 1], 'Displayed_ms': [4, 4, 4]})
  assert df.data_row_len == 3
  df = DataFrame({'TotalTime_ms': [1, 1]})
  assert df.data_row_len == 2

  # repeat
  df = DataFrame({'TotalTime_ms': [1, 1, 1], 'Displayed_ms': [4, 4, 4]})
  assert df.data_row_len == 3
  df = DataFrame({'TotalTime_ms': [1, 1]})
  assert df.data_row_len == 2

  # data_row_reduce
  df = DataFrame({'TotalTime_ms': [1, 1, 1], 'Displayed_ms': [4, 4, 4]})
  df_sum = DataFrame({'TotalTime_ms': [3], 'Displayed_ms': [12]})
  assert df.data_row_reduce(sum) == df_sum

  # merge_data_columns
  df = DataFrame({'TotalTime_ms': [1, 2, 3]})
  df2 = DataFrame({'Displayed_ms': [3, 4, 5, 6]})

  df.merge_data_columns(df2)
  assert df == DataFrame(
    {'TotalTime_ms': [1, 2, 3], 'Displayed_ms': [3, 4, 5, 6]})

  df = DataFrame({'TotalTime_ms': [1, 2, 3]})
  df2 = DataFrame({'Displayed_ms': [3, 4]})

  df.merge_data_columns(df2)
  assert df == DataFrame(
    {'TotalTime_ms': [1, 2, 3], 'Displayed_ms': [3, 4]})

  df = DataFrame({'TotalTime_ms': [1, 2, 3]})
  df2 = DataFrame({'TotalTime_ms': [10, 11]})

  df.merge_data_columns(df2)
  assert df == DataFrame({'TotalTime_ms': [10, 11, 3]})

  df = DataFrame({'TotalTime_ms': []})
  df2 = DataFrame({'TotalTime_ms': [10, 11]})

  df.merge_data_columns(df2)
  assert df == DataFrame({'TotalTime_ms': [10, 11]})
