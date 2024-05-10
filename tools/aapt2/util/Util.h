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

#ifndef AAPT_UTIL_H
#define AAPT_UTIL_H

#include <functional>
#include <memory>
#include <ostream>
#include <string>
#include <vector>

#include "androidfw/BigBuffer.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"
#include "utils/ByteOrder.h"

#ifdef _WIN32
// TODO(adamlesinski): remove once http://b/32447322 is resolved.
// utils/ByteOrder.h includes winsock2.h on WIN32,
// which will pull in the ERROR definition. This conflicts
// with android-base/logging.h, which takes care of undefining
// ERROR, but it gets included too early (before winsock2.h).
#ifdef ERROR
#undef ERROR
#endif
#endif

namespace aapt {
namespace util {

template <typename T>
struct Range {
  T start;
  T end;
};

std::vector<std::string> Split(android::StringPiece str, char sep);
std::vector<std::string> SplitAndLowercase(android::StringPiece str, char sep);

// Returns true if the string starts with prefix.
bool StartsWith(android::StringPiece str, android::StringPiece prefix);

// Returns true if the string ends with suffix.
bool EndsWith(android::StringPiece str, android::StringPiece suffix);

// Creates a new StringPiece that points to a substring of the original string without leading
// whitespace.
android::StringPiece TrimLeadingWhitespace(android::StringPiece str);

// Creates a new StringPiece that points to a substring of the original string without trailing
// whitespace.
android::StringPiece TrimTrailingWhitespace(android::StringPiece str);

// Creates a new StringPiece that points to a substring of the original string without leading or
// trailing whitespace.
android::StringPiece TrimWhitespace(android::StringPiece str);

// Tests that the string is a valid Java class name.
bool IsJavaClassName(android::StringPiece str);

// Tests that the string is a valid Java package name.
bool IsJavaPackageName(android::StringPiece str);

// Tests that the string is a valid Android package name. More strict than a Java package name.
// - First character of each component (separated by '.') must be an ASCII letter.
// - Subsequent characters of a component can be ASCII alphanumeric or an underscore.
// - Package must contain at least two components, unless it is 'android'.
// - The maximum package name length is 223.
bool IsAndroidPackageName(android::StringPiece str);

// Tests that the string is a valid Android split name.
// - First character of each component (separated by '.') must be an ASCII letter.
// - Subsequent characters of a component can be ASCII alphanumeric or an underscore.
bool IsAndroidSplitName(android::StringPiece str);

// Tests that the string is a valid Android shared user id.
// - First character of each component (separated by '.') must be an ASCII letter.
// - Subsequent characters of a component can be ASCII alphanumeric or an underscore.
// - Must contain at least two components, unless package name is 'android'.
// - The maximum shared user id length is 223.
// - Treat empty string as valid, it's the case of no shared user id.
bool IsAndroidSharedUserId(android::StringPiece package_name, android::StringPiece shared_user_id);

// Converts the class name to a fully qualified class name from the given
// `package`. Ex:
//
// asdf         --> package.asdf
// .asdf        --> package.asdf
// .a.b         --> package.a.b
// asdf.adsf    --> asdf.adsf
std::optional<std::string> GetFullyQualifiedClassName(android::StringPiece package,
                                                      android::StringPiece class_name);

// Retrieves the formatted name of aapt2.
const char* GetToolName();

// Retrieves the build fingerprint of aapt2.
std::string GetToolFingerprint();

template <typename T>
typename std::enable_if<std::is_arithmetic<T>::value, int>::type compare(const T& a, const T& b) {
  if (a < b) {
    return -1;
  } else if (a > b) {
    return 1;
  }
  return 0;
}

// Makes a std::unique_ptr<> with the template parameter inferred by the compiler.
// This will be present in C++14 and can be removed then.
template <typename T, class... Args>
std::unique_ptr<T> make_unique(Args&&... args) {
  return std::unique_ptr<T>(new T{std::forward<Args>(args)...});
}

// Writes a set of items to the std::ostream, joining the times with the provided separator.
template <typename Container>
::std::function<::std::ostream&(::std::ostream&)> Joiner(const Container& container,
                                                         const char* sep) {
  using std::begin;
  using std::end;
  const auto begin_iter = begin(container);
  const auto end_iter = end(container);
  return [begin_iter, end_iter, sep](::std::ostream& out) -> ::std::ostream& {
    for (auto iter = begin_iter; iter != end_iter; ++iter) {
      if (iter != begin_iter) {
        out << sep;
      }
      out << *iter;
    }
    return out;
  };
}

// Checks that the Java string format contains no non-positional arguments (arguments without
// explicitly specifying an index) when there are more than one argument. This is an error
// because translations may rearrange the order of the arguments in the string, which will
// break the string interpolation.
bool VerifyJavaStringFormat(android::StringPiece str);

bool AppendStyledString(android::StringPiece input, bool preserve_spaces, std::string* out_str,
                        std::string* out_error);

class StringBuilder {
 public:
  StringBuilder() = default;

  StringBuilder& Append(android::StringPiece str);
  const std::string& ToString() const;
  const std::string& Error() const;
  bool IsEmpty() const;

  // When building StyledStrings, we need UTF-16 indices into the string,
  // which is what the Java layer expects when dealing with java
  // String.charAt().
  size_t Utf16Len() const;

  explicit operator bool() const;

 private:
  std::string str_;
  size_t utf16_len_ = 0;
  bool quote_ = false;
  bool trailing_space_ = false;
  bool last_char_was_escape_ = false;
  std::string error_;
};

inline const std::string& StringBuilder::ToString() const {
  return str_;
}

inline const std::string& StringBuilder::Error() const {
  return error_;
}

inline bool StringBuilder::IsEmpty() const {
  return str_.empty();
}

inline size_t StringBuilder::Utf16Len() const {
  return utf16_len_;
}

inline StringBuilder::operator bool() const {
  return error_.empty();
}

// Writes the entire BigBuffer to the output stream.
bool WriteAll(std::ostream& out, const android::BigBuffer& buffer);

// A Tokenizer implemented as an iterable collection. It does not allocate any memory on the heap
// nor use standard containers.
class Tokenizer {
 public:
  class iterator {
   public:
    using reference = android::StringPiece&;
    using value_type = android::StringPiece;
    using difference_type = size_t;
    using pointer = android::StringPiece*;
    using iterator_category = std::forward_iterator_tag;

    iterator(const iterator&) = default;
    iterator& operator=(const iterator&) = default;

    iterator& operator++();

    android::StringPiece operator*() { return token_; }
    bool operator==(const iterator& rhs) const;
    bool operator!=(const iterator& rhs) const;

   private:
    friend class Tokenizer;

    iterator(android::StringPiece s, char sep, android::StringPiece tok, bool end);

    android::StringPiece str_;
    char separator_;
    android::StringPiece token_;
    bool end_;
  };

  Tokenizer(android::StringPiece str, char sep);

  iterator begin() const {
    return begin_;
  }

  iterator end() const {
    return end_;
  }

 private:
  const iterator begin_;
  const iterator end_;
};

inline Tokenizer Tokenize(android::StringPiece str, char sep) {
  return Tokenizer(str, sep);
}

// Given a path like: res/xml-sw600dp/foo.xml
//
// Extracts "res/xml-sw600dp/" into outPrefix.
// Extracts "foo" into outEntry.
// Extracts ".xml" into outSuffix.
//
// Returns true if successful.
bool ExtractResFilePathParts(android::StringPiece path, android::StringPiece* out_prefix,
                             android::StringPiece* out_entry, android::StringPiece* out_suffix);

}  // namespace util

}  // namespace aapt

namespace std {
// Stream operator for functions. Calls the function with the stream as an argument.
// In the aapt namespace for lookup.
inline ::std::ostream& operator<<(::std::ostream& out,
                                  const ::std::function<::std::ostream&(::std::ostream&)>& f) {
  return f(out);
}
}  // namespace std

#endif  // AAPT_UTIL_H
