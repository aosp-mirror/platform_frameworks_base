#include "XMLHandler.h"

#include <algorithm>
#include <expat.h>
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>

#define NS_SEPARATOR 1
#define MORE_INDENT "  "

static string
xml_text_escape(const string& s)
{
    string result;
    const size_t N = s.length();
    for (size_t i=0; i<N; i++) {
        char c = s[i];
        switch (c) {
            case '<':
                result += "&lt;";
                break;
            case '>':
                result += "&gt;";
                break;
            case '&':
                result += "&amp;";
                break;
            default:
                result += c;
                break;
        }
    }
    return result;
}

static string
xml_attr_escape(const string& s)
{
    string result;
    const size_t N = s.length();
    for (size_t i=0; i<N; i++) {
        char c = s[i];
        switch (c) {
            case '\"':
                result += "&quot;";
                break;
            default:
                result += c;
                break;
        }
    }
    return result;
}

XMLNamespaceMap::XMLNamespaceMap()
{
}

XMLNamespaceMap::XMLNamespaceMap(char const*const* nspaces)

{
    while (*nspaces) {
        m_map[nspaces[1]] = nspaces[0];
        nspaces += 2;
    }
}

string
XMLNamespaceMap::Get(const string& ns) const
{
    if (ns == "xml") {
        return ns;
    }
    map<string,string>::const_iterator it = m_map.find(ns);
    if (it == m_map.end()) {
        return "";
    } else {
        return it->second;
    }
}

string
XMLNamespaceMap::GetPrefix(const string& ns) const
{
    if (ns == "") {
        return "";
    }
    map<string,string>::const_iterator it = m_map.find(ns);
    if (it != m_map.end()) {
        if (it->second == "") {
            return "";
        } else {
            return it->second + ":";
        }
    } else {
        return ":"; // invalid
    }
}

void
XMLNamespaceMap::AddToAttributes(vector<XMLAttribute>* attrs) const
{
    map<string,string>::const_iterator it;
    for (it=m_map.begin(); it!=m_map.end(); it++) {
        if (it->second == "xml") {
            continue;
        }
        XMLAttribute attr;
        if (it->second == "") {
            attr.name = "xmlns";
        } else {
            attr.name = "xmlns:";
            attr.name += it->second;
        }
        attr.value = it->first;
        attrs->push_back(attr);
    }
}

XMLAttribute::XMLAttribute()
{
}

XMLAttribute::XMLAttribute(const XMLAttribute& that)
    :ns(that.ns),
     name(that.name),
     value(that.value)
{
}

XMLAttribute::XMLAttribute(string n, string na, string v)
    :ns(n),
     name(na),
     value(v)
{
}

XMLAttribute::~XMLAttribute()
{
}

int
XMLAttribute::Compare(const XMLAttribute& that) const
{
    if (ns != that.ns) {
        return ns < that.ns ? -1 : 1;
    }
    if (name != that.name) {
        return name < that.name ? -1 : 1;
    }
    return 0;
}

string
XMLAttribute::Find(const vector<XMLAttribute>& list, const string& ns, const string& name,
                    const string& def)
{
    const size_t N = list.size();
    for (size_t i=0; i<N; i++) {
        const XMLAttribute& attr = list[i];
        if (attr.ns == ns && attr.name == name) {
            return attr.value;
        }
    }
    return def;
}

struct xml_handler_data {
    vector<XMLHandler*> stack;
    XML_Parser parser;
    vector<vector<XMLAttribute>*> attributes;
    string filename;
};

XMLNode::XMLNode()
{
}

XMLNode::~XMLNode()
{
//    for_each(m_children.begin(), m_children.end(), delete_object<XMLNode>);
}

XMLNode*
XMLNode::Clone() const
{
    switch (m_type) {
        case ELEMENT: {
            XMLNode* e = XMLNode::NewElement(m_pos, m_ns, m_name, m_attrs, m_pretty);
            const size_t N = m_children.size();
            for (size_t i=0; i<N; i++) {
                e->m_children.push_back(m_children[i]->Clone());
            }
            return e;
        }
        case TEXT: {
            return XMLNode::NewText(m_pos, m_text, m_pretty);
        }
        default:
            return NULL;
    }
}

XMLNode*
XMLNode::NewElement(const SourcePos& pos, const string& ns, const string& name,
                        const vector<XMLAttribute>& attrs, int pretty)
{
    XMLNode* node = new XMLNode();
        node->m_type = ELEMENT;
        node->m_pretty = pretty;
        node->m_pos = pos;
        node->m_ns = ns;
        node->m_name = name;
        node->m_attrs = attrs;
    return node;
}

XMLNode*
XMLNode::NewText(const SourcePos& pos, const string& text, int pretty)
{
    XMLNode* node = new XMLNode();
        node->m_type = TEXT;
        node->m_pretty = pretty;
        node->m_pos = pos;
        node->m_text = text;
    return node;
}

void
XMLNode::SetPrettyRecursive(int value)
{
    m_pretty = value;
    const size_t N = m_children.size();
    for (size_t i=0; i<N; i++) {
        m_children[i]->SetPrettyRecursive(value);
    }
}

string
XMLNode::ContentsToString(const XMLNamespaceMap& nspaces) const
{
    return contents_to_string(nspaces, "");
}

string
XMLNode::ToString(const XMLNamespaceMap& nspaces) const
{
    return to_string(nspaces, "");
}

string
XMLNode::OpenTagToString(const XMLNamespaceMap& nspaces, int pretty) const
{
    return open_tag_to_string(nspaces, "", pretty);
}

string
XMLNode::contents_to_string(const XMLNamespaceMap& nspaces, const string& indent) const
{
    string result;
    const size_t N = m_children.size();
    for (size_t i=0; i<N; i++) {
        const XMLNode* child = m_children[i];
        switch (child->Type()) {
        case ELEMENT:
            if (m_pretty == PRETTY) {
                result += '\n';
                result += indent;
            }
        case TEXT:
            result += child->to_string(nspaces, indent);
            break;
        }
    }
    return result;
}

string
trim_string(const string& str)
{
    const char* p = str.c_str();
    while (*p && isspace(*p)) {
        p++;
    }
    const char* q = str.c_str() + str.length() - 1;
    while (q > p && isspace(*q)) {
        q--;
    }
    q++;
    return string(p, q-p);
}

string
XMLNode::open_tag_to_string(const XMLNamespaceMap& nspaces, const string& indent, int pretty) const
{
    if (m_type != ELEMENT) {
        return "";
    }
    string result = "<";
    result += nspaces.GetPrefix(m_ns);
    result += m_name;

    vector<XMLAttribute> attrs = m_attrs;

    sort(attrs.begin(), attrs.end());

    const size_t N = attrs.size();
    for (size_t i=0; i<N; i++) {
        const XMLAttribute& attr = attrs[i];
        if (i == 0 || m_pretty == EXACT || pretty == EXACT) {
            result += ' ';
        }
        else {
            result += "\n";
            result += indent;
            result += MORE_INDENT;
            result += MORE_INDENT;
        }
        result += nspaces.GetPrefix(attr.ns);
        result += attr.name;
        result += "=\"";
        result += xml_attr_escape(attr.value);
        result += '\"';
    }

    if (m_children.size() > 0) {
        result += '>';
    } else {
        result += " />";
    }
    return result;
}

string
XMLNode::to_string(const XMLNamespaceMap& nspaces, const string& indent) const
{
    switch (m_type)
    {
        case TEXT: {
            if (m_pretty == EXACT) {
                return xml_text_escape(m_text);
            } else {
                return xml_text_escape(trim_string(m_text));
            }
        }
        case ELEMENT: {
            string result = open_tag_to_string(nspaces, indent, PRETTY);
            
            if (m_children.size() > 0) {
                result += contents_to_string(nspaces, indent + MORE_INDENT);

                if (m_pretty == PRETTY && m_children.size() > 0) {
                    result += '\n';
                    result += indent;
                }

                result += "</";
                result += nspaces.GetPrefix(m_ns);
                result += m_name;
                result += '>';
            }
            return result;
        }
        default:
            return "";
    }
}

string
XMLNode::CollapseTextContents() const
{
    if (m_type == TEXT) {
        return m_text;
    }
    else if (m_type == ELEMENT) {
        string result;

        const size_t N=m_children.size();
        for (size_t i=0; i<N; i++) {
            result += m_children[i]->CollapseTextContents();
        }

        return result;
    }
    else {
        return "";
    }
}

vector<XMLNode*>
XMLNode::GetElementsByName(const string& ns, const string& name) const
{
    vector<XMLNode*> result;
    const size_t N=m_children.size();
    for (size_t i=0; i<N; i++) {
        XMLNode* child = m_children[i];
        if (child->m_type == ELEMENT && child->m_ns == ns && child->m_name == name) {
            result.push_back(child);
        }
    }
    return result;
}

XMLNode*
XMLNode::GetElementByNameAt(const string& ns, const string& name, size_t index) const
{
    vector<XMLNode*> result;
    const size_t N=m_children.size();
    for (size_t i=0; i<N; i++) {
        XMLNode* child = m_children[i];
        if (child->m_type == ELEMENT && child->m_ns == ns && child->m_name == name) {
            if (index == 0) {
                return child;
            } else {
                index--;
            }
        }
    }
    return NULL;
}

size_t
XMLNode::CountElementsByName(const string& ns, const string& name) const
{
    size_t result = 0;
    const size_t N=m_children.size();
    for (size_t i=0; i<N; i++) {
        XMLNode* child = m_children[i];
        if (child->m_type == ELEMENT && child->m_ns == ns && child->m_name == name) {
            result++;
        }
    }
    return result;
}

string
XMLNode::GetAttribute(const string& ns, const string& name, const string& def) const
{
    return XMLAttribute::Find(m_attrs, ns, name, def);
}

static void
parse_namespace(const char* data, string* ns, string* name)
{
    const char* p = strchr(data, NS_SEPARATOR);
    if (p != NULL) {
        ns->assign(data, p-data);
        name->assign(p+1);
    } else {
        ns->assign("");
        name->assign(data);
    }
}

static void
convert_attrs(const char** in, vector<XMLAttribute>* out)
{
    while (*in) {
        XMLAttribute attr;
        parse_namespace(in[0], &attr.ns, &attr.name);
        attr.value = in[1];
        out->push_back(attr);
        in += 2;
    }
}

static bool
list_contains(const vector<XMLHandler*>& stack, XMLHandler* handler)
{
    const size_t N = stack.size();
    for (size_t i=0; i<N; i++) {
        if (stack[i] == handler) {
            return true;
        }
    }
    return false;
}

static void XMLCALL
start_element_handler(void *userData, const char *name, const char **attrs)
{
    xml_handler_data* data = (xml_handler_data*)userData;

    XMLHandler* handler = data->stack[data->stack.size()-1];

    SourcePos pos(data->filename, (int)XML_GetCurrentLineNumber(data->parser));
    string nsString;
    string nameString;
    XMLHandler* next = handler;
    vector<XMLAttribute> attributes;

    parse_namespace(name, &nsString, &nameString);
    convert_attrs(attrs, &attributes);

    handler->OnStartElement(pos, nsString, nameString, attributes, &next);

    if (next == NULL) {
        next = handler;
    }

    if (next != handler) {
        next->elementPos = pos;
        next->elementNamespace = nsString;
        next->elementName = nameString;
        next->elementAttributes = attributes;
    }

    data->stack.push_back(next);
}

static void XMLCALL
end_element_handler(void *userData, const char *name)
{
    xml_handler_data* data = (xml_handler_data*)userData;

    XMLHandler* handler = data->stack[data->stack.size()-1];
    data->stack.pop_back();

    SourcePos pos(data->filename, (int)XML_GetCurrentLineNumber(data->parser));

    if (!list_contains(data->stack, handler)) {
        handler->OnDone(pos);
        if (data->stack.size() > 1) {
            // not top one
            delete handler;
        }
    }

    handler = data->stack[data->stack.size()-1];

    string nsString;
    string nameString;

    parse_namespace(name, &nsString, &nameString);

    handler->OnEndElement(pos, nsString, nameString);
}

static void XMLCALL
text_handler(void *userData, const XML_Char *s, int len)
{
    xml_handler_data* data = (xml_handler_data*)userData;
    XMLHandler* handler = data->stack[data->stack.size()-1];
    SourcePos pos(data->filename, (int)XML_GetCurrentLineNumber(data->parser));
    handler->OnText(pos, string(s, len));
}

static void XMLCALL
comment_handler(void *userData, const char *comment)
{
    xml_handler_data* data = (xml_handler_data*)userData;
    XMLHandler* handler = data->stack[data->stack.size()-1];
    SourcePos pos(data->filename, (int)XML_GetCurrentLineNumber(data->parser));
    handler->OnComment(pos, string(comment));
}

bool
XMLHandler::ParseFile(const string& filename, XMLHandler* handler)
{
    char buf[16384];
    int fd = open(filename.c_str(), O_RDONLY);
    if (fd < 0) {
        SourcePos(filename, -1).Error("Unable to open file for read: %s", strerror(errno));
        return false;
    }

    XML_Parser parser = XML_ParserCreateNS(NULL, NS_SEPARATOR);
    xml_handler_data state;
    state.stack.push_back(handler);
    state.parser = parser;
    state.filename = filename;

    XML_SetUserData(parser, &state);
    XML_SetElementHandler(parser, start_element_handler, end_element_handler);
    XML_SetCharacterDataHandler(parser, text_handler);
    XML_SetCommentHandler(parser, comment_handler);

    ssize_t len;
    bool done;
    do {
        len = read(fd, buf, sizeof(buf));
        done = len < (ssize_t)sizeof(buf);
        if (len < 0) {
            SourcePos(filename, -1).Error("Error reading file: %s\n", strerror(errno));
            close(fd);
            return false;
        }
        if (XML_Parse(parser, buf, len, done) == XML_STATUS_ERROR) {
            SourcePos(filename, (int)XML_GetCurrentLineNumber(parser)).Error(
                    "Error parsing XML: %s\n", XML_ErrorString(XML_GetErrorCode(parser)));
            close(fd);
            return false;
        }
    } while (!done);

    XML_ParserFree(parser);

    close(fd);
    
    return true;
}

bool
XMLHandler::ParseString(const string& filename, const string& text, XMLHandler* handler)
{
    XML_Parser parser = XML_ParserCreateNS(NULL, NS_SEPARATOR);
    xml_handler_data state;
    state.stack.push_back(handler);
    state.parser = parser;
    state.filename = filename;

    XML_SetUserData(parser, &state);
    XML_SetElementHandler(parser, start_element_handler, end_element_handler);
    XML_SetCharacterDataHandler(parser, text_handler);
    XML_SetCommentHandler(parser, comment_handler);

    if (XML_Parse(parser, text.c_str(), text.size(), true) == XML_STATUS_ERROR) {
        SourcePos(filename, (int)XML_GetCurrentLineNumber(parser)).Error(
                "Error parsing XML: %s\n", XML_ErrorString(XML_GetErrorCode(parser)));
        return false;
    }

    XML_ParserFree(parser);
    
    return true;
}

XMLHandler::XMLHandler()
{
}

XMLHandler::~XMLHandler()
{
}

int
XMLHandler::OnStartElement(const SourcePos& pos, const string& ns, const string& name,
                            const vector<XMLAttribute>& attrs, XMLHandler** next)
{
    return 0;
}

int
XMLHandler::OnEndElement(const SourcePos& pos, const string& ns, const string& name)
{
    return 0;
}

int
XMLHandler::OnText(const SourcePos& pos, const string& text)
{
    return 0;
}

int
XMLHandler::OnComment(const SourcePos& pos, const string& text)
{
    return 0;
}

int
XMLHandler::OnDone(const SourcePos& pos)
{
    return 0;
}

TopElementHandler::TopElementHandler(const string& ns, const string& name, XMLHandler* next)
    :m_ns(ns),
     m_name(name),
     m_next(next)
{
}

int
TopElementHandler::OnStartElement(const SourcePos& pos, const string& ns, const string& name,
                            const vector<XMLAttribute>& attrs, XMLHandler** next)
{
    *next = m_next;
    return 0;
}

int
TopElementHandler::OnEndElement(const SourcePos& pos, const string& ns, const string& name)
{
    return 0;
}

int
TopElementHandler::OnText(const SourcePos& pos, const string& text)
{
    return 0;
}

int
TopElementHandler::OnDone(const SourcePos& pos)
{
    return 0;
}


NodeHandler::NodeHandler(XMLNode* root, int pretty)
    :m_root(root),
     m_pretty(pretty)
{
    if (root != NULL) {
        m_nodes.push_back(root);
    }
}

NodeHandler::~NodeHandler()
{
}

int
NodeHandler::OnStartElement(const SourcePos& pos, const string& ns, const string& name,
                            const vector<XMLAttribute>& attrs, XMLHandler** next)
{
    int pretty;
    if (XMLAttribute::Find(attrs, XMLNS_XMLNS, "space", "") == "preserve") {
        pretty = XMLNode::EXACT;
    } else {
        if (m_root == NULL) {
            pretty = m_pretty;
        } else {
            pretty = m_nodes[m_nodes.size()-1]->Pretty();
        }
    }
    XMLNode* n = XMLNode::NewElement(pos, ns, name, attrs, pretty);
    if (m_root == NULL) {
        m_root = n;
    } else {
        m_nodes[m_nodes.size()-1]->EditChildren().push_back(n);
    }
    m_nodes.push_back(n);
    return 0;
}

int
NodeHandler::OnEndElement(const SourcePos& pos, const string& ns, const string& name)
{
    m_nodes.pop_back();
    return 0;
}

int
NodeHandler::OnText(const SourcePos& pos, const string& text)
{
    if (m_root == NULL) {
        return 1;
    }
    XMLNode* n = XMLNode::NewText(pos, text, m_nodes[m_nodes.size()-1]->Pretty());
    m_nodes[m_nodes.size()-1]->EditChildren().push_back(n);
    return 0;
}

int
NodeHandler::OnComment(const SourcePos& pos, const string& text)
{
    return 0;
}

int
NodeHandler::OnDone(const SourcePos& pos)
{
    return 0;
}

XMLNode*
NodeHandler::ParseFile(const string& filename, int pretty)
{
    NodeHandler handler(NULL, pretty);
    if (!XMLHandler::ParseFile(filename, &handler)) {
        fprintf(stderr, "error parsing file: %s\n", filename.c_str());
        return NULL;
    }
    return handler.Root();
}

XMLNode*
NodeHandler::ParseString(const string& filename, const string& text, int pretty)
{
    NodeHandler handler(NULL, pretty);
    if (!XMLHandler::ParseString(filename, text, &handler)) {
        fprintf(stderr, "error parsing file: %s\n", filename.c_str());
        return NULL;
    }
    return handler.Root();
}


