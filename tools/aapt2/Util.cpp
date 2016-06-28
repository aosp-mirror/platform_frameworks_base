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

#include "BigBuffer.h"
#include "Maybe.h"
#include "StringPiece.h"
#include "Util.h"

#include <algorithm>
#include <ostream>
#include <string>
#include <utils/Unicode.h>
#include <vector>

namespace aapt {
namespace util {

constexpr const char16_t* kSchemaAuto = u"http://schemas.android.com/apk/res-auto";
constexpr const char16_t* kSchemaPrefix = u"http://schemas.android.com/apk/res/";

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

StringPiece16 trimWhitespace(const StringPiece16& str) {
    if (str.size() == 0 || str.data() == nullptr) {
        return str;
    }

    const char16_t* start = str.data();
    const char16_t* end = str.data() + str.length();

    while (start != end && util::isspace16(*start)) {
        start++;
    }

    while (end != start && util::isspace16(*(end - 1))) {
        end--;
    }

    return StringPiece16(start, end - start);
}

StringPiece16::const_iterator findNonAlphaNumericAndNotInSet(const StringPiece16& str,
        const StringPiece16& allowedChars) {
    const auto endIter = str.end();
    for (auto iter = str.begin(); iter != endIter; ++iter) {
        char16_t c = *iter;
        if ((c >= u'a' && c <= u'z') ||
                (c >= u'A' && c <= u'Z') ||
                (c >= u'0' && c <= u'9')) {
            continue;
        }

        bool match = false;
        for (char16_t i : allowedChars) {
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

bool isJavaClassName(const StringPiece16& str) {
    size_t pieces = 0;
    for (const StringPiece16& piece : tokenize(str, u'.')) {
        pieces++;
        if (piece.empty()) {
            return false;
        }

        // Can't have starting or trailing $ character.
        if (piece.data()[0] == u'$' || piece.data()[piece.size() - 1] == u'$') {
            return false;
        }

        if (findNonAlphaNumericAndNotInSet(piece, u"$_") != piece.end()) {
            return false;
        }
    }
    return pieces >= 2;
}

Maybe<std::u16string> getFullyQualifiedClassName(const StringPiece16& package,
                                                 const StringPiece16& className) {
    if (className.empty()) {
        return {};
    }

    if (util::isJavaClassName(className)) {
        return className.toString();
    }

    if (package.empty()) {
        return {};
    }

    std::u16string result(package.data(), package.size());
    if (className.data()[0] != u'.') {
        result += u'.';
    }
    result.append(className.data(), className.size());
    if (!isJavaClassName(result)) {
        return {};
    }
    return result;
}

static Maybe<char16_t> parseUnicodeCodepoint(const char16_t** start, const char16_t* end) {
    char16_t code = 0;
    for (size_t i = 0; i < 4 && *start != end; i++, (*start)++) {
        char16_t c = **start;
        int a;
        if (c >= '0' && c <= '9') {
            a = c - '0';
        } else if (c >= 'a' && c <= 'f') {
            a = c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
            a = c - 'A' + 10;
        } else {
            return make_nothing<char16_t>();
        }
        code = (code << 4) | a;
    }
    return make_value(code);
}

StringBuilder& StringBuilder::append(const StringPiece16& str) {
    if (!mError.empty()) {
        return *this;
    }

    const char16_t* const end = str.end();
    const char16_t* start = str.begin();
    const char16_t* current = start;
    while (current != end) {
        if (*current == u'"') {
            if (!mQuote && mTrailingSpace) {
                // We found an opening quote, and we have
                // trailing space, so we should append that
                // space now.
                if (mTrailingSpace) {
                    // We had trailing whitespace, so
                    // replace with a single space.
                    if (!mStr.empty()) {
                        mStr += u' ';
                    }
                    mTrailingSpace = false;
                }
            }
            mQuote = !mQuote;
            mStr.append(start, current - start);
            start = current + 1;
        } else if (*current == u'\'' && !mQuote) {
            // This should be escaped.
            mError = "unescaped apostrophe";
            return *this;
        } else if (*current == u'\\') {
            // This is an escape sequence, convert to the real value.
            if (!mQuote && mTrailingSpace) {
                // We had trailing whitespace, so
                // replace with a single space.
                if (!mStr.empty()) {
                    mStr += u' ';
                }
                mTrailingSpace = false;
            }
            mStr.append(start, current - start);
            start = current + 1;

            current++;
            if (current != end) {
                switch (*current) {
                    case u't':
                        mStr += u'\t';
                        break;
                    case u'n':
                        mStr += u'\n';
                        break;
                    case u'#':
                        mStr += u'#';
                        break;
                    case u'@':
                        mStr += u'@';
                        break;
                    case u'?':
                        mStr += u'?';
                        break;
                    case u'"':
                        mStr += u'"';
                        break;
                    case u'\'':
                        mStr += u'\'';
                        break;
                    case u'\\':
                        mStr += u'\\';
                        break;
                    case u'u': {
                        current++;
                        Maybe<char16_t> c = parseUnicodeCodepoint(&current, end);
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
                start = current + 1;
            }
        } else if (!mQuote) {
            // This is not quoted text, so look for whitespace.
            if (isspace16(*current)) {
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
                    mStr += u' ';
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
    utf8_to_utf16(reinterpret_cast<const uint8_t*>(utf8.data()), utf8.length(), &*utf16.begin());
    return utf16;
}

std::string utf16ToUtf8(const StringPiece16& utf16) {
    ssize_t utf8Length = utf16_to_utf8_length(utf16.data(), utf16.length());
    if (utf8Length <= 0) {
        return {};
    }

    std::string utf8;
    // Make room for '\0' explicitly.
    utf8.resize(utf8Length + 1);
    utf16_to_utf8(utf16.data(), utf16.length(), &*utf8.begin(), utf8Length + 1);
    utf8.resize(utf8Length);
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

Maybe<std::u16string> extractPackageFromNamespace(const std::u16string& namespaceUri) {
    if (stringStartsWith<char16_t>(namespaceUri, kSchemaPrefix)) {
        StringPiece16 schemaPrefix = kSchemaPrefix;
        StringPiece16 package = namespaceUri;
        return package.substr(schemaPrefix.size(), package.size() - schemaPrefix.size())
                .toString();
    } else if (namespaceUri == kSchemaAuto) {
        return std::u16string();
    }
    return {};
}

} // namespace util
} // namespace aapt
