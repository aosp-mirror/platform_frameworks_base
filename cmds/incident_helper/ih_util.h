/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef INCIDENT_HELPER_UTIL_H
#define INCIDENT_HELPER_UTIL_H

#include <string>
#include <vector>
#include <sstream>

typedef std::vector<std::string> header_t;
typedef std::vector<std::string> record_t;
typedef std::string (*trans_func) (const std::string&);

const char DEFAULT_NEWLINE = '\n';
const std::string DEFAULT_WHITESPACE = " \t";

/**
 * When a text has a table format like this
 * line 1: HeadA HeadB HeadC
 * line 2: v1    v2    v3
 * line 3: v11   v12   v13
 *
 * We want to parse the line in structure given the delimiter.
 * parseHeader is used to parse the firse line of the table and returns a list of strings in lower case
 * parseRecord is used to parse other lines and returns a list of strings
 * empty strings are skipped
 */
header_t parseHeader(const std::string& line, const std::string& delimiters = DEFAULT_WHITESPACE);
record_t parseRecord(const std::string& line, const std::string& delimiters = DEFAULT_WHITESPACE);

/**
 * When the line starts with the given key, the function returns true
 * as well as the line argument is changed to the rest part of the original.
 * e.g. "ZRAM: 6828K physical used for 31076K in swap (524284K total swap)" becomes
 * "6828K physical used for 31076K in swap (524284K total swap)" when given key "ZRAM:",
 * otherwise the line is not changed.
 */
bool hasPrefix(std::string* line, const char* key);

/**
 * Reader class reads data from given fd in streaming fashion.
 * The buffer size is controlled by capacity parameter.
 */
class Reader
{
public:
    Reader(const int fd);
    Reader(const int fd, const size_t capacity);
    ~Reader();

    bool readLine(std::string* line, const char newline = DEFAULT_NEWLINE);
    bool ok(std::string* error);

private:
    int mFd; // set mFd to -1 when read EOF()
    const size_t mMaxSize;
    size_t mBufSize;
    char* mBuf; // implements a circular buffer

    int mRead;
    int mFlushed;
    std::string mStatus;
    // end of read
    inline bool EOR() { return mFd == -1 && mBufSize == 0; };
};

#endif  // INCIDENT_HELPER_UTIL_H
