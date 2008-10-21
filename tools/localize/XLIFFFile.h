#ifndef XLIFF_FILE_H
#define XLIFF_FILE_H

#include "Values.h"

#include "Configuration.h"

#include <set>

using namespace std;

extern const XMLNamespaceMap XLIFF_NAMESPACES;

extern const char*const XLIFF_XMLNS;

struct Stats
{
    string config;
    size_t files;
    size_t toBeTranslated;
    size_t noComments;
    size_t totalStrings;
};

struct TransUnit {
    string id;
    StringResource source;
    StringResource target;
    StringResource altSource;
    StringResource altTarget;
    string rejectComment;
};

class XLIFFFile
{
public:
    static XLIFFFile* Parse(const string& filename);
    static XLIFFFile* Create(const Configuration& sourceConfig, const Configuration& targetConfig,
                                const string& currentVersion);
    ~XLIFFFile();

    inline const Configuration& SourceConfig() const                { return m_sourceConfig; }
    inline const Configuration& TargetConfig() const                { return m_targetConfig; }

    inline const string& CurrentVersion() const                     { return m_currentVersion; }
    inline const string& OldVersion() const                         { return m_oldVersion; }

    set<string> Files() const;

    void AddStringResource(const StringResource& res);
    inline set<StringResource> const& GetStringResources() const { return m_strings; }
    bool FindStringResource(const string& filename, int version, bool source);

    void Filter(bool (*func)(const string&,const TransUnit&,void*), void* cookie);
    void Map(void (*func)(const string&,TransUnit*,void*), void* cookie);

    TransUnit* EditTransUnit(const string& file, const string& id);

    // exports this file as a n XMLNode, you own this object
    XMLNode* ToXMLNode() const;

    // writes the ValuesFile out to a string in the canonical format (i.e. writes the contents of
    // ToXMLNode()).
    string ToString() const;

    Stats GetStats(const string& config) const;

private:
    struct File {
        int Compare(const File& that) const;

        inline bool operator<(const File& that) const { return Compare(that) < 0; }
        inline bool operator<=(const File& that) const { return Compare(that) <= 0; }
        inline bool operator==(const File& that) const { return Compare(that) == 0; }
        inline bool operator!=(const File& that) const { return Compare(that) != 0; }
        inline bool operator>=(const File& that) const { return Compare(that) >= 0; }
        inline bool operator>(const File& that) const { return Compare(that) > 0; }

        string filename;
        vector<TransUnit> transUnits;
    };

    XLIFFFile();
    StringResource* find_string_res(TransUnit& g, const StringResource& str);
    
    Configuration m_sourceConfig;
    Configuration m_targetConfig;

    string m_currentVersion;
    string m_oldVersion;

    set<StringResource> m_strings;
    vector<File> m_files;
};

int convert_html_to_xliff(const XMLNode* original, const string& name, XMLNode* addTo, int* phID);

#endif // XLIFF_FILE_H
