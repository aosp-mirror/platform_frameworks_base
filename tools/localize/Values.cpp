#include "Values.h"
#include <stdlib.h>
#include <cstdio>


// =====================================================================================
StringResource::StringResource(const SourcePos& p, const string& f, const Configuration& c, 
                    const string& i, int ix, XMLNode* v, const int ve, const string& vs,
                    const string& cmnt)
    :pos(p),
     file(f),
     config(c),
     id(i),
     index(ix),
     value(v),
     version(ve),
     versionString(vs),
     comment(cmnt)
{
}

StringResource::StringResource()
    :pos(),
     file(),
     config(),
     id(),
     index(-1),
     value(NULL),
     version(),
     versionString(),
     comment()
{
}

StringResource::StringResource(const StringResource& that)
    :pos(that.pos),
     file(that.file),
     config(that.config),
     id(that.id),
     index(that.index),
     value(that.value),
     version(that.version),
     versionString(that.versionString),
     comment(that.comment)
{
}

int
StringResource::Compare(const StringResource& that) const
{
    if (file != that.file) {
        return file < that.file ? -1 : 1;
    }
    if (id != that.id) {
        return id < that.id ? -1 : 1;
    }
    if (index != that.index) {
        return index - that.index;
    }
    if (config != that.config) {
        return config < that.config ? -1 : 1;
    }
    if (version != that.version) {
        return version < that.version ? -1 : 1;
    }
    return 0;
}

string
StringResource::TypedID() const
{
    string result;
    if (index < 0) {
        result = "string:";
    } else {
        char n[20];
        sprintf(n, "%d:", index);
        result = "array:";
        result += n;
    }
    result += id;
    return result;
}

static void
split(const string& raw, vector<string>*parts)
{
    size_t index = 0;
    while (true) {
        size_t next = raw.find(':', index);
        if (next != raw.npos) {
            parts->push_back(string(raw, index, next-index));
            index = next + 1;
        } else {
            parts->push_back(string(raw, index));
            break;
        }
    }
}

bool
StringResource::ParseTypedID(const string& raw, string* id, int* index)
{
    vector<string> parts;
    split(raw, &parts);

    const size_t N = parts.size();

    for (size_t i=0; i<N; i++) {
        if (parts[i].length() == 0) {
            return false;
        }
    }

    if (N == 2 && parts[0] == "string") {
        *id = parts[1];
        *index = -1;
        return true;
    }
    else if (N == 3 && parts[0] == "array") {
        char* p;
        int n = (int)strtol(parts[1].c_str(), &p, 0);
        if (*p == '\0') {
            *id = parts[2];
            *index = n;
            return true;
        } else {
            return false;
        }
    }
    else {
        return false;
    }
}

