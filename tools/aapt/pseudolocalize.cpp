#include "pseudolocalize.h"

using namespace std;

// String basis to generate expansion
static const String16 k_expansion_string = String16("one two three "
    "four five six seven eight nine ten eleven twelve thirteen "
    "fourteen fiveteen sixteen seventeen nineteen twenty");

// Special unicode characters to override directionality of the words
static const String16 k_rlm = String16("\xe2\x80\x8f");
static const String16 k_rlo = String16("\xE2\x80\xae");
static const String16 k_pdf = String16("\xE2\x80\xac");

// Placeholder marks
static const String16 k_placeholder_open = String16("\xc2\xbb");
static const String16 k_placeholder_close = String16("\xc2\xab");

static const char*
pseudolocalize_char(const char16_t c)
{
    switch (c) {
        case 'a':   return "\xc3\xa5";
        case 'b':   return "\xc9\x93";
        case 'c':   return "\xc3\xa7";
        case 'd':   return "\xc3\xb0";
        case 'e':   return "\xc3\xa9";
        case 'f':   return "\xc6\x92";
        case 'g':   return "\xc4\x9d";
        case 'h':   return "\xc4\xa5";
        case 'i':   return "\xc3\xae";
        case 'j':   return "\xc4\xb5";
        case 'k':   return "\xc4\xb7";
        case 'l':   return "\xc4\xbc";
        case 'm':   return "\xe1\xb8\xbf";
        case 'n':   return "\xc3\xb1";
        case 'o':   return "\xc3\xb6";
        case 'p':   return "\xc3\xbe";
        case 'q':   return "\x51";
        case 'r':   return "\xc5\x95";
        case 's':   return "\xc5\xa1";
        case 't':   return "\xc5\xa3";
        case 'u':   return "\xc3\xbb";
        case 'v':   return "\x56";
        case 'w':   return "\xc5\xb5";
        case 'x':   return "\xd1\x85";
        case 'y':   return "\xc3\xbd";
        case 'z':   return "\xc5\xbe";
        case 'A':   return "\xc3\x85";
        case 'B':   return "\xce\xb2";
        case 'C':   return "\xc3\x87";
        case 'D':   return "\xc3\x90";
        case 'E':   return "\xc3\x89";
        case 'G':   return "\xc4\x9c";
        case 'H':   return "\xc4\xa4";
        case 'I':   return "\xc3\x8e";
        case 'J':   return "\xc4\xb4";
        case 'K':   return "\xc4\xb6";
        case 'L':   return "\xc4\xbb";
        case 'M':   return "\xe1\xb8\xbe";
        case 'N':   return "\xc3\x91";
        case 'O':   return "\xc3\x96";
        case 'P':   return "\xc3\x9e";
        case 'Q':   return "\x71";
        case 'R':   return "\xc5\x94";
        case 'S':   return "\xc5\xa0";
        case 'T':   return "\xc5\xa2";
        case 'U':   return "\xc3\x9b";
        case 'V':   return "\xce\xbd";
        case 'W':   return "\xc5\xb4";
        case 'X':   return "\xc3\x97";
        case 'Y':   return "\xc3\x9d";
        case 'Z':   return "\xc5\xbd";
        case '!':   return "\xc2\xa1";
        case '?':   return "\xc2\xbf";
        case '$':   return "\xe2\x82\xac";
        default:    return NULL;
    }
}

static bool
is_possible_normal_placeholder_end(const char16_t c) {
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

String16
pseudo_generate_expansion(const unsigned int length) {
    String16 result = k_expansion_string;
    const char16_t* s = result.string();
    if (result.size() < length) {
        result += String16(" ");
        result += pseudo_generate_expansion(length - result.size());
    } else {
        int ext = 0;
        // Should contain only whole words, so looking for a space
        for (unsigned int i = length + 1; i < result.size(); ++i) {
          ++ext;
          if (s[i] == ' ') {
            break;
          }
        }
        result.remove(length + ext, 0);
    }
    return result;
}

/**
 * Converts characters so they look like they've been localized.
 *
 * Note: This leaves escape sequences untouched so they can later be
 * processed by ResTable::collectString in the normal way.
 */
String16
pseudolocalize_string(const String16& source)
{
    const char16_t* s = source.string();
    String16 result;
    const size_t I = source.size();
    for (size_t i=0; i<I; i++) {
        char16_t c = s[i];
        if (c == '\\') {
            // Escape syntax, no need to pseudolocalize
            if (i<I-1) {
                result += String16("\\");
                i++;
                c = s[i];
                switch (c) {
                    case 'u':
                        // this one takes up 5 chars
                        result += String16(s+i, 5);
                        i += 4;
                        break;
                    case 't':
                    case 'n':
                    case '#':
                    case '@':
                    case '?':
                    case '"':
                    case '\'':
                    case '\\':
                    default:
                        result.append(&c, 1);
                        break;
                }
            } else {
                result.append(&c, 1);
            }
        } else if (c == '%') {
            // Placeholder syntax, no need to pseudolocalize
            result += k_placeholder_open;
            bool end = false;
            result.append(&c, 1);
            while (!end && i < I) {
                ++i;
                c = s[i];
                result.append(&c, 1);
                if (is_possible_normal_placeholder_end(c)) {
                    end = true;
                } else if (c == 't') {
                    ++i;
                    c = s[i];
                    result.append(&c, 1);
                    end = true;
                }
            }
            result += k_placeholder_close;
        } else if (c == '<' || c == '&') {
            // html syntax, no need to pseudolocalize
            bool tag_closed = false;
            while (!tag_closed && i < I) {
                if (c == '&') {
                    String16 escape_text;
                    escape_text.append(&c, 1);
                    bool end = false;
                    size_t htmlCodePos = i;
                    while (!end && htmlCodePos < I) {
                        ++htmlCodePos;
                        c = s[htmlCodePos];
                        escape_text.append(&c, 1);
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
                    result += escape_text;
                    if (escape_text != String16("&lt;")) {
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
            const char* p = pseudolocalize_char(c);
            if (p != NULL) {
                result += String16(p);
            } else {
                result.append(&c, 1);
            }
        }
    }
    return result;
}

String16
pseudobidi_string(const String16& source)
{
    const char16_t* s = source.string();
    String16 result;
    result += k_rlm;
    result += k_rlo;
    for (size_t i=0; i<source.size(); i++) {
        char16_t c = s[i];
        switch(c) {
            case ' ': result += k_pdf;
                      result += k_rlm;
                      result.append(&c, 1);
                      result += k_rlm;
                      result += k_rlo;
                      break;
            default: result.append(&c, 1);
                     break;
        }
    }
    result += k_pdf;
    result += k_rlm;
    return result;
}

