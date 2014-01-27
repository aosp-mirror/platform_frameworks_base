#include <iostream>
#include "options.h"

const bool VERBOSE = false;

using namespace std;

struct Answer {
    const char* argv[8];
    int result;
    const char* systemSearchPath[8];
    const char* localSearchPath[8];
    const char* inputFileName;
    language_t nativeLanguage;
    const char* outputH;
    const char* outputCPP;
    const char* outputJava;
};

bool
match_arrays(const char* const*expected, const vector<string> &got)
{
    int count = 0;
    while (expected[count] != NULL) {
        count++;
    }
    if (got.size() != count) {
        return false;
    }
    for (int i=0; i<count; i++) {
        if (got[i] != expected[i]) {
            return false;
        }
    }
    return true;
}

void
print_array(const char* prefix, const char* const*expected)
{
    while (*expected) {
        cout << prefix << *expected << endl;
        expected++;
    }
}

void
print_array(const char* prefix, const vector<string> &got)
{
    size_t count = got.size();
    for (size_t i=0; i<count; i++) {
        cout << prefix << got[i] << endl;
    }
}

static int
test(const Answer& answer)
{
    int argc = 0;
    while (answer.argv[argc]) {
        argc++;
    }

    int err = 0;

    Options options;
    int result = parse_options(argc, answer.argv, &options);

    // result
    if (((bool)result) != ((bool)answer.result)) {
        cout << "mismatch: result: got " << result << " expected " <<
            answer.result << endl;
        err = 1;
    }

    if (result != 0) {
        // if it failed, everything is invalid
        return err;
    }

    // systemSearchPath
    if (!match_arrays(answer.systemSearchPath, options.systemSearchPath)) {
        cout << "mismatch: systemSearchPath: got" << endl;
        print_array("        ", options.systemSearchPath);
        cout << "    expected" << endl;
        print_array("        ", answer.systemSearchPath);
        err = 1;
    }

    // localSearchPath
    if (!match_arrays(answer.localSearchPath, options.localSearchPath)) {
        cout << "mismatch: localSearchPath: got" << endl;
        print_array("        ", options.localSearchPath);
        cout << "    expected" << endl;
        print_array("        ", answer.localSearchPath);
        err = 1;
    }

    // inputFileName
    if (answer.inputFileName != options.inputFileName) {
        cout << "mismatch: inputFileName: got " << options.inputFileName
            << " expected " << answer.inputFileName << endl;
        err = 1;
    }

    // nativeLanguage
    if (answer.nativeLanguage != options.nativeLanguage) {
        cout << "mismatch: nativeLanguage: got " << options.nativeLanguage
            << " expected " << answer.nativeLanguage << endl;
        err = 1;
    }

    // outputH
    if (answer.outputH != options.outputH) {
        cout << "mismatch: outputH: got " << options.outputH
            << " expected " << answer.outputH << endl;
        err = 1;
    }

    // outputCPP
    if (answer.outputCPP != options.outputCPP) {
        cout << "mismatch: outputCPP: got " << options.outputCPP
            << " expected " << answer.outputCPP << endl;
        err = 1;
    }

    // outputJava
    if (answer.outputJava != options.outputJava) {
        cout << "mismatch: outputJava: got " << options.outputJava
            << " expected " << answer.outputJava << endl;
        err = 1;
    }

    return err;
}

const Answer g_tests[] = {

    {
        /* argv */              { "test", "-i/moof", "-I/blah", "-Ibleh", "-imoo", "inputFileName.aidl_cpp", NULL, NULL },
        /* result */            0,
        /* systemSearchPath */  { "/blah", "bleh", NULL, NULL, NULL, NULL, NULL, NULL },
        /* localSearchPath */   { "/moof", "moo", NULL, NULL, NULL, NULL, NULL, NULL },
        /* inputFileName */     "inputFileName.aidl_cpp",
        /* nativeLanguage */    CPP,
        /* outputH */           "",
        /* outputCPP */         "",
        /* outputJava */        ""
    },

    {
        /* argv */              { "test", "inputFileName.aidl_cpp", "-oh", "outputH", NULL, NULL, NULL, NULL },
        /* result */            0,
        /* systemSearchPath */  { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* localSearchPath */   { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* inputFileName */     "inputFileName.aidl_cpp",
        /* nativeLanguage */    CPP,
        /* outputH */           "outputH",
        /* outputCPP */         "",
        /* outputJava */        ""
    },

    {
        /* argv */              { "test", "inputFileName.aidl_cpp", "-ocpp", "outputCPP", NULL, NULL, NULL, NULL },
        /* result */            0,
        /* systemSearchPath */  { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* localSearchPath */   { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* inputFileName */     "inputFileName.aidl_cpp",
        /* nativeLanguage */    CPP,
        /* outputH */           "",
        /* outputCPP */         "outputCPP",
        /* outputJava */        ""
    },

    {
        /* argv */              { "test", "inputFileName.aidl_cpp", "-ojava", "outputJava", NULL, NULL, NULL, NULL },
        /* result */            0,
        /* systemSearchPath */  { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* localSearchPath */   { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* inputFileName */     "inputFileName.aidl_cpp",
        /* nativeLanguage */    CPP,
        /* outputH */           "",
        /* outputCPP */         "",
        /* outputJava */        "outputJava"
    },

    {
        /* argv */              { "test", "inputFileName.aidl_cpp", "-oh", "outputH", "-ocpp", "outputCPP", "-ojava", "outputJava" },
        /* result */            0,
        /* systemSearchPath */  { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* localSearchPath */   { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* inputFileName */     "inputFileName.aidl_cpp",
        /* nativeLanguage */    CPP,
        /* outputH */           "outputH",
        /* outputCPP */         "outputCPP",
        /* outputJava */        "outputJava"
    },

    {
        /* argv */              { "test", "inputFileName.aidl_cpp", "-oh", "outputH", "-oh", "outputH1", NULL, NULL },
        /* result */            1,
        /* systemSearchPath */  { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* localSearchPath */   { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* inputFileName */     "",
        /* nativeLanguage */    CPP,
        /* outputH */           "",
        /* outputCPP */         "",
        /* outputJava */        ""
    },

    {
        /* argv */              { "test", "inputFileName.aidl_cpp", "-ocpp", "outputCPP", "-ocpp", "outputCPP1", NULL, NULL },
        /* result */            1,
        /* systemSearchPath */  { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* localSearchPath */   { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* inputFileName */     "",
        /* nativeLanguage */    CPP,
        /* outputH */           "",
        /* outputCPP */         "",
        /* outputJava */        ""
    },

    {
        /* argv */              { "test", "inputFileName.aidl_cpp", "-ojava", "outputJava", "-ojava", "outputJava1", NULL, NULL },
        /* result */            1,
        /* systemSearchPath */  { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* localSearchPath */   { NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL },
        /* inputFileName */     "",
        /* nativeLanguage */    CPP,
        /* outputH */           "",
        /* outputCPP */         "",
        /* outputJava */        ""
    },

};

int
main(int argc, const char** argv)
{
    const int count = sizeof(g_tests)/sizeof(g_tests[0]);
    int matches[count];

    int result = 0;
    for (int i=0; i<count; i++) {
        if (VERBOSE) {
            cout << endl;
            cout << "---------------------------------------------" << endl;
            const char* const* p = g_tests[i].argv;
            while (*p) {
                cout << " " << *p;
                p++;
            }
            cout << endl;
            cout << "---------------------------------------------" << endl;
        }
        matches[i] = test(g_tests[i]);
        if (VERBOSE) {
            if (0 == matches[i]) {
                cout << "passed" << endl;
            } else {
                cout << "failed" << endl;
            }
            result |= matches[i];
        }
    }

    cout << endl;
    cout << "=============================================" << endl;
    cout << "options_test summary" << endl;
    cout << "=============================================" << endl;

    if (!result) {
        cout << "passed" << endl;
    } else {
        cout << "failed the following tests:" << endl;
        for (int i=0; i<count; i++) {
            if (matches[i]) {
                cout << "   ";
                const char* const* p = g_tests[i].argv;
                while (*p) {
                    cout << " " << *p;
                    p++;
                }
                cout << endl;
            }
        }
    }

    return result;
}

