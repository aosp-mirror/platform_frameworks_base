#include "pseudolocalize.h"

using namespace std;

static const char*
pseudolocalize_char(char c)
{
    switch (c) {
        case 'a':   return "\xc4\x83";
        case 'b':   return "\xcf\x84";
        case 'c':   return "\xc4\x8b";
        case 'd':   return "\xc4\x8f";
        case 'e':   return "\xc4\x99";
        case 'f':   return "\xc6\x92";
        case 'g':   return "\xc4\x9d";
        case 'h':   return "\xd1\x9b";
        case 'i':   return "\xcf\x8a";
        case 'j':   return "\xc4\xb5";
        case 'k':   return "\xc4\xb8";
        case 'l':   return "\xc4\xba";
        case 'm':   return "\xe1\xb8\xbf";
        case 'n':   return "\xd0\xb8";
        case 'o':   return "\xcf\x8c";
        case 'p':   return "\xcf\x81";
        case 'q':   return "\x51";
        case 'r':   return "\xd2\x91";
        case 's':   return "\xc5\xa1";
        case 't':   return "\xd1\x82";
        case 'u':   return "\xce\xb0";
        case 'v':   return "\x56";
        case 'w':   return "\xe1\xba\x85";
        case 'x':   return "\xd1\x85";
        case 'y':   return "\xe1\xbb\xb3";
        case 'z':   return "\xc5\xba";
        case 'A':   return "\xc3\x85";
        case 'B':   return "\xce\xb2";
        case 'C':   return "\xc4\x88";
        case 'D':   return "\xc4\x90";
        case 'E':   return "\xd0\x84";
        case 'F':   return "\xce\x93";
        case 'G':   return "\xc4\x9e";
        case 'H':   return "\xc4\xa6";
        case 'I':   return "\xd0\x87";
        case 'J':   return "\xc4\xb5";
        case 'K':   return "\xc4\xb6";
        case 'L':   return "\xc5\x81";
        case 'M':   return "\xe1\xb8\xbe";
        case 'N':   return "\xc5\x83";
        case 'O':   return "\xce\x98";
        case 'P':   return "\xcf\x81";
        case 'Q':   return "\x71";
        case 'R':   return "\xd0\xaf";
        case 'S':   return "\xc8\x98";
        case 'T':   return "\xc5\xa6";
        case 'U':   return "\xc5\xa8";
        case 'V':   return "\xce\xbd";
        case 'W':   return "\xe1\xba\x84";
        case 'X':   return "\xc3\x97";
        case 'Y':   return "\xc2\xa5";
        case 'Z':   return "\xc5\xbd";
        default:    return NULL;
    }
}

/**
 * Converts characters so they look like they've been localized.
 *
 * Note: This leaves escape sequences untouched so they can later be
 * processed by ResTable::collectString in the normal way.
 */
string
pseudolocalize_string(const string& source)
{
    const char* s = source.c_str();
    string result;
    const size_t I = source.length();
    for (size_t i=0; i<I; i++) {
        char c = s[i];
        if (c == '\\') {
            if (i<I-1) {
                result += '\\';
                i++;
                c = s[i];
                switch (c) {
                    case 'u':
                        // this one takes up 5 chars
                        result += string(s+i, 5);
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
                        result += c;
                        break;
                }
            } else {
                result += c;
            }
        } else {
            const char* p = pseudolocalize_char(c);
            if (p != NULL) {
                result += p;
            } else {
                result += c;
            }
        }
    }

    //printf("result=\'%s\'\n", result.c_str());
    return result;
}


