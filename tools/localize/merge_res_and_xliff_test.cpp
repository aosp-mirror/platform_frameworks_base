#include <cstdio>
#include "merge_res_and_xliff.h"
#include <stdio.h>

int
merge_test()
{
    Configuration english;
        english.locale = "en_US";
    Configuration translated;
        translated.locale = "zz_ZZ";

    ValuesFile* en_current = ValuesFile::ParseFile("testdata/merge_en_current.xml", english,
                                                    CURRENT_VERSION, "3");
    if (en_current == NULL) {
        fprintf(stderr, "merge_test: unable to read testdata/merge_en_current.xml\n");
        return 1;
    }

    ValuesFile* xx_current = ValuesFile::ParseFile("testdata/merge_xx_current.xml", translated,
                                                    CURRENT_VERSION, "3");
    if (xx_current == NULL) {
        fprintf(stderr, "merge_test: unable to read testdata/merge_xx_current.xml\n");
        return 1;
    }
    ValuesFile* xx_old = ValuesFile::ParseFile("testdata/merge_xx_old.xml", translated,
                                                    OLD_VERSION, "2");
    if (xx_old == NULL) {
        fprintf(stderr, "merge_test: unable to read testdata/merge_xx_old.xml\n");
        return 1;
    }

    XLIFFFile* xliff = XLIFFFile::Parse("testdata/merge.xliff");

    ValuesFile* result = merge_res_and_xliff(en_current, xx_current, xx_old,
                                "//device/tools/localize/testdata/res/values/strings.xml", xliff);

    if (result == NULL) {
        fprintf(stderr, "merge_test: result is NULL\n");
        return 1;
    }

    printf("======= RESULT =======\n%s===============\n", result->ToString().c_str());

    return 0;
}


