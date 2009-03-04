#ifndef PERFORCE_H
#define PERFORCE_H

#include <string>
#include <vector>

using namespace std;

class Perforce
{
public:
    static int RunCommand(const string& cmd, string* result, bool printOnFailure);
    static int GetResourceFileNames(const string& version, const string& base,
                                const vector<string>& apps, vector<string>* result,
                                bool printOnFailure);
    static int GetFile(const string& file, const string& version, string* result,
                                bool printOnFailure);
    static string GetCurrentChange(bool printOnFailure);
    static int EditFiles(const vector<string>& filename, bool printOnFailure);
    static int AddFiles(const vector<string>& files, bool printOnFailure);
    static int DeleteFiles(const vector<string>& files, bool printOnFailure);
    static string Where(const string& depotPath, bool printOnFailure);
};

#endif // PERFORCE_H
