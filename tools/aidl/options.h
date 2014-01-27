#ifndef DEVICE_TOOLS_AIDL_H
#define DEVICE_TOOLS_AIDL_H

#include <string.h>
#include <string>
#include <vector>

using namespace std;

enum {
    COMPILE_AIDL,
    PREPROCESS_AIDL
};

// This struct is the parsed version of the command line options
struct Options
{
    int task;
    bool failOnParcelable;
    vector<string> importPaths;
    vector<string> preprocessedFiles;
    string inputFileName;
    string outputFileName;
    string outputBaseFolder;
    string depFileName;
    bool autoDepFile;

    vector<string> filesToPreprocess;
};

// takes the inputs from the command line and fills in the Options struct
// Returns 0 on success, and nonzero on failure.
// It also prints the usage statement on failure.
int parse_options(int argc, const char* const* argv, Options *options);

#endif // DEVICE_TOOLS_AIDL_H
