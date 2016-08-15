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

#include "util/BigBuffer.h"
#include "util/Maybe.h"
#include "util/StringPiece.h"

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
bool stringStartsWith(const StringPiece& str, const StringPiece& prefix);

/**
 * Returns true if the string ends with suffix.
 */
bool stringEndsWith(const StringPiece& str, const StringPiece& suffix);

/**
 * Creates a new StringPiece16 that points to a substring
 * of the original string without leading or trailing whitespace.
 */
StringPiece trimWhitespace(const StringPiece& str);

StringPiece trimWhitespace(const StringPiece& str);

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
StringPiece::const_iterator findNonAlphaNumericAndNotInSet(const StringPiece& str,
                                                           const StringPiece& allowedChars);

/**
 * Tests that the string is a valid Java class name.
 */
bool isJavaClassName(const StringPiece& str);

/**
 * Tests that the string is a valid Java package name.
 */
bool isJavaPackageName(const StringPiece& str);

/**
 * Converts the class name to a fully qualified class name from the given `package`. Ex:
 *
 * asdf         --> package.asdf
 * .asdf        --> package.asdf
 * .a.b         --> package.a.b
 * asdf.adsf    --> asdf.adsf
 */
Maybe<std::string> getFullyQualifiedClassName(const StringPiece& package,
                                              const StringPiece& className);

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
 * Helper method to extract a UTF-16 string from a StringPool. If the string is stored as UTF-8,
 * the conversion to UTF-16 happens within ResStringPool.
 */
StringPiece16 getString16(const android::ResStringPool& pool, size_t idx);

/**
 * Helper method to extract a UTF-8 string from a StringPool. If the string is stored as UTF-16,
 * the conversion from UTF-16 to UTF-8 does not happen in ResStringPool and is done by this method,
 * which maintains no state or cache. This means we must return an std::string copy.
 */
std::string getString(const android::ResStringPool& pool, size_t idx);

/**
 * Checks that the Java string format contains no non-positional arguments (arguments without
 * explicitly specifying an index) when there are more than one argument. This is an error
 * because translations may rearrange the order of the arguments in the string, which will
 * break the string interpolation.
 */
bool verifyJavaStringFormat(const StringPiece& str);

class StringBuilder {
public:
    StringBuilder& append(const StringPiece& str);
    const std::string& str() const;
    const std::string& error() const;
    operator bool() const;

private:
    std::string mStr;
    bool mQuote = false;
    bool mTrailingSpace = false;
    bool mLastCharWasEscape = false;
    std::string mError;
};

inline const std::string& StringBuilder::str() const {
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
std::string utf16ToUtf8(const StringPiece16& utf16);

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
class Tokenizer {
public:
    class iterator {
    public:
        iterator(const iterator&) = default;
        iterator& operator=(const iterator&) = default;

        iterator& operator++();

        StringPiece operator*() {
            return mToken;
        }
        bool operator==(const iterator& rhs) const;
        bool operator!=(const iterator& rhs) const;

    private:
        friend class Tokenizer;

        iterator(StringPiece s, char sep, StringPiece tok, bool end);

        StringPiece mStr;
        char mSeparator;
        StringPiece mToken;
        bool mEnd;
    };

    Tokenizer(StringPiece str, char sep);

    iterator begin() {
        return mBegin;
    }

    iterator end() {
        return mEnd;
    }

private:
    const iterator mBegin;
    const iterator mEnd;
};

inline Tokenizer tokenize(const StringPiece& str, char sep) {
    return Tokenizer(str, sep);
}

inline uint16_t hostToDevice16(uint16_t value) {
    return htods(value);
}

inline uint32_t hostToDevice32(uint32_t value) {
    return htodl(value);
}

inline uint16_t deviceToHost16(uint16_t value) {
    return dtohs(value);
}

inline uint32_t deviceToHost32(uint32_t value) {
    return dtohl(value);
}

/**
 * Given a path like: res/xml-sw600dp/foo.xml
 *
 * Extracts "res/xml-sw600dp/" into outPrefix.
 * Extracts "foo" into outEntry.
 * Extracts ".xml" into outSuffix.
 *
 * Returns true if successful.
 */
bool extractResFilePathParts(const StringPiece& path, StringPiece* outPrefix,
                             StringPiece* outEntry, StringPiece* outSuffix);

} // namespace util

/**
 * Stream operator for functions. Calls the function with the stream as an argument.
 * In the aapt namespace for lookup.
 */
inline ::std::ostream& operator<<(::std::ostream& out,
                                  const ::std::function<::std::ostream&(::std::ostream&)>& f) {
    return f(out);
}

} // namespace aapt

#endif // AAPT_UTIL_H
