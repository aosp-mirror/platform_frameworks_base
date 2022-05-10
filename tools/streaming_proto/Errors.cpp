#include "Errors.h"

#include <stdarg.h>
#include <stdlib.h>

namespace android {
namespace stream_proto {

Errors ERRORS;

const string UNKNOWN_FILE;
const int UNKNOWN_LINE = 0;

Error::Error()
{
}

Error::Error(const Error& that)
    :filename(that.filename),
     lineno(that.lineno),
     message(that.message)
{
}

Error::Error(const string& f, int l, const char* m)
    :filename(f),
     lineno(l),
     message(m)
{
}

Errors::Errors()
    :m_errors()
{
}

Errors::~Errors()
{
}

void
Errors::Add(const string& filename, int lineno, const char* format, ...)
{
    va_list args;
    va_start(args, format);
    AddImpl(filename, lineno, format, args);
    va_end(args);
}

void
Errors::AddImpl(const string& filename, int lineno, const char* format, va_list args)
{
    va_list args2;
    va_copy(args2, args);
    int message_size = vsnprintf((char*)NULL, 0, format, args2);
    va_end(args2);

    char* buffer = new char[message_size+1];
    vsnprintf(buffer, message_size, format, args);
    Error error(filename, lineno, buffer);
    delete[] buffer;

    m_errors.push_back(error);
}

void
Errors::Print() const
{
    for (vector<Error>::const_iterator it = m_errors.begin(); it != m_errors.end(); it++) {
        if (it->filename == UNKNOWN_FILE) {
            fprintf(stderr, "%s", it->message.c_str());
        } else if (it->lineno == UNKNOWN_LINE) {
            fprintf(stderr, "%s:%s", it->filename.c_str(), it->message.c_str());
        } else {
            fprintf(stderr, "%s:%d:%s", it->filename.c_str(), it->lineno, it->message.c_str());
        }
    }
}

bool
Errors::HasErrors() const
{
    return m_errors.size() > 0;
}

} // namespace stream_proto
} // namespace android

