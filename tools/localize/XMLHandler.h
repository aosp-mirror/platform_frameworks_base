#ifndef XML_H
#define XML_H

#include "SourcePos.h"

#include <algorithm>
#include <string>
#include <vector>
#include <map>

#define XMLNS_XMLNS "http://www.w3.org/XML/1998/namespace"

using namespace std;

string trim_string(const string& str);

struct XMLAttribute
{
    string ns;
    string name;
    string value;

    XMLAttribute();
    XMLAttribute(const XMLAttribute& that);
    XMLAttribute(string ns, string name, string value);
    ~XMLAttribute();

    int Compare(const XMLAttribute& that) const;

    inline bool operator<(const XMLAttribute& that) const { return Compare(that) < 0; }
    inline bool operator<=(const XMLAttribute& that) const { return Compare(that) <= 0; }
    inline bool operator==(const XMLAttribute& that) const { return Compare(that) == 0; }
    inline bool operator!=(const XMLAttribute& that) const { return Compare(that) != 0; }
    inline bool operator>=(const XMLAttribute& that) const { return Compare(that) >= 0; }
    inline bool operator>(const XMLAttribute& that) const { return Compare(that) > 0; }

    static string Find(const vector<XMLAttribute>& list,
                                const string& ns, const string& name, const string& def);
};

class XMLNamespaceMap
{
public:
    XMLNamespaceMap();
    XMLNamespaceMap(char const*const* nspaces);
    string Get(const string& ns) const;
    string GetPrefix(const string& ns) const;
    void AddToAttributes(vector<XMLAttribute>* attrs) const;
private:
    map<string,string> m_map;
};

struct XMLNode
{
public:
    enum {
        EXACT = 0,
        PRETTY = 1
    };

    enum {
        ELEMENT = 0,
        TEXT = 1
    };

    static XMLNode* NewElement(const SourcePos& pos, const string& ns, const string& name,
                        const vector<XMLAttribute>& attrs, int pretty);
    static XMLNode* NewText(const SourcePos& pos, const string& text, int pretty);

    ~XMLNode();

    // a deep copy
    XMLNode* Clone() const;

    inline int Type() const                                     { return m_type; }
    inline int Pretty() const                                   { return m_pretty; }
    void SetPrettyRecursive(int value);
    string ContentsToString(const XMLNamespaceMap& nspaces) const;
    string ToString(const XMLNamespaceMap& nspaces) const;
    string OpenTagToString(const XMLNamespaceMap& nspaces, int pretty) const;

    string CollapseTextContents() const;

    inline const SourcePos& Position() const                    { return m_pos; }

    // element
    inline string Namespace() const                             { return m_ns; }
    inline string Name() const                                  { return m_name; }
    inline void SetName(const string& ns, const string& n)      { m_ns = ns; m_name = n; }
    inline const vector<XMLAttribute>& Attributes() const       { return m_attrs; }
    inline vector<XMLAttribute>& EditAttributes()               { return m_attrs; }
    inline const vector<XMLNode*>& Children() const             { return m_children; }
    inline vector<XMLNode*>& EditChildren()                     { return m_children; }
    vector<XMLNode*> GetElementsByName(const string& ns, const string& name) const;
    XMLNode* GetElementByNameAt(const string& ns, const string& name, size_t index) const;
    size_t CountElementsByName(const string& ns, const string& name) const;
    string GetAttribute(const string& ns, const string& name, const string& def) const;

    // text
    inline string Text() const                                  { return m_text; }

private:
    XMLNode();
    XMLNode(const XMLNode&);

    string contents_to_string(const XMLNamespaceMap& nspaces, const string& indent) const;
    string to_string(const XMLNamespaceMap& nspaces, const string& indent) const;
    string open_tag_to_string(const XMLNamespaceMap& nspaces, const string& indent,
            int pretty) const;

    int m_type;
    int m_pretty;
    SourcePos m_pos;

    // element
    string m_ns;
    string m_name;
    vector<XMLAttribute> m_attrs;
    vector<XMLNode*> m_children;

    // text
    string m_text;
};

class XMLHandler
{
public:
    // information about the element that started us
    SourcePos elementPos;
    string elementNamespace;
    string elementName;
    vector<XMLAttribute> elementAttributes;

    XMLHandler();
    virtual ~XMLHandler();

    XMLHandler* parent;

    virtual int OnStartElement(const SourcePos& pos, const string& ns, const string& name,
                                const vector<XMLAttribute>& attrs, XMLHandler** next);
    virtual int OnEndElement(const SourcePos& pos, const string& ns, const string& name);
    virtual int OnText(const SourcePos& pos, const string& text);
    virtual int OnComment(const SourcePos& pos, const string& text);
    virtual int OnDone(const SourcePos& pos);

    static bool ParseFile(const string& filename, XMLHandler* handler);
    static bool ParseString(const string& filename, const string& text, XMLHandler* handler);
};

class TopElementHandler : public XMLHandler
{
public:
    TopElementHandler(const string& ns, const string& name, XMLHandler* next);

    virtual int OnStartElement(const SourcePos& pos, const string& ns, const string& name,
                                const vector<XMLAttribute>& attrs, XMLHandler** next);
    virtual int OnEndElement(const SourcePos& pos, const string& ns, const string& name);
    virtual int OnText(const SourcePos& pos, const string& text);
    virtual int OnDone(const SourcePos& endPos);

private:
    string m_ns;
    string m_name;
    XMLHandler* m_next;
};

class NodeHandler : public XMLHandler
{
public:
    // after it's done, you own everything created and added to root
    NodeHandler(XMLNode* root, int pretty);
    ~NodeHandler();

    virtual int OnStartElement(const SourcePos& pos, const string& ns, const string& name,
                                const vector<XMLAttribute>& attrs, XMLHandler** next);
    virtual int OnEndElement(const SourcePos& pos, const string& ns, const string& name);
    virtual int OnText(const SourcePos& pos, const string& text);
    virtual int OnComment(const SourcePos& pos, const string& text);
    virtual int OnDone(const SourcePos& endPos);

    inline XMLNode* Root() const                { return m_root; }

    static XMLNode* ParseFile(const string& filename, int pretty);
    static XMLNode* ParseString(const string& filename, const string& text, int pretty);

private:
    XMLNode* m_root;
    int m_pretty;
    vector<XMLNode*> m_nodes;
};

template <class T>
static void delete_object(T* obj)
{
    delete obj;
}

#endif // XML_H
