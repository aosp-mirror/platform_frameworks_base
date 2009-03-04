#ifndef SOURCEPOS_H
#define SOURCEPOS_H

#include <string>

using namespace std;

class SourcePos
{
public:
    string file;
    int line;

    SourcePos(const string& f, int l);
    SourcePos(const SourcePos& that);
    SourcePos();
    ~SourcePos();

    string ToString() const;
    int Error(const char* fmt, ...) const;

    static bool HasErrors();
    static void PrintErrors(FILE* to);
};

extern const SourcePos GENERATED_POS;

#endif // SOURCEPOS_H
