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

#include <map>
#include <string>
#include <vector>

#include <android/util/ProtoOutputStream.h>

using namespace android::util;

typedef std::vector<std::string> header_t;
typedef std::vector<std::string> record_t;
typedef std::string (*trans_func) (const std::string&);

const std::string DEFAULT_WHITESPACE = " \t";
const std::string DEFAULT_NEWLINE = "\r\n";
const std::string TAB_DELIMITER = "\t";
const std::string COMMA_DELIMITER = ",";

// returns true if c is a-zA-Z0-9 or underscore _
bool isValidChar(char c);

// trim the string with the given charset
std::string trim(const std::string& s, const std::string& charset);

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
 * When a text-format table aligns by its vertical position, it is not possible to split them by purely delimiters.
 * This function allows to parse record by its header's column position' indices, must in ascending order.
 * At the same time, it still looks at the char at index, if it doesn't belong to delimiters, moves forward to find the delimiters.
 */
record_t parseRecordByColumns(const std::string& line, const std::vector<int>& indices, const std::string& delimiters = DEFAULT_WHITESPACE);

/**
 * When the line starts/ends with the given key, the function returns true
 * as well as the line argument is changed to the rest trimmed part of the original.
 * e.g. "ZRAM: 6828K physical used for 31076K in swap (524284K total swap)" becomes
 * "6828K physical used for 31076K in swap (524284K total swap)" when given key "ZRAM:",
 * otherwise the line is not changed.
 *
 * In order to prevent two values have same prefix which cause entering to incorrect conditions,
 * stripPrefix and stripSuffix can turn on a flag that requires the ending char in the line must not be a valid
 * character or digits, this feature is off by default.
 * i.e. ABC%some value, ABCD%other value
 */
bool stripPrefix(std::string* line, const char* key, bool endAtDelimiter = false);
bool stripSuffix(std::string* line, const char* key, bool endAtDelimiter = false);

/**
 * Converts string to the desired type
 */
int toInt(const std::string& s);
long long toLongLong(const std::string& s);
double toDouble(const std::string& s);

/**
 * Reader class reads data from given fd in streaming fashion.
 * The buffer size is controlled by capacity parameter.
 */
class Reader
{
public:
    Reader(const int fd);
    ~Reader();

    bool readLine(std::string* line);
    bool ok(std::string* error);

private:
    FILE* mFile;
    std::string mStatus;
};

/**
 * The class contains a mapping between table headers to its field ids.
 * And allow users to insert the field values to proto based on its header name.
 */
class Table
{
public:
    Table(const char* names[], const uint64_t ids[], const int count);
    ~Table();

    // Add enum names to values for parsing purpose.
    void addEnumTypeMap(const char* field, const char* enumNames[], const int enumValues[], const int enumSize);

    // manually add enum names to values mapping, useful when an Enum type is used by a lot of fields, and there are no name conflicts
    void addEnumNameToValue(const char* enumName, const int enumValue);

    bool insertField(ProtoOutputStream* proto, const std::string& name, const std::string& value);
private:
    map<std::string, uint64_t> mFields;
    map<std::string, map<std::string, int>> mEnums;
    map<std::string, int> mEnumValuesByName;
};

#endif  // INCIDENT_HELPER_UTIL_H
