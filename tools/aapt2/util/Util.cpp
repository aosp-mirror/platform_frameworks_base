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

#include "util/Util.h"

#include <algorithm>
#include <ostream>
#include <string>
#include <vector>

#include "androidfw/StringPiece.h"
#include "utils/Unicode.h"

#include "text/Unicode.h"
#include "text/Utf8Iterator.h"
#include "util/BigBuffer.h"
#include "util/Maybe.h"

using ::aapt::text::Utf8Iterator;
using ::android::StringPiece;
using ::android::StringPiece16;

namespace aapt {
namespace util {

static std::vector<std::string> SplitAndTransform(
    const StringPiece& str, char sep, const std::function<char(char)>& f) {
  std::vector<std::string> parts;
  const StringPiece::const_iterator end = std::end(str);
  StringPiece::const_iterator start = std::begin(str);
  StringPiece::const_iterator current;
  do {
    current = std::find(start, end, sep);
    parts.emplace_back(str.substr(start, current).to_string());
    if (f) {
      std::string& part = parts.back();
      std::transform(part.begin(), part.end(), part.begin(), f);
    }
    start = current + 1;
  } while (current != end);
  return parts;
}

std::vector<std::string> Split(const StringPiece& str, char sep) {
  return SplitAndTransform(str, sep, nullptr);
}

std::vector<std::string> SplitAndLowercase(const StringPiece& str, char sep) {
  return SplitAndTransform(str, sep, ::tolower);
}

bool StartsWith(const StringPiece& str, const StringPiece& prefix) {
  if (str.size() < prefix.size()) {
    return false;
  }
  return str.substr(0, prefix.size()) == prefix;
}

bool EndsWith(const StringPiece& str, const StringPiece& suffix) {
  if (str.size() < suffix.size()) {
    return false;
  }
  return str.substr(str.size() - suffix.size(), suffix.size()) == suffix;
}

StringPiece TrimLeadingWhitespace(const StringPiece& str) {
  if (str.size() == 0 || str.data() == nullptr) {
    return str;
  }

  const char* start = str.data();
  const char* end = start + str.length();

  while (start != end && isspace(*start)) {
    start++;
  }
  return StringPiece(start, end - start);
}

StringPiece TrimTrailingWhitespace(const StringPiece& str) {
  if (str.size() == 0 || str.data() == nullptr) {
    return str;
  }

  const char* start = str.data();
  const char* end = start + str.length();

  while (end != start && isspace(*(end - 1))) {
    end--;
  }
  return StringPiece(start, end - start);
}

StringPiece TrimWhitespace(const StringPiece& str) {
  if (str.size() == 0 || str.data() == nullptr) {
    return str;
  }

  const char* start = str.data();
  const char* end = str.data() + str.length();

  while (start != end && isspace(*start)) {
    start++;
  }

  while (end != start && isspace(*(end - 1))) {
    end--;
  }

  return StringPiece(start, end - start);
}

static int IsJavaNameImpl(const StringPiece& str) {
  int pieces = 0;
  for (const StringPiece& piece : Tokenize(str, '.')) {
    pieces++;
    if (!text::IsJavaIdentifier(piece)) {
      return -1;
    }
  }
  return pieces;
}

bool IsJavaClassName(const StringPiece& str) {
  return IsJavaNameImpl(str) >= 2;
}

bool IsJavaPackageName(const StringPiece& str) {
  return IsJavaNameImpl(str) >= 1;
}

static int IsAndroidNameImpl(const StringPiece& str) {
  int pieces = 0;
  for (const StringPiece& piece : Tokenize(str, '.')) {
    if (piece.empty()) {
      return -1;
    }

    const char first_character = piece.data()[0];
    if (!::isalpha(first_character)) {
      return -1;
    }

    bool valid = std::all_of(piece.begin() + 1, piece.end(), [](const char c) -> bool {
      return ::isalnum(c) || c == '_';
    });

    if (!valid) {
      return -1;
    }
    pieces++;
  }
  return pieces;
}

bool IsAndroidPackageName(const StringPiece& str) {
  return IsAndroidNameImpl(str) > 1 || str == "android";
}

bool IsAndroidSplitName(const StringPiece& str) {
  return IsAndroidNameImpl(str) > 0;
}

Maybe<std::string> GetFullyQualifiedClassName(const StringPiece& package,
                                              const StringPiece& classname) {
  if (classname.empty()) {
    return {};
  }

  if (util::IsJavaClassName(classname)) {
    return classname.to_string();
  }

  if (package.empty()) {
    return {};
  }

  std::string result = package.to_string();
  if (classname.data()[0] != '.') {
    result += '.';
  }

  result.append(classname.data(), classname.size());
  if (!IsJavaClassName(result)) {
    return {};
  }
  return result;
}

static size_t ConsumeDigits(const char* start, const char* end) {
  const char* c = start;
  for (; c != end && *c >= '0' && *c <= '9'; c++) {
  }
  return static_cast<size_t>(c - start);
}

bool VerifyJavaStringFormat(const StringPiece& str) {
  const char* c = str.begin();
  const char* const end = str.end();

  size_t arg_count = 0;
  bool nonpositional = false;
  while (c != end) {
    if (*c == '%' && c + 1 < end) {
      c++;

      if (*c == '%' || *c == 'n') {
        c++;
        continue;
      }

      arg_count++;

      size_t num_digits = ConsumeDigits(c, end);
      if (num_digits > 0) {
        c += num_digits;
        if (c != end && *c != '$') {
          // The digits were a size, but not a positional argument.
          nonpositional = true;
        }
      } else if (*c == '<') {
        // Reusing last argument, bad idea since positions can be moved around
        // during translation.
        nonpositional = true;

        c++;

        // Optionally we can have a $ after
        if (c != end && *c == '$') {
          c++;
        }
      } else {
        nonpositional = true;
      }

      // Ignore size, width, flags, etc.
      while (c != end && (*c == '-' || *c == '#' || *c == '+' || *c == ' ' ||
                          *c == ',' || *c == '(' || (*c >= '0' && *c <= '9'))) {
        c++;
      }

      /*
       * This is a shortcut to detect strings that are going to Time.format()
       * instead of String.format()
       *
       * Comparison of String.format() and Time.format() args:
       *
       * String: ABC E GH  ST X abcdefgh  nost x
       *   Time:    DEFGHKMS W Za  d   hkm  s w yz
       *
       * Therefore we know it's definitely Time if we have:
       *     DFKMWZkmwyz
       */
      if (c != end) {
        switch (*c) {
          case 'D':
          case 'F':
          case 'K':
          case 'M':
          case 'W':
          case 'Z':
          case 'k':
          case 'm':
          case 'w':
          case 'y':
          case 'z':
            return true;
        }
      }
    }

    if (c != end) {
      c++;
    }
  }

  if (arg_count > 1 && nonpositional) {
    // Multiple arguments were specified, but some or all were non positional.
    // Translated
    // strings may rearrange the order of the arguments, which will break the
    // string.
    return false;
  }
  return true;
}

std::u16string Utf8ToUtf16(const StringPiece& utf8) {
  ssize_t utf16_length = utf8_to_utf16_length(
      reinterpret_cast<const uint8_t*>(utf8.data()), utf8.length());
  if (utf16_length <= 0) {
    return {};
  }

  std::u16string utf16;
  utf16.resize(utf16_length);
  utf8_to_utf16(reinterpret_cast<const uint8_t*>(utf8.data()), utf8.length(),
                &*utf16.begin(), utf16_length + 1);
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

bool WriteAll(std::ostream& out, const BigBuffer& buffer) {
  for (const auto& b : buffer) {
    if (!out.write(reinterpret_cast<const char*>(b.buffer.get()), b.size)) {
      return false;
    }
  }
  return true;
}

std::unique_ptr<uint8_t[]> Copy(const BigBuffer& buffer) {
  std::unique_ptr<uint8_t[]> data =
      std::unique_ptr<uint8_t[]>(new uint8_t[buffer.size()]);
  uint8_t* p = data.get();
  for (const auto& block : buffer) {
    memcpy(p, block.buffer.get(), block.size);
    p += block.size;
  }
  return data;
}

typename Tokenizer::iterator& Tokenizer::iterator::operator++() {
  const char* start = token_.end();
  const char* end = str_.end();
  if (start == end) {
    end_ = true;
    token_.assign(token_.end(), 0);
    return *this;
  }

  start += 1;
  const char* current = start;
  while (current != end) {
    if (*current == separator_) {
      token_.assign(start, current - start);
      return *this;
    }
    ++current;
  }
  token_.assign(start, end - start);
  return *this;
}

bool Tokenizer::iterator::operator==(const iterator& rhs) const {
  // We check equality here a bit differently.
  // We need to know that the addresses are the same.
  return token_.begin() == rhs.token_.begin() &&
         token_.end() == rhs.token_.end() && end_ == rhs.end_;
}

bool Tokenizer::iterator::operator!=(const iterator& rhs) const {
  return !(*this == rhs);
}

Tokenizer::iterator::iterator(const StringPiece& s, char sep, const StringPiece& tok, bool end)
    : str_(s), separator_(sep), token_(tok), end_(end) {}

Tokenizer::Tokenizer(const StringPiece& str, char sep)
    : begin_(++iterator(str, sep, StringPiece(str.begin() - 1, 0), false)),
      end_(str, sep, StringPiece(str.end(), 0), true) {}

bool ExtractResFilePathParts(const StringPiece& path, StringPiece* out_prefix,
                             StringPiece* out_entry, StringPiece* out_suffix) {
  const StringPiece res_prefix("res/");
  if (!StartsWith(path, res_prefix)) {
    return false;
  }

  StringPiece::const_iterator last_occurence = path.end();
  for (auto iter = path.begin() + res_prefix.size(); iter != path.end();
       ++iter) {
    if (*iter == '/') {
      last_occurence = iter;
    }
  }

  if (last_occurence == path.end()) {
    return false;
  }

  auto iter = std::find(last_occurence, path.end(), '.');
  *out_suffix = StringPiece(iter, path.end() - iter);
  *out_entry = StringPiece(last_occurence + 1, iter - last_occurence - 1);
  *out_prefix = StringPiece(path.begin(), last_occurence - path.begin() + 1);
  return true;
}

StringPiece16 GetString16(const android::ResStringPool& pool, size_t idx) {
  size_t len;
  const char16_t* str = pool.stringAt(idx, &len);
  if (str != nullptr) {
    return StringPiece16(str, len);
  }
  return StringPiece16();
}

std::string GetString(const android::ResStringPool& pool, size_t idx) {
  size_t len;
  const char* str = pool.string8At(idx, &len);
  if (str != nullptr) {
    return std::string(str, len);
  }
  return Utf16ToUtf8(GetString16(pool, idx));
}

}  // namespace util
}  // namespace aapt
