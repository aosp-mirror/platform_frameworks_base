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

#include "android-base/stringprintf.h"
#include "androidfw/StringPiece.h"
#include "build/version.h"

#include "text/Unicode.h"
#include "text/Utf8Iterator.h"
#include "util/BigBuffer.h"
#include "util/Maybe.h"
#include "utils/Unicode.h"

using ::aapt::text::Utf8Iterator;
using ::android::StringPiece;
using ::android::StringPiece16;

namespace aapt {
namespace util {

// Package name and shared user id would be used as a part of the file name.
// Limits size to 223 and reserves 32 for the OS.
// See frameworks/base/core/java/android/content/pm/parsing/ParsingPackageUtils.java
constexpr static const size_t kMaxPackageNameSize = 223;

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
  if (str.size() > kMaxPackageNameSize) {
    return false;
  }
  return IsAndroidNameImpl(str) > 1 || str == "android";
}

bool IsAndroidSharedUserId(const android::StringPiece& package_name,
                           const android::StringPiece& shared_user_id) {
  if (shared_user_id.size() > kMaxPackageNameSize) {
    return false;
  }
  return shared_user_id.empty() || IsAndroidNameImpl(shared_user_id) > 1 ||
         package_name == "android";
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

const char* GetToolName() {
  static const char* const sToolName = "Android Asset Packaging Tool (aapt)";
  return sToolName;
}

std::string GetToolFingerprint() {
  // DO NOT UPDATE, this is more of a marketing version.
  static const char* const sMajorVersion = "2";

  // Update minor version whenever a feature or flag is added.
  static const char* const sMinorVersion = "19";

  // The build id of aapt2 binary.
  static const std::string sBuildId = android::build::GetBuildNumber();

  return android::base::StringPrintf("%s.%s-%s", sMajorVersion, sMinorVersion, sBuildId.c_str());
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

std::string Utf8ToModifiedUtf8(const std::string& utf8) {
  // Java uses Modified UTF-8 which only supports the 1, 2, and 3 byte formats of UTF-8. To encode
  // 4 byte UTF-8 codepoints, Modified UTF-8 allows the use of surrogate pairs in the same format
  // of CESU-8 surrogate pairs. Calculate the size of the utf8 string with all 4 byte UTF-8
  // codepoints replaced with 2 3 byte surrogate pairs
  size_t modified_size = 0;
  const size_t size = utf8.size();
  for (size_t i = 0; i < size; i++) {
    if (((uint8_t) utf8[i] >> 4) == 0xF) {
      modified_size += 6;
      i += 3;
    } else {
      modified_size++;
    }
  }

  // Early out if no 4 byte codepoints are found
  if (size == modified_size) {
    return utf8;
  }

  std::string output;
  output.reserve(modified_size);
  for (size_t i = 0; i < size; i++) {
    if (((uint8_t) utf8[i] >> 4) == 0xF) {
      int32_t codepoint = utf32_from_utf8_at(utf8.data(), size, i, nullptr);

      // Calculate the high and low surrogates as UTF-16 would
      int32_t high = ((codepoint - 0x10000) / 0x400) + 0xD800;
      int32_t low = ((codepoint - 0x10000) % 0x400) + 0xDC00;

      // Encode each surrogate in UTF-8
      output.push_back((char) (0xE4 | ((high >> 12) & 0xF)));
      output.push_back((char) (0x80 | ((high >> 6) & 0x3F)));
      output.push_back((char) (0x80 | (high & 0x3F)));
      output.push_back((char) (0xE4 | ((low >> 12) & 0xF)));
      output.push_back((char) (0x80 | ((low >> 6) & 0x3F)));
      output.push_back((char) (0x80 | (low & 0x3F)));
      i += 3;
    } else {
      output.push_back(utf8[i]);
    }
  }

  return output;
}

std::string ModifiedUtf8ToUtf8(const std::string& modified_utf8) {
  // The UTF-8 representation will have a byte length less than or equal to the Modified UTF-8
  // representation.
  std::string output;
  output.reserve(modified_utf8.size());

  size_t index = 0;
  const size_t modified_size = modified_utf8.size();
  while (index < modified_size) {
    size_t next_index;
    int32_t high_surrogate = utf32_from_utf8_at(modified_utf8.data(), modified_size, index,
                                                &next_index);
    if (high_surrogate < 0) {
      return {};
    }

    // Check that the first codepoint is within the high surrogate range
    if (high_surrogate >= 0xD800 && high_surrogate <= 0xDB7F) {
      int32_t low_surrogate = utf32_from_utf8_at(modified_utf8.data(), modified_size, next_index,
                                                 &next_index);
      if (low_surrogate < 0) {
        return {};
      }

      // Check that the second codepoint is within the low surrogate range
      if (low_surrogate >= 0xDC00 && low_surrogate <= 0xDFFF) {
        const char32_t codepoint = (char32_t) (((high_surrogate - 0xD800) * 0x400)
            + (low_surrogate - 0xDC00) + 0x10000);

        // The decoded codepoint should represent a 4 byte, UTF-8 character
        const size_t utf8_length = (size_t) utf32_to_utf8_length(&codepoint, 1);
        if (utf8_length != 4) {
          return {};
        }

        // Encode the UTF-8 representation of the codepoint into the string
        char* start = &output[output.size()];
        output.resize(output.size() + utf8_length);
        utf32_to_utf8((char32_t*) &codepoint, 1, start, utf8_length + 1);

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
  if (auto str = pool.stringAt(idx); str.ok()) {
    return *str;
  }
  return StringPiece16();
}

std::string GetString(const android::ResStringPool& pool, size_t idx) {
  if (auto str = pool.string8At(idx); str.ok()) {
    return ModifiedUtf8ToUtf8(str->to_string());
  }
  return Utf16ToUtf8(GetString16(pool, idx));
}

}  // namespace util
}  // namespace aapt
