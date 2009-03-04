#include "ValuesFile.h"

#include "XMLHandler.h"

#include <algorithm>
#include <fcntl.h>
#include <expat.h>
#include <unistd.h>
#include <errno.h>

using namespace std;

const char* const ANDROID_XMLNS = "http://schemas.android.com/apk/res/android";
const char* const XLIFF_XMLNS = "urn:oasis:names:tc:xliff:document:1.2";

const char *const NS_MAP[] = {
    "android", ANDROID_XMLNS,
    "xliff", XLIFF_XMLNS,
    NULL, NULL
};

const XMLNamespaceMap ANDROID_NAMESPACES(NS_MAP);


// =====================================================================================
class ArrayHandler : public XMLHandler
{
public:
    ArrayHandler(ValuesFile* vf, int version, const string& versionString, const string& id);

    virtual int OnStartElement(const SourcePos& pos, const string& ns, const string& name,
                                const vector<XMLAttribute>& attrs, XMLHandler** next);
    virtual int OnText(const SourcePos& pos, const string& text);
    virtual int OnComment(const SourcePos& pos, const string& text);

private:
    ValuesFile* m_vf;
    int m_version;
    int m_index;
    string m_versionString;
    string m_id;
    string m_comment;
};

ArrayHandler::ArrayHandler(ValuesFile* vf, int version, const string& versionString,
                            const string& id)
    :m_vf(vf),
     m_version(version),
     m_index(0),
     m_versionString(versionString),
     m_id(id)
{
}

int
ArrayHandler::OnStartElement(const SourcePos& pos, const string& ns, const string& name,
                                const vector<XMLAttribute>& attrs, XMLHandler** next)
{
    if (ns == "" && name == "item") {
        XMLNode* node = XMLNode::NewElement(pos, ns, name, attrs, XMLNode::EXACT);
        m_vf->AddString(StringResource(pos, pos.file, m_vf->GetConfiguration(),
                                            m_id, m_index, node, m_version, m_versionString,
                                            trim_string(m_comment)));
        *next = new NodeHandler(node, XMLNode::EXACT);
        m_index++;
        m_comment = "";
        return 0;
    } else {
        pos.Error("invalid <%s> element inside <array>\n", name.c_str());
        return 1;
    }
}

int
ArrayHandler::OnText(const SourcePos& pos, const string& text)
{
    return 0;
}

int
ArrayHandler::OnComment(const SourcePos& pos, const string& text)
{
    m_comment += text;
    return 0;
}

// =====================================================================================
class ValuesHandler : public XMLHandler
{
public:
    ValuesHandler(ValuesFile* vf, int version, const string& versionString);

    virtual int OnStartElement(const SourcePos& pos, const string& ns, const string& name,
                                const vector<XMLAttribute>& attrs, XMLHandler** next);
    virtual int OnText(const SourcePos& pos, const string& text);
    virtual int OnComment(const SourcePos& pos, const string& text);

private:
    ValuesFile* m_vf;
    int m_version;
    string m_versionString;
    string m_comment;
};

ValuesHandler::ValuesHandler(ValuesFile* vf, int version, const string& versionString)
    :m_vf(vf),
     m_version(version),
     m_versionString(versionString)
{
}

int
ValuesHandler::OnStartElement(const SourcePos& pos, const string& ns, const string& name,
                                const vector<XMLAttribute>& attrs, XMLHandler** next)
{
    if (ns == "" && name == "string") {
        string id = XMLAttribute::Find(attrs, "", "name", "");
        XMLNode* node = XMLNode::NewElement(pos, ns, name, attrs, XMLNode::EXACT);
        m_vf->AddString(StringResource(pos, pos.file, m_vf->GetConfiguration(),
                                            id, -1, node, m_version, m_versionString,
                                            trim_string(m_comment)));
        *next = new NodeHandler(node, XMLNode::EXACT);
    }
    else if (ns == "" && name == "array") {
        string id = XMLAttribute::Find(attrs, "", "name", "");
        *next = new ArrayHandler(m_vf, m_version, m_versionString, id);
    }
    m_comment = "";
    return 0;
}

int
ValuesHandler::OnText(const SourcePos& pos, const string& text)
{
    return 0;
}

int
ValuesHandler::OnComment(const SourcePos& pos, const string& text)
{
    m_comment += text;
    return 0;
}

// =====================================================================================
ValuesFile::ValuesFile(const Configuration& config)
    :m_config(config),
     m_strings(),
     m_arrays()
{
}

ValuesFile::~ValuesFile()
{
}

ValuesFile*
ValuesFile::ParseFile(const string& filename, const Configuration& config,
                    int version, const string& versionString)
{
    ValuesFile* result = new ValuesFile(config);

    TopElementHandler top("", "resources", new ValuesHandler(result, version, versionString));
    XMLHandler::ParseFile(filename, &top);

    return result;
}

ValuesFile*
ValuesFile::ParseString(const string& filename, const string& text, const Configuration& config,
                    int version, const string& versionString)
{
    ValuesFile* result = new ValuesFile(config);

    TopElementHandler top("", "resources", new ValuesHandler(result, version, versionString));
    XMLHandler::ParseString(filename, text, &top);

    return result;
}

const Configuration&
ValuesFile::GetConfiguration() const
{
    return m_config;
}

void
ValuesFile::AddString(const StringResource& str)
{
    if (str.index < 0) {
        m_strings.insert(str);
    } else {
        m_arrays[str.id].insert(str);
    }
}

set<StringResource>
ValuesFile::GetStrings() const
{
    set<StringResource> result = m_strings;

    for (map<string,set<StringResource> >::const_iterator it = m_arrays.begin();
            it != m_arrays.end(); it++) {
        result.insert(it->second.begin(), it->second.end());
    }

    return result;
}

XMLNode*
ValuesFile::ToXMLNode() const
{
    XMLNode* root;

    // <resources>
    {
        vector<XMLAttribute> attrs;
        ANDROID_NAMESPACES.AddToAttributes(&attrs);
        root = XMLNode::NewElement(GENERATED_POS, "", "resources", attrs, XMLNode::PRETTY);
    }

    // <array>
    for (map<string,set<StringResource> >::const_iterator it = m_arrays.begin();
            it != m_arrays.end(); it++) {
        vector<XMLAttribute> arrayAttrs;
        arrayAttrs.push_back(XMLAttribute("", "name", it->first));
        const set<StringResource>& items = it->second;
        XMLNode* arrayNode = XMLNode::NewElement(items.begin()->pos, "", "array", arrayAttrs,
                XMLNode::PRETTY);
        root->EditChildren().push_back(arrayNode);

        // <item>
        for (set<StringResource>::const_iterator item = items.begin();
                item != items.end(); item++) {
            XMLNode* itemNode = item->value->Clone();
            itemNode->SetName("", "item");
            itemNode->EditAttributes().clear();
            arrayNode->EditChildren().push_back(itemNode);
        }
    }

    // <string>
    for (set<StringResource>::const_iterator it=m_strings.begin(); it!=m_strings.end(); it++) {
        const StringResource& str = *it;
        vector<XMLAttribute> attrs;
        XMLNode* strNode = str.value->Clone();
        strNode->SetName("", "string");
        strNode->EditAttributes().clear();
        strNode->EditAttributes().push_back(XMLAttribute("", "name", str.id));
        root->EditChildren().push_back(strNode);
    }

    return root;
}

string
ValuesFile::ToString() const
{
    XMLNode* xml = ToXMLNode();
    string s = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
    s += xml->ToString(ANDROID_NAMESPACES);
    delete xml;
    s += '\n';
    return s;
}

