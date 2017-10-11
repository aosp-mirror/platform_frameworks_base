/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "androidfw/Util.h"

#include <string>

#include "utils/ByteOrder.h"
#include "utils/Unicode.h"

#ifdef _WIN32
#ifdef ERROR
#undef ERROR
#endif
#endif

namespace android {
namespace util {

void ReadUtf16StringFromDevice(const uint16_t* src, size_t len, std::string* out) {
  char buf[5];
  while (*src && len != 0) {
    char16_t c = static_cast<char16_t>(dtohs(*src));
    utf16_to_utf8(&c, 1, buf, sizeof(buf));
    out->append(buf, strlen(buf));
    ++src;
    --len;
  }
}

std::u16string Utf8ToUtf16(const StringPiece& utf8) {
  ssize_t utf16_length =
      utf8_to_utf16_length(reinterpret_cast<const uint8_t*>(utf8.data()), utf8.length());
  if (utf16_length <= 0) {
    return {};
  }

  std::u16string utf16;
  utf16.resize(utf16_length);
  utf8_to_utf16(reinterpret_cast<const uint8_t*>(utf8.data()), utf8.length(), &*utf16.begin(),
                utf16_length + 1);
  return utf16;
}

std::string Utf16ToUtf8(const StringPiece16& utf16) {
  ssize_t utf8_length = utf16_to_utf8_length(utf16.data(), utf16.length());
  if (utf8_length <= 0) {
    return {};
  }

  std::string utf8;
  utf8.resize(utf8_length);
  utf16_to_utf8(utf16.data(), utf16.length(), &*utf8.begin(), utf8_length + 1);
  return utf8;
}

} // namespace util
} // namespace android
