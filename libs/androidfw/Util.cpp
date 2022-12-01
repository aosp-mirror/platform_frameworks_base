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

#include <algorithm>
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

std::u16string Utf8ToUtf16(StringPiece utf8) {
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

std::string Utf16ToUtf8(StringPiece16 utf16) {
  ssize_t utf8_length = utf16_to_utf8_length(utf16.data(), utf16.length());
  if (utf8_length <= 0) {
    return {};
  }

  std::string utf8;
  utf8.resize(utf8_length);
  utf16_to_utf8(utf16.data(), utf16.length(), &*utf8.begin(), utf8_length + 1);
  return utf8;
}

std::string Utf8ToModifiedUtf8(std::string_view utf8) {
  // Java uses Modified UTF-8 which only supports the 1, 2, and 3 byte formats of UTF-8. To encode
  // 4 byte UTF-8 codepoints, Modified UTF-8 allows the use of surrogate pairs in the same format
  // of CESU-8 surrogate pairs. Calculate the size of the utf8 string with all 4 byte UTF-8
  // codepoints replaced with 2 3 byte surrogate pairs
  size_t modified_size = 0;
  const size_t size = utf8.size();
  for (size_t i = 0; i < size; i++) {
    if (((uint8_t)utf8[i] >> 4) == 0xF) {
      modified_size += 6;
      i += 3;
    } else {
      modified_size++;
    }
  }

  // Early out if no 4 byte codepoints are found
  if (size == modified_size) {
    return std::string(utf8);
  }

  std::string output;
  output.reserve(modified_size);
  for (size_t i = 0; i < size; i++) {
    if (((uint8_t)utf8[i] >> 4) == 0xF) {
      int32_t codepoint = utf32_from_utf8_at(utf8.data(), size, i, nullptr);

      // Calculate the high and low surrogates as UTF-16 would
      int32_t high = ((codepoint - 0x10000) / 0x400) + 0xD800;
      int32_t low = ((codepoint - 0x10000) % 0x400) + 0xDC00;

      // Encode each surrogate in UTF-8
      output.push_back((char)(0xE4 | ((high >> 12) & 0xF)));
      output.push_back((char)(0x80 | ((high >> 6) & 0x3F)));
      output.push_back((char)(0x80 | (high & 0x3F)));
      output.push_back((char)(0xE4 | ((low >> 12) & 0xF)));
      output.push_back((char)(0x80 | ((low >> 6) & 0x3F)));
      output.push_back((char)(0x80 | (low & 0x3F)));
      i += 3;
    } else {
      output.push_back(utf8[i]);
    }
  }

  return output;
}

std::string ModifiedUtf8ToUtf8(std::string_view modified_utf8) {
  // The UTF-8 representation will have a byte length less than or equal to the Modified UTF-8
  // representation.
  std::string output;
  output.reserve(modified_utf8.size());

  size_t index = 0;
  const size_t modified_size = modified_utf8.size();
  while (index < modified_size) {
    size_t next_index;
    int32_t high_surrogate =
        utf32_from_utf8_at(modified_utf8.data(), modified_size, index, &next_index);
    if (high_surrogate < 0) {
      return {};
    }

    // Check that the first codepoint is within the high surrogate range
    if (high_surrogate >= 0xD800 && high_surrogate <= 0xDB7F) {
      int32_t low_surrogate =
          utf32_from_utf8_at(modified_utf8.data(), modified_size, next_index, &next_index);
      if (low_surrogate < 0) {
        return {};
      }

      // Check that the second codepoint is within the low surrogate range
      if (low_surrogate >= 0xDC00 && low_surrogate <= 0xDFFF) {
        const char32_t codepoint =
            (char32_t)(((high_surrogate - 0xD800) * 0x400) + (low_surrogate - 0xDC00) + 0x10000);

        // The decoded codepoint should represent a 4 byte, UTF-8 character
        const size_t utf8_length = (size_t)utf32_to_utf8_length(&codepoint, 1);
        if (utf8_length != 4) {
          return {};
        }

        // Encode the UTF-8 representation of the codepoint into the string
        const size_t start_index = output.size();
        output.resize(start_index + utf8_length);
        char* start = &output[start_index];
        utf32_to_utf8((char32_t*)&codepoint, 1, start, utf8_length + 1);

        index = next_index;
        continue;
      }
    }

    // Append non-surrogate pairs to the output string
    for (size_t i = index; i < next_index; i++) {
      output.push_back(modified_utf8[i]);
    }
    index = next_index;
  }
  return output;
}

template <class Func>
static std::vector<std::string> SplitAndTransform(StringPiece str, char sep, Func&& f) {
  std::vector<std::string> parts;
  const StringPiece::const_iterator end = std::end(str);
  StringPiece::const_iterator start = std::begin(str);
  StringPiece::const_iterator current;
  do {
    current = std::find(start, end, sep);
    parts.emplace_back(StringPiece(start, current - start));
    std::string& part = parts.back();
    std::transform(part.begin(), part.end(), part.begin(), f);
    start = current + 1;
  } while (current != end);
  return parts;
}

std::vector<std::string> SplitAndLowercase(StringPiece str, char sep) {
  return SplitAndTransform(str, sep, [](char c) { return ::tolower(c); });
}

std::unique_ptr<uint8_t[]> Copy(const BigBuffer& buffer) {
  auto data = std::unique_ptr<uint8_t[]>(new uint8_t[buffer.size()]);
  uint8_t* p = data.get();
  for (const auto& block : buffer) {
    memcpy(p, block.buffer.get(), block.size);
    p += block.size;
  }
  return data;
}

StringPiece16 GetString16(const android::ResStringPool& pool, size_t idx) {
  if (auto str = pool.stringAt(idx); str.ok()) {
    return *str;
  }
  return StringPiece16();
}

std::string GetString(const android::ResStringPool& pool, size_t idx) {
  if (auto str = pool.string8At(idx); str.ok()) {
    return ModifiedUtf8ToUtf8(*str);
  }
  return Utf16ToUtf8(GetString16(pool, idx));
}

} // namespace util
} // namespace android
