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

#ifndef ANDROIDFW_STRING_PIECE_H
#define ANDROIDFW_STRING_PIECE_H

#include <ostream>
#include <string>
#include <string_view>

#include "utils/Unicode.h"

namespace android {

template <class T>
using BasicStringPiece = std::basic_string_view<T>;

using StringPiece = BasicStringPiece<char>;
using StringPiece16 = BasicStringPiece<char16_t>;

}  // namespace android

namespace std {

inline ::std::ostream& operator<<(::std::ostream& out, ::std::u16string_view str) {
  ssize_t utf8_len = utf16_to_utf8_length(str.data(), str.size());
  if (utf8_len < 0) {
    return out;  // empty
  }

  std::string utf8;
  utf8.resize(static_cast<size_t>(utf8_len));
  utf16_to_utf8(str.data(), str.size(), utf8.data(), utf8_len + 1);
  return out << utf8;
}

inline ::std::ostream& operator<<(::std::ostream& out, const ::std::u16string& str) {
  return out << std::u16string_view(str);
}

}  // namespace std

#endif  // ANDROIDFW_STRING_PIECE_H
