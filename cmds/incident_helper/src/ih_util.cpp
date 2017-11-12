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

#define LOG_TAG "incident_helper"

#include "ih_util.h"

#include <algorithm>
#include <sstream>
#include <unistd.h>

bool isValidChar(char c) {
    uint8_t v = (uint8_t)c;
    return (v >= (uint8_t)'a' && v <= (uint8_t)'z')
        || (v >= (uint8_t)'A' && v <= (uint8_t)'Z')
        || (v >= (uint8_t)'0' && v <= (uint8_t)'9')
        || (v == (uint8_t)'_');
}

static std::string trim(const std::string& s, const std::string& chars) {
    const auto head = s.find_first_not_of(chars);
    if (head == std::string::npos) return "";

    const auto tail = s.find_last_not_of(chars);
    return s.substr(head, tail - head + 1);
}

static std::string trimDefault(const std::string& s) {
    return trim(s, DEFAULT_WHITESPACE);
}

static std::string trimHeader(const std::string& s) {
    std::string res = trimDefault(s);
    std::transform(res.begin(), res.end(), res.begin(), ::tolower);
    return res;
}

// This is similiar to Split in android-base/file.h, but it won't add empty string
static void split(const std::string& line, std::vector<std::string>& words,
        const trans_func& func, const std::string& delimiters) {
    words.clear();  // clear the buffer before split

    size_t base = 0;
    size_t found;
    while (true) {
        found = line.find_first_of(delimiters, base);
        if (found != base) {
            std::string word = (*func) (line.substr(base, found - base));
            if (!word.empty()) {
                words.push_back(word);
            }
        }
        if (found == line.npos) break;
        base = found + 1;
    }
}

header_t parseHeader(const std::string& line, const std::string& delimiters) {
    header_t header;
    trans_func f = &trimHeader;
    split(line, header, f, delimiters);
    return header;
}

record_t parseRecord(const std::string& line, const std::string& delimiters) {
    record_t record;
    trans_func f = &trimDefault;
    split(line, record, f, delimiters);
    return record;
}

record_t parseRecordByColumns(const std::string& line, const std::vector<int>& indices, const std::string& delimiters) {
    record_t record;
    int lastIndex = 0;
    int lineSize = (int)line.size();
    for (std::vector<int>::const_iterator it = indices.begin(); it != indices.end(); ++it) {
        int idx = *it;
        if (lastIndex > idx || idx > lineSize) {
            record.clear(); // The indices is wrong, return empty;
            return record;
        }
        while (idx < lineSize && delimiters.find(line[idx++]) == std::string::npos);
        record.push_back(trimDefault(line.substr(lastIndex, idx - lastIndex)));
        lastIndex = idx;
    }
    record.push_back(trimDefault(line.substr(lastIndex, lineSize - lastIndex)));
    return record;
}

bool stripPrefix(std::string* line, const char* key, bool endAtDelimiter) {
    const auto head = line->find_first_not_of(DEFAULT_WHITESPACE);
    if (head == std::string::npos) return false;
    int len = (int)line->length();
    int i = 0;
    int j = head;
    while (key[i] != '\0') {
        if (j >= len || key[i++] != line->at(j++)) {
            return false;
        }
    }

    if (endAtDelimiter) {
        // this means if the line only have prefix or no delimiter, we still return false.
        if (j == len || isValidChar(line->at(j))) return false;
    }

    line->assign(trimDefault(line->substr(j)));
    return true;
}

bool stripSuffix(std::string* line, const char* key, bool endAtDelimiter) {
    const auto tail = line->find_last_not_of(DEFAULT_WHITESPACE);
    if (tail == std::string::npos) return false;
    int i = 0;
    while (key[++i] != '\0'); // compute the size of the key
    int j = tail;
    while (i > 0) {
        if (j < 0 || key[--i] != line->at(j--)) {
            return false;
        }
    }

    if (endAtDelimiter) {
        // this means if the line only have suffix or no delimiter, we still return false.
        if (j < 0 || isValidChar(line->at(j))) return false;
    }

    line->assign(trimDefault(line->substr(0, j+1)));
    return true;
}

int toInt(const std::string& s) {
    return atoi(s.c_str());
}

long long toLongLong(const std::string& s) {
    return atoll(s.c_str());
}

double toDouble(const std::string& s) {
    return atof(s.c_str());
}

// ==============================================================================
Reader::Reader(const int fd)
{
    mFile = fdopen(fd, "r");
    mStatus = mFile == NULL ? "Invalid fd " + std::to_string(fd) : "";
}

Reader::~Reader()
{
    if (mFile != NULL) fclose(mFile);
}

bool Reader::readLine(std::string* line) {
    if (mFile == NULL) return false;

    char* buf = NULL;
    size_t len = 0;
    ssize_t read = getline(&buf, &len, mFile);
    if (read != -1) {
        std::string s(buf);
        line->assign(trim(s, DEFAULT_NEWLINE));
    } else if (errno == EINVAL) {
        mStatus = "Bad Argument";
    }
    free(buf);
    return read != -1;
}

bool Reader::ok(std::string* error) {
    error->assign(mStatus);
    return mStatus.empty();
}

// ==============================================================================
static int
lookupName(const char** names, const int size, const char* name)
{
    for (int i=0; i<size; i++) {
        if (strcmp(name, names[i]) == 0) {
            return i;
        }
    }
    return -1;
}

EnumTypeMap::EnumTypeMap(const char* enumNames[], const uint32_t enumValues[], const int enumCount)
        :mEnumNames(enumNames),
         mEnumValues(enumValues),
         mEnumCount(enumCount)
{
}

EnumTypeMap::~EnumTypeMap()
{
}

int
EnumTypeMap::parseValue(const std::string& value)
{
    int index = lookupName(mEnumNames, mEnumCount, value.c_str());
    if (index < 0) return mEnumValues[0]; // Assume value 0 is default
    return mEnumValues[index];
}

Table::Table(const char* names[], const uint64_t ids[], const int count)
        :mFieldNames(names),
         mFieldIds(ids),
         mFieldCount(count),
         mEnums()
{
}

Table::~Table()
{
}

void
Table::addEnumTypeMap(const char* field, const char* enumNames[], const uint32_t enumValues[], const int enumSize)
{
    int index = lookupName(mFieldNames, mFieldCount, field);
    if (index < 0) return;

    EnumTypeMap enu(enumNames, enumValues, enumSize);
    mEnums[index] = enu;
}

bool
Table::insertField(ProtoOutputStream* proto, const std::string& name, const std::string& value)
{
    int index = lookupName(mFieldNames, mFieldCount, name.c_str());
    if (index < 0) return false;

    uint64_t found = mFieldIds[index];
    switch (found & FIELD_TYPE_MASK) {
        case FIELD_TYPE_DOUBLE:
        case FIELD_TYPE_FLOAT:
            proto->write(found, toDouble(value));
            break;
        case FIELD_TYPE_STRING:
        case FIELD_TYPE_BYTES:
            proto->write(found, value);
            break;
        case FIELD_TYPE_INT64:
        case FIELD_TYPE_SINT64:
        case FIELD_TYPE_UINT64:
        case FIELD_TYPE_FIXED64:
        case FIELD_TYPE_SFIXED64:
            proto->write(found, toLongLong(value));
            break;
        case FIELD_TYPE_BOOL:
            return false;
        case FIELD_TYPE_ENUM:
            if (mEnums.find(index) == mEnums.end()) {
                // forget to add enum type mapping
                return false;
            }
            proto->write(found, mEnums[index].parseValue(value));
            break;
        case FIELD_TYPE_INT32:
        case FIELD_TYPE_SINT32:
        case FIELD_TYPE_UINT32:
        case FIELD_TYPE_FIXED32:
        case FIELD_TYPE_SFIXED32:
            proto->write(found, toInt(value));
            break;
        default:
            return false;
    }
    return true;
}
