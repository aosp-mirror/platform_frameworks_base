#ifndef DEVICE_TOOLS_AIDL_SEARCH_PATH_H
#define DEVICE_TOOLS_AIDL_SEARCH_PATH_H

#include <stdio.h>

#if __cplusplus
#include <vector>
#include <string>
using namespace std;
extern "C" {
#endif

// returns a FILE* and the char* for the file that it found
// given is the class name we're looking for
char* find_import_file(const char* given);

#if __cplusplus
}; // extern "C"
void set_import_paths(const vector<string>& importPaths);
#endif

#endif // DEVICE_TOOLS_AIDL_SEARCH_PATH_H

