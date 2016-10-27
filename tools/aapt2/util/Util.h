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

#include "androidfw/ResourceTypes.h"
#include "utils/ByteOrder.h"

#include "util/BigBuffer.h"
#include "util/Maybe.h"
#include "util/StringPiece.h"

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

std::vector<std::string> Split(const StringPiece& str, char sep);
std::vector<std::string> SplitAndLowercase(const StringPiece& str, char sep);

/**
 * Returns true if the string starts with prefix.
 */
bool StartsWith(const StringPiece& str, const StringPiece& prefix);

/**
 * Returns true if the string ends with suffix.
 */
bool EndsWith(const StringPiece& str, const StringPiece& suffix);

/**
 * Creates a new StringPiece16 that points to a substring
 * of the original string without leading or trailing whitespace.
 */
StringPiece TrimWhitespace(const StringPiece& str);

StringPiece TrimWhitespace(const StringPiece& str);

/**
 * UTF-16 isspace(). It basically checks for lower range characters that are
 * whitespace.
 */
inline bool isspace16(char16_t c) { return c < 0x0080 && isspace(c); }

/**
 * Returns an iterator to the first character that is not alpha-numeric and that
 * is not in the allowedChars set.
 */
StringPiece::const_iterator FindNonAlphaNumericAndNotInSet(
    const StringPiece& str, const StringPiece& allowed_chars);

/**
 * Tests that the string is a valid Java class name.
 */
bool IsJavaClassName(const StringPiece& str);

/**
 * Tests that the string is a valid Java package name.
 */
bool IsJavaPackageName(const StringPiece& str);

/**
 * Converts the class name to a fully qualified class name from the given
 * `package`. Ex:
 *
 * asdf         --> package.asdf
 * .asdf        --> package.asdf
 * .a.b         --> package.a.b
 * asdf.adsf    --> asdf.adsf
 */
Maybe<std::string> GetFullyQualifiedClassName(const StringPiece& package,
                                              const StringPiece& class_name);

/**
 * Makes a std::unique_ptr<> with the template parameter inferred by the
 * compiler.
 * This will be present in C++14 and can be removed then.
 */
template <typename T, class... Args>
std::unique_ptr<T> make_unique(Args&&... args) {
  return std::unique_ptr<T>(new T{std::forward<Args>(args)...});
}

/**
 * Writes a set of items to the std::ostream, joining the times with the
 * provided
 * separator.
 */
template <typename Container>
::std::function<::std::ostream&(::std::ostream&)> Joiner(
    const Container& container, const char* sep) {
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

/**
 * Helper method to extract a UTF-16 string from a StringPool. If the string is
 * stored as UTF-8,
 * the conversion to UTF-16 happens within ResStringPool.
 */
StringPiece16 GetString16(const android::ResStringPool& pool, size_t idx);

/**
 * Helper method to extract a UTF-8 string from a StringPool. If the string is
 * stored as UTF-16,
 * the conversion from UTF-16 to UTF-8 does not happen in ResStringPool and is
 * done by this method,
 * which maintains no state or cache. This means we must return an std::string
 * copy.
 */
std::string GetString(const android::ResStringPool& pool, size_t idx);

/**
 * Checks that the Java string format contains no non-positional arguments
 * (arguments without
 * explicitly specifying an index) when there are more than one argument. This
 * is an error
 * because translations may rearrange the order of the arguments in the string,
 * which will
 * break the string interpolation.
 */
bool VerifyJavaStringFormat(const StringPiece& str);

class StringBuilder {
 public:
  StringBuilder& Append(const StringPiece& str);
  const std::string& ToString() const;
  const std::string& Error() const;

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

inline const std::string& StringBuilder::ToString() const { return str_; }

inline const std::string& StringBuilder::Error() const { return error_; }

inline size_t StringBuilder::Utf16Len() const { return utf16_len_; }

inline StringBuilder::operator bool() const { return error_.empty(); }

/**
 * Converts a UTF8 string to a UTF16 string.
 */
std::u16string Utf8ToUtf16(const StringPiece& utf8);
std::string Utf16ToUtf8(const StringPiece16& utf16);

/**
 * Writes the entire BigBuffer to the output stream.
 */
bool WriteAll(std::ostream& out, const BigBuffer& buffer);

/*
 * Copies the entire BigBuffer into a single buffer.
 */
std::unique_ptr<uint8_t[]> Copy(const BigBuffer& buffer);

/**
 * A Tokenizer implemented as an iterable collection. It does not allocate
 * any memory on the heap nor use standard containers.
 */
class Tokenizer {
 public:
  class iterator {
   public:
    iterator(const iterator&) = default;
    iterator& operator=(const iterator&) = default;

    iterator& operator++();

    StringPiece operator*() { return token_; }
    bool operator==(const iterator& rhs) const;
    bool operator!=(const iterator& rhs) const;

   private:
    friend class Tokenizer;

    iterator(StringPiece s, char sep, StringPiece tok, bool end);

    StringPiece str_;
    char separator_;
    StringPiece token_;
    bool end_;
  };

  Tokenizer(StringPiece str, char sep);

  iterator begin() { return begin_; }

  iterator end() { return end_; }

 private:
  const iterator begin_;
  const iterator end_;
};

inline Tokenizer Tokenize(const StringPiece& str, char sep) {
  return Tokenizer(str, sep);
}

inline uint16_t HostToDevice16(uint16_t value) { return htods(value); }

inline uint32_t HostToDevice32(uint32_t value) { return htodl(value); }

inline uint16_t DeviceToHost16(uint16_t value) { return dtohs(value); }

inline uint32_t DeviceToHost32(uint32_t value) { return dtohl(value); }

/**
 * Given a path like: res/xml-sw600dp/foo.xml
 *
 * Extracts "res/xml-sw600dp/" into outPrefix.
 * Extracts "foo" into outEntry.
 * Extracts ".xml" into outSuffix.
 *
 * Returns true if successful.
 */
bool ExtractResFilePathParts(const StringPiece& path, StringPiece* out_prefix,
                             StringPiece* out_entry, StringPiece* out_suffix);

}  // namespace util

/**
 * Stream operator for functions. Calls the function with the stream as an
 * argument.
 * In the aapt namespace for lookup.
 */
inline ::std::ostream& operator<<(
    ::std::ostream& out,
    const ::std::function<::std::ostream&(::std::ostream&)>& f) {
  return f(out);
}

}  // namespace aapt

#endif  // AAPT_UTIL_H
