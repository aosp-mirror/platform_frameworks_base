#include <stdio.h>

#include <string>
#include <vector>

namespace android {
namespace stream_proto {

using namespace std;

struct Error
{
    Error();
    explicit Error(const Error& that);
    Error(const string& filename, int lineno, const char* message);

    string filename;
    int lineno;
    string message;
};

class Errors
{
public:
    Errors();
    ~Errors();

    // Add an error
    void Add(const string& filename, int lineno, const char* format, ...);

    // Print the errors to stderr if there are any.
    void Print() const;

    bool HasErrors() const;

private:
    // The errors that have been added
    vector<Error> m_errors;
    void AddImpl(const string& filename, int lineno, const char* format, va_list ap);
};

extern Errors ERRORS;
extern const string UNKNOWN_FILE;
extern const int UNKNOWN_LINE;


} // namespace stream_proto
} // namespace android
