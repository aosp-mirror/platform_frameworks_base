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

#ifndef AAPT_STRING_PIECE_H
#define AAPT_STRING_PIECE_H

#include <ostream>
#include <string>
#include <utils/String8.h>
#include <utils/Unicode.h>

namespace aapt {

/**
 * Read only wrapper around basic C strings.
 * Prevents excessive copying.
 *
 * WARNING: When creating from std::basic_string<>, moving the original
 * std::basic_string<> will invalidate the data held in a BasicStringPiece<>.
 * BasicStringPiece<> should only be used transitively.
 */
template <typename TChar>
class BasicStringPiece {
public:
    using const_iterator = const TChar*;
    using difference_type = size_t;

    BasicStringPiece();
    BasicStringPiece(const BasicStringPiece<TChar>& str);
    BasicStringPiece(const std::basic_string<TChar>& str);
    BasicStringPiece(const TChar* str);
    BasicStringPiece(const TChar* str, size_t len);

    BasicStringPiece<TChar>& operator=(const BasicStringPiece<TChar>& rhs);
    BasicStringPiece<TChar>& assign(const TChar* str, size_t len);

    BasicStringPiece<TChar> substr(size_t start, size_t len) const;
    BasicStringPiece<TChar> substr(BasicStringPiece<TChar>::const_iterator begin,
                                   BasicStringPiece<TChar>::const_iterator end) const;

    const TChar* data() const;
    size_t length() const;
    size_t size() const;
    bool empty() const;
    std::basic_string<TChar> toString() const;

    bool contains(const BasicStringPiece<TChar>& rhs) const;
    int compare(const BasicStringPiece<TChar>& rhs) const;
    bool operator<(const BasicStringPiece<TChar>& rhs) const;
    bool operator>(const BasicStringPiece<TChar>& rhs) const;
    bool operator==(const BasicStringPiece<TChar>& rhs) const;
    bool operator!=(const BasicStringPiece<TChar>& rhs) const;

    const_iterator begin() const;
    const_iterator end() const;

private:
    const TChar* mData;
    size_t mLength;
};

using StringPiece = BasicStringPiece<char>;
using StringPiece16 = BasicStringPiece<char16_t>;

//
// BasicStringPiece implementation.
//

template <typename TChar>
inline BasicStringPiece<TChar>::BasicStringPiece() : mData(nullptr) , mLength(0) {
}

template <typename TChar>
inline BasicStringPiece<TChar>::BasicStringPiece(const BasicStringPiece<TChar>& str) :
        mData(str.mData), mLength(str.mLength) {
}

template <typename TChar>
inline BasicStringPiece<TChar>::BasicStringPiece(const std::basic_string<TChar>& str) :
        mData(str.data()), mLength(str.length()) {
}

template <>
inline BasicStringPiece<char>::BasicStringPiece(const char* str) :
        mData(str), mLength(str != nullptr ? strlen(str) : 0) {
}

template <>
inline BasicStringPiece<char16_t>::BasicStringPiece(const char16_t* str) :
        mData(str), mLength(str != nullptr ? strlen16(str) : 0) {
}

template <typename TChar>
inline BasicStringPiece<TChar>::BasicStringPiece(const TChar* str, size_t len) :
        mData(str), mLength(len) {
}

template <typename TChar>
inline BasicStringPiece<TChar>& BasicStringPiece<TChar>::operator=(
        const BasicStringPiece<TChar>& rhs) {
    mData = rhs.mData;
    mLength = rhs.mLength;
    return *this;
}

template <typename TChar>
inline BasicStringPiece<TChar>& BasicStringPiece<TChar>::assign(const TChar* str, size_t len) {
    mData = str;
    mLength = len;
    return *this;
}


template <typename TChar>
inline BasicStringPiece<TChar> BasicStringPiece<TChar>::substr(size_t start, size_t len) const {
    if (start + len > mLength) {
        return BasicStringPiece<TChar>();
    }
    return BasicStringPiece<TChar>(mData + start, len);
}

template <typename TChar>
inline BasicStringPiece<TChar> BasicStringPiece<TChar>::substr(
        BasicStringPiece<TChar>::const_iterator begin,
        BasicStringPiece<TChar>::const_iterator end) const {
    return BasicStringPiece<TChar>(begin, end - begin);
}

template <typename TChar>
inline const TChar* BasicStringPiece<TChar>::data() const {
    return mData;
}

template <typename TChar>
inline size_t BasicStringPiece<TChar>::length() const {
    return mLength;
}

template <typename TChar>
inline size_t BasicStringPiece<TChar>::size() const {
    return mLength;
}

template <typename TChar>
inline bool BasicStringPiece<TChar>::empty() const {
    return mLength == 0;
}

template <typename TChar>
inline std::basic_string<TChar> BasicStringPiece<TChar>::toString() const {
    return std::basic_string<TChar>(mData, mLength);
}

template <>
inline bool BasicStringPiece<char>::contains(const BasicStringPiece<char>& rhs) const {
    if (!mData || !rhs.mData) {
        return false;
    }
    if (rhs.mLength > mLength) {
        return false;
    }
    return strstr(mData, rhs.mData) != nullptr;
}

template <>
inline int BasicStringPiece<char>::compare(const BasicStringPiece<char>& rhs) const {
    const char nullStr = '\0';
    const char* b1 = mData != nullptr ? mData : &nullStr;
    const char* e1 = b1 + mLength;
    const char* b2 = rhs.mData != nullptr ? rhs.mData : &nullStr;
    const char* e2 = b2 + rhs.mLength;

    while (b1 < e1 && b2 < e2) {
        const int d = static_cast<int>(*b1++) - static_cast<int>(*b2++);
        if (d) {
            return d;
        }
    }
    return static_cast<int>(mLength - rhs.mLength);
}

inline ::std::ostream& operator<<(::std::ostream& out, const BasicStringPiece<char16_t>& str) {
    android::String8 utf8(str.data(), str.size());
    return out.write(utf8.string(), utf8.size());
}

template <>
inline bool BasicStringPiece<char16_t>::contains(const BasicStringPiece<char16_t>& rhs) const {
    if (!mData || !rhs.mData) {
        return false;
    }
    if (rhs.mLength > mLength) {
        return false;
    }
    return strstr16(mData, rhs.mData) != nullptr;
}

template <>
inline int BasicStringPiece<char16_t>::compare(const BasicStringPiece<char16_t>& rhs) const {
    const char16_t nullStr = u'\0';
    const char16_t* b1 = mData != nullptr ? mData : &nullStr;
    const char16_t* b2 = rhs.mData != nullptr ? rhs.mData : &nullStr;
    return strzcmp16(b1, mLength, b2, rhs.mLength);
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
    return mData;
}

template <typename TChar>
inline typename BasicStringPiece<TChar>::const_iterator BasicStringPiece<TChar>::end() const {
    return mData + mLength;
}

inline ::std::ostream& operator<<(::std::ostream& out, const BasicStringPiece<char>& str) {
    return out.write(str.data(), str.size());
}

} // namespace aapt

inline ::std::ostream& operator<<(::std::ostream& out, const std::u16string& str) {
    android::String8 utf8(str.data(), str.size());
    return out.write(utf8.string(), utf8.size());
}

#endif // AAPT_STRING_PIECE_H
