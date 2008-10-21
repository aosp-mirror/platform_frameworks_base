#include "ValuesFile.h"
#include <stdio.h>

int
ValuesFile_test()
{
    int err = 0;
    Configuration config;
    config.locale = "zz_ZZ";
    ValuesFile* vf = ValuesFile::ParseFile("testdata/values/strings.xml", config,
                                        OLD_VERSION, "1");

    const set<StringResource>& strings = vf->GetStrings();
    string canonical = vf->ToString();

    if (false) {
        printf("Strings (%zd)\n", strings.size());
            for (set<StringResource>::const_iterator it=strings.begin();
                    it!=strings.end(); it++) {
            const StringResource& str = *it;
            printf("%s: '%s'[%d]='%s' (%s) <!-- %s -->\n", str.pos.ToString().c_str(),
                    str.id.c_str(), str.index,
                    str.value->ContentsToString(ANDROID_NAMESPACES).c_str(),
                    str.config.ToString().c_str(), str.comment.c_str());
        }

        printf("XML:[[%s]]\n", canonical.c_str());
    }

    const char * const EXPECTED =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        "<resources xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
        "    xmlns:xliff=\"urn:oasis:names:tc:xliff:document:1.2\">\n"
        "  <array name=\"emailAddressTypes\">\n"
        "    <item>Email</item>\n"
        "    <item>Home</item>\n"
        "    <item>Work</item>\n"
        "    <item>Other\\u2026</item>\n"
        "  </array>\n"
        "  <string name=\"test1\">Discard</string>\n"
        "  <string name=\"test2\">a<b>b<i>c</i></b>d</string>\n"
        "  <string name=\"test3\">a<xliff:g a=\"b\" xliff:a=\"asdf\">bBb</xliff:g>C</string>\n"
        "</resources>\n";

    if (canonical != EXPECTED) {
        fprintf(stderr, "ValuesFile_test failed\n");
        fprintf(stderr, "canonical=[[%s]]\n", canonical.c_str());
        fprintf(stderr, "EXPECTED=[[%s]]\n", EXPECTED);
        err = 1;
    }

    delete vf;
    return err;
}
