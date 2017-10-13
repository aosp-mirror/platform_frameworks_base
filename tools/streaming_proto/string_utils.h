#include <string>
#include <vector>

namespace android {
namespace stream_proto {

using namespace std;

/**
 * Capitalizes the string, removes underscores and makes the next letter
 * capitalized, and makes the letter following numbers capitalized.
 */
string to_camel_case(const string& str);

/**
 * Capitalize and insert underscores for CamelCase.
 */
string make_constant_name(const string& str);

/**
 * Returns the part of a file name that isn't a path and isn't a type suffix.
 */
string file_base_name(const string& str);

/**
 * Replace all occurances of 'replace' with 'with'.
 */
string replace_string(const string& str, const char replace, const char with);

/**
 * Split a string to parts by delimiter.
 */
vector<string> split(const string& str, const char delimiter);

} // namespace stream_proto
} // namespace android

