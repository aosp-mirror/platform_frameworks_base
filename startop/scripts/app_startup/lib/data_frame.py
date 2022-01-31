import itertools
from typing import Dict, List

class DataFrame:
  """Table-like class for storing a 2D cells table with named columns."""
  def __init__(self, data: Dict[str, List[object]] = {}):
    """
    Create a new DataFrame from a dictionary (keys = headers,
    values = columns).
    """
    self._headers = [i for i in data.keys()]
    self._rows = []

    row_num = 0

    def get_data_row(idx):
      r = {}
      for header, header_data in data.items():

        if not len(header_data) > idx:
          continue

        r[header] = header_data[idx]

      return r

    while True:
      row_dict = get_data_row(row_num)
      if len(row_dict) == 0:
        break

      self._append_row(row_dict.keys(), row_dict.values())
      row_num = row_num + 1

  def concat_rows(self, other: 'DataFrame') -> None:
    """
    In-place concatenate rows of other into the rows of the
    current DataFrame.

    None is added in pre-existing cells if new headers
    are introduced.
    """
    other_datas = other._data_only()

    other_headers = other.headers

    for d in other_datas:
      self._append_row(other_headers, d)

  def _append_row(self, headers: List[str], data: List[object]):
    new_row = {k:v for k,v in zip(headers, data)}
    self._rows.append(new_row)

    for header in headers:
      if not header in self._headers:
        self._headers.append(header)

  def __repr__(self):
#     return repr(self._rows)
    repr = ""

    header_list = self._headers_only()

    row_format = u""
    for header in header_list:
      row_format = row_format + u"{:>%d}" %(len(header) + 1)

    repr = row_format.format(*header_list) + "\n"

    for v in self._data_only():
      repr = repr + row_format.format(*v) + "\n"

    return repr

  def __eq__(self, other):
    if isinstance(other, self.__class__):
      return self.headers == other.headers and self.data_table == other.data_table
    else:
      print("wrong instance", other.__class__)
      return False

  @property
  def headers(self) -> List[str]:
    return [i for i in self._headers_only()]

  @property
  def data_table(self) -> List[List[object]]:
    return list(self._data_only())

  @property
  def data_table_transposed(self) -> List[List[object]]:
    return list(self._transposed_data())

  @property
  def data_row_len(self) -> int:
    return len(self._rows)

  def data_row_at(self, idx) -> List[object]:
    """
    Return a single data row at the specified index (0th based).

    Accepts negative indices, e.g. -1 is last row.
    """
    row_dict = self._rows[idx]
    l = []

    for h in self._headers_only():
      l.append(row_dict.get(h)) # Adds None in blank spots.

    return l

  def copy(self) -> 'DataFrame':
    """
    Shallow copy of this DataFrame.
    """
    return self.repeat(count=0)

  def repeat(self, count: int) -> 'DataFrame':
    """
    Returns a new DataFrame where each row of this dataframe is repeated count times.
    A repeat of a row is adjacent to other repeats of that same row.
    """
    df = DataFrame()
    df._headers = self._headers.copy()

    rows = []
    for row in self._rows:
      for i in range(count):
        rows.append(row.copy())

    df._rows = rows

    return df

  def merge_data_columns(self, other: 'DataFrame'):
    """
    Merge self and another DataFrame by adding the data from other column-wise.
    For any headers that are the same, data from 'other' is preferred.
    """
    for h in other._headers:
      if not h in self._headers:
        self._headers.append(h)

    append_rows = []

    for self_dict, other_dict in itertools.zip_longest(self._rows, other._rows):
      if not self_dict:
        d = {}
        append_rows.append(d)
      else:
        d = self_dict

      d_other = other_dict
      if d_other:
        for k,v in d_other.items():
          d[k] = v

    for r in append_rows:
      self._rows.append(r)

  def data_row_reduce(self, fnc) -> 'DataFrame':
    """
    Reduces the data row-wise by applying the fnc to each row (column-wise).
    Empty cells are skipped.

    fnc(Iterable[object]) -> object
    fnc is applied over every non-empty cell in that column (descending row-wise).

    Example:
      DataFrame({'a':[1,2,3]}).data_row_reduce(sum) == DataFrame({'a':[6]})

    Returns a new single-row DataFrame.
    """
    df = DataFrame()
    df._headers = self._headers.copy()

    def yield_by_column(header_key):
      for row_dict in self._rows:
        val = row_dict.get(header_key)
        if val:
          yield val

    new_row_dict = {}
    for h in df._headers:
      cell_value = fnc(yield_by_column(h))
      new_row_dict[h] = cell_value

    df._rows = [new_row_dict]
    return df

  def _headers_only(self):
    return self._headers

  def _data_only(self):
    row_len = len(self._rows)

    for i in range(row_len):
      yield self.data_row_at(i)

  def _transposed_data(self):
    return zip(*self._data_only())