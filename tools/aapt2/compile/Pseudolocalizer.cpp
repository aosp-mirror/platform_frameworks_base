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

#include "compile/Pseudolocalizer.h"
#include "util/Util.h"

namespace aapt {

// String basis to generate expansion
static const std::u16string k_expansion_string = u"one two three "
        "four five six seven eight nine ten eleven twelve thirteen "
        "fourteen fiveteen sixteen seventeen nineteen twenty";

// Special unicode characters to override directionality of the words
static const std::u16string k_rlm = u"\u200f";
static const std::u16string k_rlo = u"\u202e";
static const std::u16string k_pdf = u"\u202c";

// Placeholder marks
static const std::u16string k_placeholder_open = u"\u00bb";
static const std::u16string k_placeholder_close = u"\u00ab";

static const char16_t k_arg_start = u'{';
static const char16_t k_arg_end = u'}';

class PseudoMethodNone : public PseudoMethodImpl {
public:
    std::u16string text(const StringPiece16& text) override { return text.toString(); }
    std::u16string placeholder(const StringPiece16& text) override { return text.toString(); }
};

class PseudoMethodBidi : public PseudoMethodImpl {
public:
    std::u16string text(const StringPiece16& text) override;
    std::u16string placeholder(const StringPiece16& text) override;
};

class PseudoMethodAccent : public PseudoMethodImpl {
public:
    PseudoMethodAccent() : mDepth(0), mWordCount(0), mLength(0) {}
    std::u16string start() override;
    std::u16string end() override;
    std::u16string text(const StringPiece16& text) override;
    std::u16string placeholder(const StringPiece16& text) override;
private:
    size_t mDepth;
    size_t mWordCount;
    size_t mLength;
};

Pseudolocalizer::Pseudolocalizer(Method method) : mLastDepth(0) {
    setMethod(method);
}

void Pseudolocalizer::setMethod(Method method) {
    switch (method) {
    case Method::kNone:
        mImpl = util::make_unique<PseudoMethodNone>();
        break;
    case Method::kAccent:
        mImpl = util::make_unique<PseudoMethodAccent>();
        break;
    case Method::kBidi:
        mImpl = util::make_unique<PseudoMethodBidi>();
        break;
    }
}

std::u16string Pseudolocalizer::text(const StringPiece16& text) {
    std::u16string out;
    size_t depth = mLastDepth;
    size_t lastpos, pos;
    const size_t length = text.size();
    const char16_t* str = text.data();
    bool escaped = false;
    for (lastpos = pos = 0; pos < length; pos++) {
        char16_t c = str[pos];
        if (escaped) {
            escaped = false;
            continue;
        }
        if (c == '\'') {
            escaped = true;
            continue;
        }

        if (c == k_arg_start) {
            depth++;
        } else if (c == k_arg_end && depth) {
            depth--;
        }

        if (mLastDepth != depth || pos == length - 1) {
            bool pseudo = ((mLastDepth % 2) == 0);
            size_t nextpos = pos;
            if (!pseudo || depth == mLastDepth) {
                nextpos++;
            }
            size_t size = nextpos - lastpos;
            if (size) {
                std::u16string chunk = text.substr(lastpos, size).toString();
                if (pseudo) {
                    chunk = mImpl->text(chunk);
                } else if (str[lastpos] == k_arg_start && str[nextpos - 1] == k_arg_end) {
                    chunk = mImpl->placeholder(chunk);
                }
                out.append(chunk);
            }
            if (pseudo && depth < mLastDepth) { // End of message
                out.append(mImpl->end());
            } else if (!pseudo && depth > mLastDepth) { // Start of message
                out.append(mImpl->start());
            }
            lastpos = nextpos;
            mLastDepth = depth;
        }
    }
    return out;
}

static const char16_t* pseudolocalizeChar(const char16_t c) {
    switch (c) {
        case 'a':   return u"\u00e5";
        case 'b':   return u"\u0253";
        case 'c':   return u"\u00e7";
        case 'd':   return u"\u00f0";
        case 'e':   return u"\u00e9";
        case 'f':   return u"\u0192";
        case 'g':   return u"\u011d";
        case 'h':   return u"\u0125";
        case 'i':   return u"\u00ee";
        case 'j':   return u"\u0135";
        case 'k':   return u"\u0137";
        case 'l':   return u"\u013c";
        case 'm':   return u"\u1e3f";
        case 'n':   return u"\u00f1";
        case 'o':   return u"\u00f6";
        case 'p':   return u"\u00fe";
        case 'q':   return u"\u0051";
        case 'r':   return u"\u0155";
        case 's':   return u"\u0161";
        case 't':   return u"\u0163";
        case 'u':   return u"\u00fb";
        case 'v':   return u"\u0056";
        case 'w':   return u"\u0175";
        case 'x':   return u"\u0445";
        case 'y':   return u"\u00fd";
        case 'z':   return u"\u017e";
        case 'A':   return u"\u00c5";
        case 'B':   return u"\u03b2";
        case 'C':   return u"\u00c7";
        case 'D':   return u"\u00d0";
        case 'E':   return u"\u00c9";
        case 'G':   return u"\u011c";
        case 'H':   return u"\u0124";
        case 'I':   return u"\u00ce";
        case 'J':   return u"\u0134";
        case 'K':   return u"\u0136";
        case 'L':   return u"\u013b";
        case 'M':   return u"\u1e3e";
        case 'N':   return u"\u00d1";
        case 'O':   return u"\u00d6";
        case 'P':   return u"\u00de";
        case 'Q':   return u"\u0071";
        case 'R':   return u"\u0154";
        case 'S':   return u"\u0160";
        case 'T':   return u"\u0162";
        case 'U':   return u"\u00db";
        case 'V':   return u"\u03bd";
        case 'W':   return u"\u0174";
        case 'X':   return u"\u00d7";
        case 'Y':   return u"\u00dd";
        case 'Z':   return u"\u017d";
        case '!':   return u"\u00a1";
        case '?':   return u"\u00bf";
        case '$':   return u"\u20ac";
        default:    return NULL;
    }
}

static bool isPossibleNormalPlaceholderEnd(const char16_t c) {
    switch (c) {
        case 's': return true;
        case 'S': return true;
        case 'c': return true;
        case 'C': return true;
        case 'd': return true;
        case 'o': return true;
        case 'x': return true;
        case 'X': return true;
        case 'f': return true;
        case 'e': return true;
        case 'E': return true;
        case 'g': return true;
        case 'G': return true;
        case 'a': return true;
        case 'A': return true;
        case 'b': return true;
        case 'B': return true;
        case 'h': return true;
        case 'H': return true;
        case '%': return true;
        case 'n': return true;
        default:  return false;
    }
}

static std::u16string pseudoGenerateExpansion(const unsigned int length) {
    std::u16string result = k_expansion_string;
    const char16_t* s = result.data();
    if (result.size() < length) {
        result += u" ";
        result += pseudoGenerateExpansion(length - result.size());
    } else {
        int ext = 0;
        // Should contain only whole words, so looking for a space
        for (unsigned int i = length + 1; i < result.size(); ++i) {
            ++ext;
            if (s[i] == ' ') {
                break;
            }
        }
        result = result.substr(0, length + ext);
    }
    return result;
}

std::u16string PseudoMethodAccent::start() {
    std::u16string result;
    if (mDepth == 0) {
        result = u"[";
    }
    mWordCount = mLength = 0;
    mDepth++;
    return result;
}

std::u16string PseudoMethodAccent::end() {
    std::u16string result;
    if (mLength) {
        result += u" ";
        result += pseudoGenerateExpansion(mWordCount > 3 ? mLength : mLength / 2);
    }
    mWordCount = mLength = 0;
    mDepth--;
    if (mDepth == 0) {
        result += u"]";
    }
    return result;
}

/**
 * Converts characters so they look like they've been localized.
 *
 * Note: This leaves placeholder syntax untouched.
 */
std::u16string PseudoMethodAccent::text(const StringPiece16& source)
{
    const char16_t* s = source.data();
    std::u16string result;
    const size_t I = source.size();
    bool lastspace = true;
    for (size_t i = 0; i < I; i++) {
        char16_t c = s[i];
        if (c == '%') {
            // Placeholder syntax, no need to pseudolocalize
            std::u16string chunk;
            bool end = false;
            chunk.append(&c, 1);
            while (!end && i < I) {
                ++i;
                c = s[i];
                chunk.append(&c, 1);
                if (isPossibleNormalPlaceholderEnd(c)) {
                    end = true;
                } else if (c == 't') {
                    ++i;
                    c = s[i];
                    chunk.append(&c, 1);
                    end = true;
                }
            }
            // Treat chunk as a placeholder unless it ends with %.
            result += ((c == '%') ? chunk : placeholder(chunk));
        } else if (c == '<' || c == '&') {
            // html syntax, no need to pseudolocalize
            bool tag_closed = false;
            while (!tag_closed && i < I) {
                if (c == '&') {
                    std::u16string escapeText;
                    escapeText.append(&c, 1);
                    bool end = false;
                    size_t htmlCodePos = i;
                    while (!end && htmlCodePos < I) {
                        ++htmlCodePos;
                        c = s[htmlCodePos];
                        escapeText.append(&c, 1);
                        // Valid html code
                        if (c == ';') {
                            end = true;
                            i = htmlCodePos;
                        }
                        // Wrong html code
                        else if (!((c == '#' ||
                                 (c >= 'a' && c <= 'z') ||
                                 (c >= 'A' && c <= 'Z') ||
                                 (c >= '0' && c <= '9')))) {
                            end = true;
                        }
                    }
                    result += escapeText;
                    if (escapeText != u"&lt;") {
                        tag_closed = true;
                    }
                    continue;
                }
                if (c == '>') {
                    tag_closed = true;
                    result.append(&c, 1);
                    continue;
                }
                result.append(&c, 1);
                i++;
                c = s[i];
            }
        } else {
            // This is a pure text that should be pseudolocalized
            const char16_t* p = pseudolocalizeChar(c);
            if (p != nullptr) {
                result += p;
            } else {
                bool space = util::isspace16(c);
                if (lastspace && !space) {
                    mWordCount++;
                }
                lastspace = space;
                result.append(&c, 1);
            }
            // Count only pseudolocalizable chars and delimiters
            mLength++;
        }
    }
    return result;
}

std::u16string PseudoMethodAccent::placeholder(const StringPiece16& source) {
    // Surround a placeholder with brackets
    return k_placeholder_open + source.toString() + k_placeholder_close;
}

std::u16string PseudoMethodBidi::text(const StringPiece16& source) {
    const char16_t* s = source.data();
    std::u16string result;
    bool lastspace = true;
    bool space = true;
    for (size_t i = 0; i < source.size(); i++) {
        char16_t c = s[i];
        space = util::isspace16(c);
        if (lastspace && !space) {
            // Word start
            result += k_rlm + k_rlo;
        } else if (!lastspace && space) {
            // Word end
            result += k_pdf + k_rlm;
        }
        lastspace = space;
        result.append(&c, 1);
    }
    if (!lastspace) {
        // End of last word
        result += k_pdf + k_rlm;
    }
    return result;
}

std::u16string PseudoMethodBidi::placeholder(const StringPiece16& source) {
    // Surround a placeholder with directionality change sequence
    return k_rlm + k_rlo + source.toString() + k_pdf + k_rlm;
}

} // namespace aapt
