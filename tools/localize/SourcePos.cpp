#include "SourcePos.h"

#include <stdarg.h>
#include <cstdio>
#include <set>
#include <cstdio>

using namespace std;

const SourcePos GENERATED_POS("<generated>", -1);

// ErrorPos
// =============================================================================
struct ErrorPos
{
    string file;
    int line;
    string error;

    ErrorPos();
    ErrorPos(const ErrorPos& that);
    ErrorPos(const string& file, int line, const string& error);
    ~ErrorPos();
    bool operator<(const ErrorPos& rhs) const;
    bool operator==(const ErrorPos& rhs) const;
    ErrorPos& operator=(const ErrorPos& rhs);

    void Print(FILE* to) const;
};

static set<ErrorPos> g_errors;

ErrorPos::ErrorPos()
{
}

ErrorPos::ErrorPos(const ErrorPos& that)
    :file(that.file),
     line(that.line),
     error(that.error)
{
}

ErrorPos::ErrorPos(const string& f, int l, const string& e)
    :file(f),
     line(l),
     error(e)
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
ErrorPos::Print(FILE* to) const
{
    if (this->line >= 0) {
        fprintf(to, "%s:%d: %s\n", this->file.c_str(), this->line, this->error.c_str());
    } else {
        fprintf(to, "%s: %s\n", this->file.c_str(), this->error.c_str());
    }
}

// SourcePos
// =============================================================================
SourcePos::SourcePos(const string& f, int l)
    : file(f), line(l)
{
}

SourcePos::SourcePos(const SourcePos& that)
    : file(that.file), line(that.line)
{
}

SourcePos::SourcePos()
    : file("???", 0)
{
}

SourcePos::~SourcePos()
{
}

string
SourcePos::ToString() const
{
    char buf[1024];
    if (this->line >= 0) {
        snprintf(buf, sizeof(buf)-1, "%s:%d", this->file.c_str(), this->line);
    } else {
        snprintf(buf, sizeof(buf)-1, "%s:", this->file.c_str());
    }
    buf[sizeof(buf)-1] = '\0';
    return string(buf);
}

int
SourcePos::Error(const char* fmt, ...) const
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
    ErrorPos err(this->file, this->line, string(buf));
    if (g_errors.find(err) == g_errors.end()) {
        err.Print(stderr);
        g_errors.insert(err);
    }
    return retval;
}

bool
SourcePos::HasErrors()
{
    return g_errors.size() > 0;
}

void
SourcePos::PrintErrors(FILE* to)
{
    set<ErrorPos>::const_iterator it;
    for (it=g_errors.begin(); it!=g_errors.end(); it++) {
        it->Print(to);
    }
}




