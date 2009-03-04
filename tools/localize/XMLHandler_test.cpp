#include "XMLHandler.h"
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>

const char *const NS_MAP[] = {
    "xml", XMLNS_XMLNS,
    NULL, NULL
};

const XMLNamespaceMap NO_NAMESPACES(NS_MAP);

char const*const EXPECTED_EXACT = 
       "<ASDF>\n"
        "    <a id=\"system\" old-cl=\"1\" new-cl=\"43019\">\n"
        "        <app dir=\"apps/common\" />\n"
        "    </a>\n"
        "    <a id=\"samples\" old-cl=\"1\" new-cl=\"43019\">asdf\n"
        "        <app dir=\"samples/NotePad\" />\n"
        "        <app dir=\"samples/LunarLander\" />\n"
        "        <something>a<b>,</b>b </something>\n"
        "        <exact xml:space=\"preserve\">a<b>,</b>b </exact>\n"
        "    </a>\n"
        "</ASDF>\n";

char const*const EXPECTED_PRETTY =
        "<ASDF>\n"
        "  <a id=\"system\"\n"
        "      old-cl=\"1\"\n"
        "      new-cl=\"43019\">\n"
        "    <app dir=\"apps/common\" />\n"
        "  </a>\n"
        "  <a id=\"samples\"\n"
        "      old-cl=\"1\"\n"
        "      new-cl=\"43019\">asdf\n"
        "    <app dir=\"samples/NotePad\" />\n"
        "    <app dir=\"samples/LunarLander\" />\n"
        "    <something>a\n"
        "      <b>,\n"
        "      </b>b \n"
        "    </something>\n"
        "    <exact xml:space=\"preserve\">a<b>,</b>b </exact>\n"
        "  </a>\n"
        "</ASDF>\n";

static string
read_file(const string& filename)
{
    char buf[1024];
    int fd = open(filename.c_str(), O_RDONLY);
    if (fd < 0) {
        return "";
    }
    string result;
    while (true) {
        ssize_t len = read(fd, buf, sizeof(buf)-1);
        buf[len] = '\0';
        if (len <= 0) {
            break;
        }
        result.append(buf, len);
    }
    close(fd);
    return result;
}

static int
ParseFile_EXACT_test()
{
    XMLNode* root = NodeHandler::ParseFile("testdata/xml.xml", XMLNode::EXACT);
    if (root == NULL) {
        return 1;
    }
    string result = root->ToString(NO_NAMESPACES);
    delete root;
    //printf("[[%s]]\n", result.c_str());
    return result == EXPECTED_EXACT;
}

static int
ParseFile_PRETTY_test()
{
    XMLNode* root = NodeHandler::ParseFile("testdata/xml.xml", XMLNode::PRETTY);
    if (root == NULL) {
        return 1;
    }
    string result = root->ToString(NO_NAMESPACES);
    delete root;
    //printf("[[%s]]\n", result.c_str());
    return result == EXPECTED_PRETTY;
}

static int
ParseString_EXACT_test()
{
    string text = read_file("testdata/xml.xml");
    XMLNode* root = NodeHandler::ParseString("testdata/xml.xml", text, XMLNode::EXACT);
    if (root == NULL) {
        return 1;
    }
    string result = root->ToString(NO_NAMESPACES);
    delete root;
    //printf("[[%s]]\n", result.c_str());
    return result == EXPECTED_EXACT;
}

static int
ParseString_PRETTY_test()
{
    string text = read_file("testdata/xml.xml");
    XMLNode* root = NodeHandler::ParseString("testdata/xml.xml", text, XMLNode::PRETTY);
    if (root == NULL) {
        return 1;
    }
    string result = root->ToString(NO_NAMESPACES);
    delete root;
    //printf("[[%s]]\n", result.c_str());
    return result == EXPECTED_PRETTY;
}

int
XMLHandler_test()
{
    int err = 0;
    bool all = true;

    if (all) err |= ParseFile_EXACT_test();
    if (all) err |= ParseFile_PRETTY_test();
    if (all) err |= ParseString_EXACT_test();
    if (all) err |= ParseString_PRETTY_test();

    return err;
}
