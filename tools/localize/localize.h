#ifndef LOCALIZE_H
#define LOCALIZE_H

#include "XLIFFFile.h"

#include <map>
#include <string>

using namespace std;

struct Reject
{
    string file;
    string name;
    string comment;
};

struct Settings
{
    string id;
    string oldVersion;
    string currentVersion;
    vector<string> apps;
    vector<Reject> reject;
};

int read_settings(const string& filename, map<string,Settings>* result, const string& rootDir);
string translated_file_name(const string& file, const string& locale);
bool keep_this_trans_unit(const string& file, const TransUnit& unit, void* cookie);
int validate_config(const string& settingsFile, const map<string,Settings>& settings,
        const string& configs);
int validate_configs(const string& settingsFile, const map<string,Settings>& settings,
        const vector<string>& configs);
int select_files(vector<string> *resFiles, const string& config,
        const map<string,Settings>& settings, const string& rootDir);
int select_files(vector<vector<string> > *allResFiles, const vector<string>& configs,
        const map<string,Settings>& settings, const string& rootDir);


#endif // LOCALIZE_H
