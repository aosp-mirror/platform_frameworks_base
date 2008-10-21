#ifndef VALUES_H
#define VALUES_H

#include "Configuration.h"
#include "XMLHandler.h"

#include <string>

using namespace std;

enum {
    CURRENT_VERSION,
    OLD_VERSION
};

struct StringResource
{
    StringResource();
    StringResource(const SourcePos& pos, const string& file, const Configuration& config, 
                    const string& id, int index, XMLNode* value,
                    int version, const string& versionString, const string& comment = "");
    StringResource(const StringResource& that);

    // Compare two configurations
    int Compare(const StringResource& that) const;

    inline bool operator<(const StringResource& that) const { return Compare(that) < 0; }
    inline bool operator<=(const StringResource& that) const { return Compare(that) <= 0; }
    inline bool operator==(const StringResource& that) const { return Compare(that) == 0; }
    inline bool operator!=(const StringResource& that) const { return Compare(that) != 0; }
    inline bool operator>=(const StringResource& that) const { return Compare(that) >= 0; }
    inline bool operator>(const StringResource& that) const { return Compare(that) > 0; }

    string TypedID() const;
    static bool ParseTypedID(const string& typed, string* id, int* index);

    SourcePos pos;
    string file;
    Configuration config;
    string id;
    int index;
    XMLNode* value;
    int version;
    string versionString;
    string comment;
};

#endif // VALUES_H
