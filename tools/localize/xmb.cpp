#include "xmb.h"

#include "file_utils.h"
#include "localize.h"
#include "ValuesFile.h"
#include "XMLHandler.h"
#include "XLIFFFile.h"

#include <map>
#include <cstdio>

using namespace std;

const char *const NS_MAP[] = {
    "xml", XMLNS_XMLNS,
    NULL, NULL
};

set<string> g_tags;

static string
strip_newlines(const string& str)
{
    string res;
    const size_t N = str.length();
    for (size_t i=0; i<N; i++) {
        char c = str[i];
        if (c != '\n' && c != '\r') {
            res += c;
        } else {
            res += ' ';
        }
    }
    return res;
}

static int
rename_id_attribute(XMLNode* node)
{
    vector<XMLAttribute>& attrs = node->EditAttributes();
    const size_t I = attrs.size();
    for (size_t i=0; i<I; i++) {
        XMLAttribute attr = attrs[i];
        if (attr.name == "id") {
            attr.name = "name";
            attrs.erase(attrs.begin()+i);
            attrs.push_back(attr);
            return 0;
        }
    }
    return 1;
}

static int
convert_xliff_to_ph(XMLNode* node, int* phID)
{
    int err = 0;
    if (node->Type() == XMLNode::ELEMENT) {
        if (node->Namespace() == XLIFF_XMLNS) {
            g_tags.insert(node->Name());
            node->SetName("", "ph");

            err = rename_id_attribute(node);
            if (err != 0) {
                char name[30];
                (*phID)++;
                sprintf(name, "id-%d", *phID);
                node->EditAttributes().push_back(XMLAttribute("", "name", name));
                err = 0;
            }
        }
        vector<XMLNode*>& children = node->EditChildren();
        const size_t I = children.size();
        for (size_t i=0; i<I; i++) {
            err |= convert_xliff_to_ph(children[i], phID);
        }
    }
    return err;
}

XMLNode*
resource_to_xmb_msg(const StringResource& res)
{
    // the msg element
    vector<XMLAttribute> attrs;
    string name = res.pos.file;
    name += ":";
    name += res.TypedID();
    attrs.push_back(XMLAttribute("", "name", name));
    attrs.push_back(XMLAttribute("", "desc", strip_newlines(res.comment)));
    attrs.push_back(XMLAttribute(XMLNS_XMLNS, "space", "preserve"));
    XMLNode* msg = XMLNode::NewElement(res.pos, "", "msg", attrs, XMLNode::EXACT);

    // the contents are in xliff/html, convert it to xliff
    int err = 0;
    XMLNode* value = res.value;
    string tag = value->Name();
    int phID = 0;
    for (vector<XMLNode*>::const_iterator it=value->Children().begin();
            it!=value->Children().end(); it++) {
        err |= convert_html_to_xliff(*it, tag, msg, &phID);
    }

    if (err != 0) {
        return NULL;
    }

    // and then convert that to xmb
    for (vector<XMLNode*>::iterator it=msg->EditChildren().begin();
            it!=msg->EditChildren().end(); it++) {
        err |= convert_xliff_to_ph(*it, &phID);
    }

    if (err == 0) {
        return msg;
    } else {
        return NULL;
    }
}

int
do_xlb_export(const string& outfile, const vector<string>& resFiles)
{
    int err = 0;

    size_t totalFileCount = resFiles.size();

    Configuration english;
        english.locale = "en_US";

    set<StringResource> allResources;

    const size_t J = resFiles.size();
    for (size_t j=0; j<J; j++) {
        string resFile = resFiles[j];

        ValuesFile* valuesFile = get_local_values_file(resFile, english, CURRENT_VERSION, "", true);
        if (valuesFile != NULL) {
            set<StringResource> resources = valuesFile->GetStrings();
            allResources.insert(resources.begin(), resources.end());
        } else {
            fprintf(stderr, "error reading file %s\n", resFile.c_str());
        }

        delete valuesFile;
    }

    // Construct the XLB xml
    vector<XMLAttribute> attrs;
    attrs.push_back(XMLAttribute("", "locale", "en"));
    XMLNode* localizationbundle = XMLNode::NewElement(GENERATED_POS, "", "localizationbundle",
            attrs, XMLNode::PRETTY);

    for (set<StringResource>::iterator it=allResources.begin(); it!=allResources.end(); it++) {
        XMLNode* msg = resource_to_xmb_msg(*it);
        if (msg) {
            localizationbundle->EditChildren().push_back(msg);
        } else {
            err = 1;
        }
    }

#if 0
    for (set<string>::iterator it=g_tags.begin(); it!=g_tags.end(); it++) {
        printf("tag: %s\n", it->c_str());
    }
    printf("err=%d\n", err);
#endif
    if (err == 0) {
        FILE* f = fopen(outfile.c_str(), "wb");
        if (f == NULL) {
            fprintf(stderr, "can't open outputfile: %s\n", outfile.c_str());
            return 1;
        }
        fprintf(f, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        fprintf(f, "%s\n", localizationbundle->ToString(NS_MAP).c_str());
        fclose(f);
    }

    return err;
}

