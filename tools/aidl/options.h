#ifndef AIDL_OPTIONS_H_
#define AIDL_OPTIONS_H_

#include <string.h>
#include <string>
#include <vector>

using std::string;
using std::vector;

enum {
    COMPILE_AIDL,
    PREPROCESS_AIDL
};

// This struct is the parsed version of the command line options
struct Options
{
    int task{COMPILE_AIDL};
    bool failOnParcelable{false};
    vector<string> importPaths;
    vector<string> preprocessedFiles;
    string inputFileName;
    string outputFileName;
    string outputBaseFolder;
    string depFileName;
    bool autoDepFile{false};

    vector<string> filesToPreprocess;
};

// takes the inputs from the command line and fills in the Options struct
// Returns 0 on success, and nonzero on failure.
// It also prints the usage statement on failure.
int parse_options(int argc, const char* const* argv, Options *options);

#endif // AIDL_OPTIONS_H_
