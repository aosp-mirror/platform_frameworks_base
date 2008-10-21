#include "XLIFFFile.h"
#include <stdio.h>
#include "ValuesFile.h"

XMLNode* create_string_node(const StringResource& str, const string& name);

static int
Parse_test()
{
    XLIFFFile* xf = XLIFFFile::Parse("testdata/xliff1.xliff");
    if (xf == NULL) {
        return 1;
    }

    set<StringResource> const& strings = xf->GetStringResources();

    if (false) {
        for (set<StringResource>::iterator it=strings.begin(); it!=strings.end(); it++) {
            const StringResource& str = *it;
            printf("STRING!!! id=%s index=%d value='%s' pos=%s file=%s version=%d(%s)\n",
                    str.id.c_str(), str.index,
                    str.value->ContentsToString(ANDROID_NAMESPACES).c_str(),
                    str.pos.ToString().c_str(), str.file.c_str(), str.version,
                    str.versionString.c_str());
        }
        printf("XML:[[%s]]\n", xf->ToString().c_str());
    }

    delete xf;
    return 0;
}

static XMLNode*
add_html_tag(XMLNode* addTo, const string& tag)
{
    vector<XMLAttribute> attrs;
    XMLNode* node = XMLNode::NewElement(GENERATED_POS, "", tag, attrs, XMLNode::EXACT);
    addTo->EditChildren().push_back(node);
    return node;
}

static int
create_string_node_test()
{
    int err = 0;
    StringResource res;
    vector<XMLAttribute> attrs;
    res.value = XMLNode::NewElement(GENERATED_POS, "", "something", attrs, XMLNode::EXACT);
    res.value->EditChildren().push_back(XMLNode::NewText(GENERATED_POS, " begin ", XMLNode::EXACT));

    XMLNode* child;

    child = add_html_tag(res.value, "b");
    child->EditChildren().push_back(XMLNode::NewText(GENERATED_POS, "b", XMLNode::EXACT));

    child = add_html_tag(res.value, "i");
    child->EditChildren().push_back(XMLNode::NewText(GENERATED_POS, "i", XMLNode::EXACT));

    child = add_html_tag(child, "b");
    child->EditChildren().push_back(XMLNode::NewText(GENERATED_POS, "b", XMLNode::EXACT));

    child = add_html_tag(res.value, "u");
    child->EditChildren().push_back(XMLNode::NewText(GENERATED_POS, "u", XMLNode::EXACT));


    res.value->EditChildren().push_back(XMLNode::NewText(GENERATED_POS, " end ", XMLNode::EXACT));

    XMLNode* xliff = create_string_node(res, "blah");

    string oldString = res.value->ToString(XLIFF_NAMESPACES);
    string newString = xliff->ToString(XLIFF_NAMESPACES);

    if (false) {
        printf("OLD=\"%s\"\n", oldString.c_str());
        printf("NEW=\"%s\"\n", newString.c_str());
    }

    const char* const EXPECTED_OLD
                    = "<something> begin <b>b</b><i>i<b>b</b></i><u>u</u> end </something>";
    if (oldString != EXPECTED_OLD) {
        fprintf(stderr, "oldString mismatch:\n");
        fprintf(stderr, "    expected='%s'\n", EXPECTED_OLD);
        fprintf(stderr, "      actual='%s'\n", oldString.c_str());
        err |= 1;
    }

    const char* const EXPECTED_NEW
                    = "<blah xml:space=\"preserve\"> begin <g ctype=\"bold\">b</g>"
                    "<g ctype=\"italic\">i<g ctype=\"bold\">b</g></g><g ctype=\"underline\">u</g>"
                    " end </blah>";
    if (newString != EXPECTED_NEW) {
        fprintf(stderr, "newString mismatch:\n");
        fprintf(stderr, "    expected='%s'\n", EXPECTED_NEW);
        fprintf(stderr, "      actual='%s'\n", newString.c_str());
        err |= 1;
    }

    if (err != 0) {
        fprintf(stderr, "create_string_node_test failed\n");
    }
    return err;
}

int
XLIFFFile_test()
{
    bool all = true;
    int err = 0;

    if (all) err |= Parse_test();
    if (all) err |= create_string_node_test();

    return err;
}

