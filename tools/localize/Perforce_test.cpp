#include "Perforce.h"
#include <stdio.h>

static int
RunCommand_test()
{
    string result;
    int err = Perforce::RunCommand("p4 help csommands", &result, true);
    printf("err=%d result=[[%s]]\n", err, result.c_str());
    return 0;
}

static int
GetResourceFileNames_test()
{
    vector<string> results;
    vector<string> apps;
    apps.push_back("apps/common");
    apps.push_back("apps/Contacts");
    int err = Perforce::GetResourceFileNames("43019", "//device", apps, &results, true);
    if (err != 0) {
        return err;
    }
    if (results.size() != 2) {
        return 1;
    }
    if (results[0] != "//device/apps/common/res/values/strings.xml") {
        return 1;
    }
    if (results[1] != "//device/apps/Contacts/res/values/strings.xml") {
        return 1;
    }
    if (false) {
        for (size_t i=0; i<results.size(); i++) {
            printf("[%zd] '%s'\n", i, results[i].c_str());
        }
    }
    return 0;
}

static int
GetFile_test()
{
    string result;
    int err = Perforce::GetFile("//device/Makefile", "296", &result, true);
    printf("err=%d result=[[%s]]\n", err, result.c_str());
    return 0;
}

int
Perforce_test()
{
    bool all = false;
    int err = 0;

    if (all) err |= RunCommand_test();
    if (all) err |= GetResourceFileNames_test();
    if (all) err |= GetFile_test();

    return err;
}

