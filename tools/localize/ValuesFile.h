#ifndef VALUES_FILE_H
#define VALUES_FILE_H

#include "SourcePos.h"
#include "Configuration.h"
#include "XMLHandler.h"
#include "Values.h"

#include <string>
#include <set>

using namespace std;

extern const XMLNamespaceMap ANDROID_NAMESPACES;

class ValuesFile
{
public:
    ValuesFile(const Configuration& config);

    static ValuesFile* ParseFile(const string& filename, const Configuration& config,
                                     int version, const string& versionString);
    static ValuesFile* ParseString(const string& filename, const string& text,
                                     const Configuration& config,
                                     int version, const string& versionString);
    ~ValuesFile();

    const Configuration& GetConfiguration() const;

    void AddString(const StringResource& str);
    set<StringResource> GetStrings() const;

    // exports this file as a n XMLNode, you own this object
    XMLNode* ToXMLNode() const;

    // writes the ValuesFile out to a string in the canonical format (i.e. writes the contents of
    // ToXMLNode()).
    string ToString() const;

private:
    class ParseState;
    friend class ValuesFile::ParseState;
    friend class StringHandler;

    ValuesFile();

    Configuration m_config;
    set<StringResource> m_strings;
    map<string,set<StringResource> > m_arrays;
};

#endif // VALUES_FILE_H
