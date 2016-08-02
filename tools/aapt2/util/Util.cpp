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

#include "util/BigBuffer.h"
#include "util/Maybe.h"
#include "util/StringPiece.h"
#include "util/Util.h"

#include <algorithm>
#include <ostream>
#include <string>
#include <utils/Unicode.h>
#include <vector>

namespace aapt {
namespace util {

static std::vector<std::string> splitAndTransform(const StringPiece& str, char sep,
        const std::function<char(char)>& f) {
    std::vector<std::string> parts;
    const StringPiece::const_iterator end = std::end(str);
    StringPiece::const_iterator start = std::begin(str);
    StringPiece::const_iterator current;
    do {
        current = std::find(start, end, sep);
        parts.emplace_back(str.substr(start, current).toString());
        if (f) {
            std::string& part = parts.back();
            std::transform(part.begin(), part.end(), part.begin(), f);
        }
        start = current + 1;
    } while (current != end);
    return parts;
}

std::vector<std::string> split(const StringPiece& str, char sep) {
    return splitAndTransform(str, sep, nullptr);
}

std::vector<std::string> splitAndLowercase(const StringPiece& str, char sep) {
    return splitAndTransform(str, sep, ::tolower);
}

bool stringStartsWith(const StringPiece& str, const StringPiece& prefix) {
    if (str.size() < prefix.size()) {
        return false;
    }
    return str.substr(0, prefix.size()) == prefix;
}

bool stringEndsWith(const StringPiece& str, const StringPiece& suffix) {
    if (str.size() < suffix.size()) {
        return false;
    }
    return str.substr(str.size() - suffix.size(), suffix.size()) == suffix;
}

StringPiece trimWhitespace(const StringPiece& str) {
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

StringPiece::const_iterator findNonAlphaNumericAndNotInSet(const StringPiece& str,
                                                           const StringPiece& allowedChars) {
    const auto endIter = str.end();
    for (auto iter = str.begin(); iter != endIter; ++iter) {
        char c = *iter;
        if ((c >= u'a' && c <= u'z') ||
                (c >= u'A' && c <= u'Z') ||
                (c >= u'0' && c <= u'9')) {
            continue;
        }

        bool match = false;
        for (char i : allowedChars) {
            if (c == i) {
                match = true;
                break;
            }
        }

        if (!match) {
            return iter;
        }
    }
    return endIter;
}

bool isJavaClassName(const StringPiece& str) {
    size_t pieces = 0;
    for (const StringPiece& piece : tokenize(str, '.')) {
        pieces++;
        if (piece.empty()) {
            return false;
        }

        // Can't have starting or trailing $ character.
        if (piece.data()[0] == '$' || piece.data()[piece.size() - 1] == '$') {
            return false;
        }

        if (findNonAlphaNumericAndNotInSet(piece, "$_") != piece.end()) {
            return false;
        }
    }
    return pieces >= 2;
}

bool isJavaPackageName(const StringPiece& str) {
    if (str.empty()) {
        return false;
    }

    size_t pieces = 0;
    for (const StringPiece& piece : tokenize(str, '.')) {
        pieces++;
        if (piece.empty()) {
            return false;
        }

        if (piece.data()[0] == '_' || piece.data()[piece.size() - 1] == '_') {
            return false;
        }

        if (findNonAlphaNumericAndNotInSet(piece, "_") != piece.end()) {
            return false;
        }
    }
    return pieces >= 1;
}

Maybe<std::string> getFullyQualifiedClassName(const StringPiece& package,
                                              const StringPiece& className) {
    if (className.empty()) {
        return {};
    }

    if (util::isJavaClassName(className)) {
        return className.toString();
    }

    if (package.empty()) {
        return {};
    }

    std::string result(package.data(), package.size());
    if (className.data()[0] != '.') {
        result += '.';
    }

    result.append(className.data(), className.size());
    if (!isJavaClassName(result)) {
        return {};
    }
    return result;
}

static size_t consumeDigits(const char* start, const char* end) {
    const char* c = start;
    for (; c != end && *c >= '0' && *c <= '9'; c++) {}
    return static_cast<size_t>(c - start);
}

bool verifyJavaStringFormat(const StringPiece& str) {
    const char* c = str.begin();
    const char* const end = str.end();

    size_t argCount = 0;
    bool nonpositional = false;
    while (c != end) {
        if (*c == '%' && c + 1 < end) {
            c++;

            if (*c == '%') {
                c++;
                continue;
            }

            argCount++;

            size_t numDigits = consumeDigits(c, end);
            if (numDigits > 0) {
                c += numDigits;
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
            while (c != end && (*c == '-' ||
                    *c == '#' ||
                    *c == '+' ||
                    *c == ' ' ||
                    *c == ',' ||
                    *c == '(' ||
                    (*c >= '0' && *c <= '9'))) {
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

    if (argCount > 1 && nonpositional) {
        // Multiple arguments were specified, but some or all were non positional. Translated
        // strings may rearrange the order of the arguments, which will break the string.
        return false;
    }
    return true;
}

static Maybe<std::string> parseUnicodeCodepoint(const char** start, const char* end) {
    char32_t code = 0;
    for (size_t i = 0; i < 4 && *start != end; i++, (*start)++) {
        char c = **start;
        char32_t a;
        if (c >= '0' && c <= '9') {
            a = c - '0';
        } else if (c >= 'a' && c <= 'f') {
            a = c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
            a = c - 'A' + 10;
        } else {
            return {};
        }
        code = (code << 4) | a;
    }

    ssize_t len = utf32_to_utf8_length(&code, 1);
    if (len < 0) {
        return {};
    }

    std::string resultUtf8;
    resultUtf8.resize(len);
    utf32_to_utf8(&code, 1, &*resultUtf8.begin(), len + 1);
    return resultUtf8;
}

StringBuilder& StringBuilder::append(const StringPiece& str) {
    if (!mError.empty()) {
        return *this;
    }

    const char* const end = str.end();
    const char* start = str.begin();
    const char* current = start;
    while (current != end) {
        if (mLastCharWasEscape) {
            switch (*current) {
                case 't':
                    mStr += '\t';
                    break;
                case 'n':
                    mStr += '\n';
                    break;
                case '#':
                    mStr += '#';
                    break;
                case '@':
                    mStr += '@';
                    break;
                case '?':
                    mStr += '?';
                    break;
                case '"':
                    mStr += '"';
                    break;
                case '\'':
                    mStr += '\'';
                    break;
                case '\\':
                    mStr += '\\';
                    break;
                case 'u': {
                    current++;
                    Maybe<std::string> c = parseUnicodeCodepoint(&current, end);
                    if (!c) {
                        mError = "invalid unicode escape sequence";
                        return *this;
                    }
                    mStr += c.value();
                    current -= 1;
                    break;
                }

                default:
                    // Ignore.
                    break;
            }
            mLastCharWasEscape = false;
            start = current + 1;
        } else if (*current == '"') {
            if (!mQuote && mTrailingSpace) {
                // We found an opening quote, and we have
                // trailing space, so we should append that
                // space now.
                if (mTrailingSpace) {
                    // We had trailing whitespace, so
                    // replace with a single space.
                    if (!mStr.empty()) {
                        mStr += ' ';
                    }
                    mTrailingSpace = false;
                }
            }
            mQuote = !mQuote;
            mStr.append(start, current - start);
            start = current + 1;
        } else if (*current == '\'' && !mQuote) {
            // This should be escaped.
            mError = "unescaped apostrophe";
            return *this;
        } else if (*current == '\\') {
            // This is an escape sequence, convert to the real value.
            if (!mQuote && mTrailingSpace) {
                // We had trailing whitespace, so
                // replace with a single space.
                if (!mStr.empty()) {
                    mStr += ' ';
                }
                mTrailingSpace = false;
            }
            mStr.append(start, current - start);
            start = current + 1;
            mLastCharWasEscape = true;
        } else if (!mQuote) {
            // This is not quoted text, so look for whitespace.
            if (isspace(*current)) {
                // We found whitespace, see if we have seen some
                // before.
                if (!mTrailingSpace) {
                    // We didn't see a previous adjacent space,
                    // so mark that we did.
                    mTrailingSpace = true;
                    mStr.append(start, current - start);
                }

                // Keep skipping whitespace.
                start = current + 1;
            } else if (mTrailingSpace) {
                // We saw trailing space before, so replace all
                // that trailing space with one space.
                if (!mStr.empty()) {
                    mStr += ' ';
                }
                mTrailingSpace = false;
            }
        }
        current++;
    }
    mStr.append(start, end - start);
    return *this;
}

std::u16string utf8ToUtf16(const StringPiece& utf8) {
    ssize_t utf16Length = utf8_to_utf16_length(reinterpret_cast<const uint8_t*>(utf8.data()),
            utf8.length());
    if (utf16Length <= 0) {
        return {};
    }

    std::u16string utf16;
    utf16.resize(utf16Length);
    utf8_to_utf16(
            reinterpret_cast<const uint8_t*>(utf8.data()),
            utf8.length(),
            &*utf16.begin(),
            (size_t) utf16Length + 1);
    return utf16;
}

std::string utf16ToUtf8(const StringPiece16& utf16) {
    ssize_t utf8Length = utf16_to_utf8_length(utf16.data(), utf16.length());
    if (utf8Length <= 0) {
        return {};
    }

    std::string utf8;
    utf8.resize(utf8Length);
    utf16_to_utf8(utf16.data(), utf16.length(), &*utf8.begin(), utf8Length + 1);
    return utf8;
}

bool writeAll(std::ostream& out, const BigBuffer& buffer) {
    for (const auto& b : buffer) {
        if (!out.write(reinterpret_cast<const char*>(b.buffer.get()), b.size)) {
            return false;
        }
    }
    return true;
}

std::unique_ptr<uint8_t[]> copy(const BigBuffer& buffer) {
    std::unique_ptr<uint8_t[]> data = std::unique_ptr<uint8_t[]>(new uint8_t[buffer.size()]);
    uint8_t* p = data.get();
    for (const auto& block : buffer) {
        memcpy(p, block.buffer.get(), block.size);
        p += block.size;
    }
    return data;
}

typename Tokenizer::iterator& Tokenizer::iterator::operator++() {
    const char* start = mToken.end();
    const char* end = mStr.end();
    if (start == end) {
        mEnd = true;
        mToken.assign(mToken.end(), 0);
        return *this;
    }

    start += 1;
    const char* current = start;
    while (current != end) {
        if (*current == mSeparator) {
            mToken.assign(start, current - start);
            return *this;
        }
        ++current;
    }
    mToken.assign(start, end - start);
    return *this;
}

bool Tokenizer::iterator::operator==(const iterator& rhs) const {
    // We check equality here a bit differently.
    // We need to know that the addresses are the same.
    return mToken.begin() == rhs.mToken.begin() && mToken.end() == rhs.mToken.end() &&
            mEnd == rhs.mEnd;
}

bool Tokenizer::iterator::operator!=(const iterator& rhs) const {
    return !(*this == rhs);
}

Tokenizer::iterator::iterator(StringPiece s, char sep, StringPiece tok, bool end) :
        mStr(s), mSeparator(sep), mToken(tok), mEnd(end) {
}

Tokenizer::Tokenizer(StringPiece str, char sep) :
        mBegin(++iterator(str, sep, StringPiece(str.begin() - 1, 0), false)),
        mEnd(str, sep, StringPiece(str.end(), 0), true) {
}

bool extractResFilePathParts(const StringPiece& path, StringPiece* outPrefix,
                             StringPiece* outEntry, StringPiece* outSuffix) {
    const StringPiece resPrefix("res/");
    if (!stringStartsWith(path, resPrefix)) {
        return false;
    }

    StringPiece::const_iterator lastOccurence = path.end();
    for (auto iter = path.begin() + resPrefix.size(); iter != path.end(); ++iter) {
        if (*iter == '/') {
            lastOccurence = iter;
        }
    }

    if (lastOccurence == path.end()) {
        return false;
    }

    auto iter = std::find(lastOccurence, path.end(), '.');
    *outSuffix = StringPiece(iter, path.end() - iter);
    *outEntry = StringPiece(lastOccurence + 1, iter - lastOccurence - 1);
    *outPrefix = StringPiece(path.begin(), lastOccurence - path.begin() + 1);
    return true;
}

StringPiece16 getString16(const android::ResStringPool& pool, size_t idx) {
    size_t len;
    const char16_t* str = pool.stringAt(idx, &len);
    if (str != nullptr) {
        return StringPiece16(str, len);
    }
    return StringPiece16();
}

std::string getString(const android::ResStringPool& pool, size_t idx) {
    size_t len;
    const char* str = pool.string8At(idx, &len);
    if (str != nullptr) {
        return std::string(str, len);
    }
    return utf16ToUtf8(getString16(pool, idx));
}

} // namespace util
} // namespace aapt
