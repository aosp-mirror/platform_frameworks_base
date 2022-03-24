/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef UTIL_H
#define UTIL_H

#include <sys/types.h>

#include <map>
#include <string>
#include <vector>

using namespace std;

struct FileInfo
{
    bool exists;
    time_t mtime;
    time_t ctime;
    off_t size;

    FileInfo();
    FileInfo(const FileInfo& that);
    explicit FileInfo(const string& filename);
    ~FileInfo();

    bool operator==(const FileInfo& that) const;
    bool operator!=(const FileInfo& that) const;
};


/**
 * Record for a file that we are watching
 */
struct TrackedFile {
    string filename;
    FileInfo fileInfo;

    TrackedFile();
    TrackedFile(const TrackedFile& that);
    explicit TrackedFile(const string& filename);
    ~TrackedFile();

    // Returns if the file has changed. If it doesn't currently exist, returns true.
    bool HasChanged() const;
};

/**
 * Get FileInfo structures recursively for all the files and symlinks in a directory.
 * Does not traverse symlinks, but it does record them.
 */
void get_directory_contents(const string& dir, map<string,FileInfo>* results);

bool directory_contents_differ(const map<string,FileInfo>& before,
        const map<string,FileInfo>& after);

string escape_quotes(const char* str);

string escape_for_commandline(const char* str);

string trim(const string& trim);

bool starts_with(const string& str, const string& prefix);

bool ends_with(const string& str, const string& suffix);

void split_lines(vector<string>* result, const string& str);

string read_file(const string& filename);

bool is_executable(const string& filename);

string dirname(const string& filename);
string leafname(const string& filename);

#endif // UTIL_H

