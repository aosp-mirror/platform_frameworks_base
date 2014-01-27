#include "SourcePos.h"

#include <stdarg.h>
#include <vector>

using namespace std;


// ErrorPos
// =============================================================================
struct ErrorPos
{
    String8 file;
    int line;
    String8 error;
    bool fatal;

    ErrorPos();
    ErrorPos(const ErrorPos& that);
    ErrorPos(const String8& file, int line, const String8& error, bool fatal);
    ~ErrorPos();
    bool operator<(const ErrorPos& rhs) const;
    bool operator==(const ErrorPos& rhs) const;
    ErrorPos& operator=(const ErrorPos& rhs);

    void print(FILE* to) const;
};

static vector<ErrorPos> g_errors;

ErrorPos::ErrorPos()
    :line(-1), fatal(false)
{
}

ErrorPos::ErrorPos(const ErrorPos& that)
    :file(that.file),
     line(that.line),
     error(that.error),
     fatal(that.fatal)
{
}

ErrorPos::ErrorPos(const String8& f, int l, const String8& e, bool fat)
    :file(f),
     line(l),
     error(e),
     fatal(fat)
{
}

ErrorPos::~ErrorPos()
{
}

bool
ErrorPos::operator<(const ErrorPos& rhs) const
{
    if (this->file < rhs.file) return true;
    if (this->file == rhs.file) {
        if (this->line < rhs.line) return true;
        if (this->line == rhs.line) {
            if (this->error < rhs.error) return true;
        }
    }
    return false;
}

bool
ErrorPos::operator==(const ErrorPos& rhs) const
{
    return this->file == rhs.file
            && this->line == rhs.line
            && this->error == rhs.error;
}

ErrorPos&
ErrorPos::operator=(const ErrorPos& rhs)
{
    this->file = rhs.file;
    this->line = rhs.line;
    this->error = rhs.error;
    return *this;
}

void
ErrorPos::print(FILE* to) const
{
    const char* type = fatal ? "error:" : "warning:";
    
    if (this->line >= 0) {
        fprintf(to, "%s:%d: %s %s\n", this->file.string(), this->line, type, this->error.string());
    } else {
        fprintf(to, "%s: %s %s\n", this->file.string(), type, this->error.string());
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

int
SourcePos::error(const char* fmt, ...) const
{
    int retval=0;
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    retval = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    char* p = buf + retval - 1;
    while (p > buf && *p == '\n') {
        *p = '\0';
        p--;
    }
    g_errors.push_back(ErrorPos(this->file, this->line, String8(buf), true));
    return retval;
}

int
SourcePos::warning(const char* fmt, ...) const
{
    int retval=0;
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    retval = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    char* p = buf + retval - 1;
    while (p > buf && *p == '\n') {
        *p = '\0';
        p--;
    }
    ErrorPos(this->file, this->line, String8(buf), false).print(stderr);
    return retval;
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



