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

#include "util.h"

#include <sys/types.h>
#include <sys/stat.h>
#include <dirent.h>
#include <string.h>
#include <unistd.h>


FileInfo::FileInfo()
{
    memset(this, 0, sizeof(FileInfo));
}

FileInfo::FileInfo(const FileInfo& that)
{
    memcpy(this, &that, sizeof(FileInfo));
}

FileInfo::FileInfo(const string& filename)
{
    struct stat st;
    int err = stat(filename.c_str(), &st);
    if (err != 0) {
        memset(this, 0, sizeof(FileInfo));
    } else {
        exists = true;
        mtime = st.st_mtime;
        ctime = st.st_ctime;
        size = st.st_size;
    }
}

bool
FileInfo::operator==(const FileInfo& that) const
{
    return exists == that.exists
            && mtime == that.mtime
            && ctime == that.ctime
            && size == that.size;
}

bool
FileInfo::operator!=(const FileInfo& that) const
{
    return exists != that.exists
            || mtime != that.mtime
            || ctime != that.ctime
            || size != that.size;
}

FileInfo::~FileInfo()
{
}

TrackedFile::TrackedFile()
    :filename(),
     fileInfo()
{
}

TrackedFile::TrackedFile(const TrackedFile& that)
{
    filename = that.filename;
    fileInfo = that.fileInfo;
}

TrackedFile::TrackedFile(const string& file)
    :filename(file),
     fileInfo(file)
{
}

TrackedFile::~TrackedFile()
{
}

bool
TrackedFile::HasChanged() const
{
    FileInfo updated(filename);
    return !updated.exists || fileInfo != updated;
}

void
get_directory_contents(const string& name, map<string,FileInfo>* results)
{
    DIR* dir = opendir(name.c_str());
    if (dir == NULL) {
        return;
    }

    dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }
        if (entry->d_type == DT_DIR) {
            string subdir = name + "/" + entry->d_name;
            get_directory_contents(subdir, results);
        } else if (entry->d_type == DT_LNK || entry->d_type == DT_REG) {
            string filename(name + "/" + entry->d_name);
            (*results)[filename] = FileInfo(filename);
        }
    }

    closedir(dir);
}

bool
directory_contents_differ(const map<string,FileInfo>& before, const map<string,FileInfo>& after)
{
    if (before.size() != after.size()) {
        return true;
    }
    map<string,FileInfo>::const_iterator b = before.begin();
    map<string,FileInfo>::const_iterator a = after.begin();
    while (b != before.end() && a != after.end()) {
        if (b->first != a->first) {
            return true;
        }
        if (a->second != b->second) {
            return true;
        }
        a++;
        b++;
    }
    return false;
}

string
escape_quotes(const char* str)
{
    string result;
    while (*str) {
        if (*str == '"') {
            result += '\\';
            result += '"';
        } else {
            result += *str;
        }
    }
    return result;
}

string
escape_for_commandline(const char* str)
{
    if (strchr(str, '"') != NULL || strchr(str, ' ') != NULL
            || strchr(str, '\t') != NULL) {
        return escape_quotes(str);
    } else {
        return str;
    }
}

static bool
spacechr(char c)
{
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
}

string
trim(const string& str)
{
    const ssize_t N = (ssize_t)str.size();
    ssize_t begin = 0;
    while (begin < N && spacechr(str[begin])) {
        begin++;
    }
    ssize_t end = N - 1;
    while (end >= begin && spacechr(str[end])) {
        end--;
    }
    return string(str, begin, end-begin+1);
}

bool
starts_with(const string& str, const string& prefix)
{
    return str.compare(0, prefix.length(), prefix) == 0;
}

bool
ends_with(const string& str, const string& suffix)
{
    if (str.length() < suffix.length()) {
        return false;
    } else {
        return str.compare(str.length()-suffix.length(), suffix.length(), suffix) == 0;
    }
}

void
split_lines(vector<string>* result, const string& str)
{
    const int N = str.length();
    int begin = 0;
    int end = 0;
    for (; end < N; end++) {
        const char c = str[end];
        if (c == '\r' || c == '\n') {
            if (begin != end) {
                result->push_back(string(str, begin, end-begin));
            }
            begin = end+1;
        }
    }
    if (begin != end) {
        result->push_back(string(str, begin, end-begin));
    }
}

string
read_file(const string& filename)
{
    FILE* file = fopen(filename.c_str(), "r");
    if (file == NULL) {
        return string();
    }
    
    fseek(file, 0, SEEK_END);
    int size = ftell(file);
    fseek(file, 0, SEEK_SET);

    char* buf = (char*)malloc(size);
    if ((size_t) size != fread(buf, 1, size, file)) {
        return string();
    }

    string result(buf, size);

    free(buf);
    fclose(file);

    return result;
}


