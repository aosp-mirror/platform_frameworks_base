#include <cstdio>
#include "XLIFFFile.h"
#include "ValuesFile.h"
#include "localize.h"
#include <stdio.h>

int pseudolocalize_xliff(XLIFFFile* xliff, bool expand);

static int
test_filename(const string& file, const string& locale, const string& expected)
{
    string result = translated_file_name(file, locale);
    if (result != expected) {
        fprintf(stderr, "translated_file_name test failed\n");
        fprintf(stderr, "  locale='%s'\n", locale.c_str());
        fprintf(stderr, "  expected='%s'\n", expected.c_str());
        fprintf(stderr, "    result='%s'\n", result.c_str());
        return 1;
    } else {
        if (false) {
            fprintf(stderr, "translated_file_name test passed\n");
            fprintf(stderr, "  locale='%s'\n", locale.c_str());
            fprintf(stderr, "  expected='%s'\n", expected.c_str());
            fprintf(stderr, "    result='%s'\n", result.c_str());
        }
        return 0;
    }
}

static int
translated_file_name_test()
{
    bool all = true;
    int err = 0;

    if (all) err |= test_filename("//device/samples/NotePad/res/values/strings.xml", "zz_ZZ",
                                  "//device/samples/NotePad/res/values-zz-rZZ/strings.xml");

    if (all) err |= test_filename("//device/samples/NotePad/res/values/strings.xml", "zz",
                                  "//device/samples/NotePad/res/values-zz/strings.xml");

    if (all) err |= test_filename("//device/samples/NotePad/res/values/strings.xml", "",
                                  "//device/samples/NotePad/res/values/strings.xml");

    return err;
}

bool
return_false(const string&, const TransUnit& unit, void* cookie)
{
    return false;
}

static int
delete_trans_units()
{
    XLIFFFile* xliff = XLIFFFile::Parse("testdata/strip_xliff.xliff");
    if (xliff == NULL) {
        printf("couldn't read file\n");
        return 1;
    }
    if (false) {
        printf("XLIFF was [[%s]]\n", xliff->ToString().c_str());
    }

    xliff->Filter(return_false, NULL);

    if (false) {
        printf("XLIFF is [[%s]]\n", xliff->ToString().c_str());

        set<StringResource> const& strings = xliff->GetStringResources();
        printf("strings.size=%zd\n", strings.size());
        for (set<StringResource>::iterator it=strings.begin(); it!=strings.end(); it++) {
            const StringResource& str = *it;
            printf("STRING!!! id=%s value='%s' pos=%s file=%s version=%d(%s)\n", str.id.c_str(),
                    str.value->ContentsToString(ANDROID_NAMESPACES).c_str(),
                    str.pos.ToString().c_str(), str.file.c_str(), str.version,
                    str.versionString.c_str());
        }
    }
 
    return 0;
}

static int
filter_trans_units()
{
    XLIFFFile* xliff = XLIFFFile::Parse("testdata/strip_xliff.xliff");
    if (xliff == NULL) {
        printf("couldn't read file\n");
        return 1;
    }

    if (false) {
        printf("XLIFF was [[%s]]\n", xliff->ToString().c_str());
    }

    Settings setting;
    xliff->Filter(keep_this_trans_unit, &setting);

    if (false) {
        printf("XLIFF is [[%s]]\n", xliff->ToString().c_str());

        set<StringResource> const& strings = xliff->GetStringResources();
        printf("strings.size=%zd\n", strings.size());
        for (set<StringResource>::iterator it=strings.begin(); it!=strings.end(); it++) {
            const StringResource& str = *it;
            printf("STRING!!! id=%s value='%s' pos=%s file=%s version=%d(%s)\n", str.id.c_str(),
                    str.value->ContentsToString(ANDROID_NAMESPACES).c_str(),
                    str.pos.ToString().c_str(), str.file.c_str(), str.version,
                    str.versionString.c_str());
        }
    }
 
    return 0;
}

static int
settings_test()
{
    int err;
    map<string,Settings> settings;
    map<string,Settings>::iterator it;

    err = read_settings("testdata/config.xml", &settings, "//asdf");
    if (err != 0) {
        return err;
    }

    if (false) {
        for (it=settings.begin(); it!=settings.end(); it++) {
            const Settings& setting = it->second;
            printf("CONFIG:\n");
            printf("              id='%s'\n", setting.id.c_str());
            printf("      oldVersion='%s'\n", setting.oldVersion.c_str());
            printf("  currentVersion='%s'\n", setting.currentVersion.c_str());
            int i=0;
            for (vector<string>::const_iterator app=setting.apps.begin();
                    app!=setting.apps.end(); app++) {
                printf("        apps[%02d]='%s'\n", i, app->c_str());
                i++;
            }
            i=0;
            for (vector<Reject>::const_iterator reject=setting.reject.begin();
                    reject!=setting.reject.end(); reject++) {
                i++;
                printf("      reject[%02d]=('%s','%s','%s')\n", i, reject->file.c_str(),
                        reject->name.c_str(), reject->comment.c_str());
            }
        }
    }

    for (it=settings.begin(); it!=settings.end(); it++) {
        const Settings& setting = it->second;
        if (it->first != setting.id) {
            fprintf(stderr, "it->first='%s' setting.id='%s'\n", it->first.c_str(),
                    setting.id.c_str());
            err |= 1;
        }
    }


    return err;
}

static int
test_one_pseudo(bool big, const char* expected)
{
    XLIFFFile* xliff = XLIFFFile::Parse("testdata/pseudo.xliff");
    if (xliff == NULL) {
        printf("couldn't read file\n");
        return 1;
    }
    if (false) {
        printf("XLIFF was [[%s]]\n", xliff->ToString().c_str());
    }

    pseudolocalize_xliff(xliff, big);
    string newString = xliff->ToString();
    delete xliff;

    if (false) {
        printf("XLIFF is [[%s]]\n", newString.c_str());
    }

    if (false && newString != expected) {
        fprintf(stderr, "xliff didn't translate as expected\n");
        fprintf(stderr, "newString=[[%s]]\n", newString.c_str());
        fprintf(stderr, "expected=[[%s]]\n", expected);
        return 1;
    }

    return 0;
}

static int
pseudolocalize_test()
{
    int err = 0;
    
    err |= test_one_pseudo(false, "");
    //err |= test_one_pseudo(true, "");

    return err;
}

int
localize_test()
{
    bool all = true;
    int err = 0;

    if (all) err |= translated_file_name_test();
    if (all) err |= delete_trans_units();
    if (all) err |= filter_trans_units();
    if (all) err |= settings_test();
    if (all) err |= pseudolocalize_test();

    return err;
}

