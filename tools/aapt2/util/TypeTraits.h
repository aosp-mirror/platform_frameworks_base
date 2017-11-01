/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef AAPT_UTIL_TYPETRAITS_H
#define AAPT_UTIL_TYPETRAITS_H

#include <type_traits>

namespace aapt {

#define DEFINE_HAS_BINARY_OP_TRAIT(name, op)                                  \
  template <typename T, typename U>                                           \
  struct name {                                                               \
    template <typename V, typename W>                                         \
    static constexpr decltype(std::declval<V>() op std::declval<W>(), bool()) \
    test(int) {                                                               \
      return true;                                                            \
    }                                                                         \
    template <typename V, typename W>                                         \
    static constexpr bool test(...) {                                         \
      return false;                                                           \
    }                                                                         \
    static constexpr bool value = test<T, U>(int());                          \
  }

DEFINE_HAS_BINARY_OP_TRAIT(has_eq_op, ==);
DEFINE_HAS_BINARY_OP_TRAIT(has_lt_op, <);

/**
 * Type trait that checks if two types can be equated (==) and compared (<).
 */
template <typename T, typename U>
struct is_comparable {
  static constexpr bool value =
      has_eq_op<T, U>::value && has_lt_op<T, U>::value;
};

}  // namespace aapt

#endif /* AAPT_UTIL_TYPETRAITS_H */
