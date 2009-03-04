#include <unistd.h>
#include "search_path.h"
#include "options.h"
#include <string.h>

#ifdef HAVE_MS_C_RUNTIME
#include <io.h>
#endif

static vector<string> g_importPaths;

void
set_import_paths(const vector<string>& importPaths)
{
    g_importPaths = importPaths;
}

char*
find_import_file(const char* given)
{
    string expected = given;

    int N = expected.length();
    for (int i=0; i<N; i++) {
        char c = expected[i];
        if (c == '.') {
            expected[i] = OS_PATH_SEPARATOR;
        }
    }
    expected += ".aidl";

    vector<string>& paths = g_importPaths;
    for (vector<string>::iterator it=paths.begin(); it!=paths.end(); it++) {
        string f = *it;
        if (f.size() == 0) {
            f = ".";
            f += OS_PATH_SEPARATOR;
        }
        else if (f[f.size()-1] != OS_PATH_SEPARATOR) {
            f += OS_PATH_SEPARATOR;
        }
        f.append(expected);

#ifdef HAVE_MS_C_RUNTIME
        /* check that the file exists and is not write-only */
        if (0 == _access(f.c_str(), 0) &&  /* mode 0=exist */
            0 == _access(f.c_str(), 4) ) { /* mode 4=readable */
#else
        if (0 == access(f.c_str(), R_OK)) {
#endif        
            return strdup(f.c_str());
        }
    }

    return NULL;
}

