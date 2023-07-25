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

#include "android-base/parseint.h"
#include "android-base/stringprintf.h"
#include "android-base/strings.h"
#include "androidfw/BigBuffer.h"
#include "androidfw/StringPiece.h"
#include "androidfw/Util.h"
#include "build/version.h"
#include "text/Unicode.h"
#include "text/Utf8Iterator.h"
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

static std::vector<std::string> SplitAndTransform(StringPiece str, char sep, char (*f)(char)) {
  std::vector<std::string> parts;
  const StringPiece::const_iterator end = std::end(str);
  StringPiece::const_iterator start = std::begin(str);
  StringPiece::const_iterator current;
  do {
    current = std::find(start, end, sep);
    parts.emplace_back(start, current);
    if (f) {
      std::string& part = parts.back();
      std::transform(part.begin(), part.end(), part.begin(), f);
    }
    start = current + 1;
  } while (current != end);
  return parts;
}

std::vector<std::string> Split(StringPiece str, char sep) {
  return SplitAndTransform(str, sep, nullptr);
}

std::vector<std::string> SplitAndLowercase(StringPiece str, char sep) {
  return SplitAndTransform(str, sep, [](char c) -> char { return ::tolower(c); });
}

bool StartsWith(StringPiece str, StringPiece prefix) {
  if (str.size() < prefix.size()) {
    return false;
  }
  return str.substr(0, prefix.size()) == prefix;
}

bool EndsWith(StringPiece str, StringPiece suffix) {
  if (str.size() < suffix.size()) {
    return false;
  }
  return str.substr(str.size() - suffix.size(), suffix.size()) == suffix;
}

StringPiece TrimLeadingWhitespace(StringPiece str) {
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

StringPiece TrimTrailingWhitespace(StringPiece str) {
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

StringPiece TrimWhitespace(StringPiece str) {
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

static int IsJavaNameImpl(StringPiece str) {
  int pieces = 0;
  for (StringPiece piece : Tokenize(str, '.')) {
    pieces++;
    if (!text::IsJavaIdentifier(piece)) {
      return -1;
    }
  }
  return pieces;
}

bool IsJavaClassName(StringPiece str) {
  return IsJavaNameImpl(str) >= 2;
}

bool IsJavaPackageName(StringPiece str) {
  return IsJavaNameImpl(str) >= 1;
}

static int IsAndroidNameImpl(StringPiece str) {
  int pieces = 0;
  for (StringPiece piece : Tokenize(str, '.')) {
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

bool IsAndroidPackageName(StringPiece str) {
  if (str.size() > kMaxPackageNameSize) {
    return false;
  }
  return IsAndroidNameImpl(str) > 1 || str == "android";
}

bool IsAndroidSharedUserId(android::StringPiece package_name, android::StringPiece shared_user_id) {
  if (shared_user_id.size() > kMaxPackageNameSize) {
    return false;
  }
  return shared_user_id.empty() || IsAndroidNameImpl(shared_user_id) > 1 ||
         package_name == "android";
}

bool IsAndroidSplitName(StringPiece str) {
  return IsAndroidNameImpl(str) > 0;
}

std::optional<std::string> GetFullyQualifiedClassName(StringPiece package, StringPiece classname) {
  if (classname.empty()) {
    return {};
  }

  if (util::IsJavaClassName(classname)) {
    return std::string(classname);
  }

  if (package.empty()) {
    return {};
  }

  std::string result{package};
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
  static const std::string sBuildId = [] {
    std::string buildNumber = android::build::GetBuildNumber();

    if (android::base::StartsWith(buildNumber, "eng.")) {
      // android::build::GetBuildNumber() returns something like "eng.user.20230725.214219" where
      // the latter two parts are "yyyyMMdd.HHmmss" at build time. Use "yyyyMM" in the fingerprint.
      std::vector<std::string> parts = util::Split(buildNumber, '.');
      int buildYear;
      int buildMonth;
      if (parts.size() < 3 || parts[2].length() < 6 ||
          !android::base::ParseInt(parts[2].substr(0, 4), &buildYear) ||
          !android::base::ParseInt(parts[2].substr(4, 2), &buildMonth)) {
        // Fallback to localtime() if GetBuildNumber() returns an unexpected output.
        time_t now = time(0);
        tm* ltm = localtime(&now);
        buildYear = 1900 + ltm->tm_year;
        buildMonth = 1 + ltm->tm_mon;
      }

      buildNumber = android::base::StringPrintf("eng.%04d%02d", buildYear, buildMonth);
    }
    return buildNumber;
  }();

  return android::base::StringPrintf("%s.%s-%s", sMajorVersion, sMinorVersion, sBuildId.c_str());
}

static size_t ConsumeDigits(const char* start, const char* end) {
  const char* c = start;
  for (; c != end && *c >= '0' && *c <= '9'; c++) {
  }
  return static_cast<size_t>(c - start);
}

bool VerifyJavaStringFormat(StringPiece str) {
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

std::u16string Utf8ToUtf16(StringPiece utf8) {
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

bool WriteAll(std::ostream& out, const android::BigBuffer& buffer) {
  for (const auto& b : buffer) {
    if (!out.write(reinterpret_cast<const char*>(b.buffer.get()), b.size)) {
      return false;
    }
  }
  return true;
}

typename Tokenizer::iterator& Tokenizer::iterator::operator++() {
  const char* start = token_.end();
  const char* end = str_.end();
  if (start == end) {
    end_ = true;
    token_ = StringPiece(token_.end(), 0);
    return *this;
  }

  start += 1;
  const char* current = start;
  while (current != end) {
    if (*current == separator_) {
      token_ = StringPiece(start, current - start);
      return *this;
    }
    ++current;
  }
  token_ = StringPiece(start, end - start);
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

Tokenizer::iterator::iterator(StringPiece s, char sep, StringPiece tok, bool end)
    : str_(s), separator_(sep), token_(tok), end_(end) {
}

Tokenizer::Tokenizer(StringPiece str, char sep)
    : begin_(++iterator(str, sep, StringPiece(str.begin() - 1, 0), false)),
      end_(str, sep, StringPiece(str.end(), 0), true) {
}

bool ExtractResFilePathParts(StringPiece path, StringPiece* out_prefix, StringPiece* out_entry,
                             StringPiece* out_suffix) {
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

}  // namespace util
}  // namespace aapt
