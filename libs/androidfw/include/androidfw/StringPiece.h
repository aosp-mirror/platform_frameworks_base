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

#include "utils/JenkinsHash.h"
#include "utils/Unicode.h"

namespace android {

// Read only wrapper around basic C strings. Prevents excessive copying.
// StringPiece does not own the data it is wrapping. The lifetime of the underlying
// data must outlive this StringPiece.
//
// WARNING: When creating from std::basic_string<>, moving the original
// std::basic_string<> will invalidate the data held in a BasicStringPiece<>.
// BasicStringPiece<> should only be used transitively.
//
// NOTE: When creating an std::pair<StringPiece, T> using std::make_pair(),
// passing an std::string will first copy the string, then create a StringPiece
// on the copy, which is then immediately destroyed.
// Instead, create a StringPiece explicitly:
//
// std::string my_string = "foo";
// std::make_pair<StringPiece, T>(StringPiece(my_string), ...);
template <typename TChar>
class BasicStringPiece {
 public:
  using const_iterator = const TChar*;
  using difference_type = size_t;
  using size_type = size_t;

  // End of string marker.
  constexpr static const size_t npos = static_cast<size_t>(-1);

  BasicStringPiece();
  BasicStringPiece(const BasicStringPiece<TChar>& str);
  BasicStringPiece(const std::basic_string<TChar>& str);  // NOLINT(google-explicit-constructor)
  BasicStringPiece(const TChar* str);                     // NOLINT(google-explicit-constructor)
  BasicStringPiece(const TChar* str, size_t len);

  BasicStringPiece<TChar>& operator=(const BasicStringPiece<TChar>& rhs);
  BasicStringPiece<TChar>& assign(const TChar* str, size_t len);

  BasicStringPiece<TChar> substr(size_t start, size_t len = npos) const;
  BasicStringPiece<TChar> substr(BasicStringPiece<TChar>::const_iterator begin,
                                 BasicStringPiece<TChar>::const_iterator end) const;

  const TChar* data() const;
  size_t length() const;
  size_t size() const;
  bool empty() const;
  std::basic_string<TChar> to_string() const;

  bool contains(const BasicStringPiece<TChar>& rhs) const;
  int compare(const BasicStringPiece<TChar>& rhs) const;
  bool operator<(const BasicStringPiece<TChar>& rhs) const;
  bool operator>(const BasicStringPiece<TChar>& rhs) const;
  bool operator==(const BasicStringPiece<TChar>& rhs) const;
  bool operator!=(const BasicStringPiece<TChar>& rhs) const;

  const_iterator begin() const;
  const_iterator end() const;

 private:
  const TChar* data_;
  size_t length_;
};

using StringPiece = BasicStringPiece<char>;
using StringPiece16 = BasicStringPiece<char16_t>;

//
// BasicStringPiece implementation.
//

template <typename TChar>
constexpr const size_t BasicStringPiece<TChar>::npos;

template <typename TChar>
inline BasicStringPiece<TChar>::BasicStringPiece() : data_(nullptr), length_(0) {}

template <typename TChar>
inline BasicStringPiece<TChar>::BasicStringPiece(const BasicStringPiece<TChar>& str)
    : data_(str.data_), length_(str.length_) {}

template <typename TChar>
inline BasicStringPiece<TChar>::BasicStringPiece(const std::basic_string<TChar>& str)
    : data_(str.data()), length_(str.length()) {}

template <>
inline BasicStringPiece<char>::BasicStringPiece(const char* str)
    : data_(str), length_(str != nullptr ? strlen(str) : 0) {}

template <>
inline BasicStringPiece<char16_t>::BasicStringPiece(const char16_t* str)
    : data_(str), length_(str != nullptr ? strlen16(str) : 0) {}

template <typename TChar>
inline BasicStringPiece<TChar>::BasicStringPiece(const TChar* str, size_t len)
    : data_(str), length_(len) {}

template <typename TChar>
inline BasicStringPiece<TChar>& BasicStringPiece<TChar>::operator=(
    const BasicStringPiece<TChar>& rhs) {
  data_ = rhs.data_;
  length_ = rhs.length_;
  return *this;
}

template <typename TChar>
inline BasicStringPiece<TChar>& BasicStringPiece<TChar>::assign(const TChar* str, size_t len) {
  data_ = str;
  length_ = len;
  return *this;
}

template <typename TChar>
inline BasicStringPiece<TChar> BasicStringPiece<TChar>::substr(size_t start, size_t len) const {
  if (len == npos) {
    len = length_ - start;
  }

  if (start > length_ || start + len > length_) {
    return BasicStringPiece<TChar>();
  }
  return BasicStringPiece<TChar>(data_ + start, len);
}

template <typename TChar>
inline BasicStringPiece<TChar> BasicStringPiece<TChar>::substr(
    BasicStringPiece<TChar>::const_iterator begin,
    BasicStringPiece<TChar>::const_iterator end) const {
  return BasicStringPiece<TChar>(begin, end - begin);
}

template <typename TChar>
inline const TChar* BasicStringPiece<TChar>::data() const {
  return data_;
}

template <typename TChar>
inline size_t BasicStringPiece<TChar>::length() const {
  return length_;
}

template <typename TChar>
inline size_t BasicStringPiece<TChar>::size() const {
  return length_;
}

template <typename TChar>
inline bool BasicStringPiece<TChar>::empty() const {
  return length_ == 0;
}

template <typename TChar>
inline std::basic_string<TChar> BasicStringPiece<TChar>::to_string() const {
  return std::basic_string<TChar>(data_, length_);
}

template <>
inline bool BasicStringPiece<char>::contains(const BasicStringPiece<char>& rhs) const {
  if (!data_ || !rhs.data_) {
    return false;
  }
  if (rhs.length_ > length_) {
    return false;
  }
  return strstr(data_, rhs.data_) != nullptr;
}

template <>
inline int BasicStringPiece<char>::compare(const BasicStringPiece<char>& rhs) const {
  const char nullStr = '\0';
  const char* b1 = data_ != nullptr ? data_ : &nullStr;
  const char* e1 = b1 + length_;
  const char* b2 = rhs.data_ != nullptr ? rhs.data_ : &nullStr;
  const char* e2 = b2 + rhs.length_;

  while (b1 < e1 && b2 < e2) {
    const int d = static_cast<int>(*b1++) - static_cast<int>(*b2++);
    if (d) {
      return d;
    }
  }
  return static_cast<int>(length_ - rhs.length_);
}

inline ::std::ostream& operator<<(::std::ostream& out, const BasicStringPiece<char16_t>& str) {
  const ssize_t result_len = utf16_to_utf8_length(str.data(), str.size());
  if (result_len < 0) {
    // Empty string.
    return out;
  }

  std::string result;
  result.resize(static_cast<size_t>(result_len));
  utf16_to_utf8(str.data(), str.length(), &*result.begin(), static_cast<size_t>(result_len) + 1);
  return out << result;
}

template <>
inline bool BasicStringPiece<char16_t>::contains(const BasicStringPiece<char16_t>& rhs) const {
  if (!data_ || !rhs.data_) {
    return false;
  }
  if (rhs.length_ > length_) {
    return false;
  }
  return strstr16(data_, rhs.data_) != nullptr;
}

template <>
inline int BasicStringPiece<char16_t>::compare(const BasicStringPiece<char16_t>& rhs) const {
  const char16_t nullStr = u'\0';
  const char16_t* b1 = data_ != nullptr ? data_ : &nullStr;
  const char16_t* b2 = rhs.data_ != nullptr ? rhs.data_ : &nullStr;
  return strzcmp16(b1, length_, b2, rhs.length_);
}

template <typename TChar>
inline bool BasicStringPiece<TChar>::operator<(const BasicStringPiece<TChar>& rhs) const {
  return compare(rhs) < 0;
}

template <typename TChar>
inline bool BasicStringPiece<TChar>::operator>(const BasicStringPiece<TChar>& rhs) const {
  return compare(rhs) > 0;
}

template <typename TChar>
inline bool BasicStringPiece<TChar>::operator==(const BasicStringPiece<TChar>& rhs) const {
  return compare(rhs) == 0;
}

template <typename TChar>
inline bool BasicStringPiece<TChar>::operator!=(const BasicStringPiece<TChar>& rhs) const {
  return compare(rhs) != 0;
}

template <typename TChar>
inline typename BasicStringPiece<TChar>::const_iterator BasicStringPiece<TChar>::begin() const {
  return data_;
}

template <typename TChar>
inline typename BasicStringPiece<TChar>::const_iterator BasicStringPiece<TChar>::end() const {
  return data_ + length_;
}

template <typename TChar>
inline bool operator==(const TChar* lhs, const BasicStringPiece<TChar>& rhs) {
  return BasicStringPiece<TChar>(lhs) == rhs;
}

template <typename TChar>
inline bool operator!=(const TChar* lhs, const BasicStringPiece<TChar>& rhs) {
  return BasicStringPiece<TChar>(lhs) != rhs;
}

inline ::std::ostream& operator<<(::std::ostream& out, const BasicStringPiece<char>& str) {
  return out.write(str.data(), str.size());
}

template <typename TChar>
inline ::std::basic_string<TChar>& operator+=(::std::basic_string<TChar>& lhs,
                                              const BasicStringPiece<TChar>& rhs) {
  return lhs.append(rhs.data(), rhs.size());
}

template <typename TChar>
inline bool operator==(const ::std::basic_string<TChar>& lhs, const BasicStringPiece<TChar>& rhs) {
  return rhs == lhs;
}

template <typename TChar>
inline bool operator!=(const ::std::basic_string<TChar>& lhs, const BasicStringPiece<TChar>& rhs) {
  return rhs != lhs;
}

}  // namespace android

inline ::std::ostream& operator<<(::std::ostream& out, const std::u16string& str) {
  ssize_t utf8_len = utf16_to_utf8_length(str.data(), str.size());
  if (utf8_len < 0) {
    return out << "???";
  }

  std::string utf8;
  utf8.resize(static_cast<size_t>(utf8_len));
  utf16_to_utf8(str.data(), str.size(), &*utf8.begin(), utf8_len + 1);
  return out << utf8;
}

namespace std {

template <typename TChar>
struct hash<android::BasicStringPiece<TChar>> {
  size_t operator()(const android::BasicStringPiece<TChar>& str) const {
    uint32_t hashCode = android::JenkinsHashMixBytes(
        0, reinterpret_cast<const uint8_t*>(str.data()), sizeof(TChar) * str.size());
    return static_cast<size_t>(hashCode);
  }
};

}  // namespace std

#endif  // ANDROIDFW_STRING_PIECE_H
