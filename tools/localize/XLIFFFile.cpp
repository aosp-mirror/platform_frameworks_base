#include "XLIFFFile.h"

#include <algorithm>
#include <sys/time.h>
#include <time.h>
#include <cstdio>

const char* const XLIFF_XMLNS = "urn:oasis:names:tc:xliff:document:1.2";

const char *const NS_MAP[] = {
    "", XLIFF_XMLNS,
    "xml", XMLNS_XMLNS,
    NULL, NULL
};

const XMLNamespaceMap XLIFF_NAMESPACES(NS_MAP);

int
XLIFFFile::File::Compare(const XLIFFFile::File& that) const
{
    if (filename != that.filename) {
        return filename < that.filename ? -1 : 1;
    }
    return 0;
}

// =====================================================================================
XLIFFFile::XLIFFFile()
{
}

XLIFFFile::~XLIFFFile()
{
}

static XMLNode*
get_unique_node(const XMLNode* parent, const string& ns, const string& name, bool required)
{
    size_t count = parent->CountElementsByName(ns, name);
    if (count == 1) {
        return parent->GetElementByNameAt(ns, name, 0);
    } else {
        if (required) {
            SourcePos pos = count == 0
                                ? parent->Position()
                                : parent->GetElementByNameAt(XLIFF_XMLNS, name, 1)->Position();
            pos.Error("<%s> elements must contain exactly one <%s> element",
                                parent->Name().c_str(), name.c_str());
        }
        return NULL;
    }
}

XLIFFFile*
XLIFFFile::Parse(const string& filename)
{
    XLIFFFile* result = new XLIFFFile();

    XMLNode* root = NodeHandler::ParseFile(filename, XMLNode::PRETTY);
    if (root == NULL) {
        return NULL;
    }

    // <file>
    vector<XMLNode*> files = root->GetElementsByName(XLIFF_XMLNS, "file");
    for (size_t i=0; i<files.size(); i++) {
        XMLNode* file = files[i];

        string datatype = file->GetAttribute("", "datatype", "");
        string originalFile = file->GetAttribute("", "original", "");

        Configuration sourceConfig;
        sourceConfig.locale = file->GetAttribute("", "source-language", "");
        result->m_sourceConfig = sourceConfig;

        Configuration targetConfig;
        targetConfig.locale = file->GetAttribute("", "target-language", "");
        result->m_targetConfig = targetConfig;

        result->m_currentVersion = file->GetAttribute("", "build-num", "");
        result->m_oldVersion = "old";

        // <body>
        XMLNode* body = get_unique_node(file, XLIFF_XMLNS, "body", true);
        if (body == NULL) continue;

        // <trans-unit>
        vector<XMLNode*> transUnits = body->GetElementsByName(XLIFF_XMLNS, "trans-unit");
        for (size_t j=0; j<transUnits.size(); j++) {
            XMLNode* transUnit = transUnits[j];

            string rawID = transUnit->GetAttribute("", "id", "");
            if (rawID == "") {
                transUnit->Position().Error("<trans-unit> tag requires an id");
                continue;
            }
            string id;
            int index;

            if (!StringResource::ParseTypedID(rawID, &id, &index)) {
                transUnit->Position().Error("<trans-unit> has invalid id '%s'\n", rawID.c_str());
                continue;
            }

            // <source>
            XMLNode* source = get_unique_node(transUnit, XLIFF_XMLNS, "source", false);
            if (source != NULL) {
                XMLNode* node = source->Clone();
                node->SetPrettyRecursive(XMLNode::EXACT);
                result->AddStringResource(StringResource(source->Position(), originalFile,
                            sourceConfig, id, index, node, CURRENT_VERSION,
                            result->m_currentVersion));
            }

            // <target>
            XMLNode* target = get_unique_node(transUnit, XLIFF_XMLNS, "target", false);
            if (target != NULL) {
                XMLNode* node = target->Clone();
                node->SetPrettyRecursive(XMLNode::EXACT);
                result->AddStringResource(StringResource(target->Position(), originalFile,
                            targetConfig, id, index, node, CURRENT_VERSION,
                            result->m_currentVersion));
            }

            // <alt-trans>
            XMLNode* altTrans = get_unique_node(transUnit, XLIFF_XMLNS, "alt-trans", false);
            if (altTrans != NULL) {
                // <source>
                XMLNode* altSource = get_unique_node(altTrans, XLIFF_XMLNS, "source", false);
                if (altSource != NULL) {
                    XMLNode* node = altSource->Clone();
                    node->SetPrettyRecursive(XMLNode::EXACT);
                    result->AddStringResource(StringResource(altSource->Position(),
                                originalFile, sourceConfig, id, index, node, OLD_VERSION,
                                result->m_oldVersion));
                }

                // <target>
                XMLNode* altTarget = get_unique_node(altTrans, XLIFF_XMLNS, "target", false);
                if (altTarget != NULL) {
                    XMLNode* node = altTarget->Clone();
                    node->SetPrettyRecursive(XMLNode::EXACT);
                    result->AddStringResource(StringResource(altTarget->Position(),
                                originalFile, targetConfig, id, index, node, OLD_VERSION,
                                result->m_oldVersion));
                }
            }
        }
    }
    delete root;
    return result;
}

XLIFFFile*
XLIFFFile::Create(const Configuration& sourceConfig, const Configuration& targetConfig,
                                const string& currentVersion)
{
    XLIFFFile* result = new XLIFFFile();
        result->m_sourceConfig = sourceConfig;
        result->m_targetConfig = targetConfig;
        result->m_currentVersion = currentVersion;
    return result;
}

set<string>
XLIFFFile::Files() const
{
    set<string> result;
    for (vector<File>::const_iterator f = m_files.begin(); f != m_files.end(); f++) {
        result.insert(f->filename);
    }
    return result;
}

void
XLIFFFile::AddStringResource(const StringResource& str)
{
    string id = str.TypedID();

    File* f = NULL;
    const size_t I = m_files.size();
    for (size_t i=0; i<I; i++) {
        if (m_files[i].filename == str.file) {
            f = &m_files[i];
            break;
        }
    }
    if (f == NULL) {
        File file;
        file.filename = str.file;
        m_files.push_back(file);
        f = &m_files[I];
    }

    const size_t J = f->transUnits.size();
    TransUnit* g = NULL;
    for (size_t j=0; j<J; j++) {
        if (f->transUnits[j].id == id) {
            g = &f->transUnits[j];
        }
    }
    if (g == NULL) {
        TransUnit group;
        group.id = id;
        f->transUnits.push_back(group);
        g = &f->transUnits[J];
    }

    StringResource* res = find_string_res(*g, str);
    if (res == NULL) {
        return ;
    }
    if (res->id != "") {
        str.pos.Error("Duplicate string resource: %s", res->id.c_str());
        res->pos.Error("Previous definition here");
        return ;
    }
    *res = str;

    m_strings.insert(str);
}

void
XLIFFFile::Filter(bool (*func)(const string&,const TransUnit&,void*), void* cookie)
{
    const size_t I = m_files.size();
    for (size_t ix=0, i=I-1; ix<I; ix++, i--) {
        File& file = m_files[i];

        const size_t J = file.transUnits.size();
        for (size_t jx=0, j=J-1; jx<J; jx++, j--) {
            TransUnit& tu = file.transUnits[j];

            bool keep = func(file.filename, tu, cookie);
            if (!keep) {
                if (tu.source.id != "") {
                    m_strings.erase(tu.source);
                }
                if (tu.target.id != "") {
                    m_strings.erase(tu.target);
                }
                if (tu.altSource.id != "") {
                    m_strings.erase(tu.altSource);
                }
                if (tu.altTarget.id != "") {
                    m_strings.erase(tu.altTarget);
                }
                file.transUnits.erase(file.transUnits.begin()+j);
            }
        }
        if (file.transUnits.size() == 0) {
            m_files.erase(m_files.begin()+i);
        }
    }
}

void
XLIFFFile::Map(void (*func)(const string&,TransUnit*,void*), void* cookie)
{
    const size_t I = m_files.size();
    for (size_t i=0; i<I; i++) {
        File& file = m_files[i];

        const size_t J = file.transUnits.size();
        for (size_t j=0; j<J; j++) {
            func(file.filename, &(file.transUnits[j]), cookie);
        }
    }
}

TransUnit*
XLIFFFile::EditTransUnit(const string& filename, const string& id)
{
    const size_t I = m_files.size();
    for (size_t ix=0, i=I-1; ix<I; ix++, i--) {
        File& file = m_files[i];
        if (file.filename == filename) {
            const size_t J = file.transUnits.size();
            for (size_t jx=0, j=J-1; jx<J; jx++, j--) {
                TransUnit& tu = file.transUnits[j];
                if (tu.id == id) {
                    return &tu;
                }
            }
        }
    }
    return NULL;
}

StringResource*
XLIFFFile::find_string_res(TransUnit& g, const StringResource& str)
{
    int index;
    if (str.version == CURRENT_VERSION) {
        index = 0;
    }
    else if (str.version == OLD_VERSION) {
        index = 2;
    }
    else {
        str.pos.Error("Internal Error %s:%d\n", __FILE__, __LINE__);
        return NULL;
    }
    if (str.config == m_sourceConfig) {
        // index += 0;
    }
    else if (str.config == m_targetConfig) {
        index += 1;
    }
    else {
        str.pos.Error("unknown config for string %s: %s", str.id.c_str(),
                            str.config.ToString().c_str());
        return NULL;
    }
    switch (index) {
        case 0:
            return &g.source;
        case 1:
            return &g.target;
        case 2:
            return &g.altSource;
        case 3:
            return &g.altTarget;
    }
    str.pos.Error("Internal Error %s:%d\n", __FILE__, __LINE__);
    return NULL;
}

int
convert_html_to_xliff(const XMLNode* original, const string& name, XMLNode* addTo, int* phID)
{
    int err = 0;
    if (original->Type() == XMLNode::TEXT) {
        addTo->EditChildren().push_back(original->Clone());
        return 0;
    } else {
        string ctype;
        if (original->Namespace() == "") {
            if (original->Name() == "b") {
                ctype = "bold";
            }
            else if (original->Name() == "i") {
                ctype = "italic";
            }
            else if (original->Name() == "u") {
                ctype = "underline";
            }
        }
        if (ctype != "") {
            vector<XMLAttribute> attrs;
            attrs.push_back(XMLAttribute(XLIFF_XMLNS, "ctype", ctype));
            XMLNode* copy = XMLNode::NewElement(original->Position(), XLIFF_XMLNS, "g",
                                                attrs, XMLNode::EXACT);

            const vector<XMLNode*>& children = original->Children();
            size_t I = children.size();
            for (size_t i=0; i<I; i++) {
                err |= convert_html_to_xliff(children[i], name, copy, phID);
            }
            return err;
        }
        else {
            if (original->Namespace() == XLIFF_XMLNS) {
                addTo->EditChildren().push_back(original->Clone());
                return 0;
            } else {
                if (original->Namespace() == "") {
                    // flatten out the tag into ph tags -- but only if there is no namespace
                    // that's still unsupported because propagating the xmlns attribute is hard.
                    vector<XMLAttribute> attrs;
                    char idStr[30];
                    (*phID)++;
                    sprintf(idStr, "id-%d", *phID);
                    attrs.push_back(XMLAttribute(XLIFF_XMLNS, "id", idStr));

                    if (original->Children().size() == 0) {
                        XMLNode* ph = XMLNode::NewElement(original->Position(), XLIFF_XMLNS,
                                "ph", attrs, XMLNode::EXACT);
                        ph->EditChildren().push_back(
                                XMLNode::NewText(original->Position(),
                                    original->ToString(XLIFF_NAMESPACES),
                                    XMLNode::EXACT));
                        addTo->EditChildren().push_back(ph);
                    } else {
                        XMLNode* begin = XMLNode::NewElement(original->Position(), XLIFF_XMLNS,
                                "bpt", attrs, XMLNode::EXACT);
                        begin->EditChildren().push_back(
                                XMLNode::NewText(original->Position(),
                                    original->OpenTagToString(XLIFF_NAMESPACES, XMLNode::EXACT),
                                    XMLNode::EXACT));
                        XMLNode* end = XMLNode::NewElement(original->Position(), XLIFF_XMLNS,
                                "ept", attrs, XMLNode::EXACT);
                        string endText = "</";
                            endText += original->Name();
                            endText += ">";
                        end->EditChildren().push_back(XMLNode::NewText(original->Position(),
                                endText, XMLNode::EXACT));

                        addTo->EditChildren().push_back(begin);

                        const vector<XMLNode*>& children = original->Children();
                        size_t I = children.size();
                        for (size_t i=0; i<I; i++) {
                            err |= convert_html_to_xliff(children[i], name, addTo, phID);
                        }

                        addTo->EditChildren().push_back(end);
                    }
                    return err;
                } else {
                    original->Position().Error("invalid <%s> element in <%s> tag\n",
                                                original->Name().c_str(), name.c_str());
                    return 1;
                }
            }
        }
    }
}

XMLNode*
create_string_node(const StringResource& str, const string& name)
{
    vector<XMLAttribute> attrs;
    attrs.push_back(XMLAttribute(XMLNS_XMLNS, "space", "preserve"));
    XMLNode* node = XMLNode::NewElement(str.pos, XLIFF_XMLNS, name, attrs, XMLNode::EXACT);

    const vector<XMLNode*>& children = str.value->Children();
    size_t I = children.size();
    int err = 0;
    for (size_t i=0; i<I; i++) {
        int phID = 0;
        err |= convert_html_to_xliff(children[i], name, node, &phID);
    }

    if (err != 0) {
        delete node;
    }
    return node;
}

static bool
compare_id(const TransUnit& lhs, const TransUnit& rhs)
{
    string lid, rid;
    int lindex, rindex;
    StringResource::ParseTypedID(lhs.id, &lid, &lindex);
    StringResource::ParseTypedID(rhs.id, &rid, &rindex);
    if (lid < rid) return true;
    if (lid == rid && lindex < rindex) return true;
    return false;
}

XMLNode*
XLIFFFile::ToXMLNode() const
{
    XMLNode* root;
    size_t N;

    // <xliff>
    {
        vector<XMLAttribute> attrs;
        XLIFF_NAMESPACES.AddToAttributes(&attrs);
        attrs.push_back(XMLAttribute(XLIFF_XMLNS, "version", "1.2"));
        root = XMLNode::NewElement(GENERATED_POS, XLIFF_XMLNS, "xliff", attrs, XMLNode::PRETTY);
    }

    vector<TransUnit> groups;

    // <file>
    vector<File> files = m_files;
    sort(files.begin(), files.end());
    const size_t I = files.size();
    for (size_t i=0; i<I; i++) {
        const File& file = files[i];

        vector<XMLAttribute> fileAttrs;
        fileAttrs.push_back(XMLAttribute(XLIFF_XMLNS, "datatype", "x-android-res"));
        fileAttrs.push_back(XMLAttribute(XLIFF_XMLNS, "original", file.filename));

        struct timeval tv;
        struct timezone tz;
        gettimeofday(&tv, &tz);
        fileAttrs.push_back(XMLAttribute(XLIFF_XMLNS, "date", trim_string(ctime(&tv.tv_sec))));

        fileAttrs.push_back(XMLAttribute(XLIFF_XMLNS, "source-language", m_sourceConfig.locale));
        fileAttrs.push_back(XMLAttribute(XLIFF_XMLNS, "target-language", m_targetConfig.locale));
        fileAttrs.push_back(XMLAttribute(XLIFF_XMLNS, "build-num", m_currentVersion));

        XMLNode* fileNode = XMLNode::NewElement(GENERATED_POS, XLIFF_XMLNS, "file", fileAttrs,
                                                XMLNode::PRETTY);
        root->EditChildren().push_back(fileNode);

        // <body>
        XMLNode* bodyNode = XMLNode::NewElement(GENERATED_POS, XLIFF_XMLNS, "body",
                                                vector<XMLAttribute>(), XMLNode::PRETTY);
        fileNode->EditChildren().push_back(bodyNode);

        // <trans-unit>
        vector<TransUnit> transUnits = file.transUnits;
        sort(transUnits.begin(), transUnits.end(), compare_id);
        const size_t J = transUnits.size();
        for (size_t j=0; j<J; j++) {
            const TransUnit& transUnit = transUnits[j];

            vector<XMLAttribute> tuAttrs;

            // strings start with string:
            tuAttrs.push_back(XMLAttribute(XLIFF_XMLNS, "id", transUnit.id));
            XMLNode* transUnitNode = XMLNode::NewElement(GENERATED_POS, XLIFF_XMLNS, "trans-unit",
                                                         tuAttrs, XMLNode::PRETTY);
            bodyNode->EditChildren().push_back(transUnitNode);

            // <extradata>
            if (transUnit.source.comment != "") {
                vector<XMLAttribute> extradataAttrs;
                XMLNode* extraNode = XMLNode::NewElement(GENERATED_POS, XLIFF_XMLNS, "extradata",
                                                            extradataAttrs, XMLNode::EXACT);
                transUnitNode->EditChildren().push_back(extraNode);
                extraNode->EditChildren().push_back(
                        XMLNode::NewText(GENERATED_POS, transUnit.source.comment,
                                         XMLNode::PRETTY));
            }

            // <source>
            if (transUnit.source.id != "") {
                transUnitNode->EditChildren().push_back(
                                    create_string_node(transUnit.source, "source"));
            }
            
            // <target>
            if (transUnit.target.id != "") {
                transUnitNode->EditChildren().push_back(
                                    create_string_node(transUnit.target, "target"));
            }

            // <alt-trans>
            if (transUnit.altSource.id != "" || transUnit.altTarget.id != ""
                    || transUnit.rejectComment != "") {
                vector<XMLAttribute> altTransAttrs;
                XMLNode* altTransNode = XMLNode::NewElement(GENERATED_POS, XLIFF_XMLNS, "alt-trans",
                                                            altTransAttrs, XMLNode::PRETTY);
                transUnitNode->EditChildren().push_back(altTransNode);

                // <extradata>
                if (transUnit.rejectComment != "") {
                    vector<XMLAttribute> extradataAttrs;
                    XMLNode* extraNode = XMLNode::NewElement(GENERATED_POS, XLIFF_XMLNS,
                                                                "extradata", extradataAttrs,
                                                                XMLNode::EXACT);
                    altTransNode->EditChildren().push_back(extraNode);
                    extraNode->EditChildren().push_back(
                            XMLNode::NewText(GENERATED_POS, transUnit.rejectComment,
                                             XMLNode::PRETTY));
                }
                
                // <source>
                if (transUnit.altSource.id != "") {
                    altTransNode->EditChildren().push_back(
                                        create_string_node(transUnit.altSource, "source"));
                }
                
                // <target>
                if (transUnit.altTarget.id != "") {
                    altTransNode->EditChildren().push_back(
                                        create_string_node(transUnit.altTarget, "target"));
                }
            }
            
        }
    }

    return root;
}


string
XLIFFFile::ToString() const
{
    XMLNode* xml = ToXMLNode();
    string s = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
    s += xml->ToString(XLIFF_NAMESPACES);
    delete xml;
    s += '\n';
    return s;
}

Stats
XLIFFFile::GetStats(const string& config) const
{
    Stats stat;
    stat.config = config;
    stat.files = m_files.size();
    stat.toBeTranslated = 0;
    stat.noComments = 0;

    for (vector<File>::const_iterator file=m_files.begin(); file!=m_files.end(); file++) {
        stat.toBeTranslated += file->transUnits.size();

        for (vector<TransUnit>::const_iterator tu=file->transUnits.begin();
                    tu!=file->transUnits.end(); tu++) {
            if (tu->source.comment == "") {
                stat.noComments++;
            }
        }
    }

    stat.totalStrings = stat.toBeTranslated;

    return stat;
}
