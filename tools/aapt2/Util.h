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

#include "BigBuffer.h"
#include "Maybe.h"
#include "StringPiece.h"

#include <androidfw/ResourceTypes.h>
#include <functional>
#include <memory>
#include <ostream>
#include <string>
#include <vector>

namespace aapt {
namespace util {

std::vector<std::string> split(const StringPiece& str, char sep);
std::vector<std::string> splitAndLowercase(const StringPiece& str, char sep);

/**
 * Returns true if the string starts with prefix.
 */
template <typename T>
bool stringStartsWith(const BasicStringPiece<T>& str, const BasicStringPiece<T>& prefix) {
    if (str.size() < prefix.size()) {
        return false;
    }
    return str.substr(0, prefix.size()) == prefix;
}

/**
 * Returns true if the string ends with suffix.
 */
template <typename T>
bool stringEndsWith(const BasicStringPiece<T>& str, const BasicStringPiece<T>& suffix) {
    if (str.size() < suffix.size()) {
        return false;
    }
    return str.substr(str.size() - suffix.size(), suffix.size()) == suffix;
}

/**
 * Creates a new StringPiece16 that points to a substring
 * of the original string without leading or trailing whitespace.
 */
StringPiece16 trimWhitespace(const StringPiece16& str);

/**
 * UTF-16 isspace(). It basically checks for lower range characters that are
 * whitespace.
 */
inline bool isspace16(char16_t c) {
    return c < 0x0080 && isspace(c);
}

/**
 * Returns an iterator to the first character that is not alpha-numeric and that
 * is not in the allowedChars set.
 */
StringPiece16::const_iterator findNonAlphaNumericAndNotInSet(const StringPiece16& str,
        const StringPiece16& allowedChars);

/**
 * Tests that the string is a valid Java class name.
 */
bool isJavaClassName(const StringPiece16& str);

/**
 * Converts the class name to a fully qualified class name from the given `package`. Ex:
 *
 * asdf         --> package.asdf
 * .asdf        --> package.asdf
 * .a.b         --> package.a.b
 * asdf.adsf    --> asdf.adsf
 */
Maybe<std::u16string> getFullyQualifiedClassName(const StringPiece16& package,
                                                 const StringPiece16& className);


/**
 * Makes a std::unique_ptr<> with the template parameter inferred by the compiler.
 * This will be present in C++14 and can be removed then.
 */
template <typename T, class... Args>
std::unique_ptr<T> make_unique(Args&&... args) {
    return std::unique_ptr<T>(new T{std::forward<Args>(args)...});
}

/**
 * Writes a set of items to the std::ostream, joining the times with the provided
 * separator.
 */
template <typename Iterator>
::std::function<::std::ostream&(::std::ostream&)> joiner(Iterator begin, Iterator end,
        const char* sep) {
    return [begin, end, sep](::std::ostream& out) -> ::std::ostream& {
        for (auto iter = begin; iter != end; ++iter) {
            if (iter != begin) {
                out << sep;
            }
            out << *iter;
        }
        return out;
    };
}

inline ::std::function<::std::ostream&(::std::ostream&)> formatSize(size_t size) {
    return [size](::std::ostream& out) -> ::std::ostream& {
        constexpr size_t K = 1024u;
        constexpr size_t M = K * K;
        constexpr size_t G = M * K;
        if (size < K) {
            out << size << "B";
        } else if (size < M) {
            out << (double(size) / K) << " KiB";
        } else if (size < G) {
            out << (double(size) / M) << " MiB";
        } else {
            out << (double(size) / G) << " GiB";
        }
        return out;
    };
}

/**
 * Helper method to extract a string from a StringPool.
 */
inline StringPiece16 getString(const android::ResStringPool& pool, size_t idx) {
    size_t len;
    const char16_t* str = pool.stringAt(idx, &len);
    if (str != nullptr) {
        return StringPiece16(str, len);
    }
    return StringPiece16();
}

class StringBuilder {
public:
    StringBuilder& append(const StringPiece16& str);
    const std::u16string& str() const;
    const std::string& error() const;
    operator bool() const;

private:
    std::u16string mStr;
    bool mQuote = false;
    bool mTrailingSpace = false;
    std::string mError;
};

inline const std::u16string& StringBuilder::str() const {
    return mStr;
}

inline const std::string& StringBuilder::error() const {
    return mError;
}

inline StringBuilder::operator bool() const {
    return mError.empty();
}

/**
 * Converts a UTF8 string to a UTF16 string.
 */
std::u16string utf8ToUtf16(const StringPiece& utf8);
std::string utf16ToUtf8(const StringPiece16& utf8);

/**
 * Writes the entire BigBuffer to the output stream.
 */
bool writeAll(std::ostream& out, const BigBuffer& buffer);

/*
 * Copies the entire BigBuffer into a single buffer.
 */
std::unique_ptr<uint8_t[]> copy(const BigBuffer& buffer);

/**
 * A Tokenizer implemented as an iterable collection. It does not allocate
 * any memory on the heap nor use standard containers.
 */
template <typename Char>
class Tokenizer {
public:
    class iterator {
    public:
        iterator(const iterator&) = default;
        iterator& operator=(const iterator&) = default;

        iterator& operator++();
        BasicStringPiece<Char> operator*();
        bool operator==(const iterator& rhs) const;
        bool operator!=(const iterator& rhs) const;

    private:
        friend class Tokenizer<Char>;

        iterator(BasicStringPiece<Char> s, Char sep, BasicStringPiece<Char> tok);

        BasicStringPiece<Char> str;
        Char separator;
        BasicStringPiece<Char> token;
    };

    Tokenizer(BasicStringPiece<Char> str, Char sep);
    iterator begin();
    iterator end();

private:
    const iterator mBegin;
    const iterator mEnd;
};

template <typename Char>
inline Tokenizer<Char> tokenize(BasicStringPiece<Char> str, Char sep) {
    return Tokenizer<Char>(str, sep);
}

template <typename Char>
typename Tokenizer<Char>::iterator& Tokenizer<Char>::iterator::operator++() {
    const Char* start = token.end();
    const Char* end = str.end();
    if (start == end) {
        token.assign(token.end(), 0);
        return *this;
    }

    start += 1;
    const Char* current = start;
    while (current != end) {
        if (*current == separator) {
            token.assign(start, current - start);
            return *this;
        }
        ++current;
    }
    token.assign(start, end - start);
    return *this;
}

template <typename Char>
inline BasicStringPiece<Char> Tokenizer<Char>::iterator::operator*() {
    return token;
}

template <typename Char>
inline bool Tokenizer<Char>::iterator::operator==(const iterator& rhs) const {
    // We check equality here a bit differently.
    // We need to know that the addresses are the same.
    return token.begin() == rhs.token.begin() && token.end() == rhs.token.end();
}

template <typename Char>
inline bool Tokenizer<Char>::iterator::operator!=(const iterator& rhs) const {
    return !(*this == rhs);
}

template <typename Char>
inline Tokenizer<Char>::iterator::iterator(BasicStringPiece<Char> s, Char sep,
                                           BasicStringPiece<Char> tok) :
        str(s), separator(sep), token(tok) {
}

template <typename Char>
inline typename Tokenizer<Char>::iterator Tokenizer<Char>::begin() {
    return mBegin;
}

template <typename Char>
inline typename Tokenizer<Char>::iterator Tokenizer<Char>::end() {
    return mEnd;
}

template <typename Char>
inline Tokenizer<Char>::Tokenizer(BasicStringPiece<Char> str, Char sep) :
        mBegin(++iterator(str, sep, BasicStringPiece<Char>(str.begin() - 1, 0))),
        mEnd(str, sep, BasicStringPiece<Char>(str.end(), 0)) {
}

/**
 * Returns a package name if the namespace URI is of the form:
 * http://schemas.android.com/apk/res/<package>
 *
 * Special case: if namespaceUri is http://schemas.android.com/apk/res-auto,
 * returns an empty package name.
 */
Maybe<std::u16string> extractPackageFromNamespace(const std::u16string& namespaceUri);

} // namespace util

/**
 * Stream operator for functions. Calls the function with the stream as an argument.
 * In the aapt namespace for lookup.
 */
inline ::std::ostream& operator<<(::std::ostream& out,
                                  ::std::function<::std::ostream&(::std::ostream&)> f) {
    return f(out);
}

} // namespace aapt

#endif // AAPT_UTIL_H
