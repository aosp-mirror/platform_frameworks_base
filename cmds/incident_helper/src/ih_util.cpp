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

std::string trim(const std::string& s, const std::string& charset) {
    const auto head = s.find_first_not_of(charset);
    if (head == std::string::npos) return "";

    const auto tail = s.find_last_not_of(charset);
    return s.substr(head, tail - head + 1);
}

static inline std::string toLowerStr(const std::string& s) {
    std::string res(s);
    std::transform(res.begin(), res.end(), res.begin(), ::tolower);
    return res;
}

static inline std::string trimDefault(const std::string& s) {
    return trim(s, DEFAULT_WHITESPACE);
}

static inline std::string trimHeader(const std::string& s) {
    return toLowerStr(trimDefault(s));
}

static inline bool isNumber(const std::string& s) {
    std::string::const_iterator it = s.begin();
    while (it != s.end() && std::isdigit(*it)) ++it;
    return !s.empty() && it == s.end();
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

bool getColumnIndices(std::vector<int>& indices, const char** headerNames, const std::string& line) {
    indices.clear();

    size_t lastIndex = 0;
    int i = 0;
    while (headerNames[i] != nullptr) {
        std::string s = headerNames[i];
        lastIndex = line.find(s, lastIndex);
        if (lastIndex == std::string::npos) {
            fprintf(stderr, "Bad Task Header: %s\n", line.c_str());
            return false;
        }
        lastIndex += s.length();
        indices.push_back(lastIndex);
        i++;
    }

    return true;
}

record_t parseRecordByColumns(const std::string& line, const std::vector<int>& indices, const std::string& delimiters) {
    record_t record;
    int lastIndex = 0;
    int lastBeginning = 0;
    int lineSize = (int)line.size();
    for (std::vector<int>::const_iterator it = indices.begin(); it != indices.end(); ++it) {
        int idx = *it;
        if (idx <= lastIndex) {
            // We saved up until lastIndex last time, so we should start at
            // lastIndex + 1 this time.
            idx = lastIndex + 1;
        }
        if (idx > lineSize) {
            if (lastIndex < idx && lastIndex < lineSize) {
                // There's a little bit more for us to save, which we'll do
                // outside of the loop.
                break;
            }
            // If we're past the end of the line AND we've already saved everything up to the end.
            fprintf(stderr, "index wrong: lastIndex: %d, idx: %d, lineSize: %d\n", lastIndex, idx, lineSize);
            record.clear(); // The indices are wrong, return empty.
            return record;
        }
        while (idx < lineSize && delimiters.find(line[idx++]) == std::string::npos);
        record.push_back(trimDefault(line.substr(lastIndex, idx - lastIndex)));
        lastBeginning = lastIndex;
        lastIndex = idx;
    }
    if (lineSize - lastIndex > 0) {
        int beginning = lastIndex;
        if (record.size() == indices.size() && !record.empty()) {
            // We've already encountered all of the columns...put whatever is
            // left in the last column.
            record.pop_back();
            beginning = lastBeginning;
        }
        record.push_back(trimDefault(line.substr(beginning, lineSize - beginning)));
    }
    return record;
}

void printRecord(const record_t& record) {
    fprintf(stderr, "Record: { ");
    if (record.size() == 0) {
        fprintf(stderr, "}\n");
        return;
    }
    for(size_t i = 0; i < record.size(); ++i) {
        if(i != 0) fprintf(stderr, "\", ");
        fprintf(stderr, "\"%s", record[i].c_str());
    }
    fprintf(stderr, "\" }\n");
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

std::string behead(std::string* line, const char cut) {
    auto found = line->find_first_of(cut);
    if (found == std::string::npos) {
        std::string head = line->substr(0);
        line->assign("");
        return head;
    }
    std::string head = line->substr(0, found);
    while(line->at(found) == cut) found++; // trim more cut of the rest
    line->assign(line->substr(found));
    return head;
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
    mStatus = mFile == nullptr ? "Invalid fd " + std::to_string(fd) : "";
}

Reader::~Reader()
{
    if (mFile != nullptr) fclose(mFile);
}

bool Reader::readLine(std::string* line) {
    if (mFile == nullptr) return false;

    char* buf = nullptr;
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
Table::Table(const char* names[], const uint64_t ids[], const int count)
        :mEnums(),
         mEnumValuesByName()
{
    std::map<std::string, uint64_t> fields;
    for (int i = 0; i < count; i++) {
        fields[names[i]] = ids[i];
    }
    mFields = fields;
}

Table::~Table()
{
}

void
Table::addEnumTypeMap(const char* field, const char* enumNames[], const int enumValues[], const int enumSize)
{
    if (mFields.find(field) == mFields.end()) {
        fprintf(stderr, "Field '%s' not found", field);
        return;
    }

    std::map<std::string, int> enu;
    for (int i = 0; i < enumSize; i++) {
        enu[enumNames[i]] = enumValues[i];
    }
    mEnums[field] = enu;
}

void
Table::addEnumNameToValue(const char* enumName, const int enumValue)
{
    mEnumValuesByName[enumName] = enumValue;
}

bool
Table::insertField(ProtoOutputStream* proto, const std::string& name, const std::string& value)
{
    if (mFields.find(name) == mFields.end()) return false;

    uint64_t found = mFields[name];
    record_t repeats; // used for repeated fields
    switch ((found & FIELD_COUNT_MASK) | (found & FIELD_TYPE_MASK)) {
        case FIELD_COUNT_SINGLE | FIELD_TYPE_DOUBLE:
        case FIELD_COUNT_SINGLE | FIELD_TYPE_FLOAT:
            proto->write(found, toDouble(value));
            break;
        case FIELD_COUNT_SINGLE | FIELD_TYPE_STRING:
        case FIELD_COUNT_SINGLE | FIELD_TYPE_BYTES:
            proto->write(found, value);
            break;
        case FIELD_COUNT_SINGLE | FIELD_TYPE_INT64:
        case FIELD_COUNT_SINGLE | FIELD_TYPE_SINT64:
        case FIELD_COUNT_SINGLE | FIELD_TYPE_UINT64:
        case FIELD_COUNT_SINGLE | FIELD_TYPE_FIXED64:
        case FIELD_COUNT_SINGLE | FIELD_TYPE_SFIXED64:
            proto->write(found, toLongLong(value));
            break;
        case FIELD_COUNT_SINGLE | FIELD_TYPE_BOOL:
            if (strcmp(toLowerStr(value).c_str(), "true") == 0 || strcmp(value.c_str(), "1") == 0) {
                proto->write(found, true);
                break;
            }
            if (strcmp(toLowerStr(value).c_str(), "false") == 0 || strcmp(value.c_str(), "0") == 0) {
                proto->write(found, false);
                break;
            }
            return false;
        case FIELD_COUNT_SINGLE | FIELD_TYPE_ENUM:
            // if the field has its own enum mapping, use this, otherwise use general name to value mapping.
            if (mEnums.find(name) != mEnums.end()) {
                if (mEnums[name].find(value) != mEnums[name].end()) {
                    proto->write(found, mEnums[name][value]);
                } else {
                    proto->write(found, 0); // TODO: should get the default enum value (Unknown)
                }
            } else if (mEnumValuesByName.find(value) != mEnumValuesByName.end()) {
                proto->write(found, mEnumValuesByName[value]);
            } else if (isNumber(value)) {
                proto->write(found, toInt(value));
            } else {
                return false;
            }
            break;
        case FIELD_COUNT_SINGLE | FIELD_TYPE_INT32:
        case FIELD_COUNT_SINGLE | FIELD_TYPE_SINT32:
        case FIELD_COUNT_SINGLE | FIELD_TYPE_UINT32:
        case FIELD_COUNT_SINGLE | FIELD_TYPE_FIXED32:
        case FIELD_COUNT_SINGLE | FIELD_TYPE_SFIXED32:
            proto->write(found, toInt(value));
            break;
        // REPEATED TYPE below:
        case FIELD_COUNT_REPEATED | FIELD_TYPE_INT32:
            repeats = parseRecord(value, COMMA_DELIMITER);
            for (size_t i=0; i<repeats.size(); i++) {
                proto->write(found, toInt(repeats[i]));
            }
            break;
        case FIELD_COUNT_REPEATED | FIELD_TYPE_STRING:
            repeats = parseRecord(value, COMMA_DELIMITER);
            for (size_t i=0; i<repeats.size(); i++) {
                proto->write(found, repeats[i]);
            }
            break;
        default:
            return false;
    }
    return true;
}

// ================================================================================
Message::Message(Table* table)
        :mTable(table),
         mPreviousField(""),
         mTokens(),
         mSubMessages()
{
}

Message::~Message()
{
}

void
Message::addSubMessage(uint64_t fieldId, Message* fieldMsg)
{
    for (auto iter = mTable->mFields.begin(); iter != mTable->mFields.end(); iter++) {
        if (iter->second == fieldId) {
            mSubMessages[iter->first] = fieldMsg;
            return;
        }
    }
}

bool
Message::insertField(ProtoOutputStream* proto, const std::string& name, const std::string& value)
{
    // If the field name can be found, it means the name is a primitive field.
    if (mTable->mFields.find(name) != mTable->mFields.end()) {
        endSession(proto);
        // The only edge case is for example ro.hardware itself is a message, so a field called "value"
        // would be defined in proto Ro::Hardware and it must be the first field.
        if (mSubMessages.find(name) != mSubMessages.end()) {
            startSession(proto, name);
            return mSubMessages[name]->insertField(proto, "value", value);
        } else {
            return mTable->insertField(proto, name, value);
        }
    }

    // Try to find the message field which is the prefix of name, so the value would be inserted
    // recursively into the submessage.
    std::string mutableName = name;
    for (auto iter = mSubMessages.begin(); iter != mSubMessages.end(); iter++) {
        std::string fieldName = iter->first;
        std::string prefix = fieldName + "_"; // underscore is the delimiter in the name
        if (stripPrefix(&mutableName, prefix.c_str())) {
            if (mPreviousField != fieldName) {
                endSession(proto);
                startSession(proto, fieldName);
            }
            return mSubMessages[fieldName]->insertField(proto, mutableName, value);
        }
    }
    // Can't find the name in proto definition, handle it separately.
    return false;
}

void
Message::startSession(ProtoOutputStream* proto, const std::string& name)
{
    uint64_t fieldId = mTable->mFields[name];
    uint64_t token = proto->start(fieldId);
    mPreviousField = name;
    mTokens.push(token);
}

void
Message::endSession(ProtoOutputStream* proto)
{
    if (mPreviousField == "") return;
    if (mSubMessages.find(mPreviousField) != mSubMessages.end()) {
        mSubMessages[mPreviousField]->endSession(proto);
    }
    proto->end(mTokens.top());
    mTokens.pop();
    mPreviousField = "";
}
