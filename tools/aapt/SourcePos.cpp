#include "SourcePos.h"

#include <stdarg.h>
#include <vector>

using namespace std;


// ErrorPos
// =============================================================================
struct ErrorPos
{
    enum Level {
        NOTE,
        WARNING,
        ERROR
    };

    String8 file;
    int line;
    String8 error;
    Level level;

    ErrorPos();
    ErrorPos(const ErrorPos& that);
    ErrorPos(const String8& file, int line, const String8& error, Level level);
    ErrorPos& operator=(const ErrorPos& rhs);

    void print(FILE* to) const;
};

static vector<ErrorPos> g_errors;

ErrorPos::ErrorPos()
    :line(-1), level(NOTE)
{
}

ErrorPos::ErrorPos(const ErrorPos& that)
    :file(that.file),
     line(that.line),
     error(that.error),
     level(that.level)
{
}

ErrorPos::ErrorPos(const String8& f, int l, const String8& e, Level lev)
    :file(f),
     line(l),
     error(e),
     level(lev)
{
}

ErrorPos&
ErrorPos::operator=(const ErrorPos& rhs)
{
    this->file = rhs.file;
    this->line = rhs.line;
    this->error = rhs.error;
    this->level = rhs.level;
    return *this;
}

void
ErrorPos::print(FILE* to) const
{
    const char* type = "";
    switch (level) {
    case NOTE:
        type = "note: ";
        break;
    case WARNING:
        type = "warning: ";
        break;
    case ERROR:
        type = "error: ";
        break;
    }
    
    if (!this->file.empty()) {
        if (this->line >= 0) {
            fprintf(to, "%s:%d: %s%s\n", this->file.c_str(), this->line, type, this->error.c_str());
        } else {
            fprintf(to, "%s: %s%s\n", this->file.c_str(), type, this->error.c_str());
        }
    } else {
        fprintf(to, "%s%s\n", type, this->error.c_str());
    }
}

// SourcePos
// =============================================================================
SourcePos::SourcePos(const String8& f, int l)
    : file(f), line(l)
{
}

SourcePos::SourcePos(const SourcePos& that)
    : file(that.file), line(that.line)
{
}

SourcePos::SourcePos()
    : file("???", 0), line(-1)
{
}

SourcePos::~SourcePos()
{
}

void
SourcePos::error(const char* fmt, ...) const
{
    va_list ap;
    va_start(ap, fmt);
    String8 msg = String8::formatV(fmt, ap);
    va_end(ap);
    g_errors.push_back(ErrorPos(this->file, this->line, msg, ErrorPos::ERROR));
}

void
SourcePos::warning(const char* fmt, ...) const
{
    va_list ap;
    va_start(ap, fmt);
    String8 msg = String8::formatV(fmt, ap);
    va_end(ap);
    ErrorPos(this->file, this->line, msg, ErrorPos::WARNING).print(stderr);
}

void
SourcePos::printf(const char* fmt, ...) const
{
    va_list ap;
    va_start(ap, fmt);
    String8 msg = String8::formatV(fmt, ap);
    va_end(ap);
    ErrorPos(this->file, this->line, msg, ErrorPos::NOTE).print(stderr);
}

bool
SourcePos::operator<(const SourcePos& rhs) const
{
    return (file < rhs.file) || (line < rhs.line);
}

bool
SourcePos::hasErrors()
{
    return g_errors.size() > 0;
}

void
SourcePos::printErrors(FILE* to)
{
    vector<ErrorPos>::const_iterator it;
    for (it=g_errors.begin(); it!=g_errors.end(); it++) {
        it->print(to);
    }
}



