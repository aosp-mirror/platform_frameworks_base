
#include "string_utils.h"
#include <iostream>

namespace android {
namespace stream_proto {

using namespace std;

string
to_camel_case(const string& str)
{
    string result;
    const int N = str.size();
    result.reserve(N);
    bool capitalize_next = true;
    for (int i=0; i<N; i++) {
        char c = str[i];
        if (c == '_') {
            capitalize_next = true;
        } else {
            if (capitalize_next && c >= 'a' && c <= 'z') {
                c = 'A' + c - 'a';
                capitalize_next = false;
            } else if (c >= 'A' && c <= 'Z') {
                capitalize_next = false;
            } else if (c >= '0' && c <= '9') {
                capitalize_next = true;
            } else {
                // All other characters (e.g. non-latin) count as capital.
                capitalize_next = false;
            }
            result += c;
        }
    }
    return result;
}

string
make_constant_name(const string& str)
{
    string result;
    const int N = str.size();
    bool underscore_next = false;
    for (int i=0; i<N; i++) {
        char c = str[i];
        if (c >= 'A' && c <= 'Z') {
            if (underscore_next) {
                result += '_';
                underscore_next = false;
            }
        } else if (c >= 'a' && c <= 'z') {
            c = 'A' + c - 'a';
            underscore_next = true;
        } else if (c == '_') {
            underscore_next = false;
        }
        result += c;
    }
    return result;
}

string
file_base_name(const string& str)
{
    size_t start = str.rfind('/');
    if (start == string::npos) {
        start = 0;
    } else {
        start++;
    }
    size_t end = str.find('.', start);
    if (end == string::npos) {
        end = str.size();
    }
    return str.substr(start, end-start);
}

string
replace_string(const string& str, const char replace, const char with)
{
    string result(str);
    const int N = result.size();
    for (int i=0; i<N; i++) {
        if (result[i] == replace) {
            result[i] = with;
        }
    }
    return result;
}

vector<string>
split(const string& str, const char delimiter)
{
    vector<string> result;
    size_t base = 0, found = 0;
    while (true) {
        found = str.find_first_of(delimiter, base);
        if (found != base) {
            string part = str.substr(base, found - base);
            if (!part.empty()) {
                result.push_back(part);
            }
        }
        if (found == str.npos) break;
        base = found + 1;
    }
    return result;
}

string
stripPrefix(const string& str, const string& prefix)
{
    if (str.size() <= prefix.size()) return str;
    size_t i = 0, len = prefix.size();
    for (; i<len; i++) {
        if (str[i] != prefix[i]) return str;
    }
    return str.substr(i);
}

} // namespace stream_proto
} // namespace android


