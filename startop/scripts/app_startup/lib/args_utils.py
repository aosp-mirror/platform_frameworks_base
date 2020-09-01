import itertools
import os
import sys
from typing import Any, Callable, Dict, Iterable, List, NamedTuple, Tuple, \
    TypeVar, Optional

# local import
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))
import lib.print_utils as print_utils

T = TypeVar('T')
NamedTupleMeta = Callable[
    ..., T]  # approximation of a (S : NamedTuple<T> where S() == T) metatype.
FilterFuncType = Callable[[NamedTuple], bool]

def dict_lookup_any_key(dictionary: dict, *keys: List[Any]):
  for k in keys:
    if k in dictionary:
      return dictionary[k]


  print_utils.debug_print("None of the keys {} were in the dictionary".format(
      keys))
  return [None]

def generate_run_combinations(named_tuple: NamedTupleMeta[T],
                              opts_dict: Dict[str, List[Optional[object]]],
                              loop_count: int = 1) -> Iterable[T]:
  """
  Create all possible combinations given the values in opts_dict[named_tuple._fields].

  :type T: type annotation for the named_tuple type.
  :param named_tuple: named tuple type, whose fields are used to make combinations for
  :param opts_dict: dictionary of keys to value list. keys correspond to the named_tuple fields.
  :param loop_count: number of repetitions.
  :return: an iterable over named_tuple instances.
  """
  combinations_list = []
  for k in named_tuple._fields:
    # the key can be either singular or plural , e.g. 'package' or 'packages'
    val = dict_lookup_any_key(opts_dict, k, k + "s")

    # treat {'x': None} key value pairs as if it was [None]
    # otherwise itertools.product throws an exception about not being able to iterate None.
    combinations_list.append(val or [None])

  print_utils.debug_print("opts_dict: ", opts_dict)
  print_utils.debug_print_nd("named_tuple: ", named_tuple)
  print_utils.debug_print("combinations_list: ", combinations_list)

  for i in range(loop_count):
    for combo in itertools.product(*combinations_list):
      yield named_tuple(*combo)

def filter_run_combinations(named_tuple: NamedTuple,
                            filters: List[FilterFuncType]) -> bool:
  for filter in filters:
    if filter(named_tuple):
      return False
  return True

def generate_group_run_combinations(run_combinations: Iterable[NamedTuple],
                                    dst_nt: NamedTupleMeta[T]) \
    -> Iterable[Tuple[T, Iterable[NamedTuple]]]:
  def group_by_keys(src_nt):
    src_d = src_nt._asdict()
    # now remove the keys that aren't legal in dst.
    for illegal_key in set(src_d.keys()) - set(dst_nt._fields):
      if illegal_key in src_d:
        del src_d[illegal_key]

    return dst_nt(**src_d)

  for args_list_it in itertools.groupby(run_combinations, group_by_keys):
    (group_key_value, args_it) = args_list_it
    yield (group_key_value, args_it)
