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

#include <android/util/ProtoOutputStream.h>

#include "frameworks/base/core/proto/android/os/system_properties.proto.h"
#include "ih_util.h"
#include "SystemPropertiesParser.h"

using namespace android::os;

const string LINE_DELIMITER = "]: [";

// system properties' names sometimes are not valid proto field names, make the names valid.
static string convertToFieldName(const string& name) {
    int len = (int)name.length();
    char cstr[len + 1];
    strcpy(cstr, name.c_str());
    for (int i = 0; i < len; i++) {
        if (!isValidChar(cstr[i])) {
            cstr[i] = '_';
        }
    }
    return string(cstr);
}

status_t
SystemPropertiesParser::Parse(const int in, const int out) const
{
    Reader reader(in);
    string line;
    string name;  // the name of the property
    string value; // the string value of the property

    ProtoOutputStream proto;
    Table table(SystemPropertiesProto::_FIELD_NAMES, SystemPropertiesProto::_FIELD_IDS, SystemPropertiesProto::_FIELD_COUNT);
    table.addEnumNameToValue("running", SystemPropertiesProto::STATUS_RUNNING);
    table.addEnumNameToValue("stopped", SystemPropertiesProto::STATUS_STOPPED);

    // parse line by line
    while (reader.readLine(&line)) {
        if (line.empty()) continue;

        line = line.substr(1, line.size() - 2); // trim []
        size_t index = line.find(LINE_DELIMITER); // split by "]: ["
        if (index == string::npos) {
            fprintf(stderr, "Bad Line %s\n", line.c_str());
            continue;
        }
        name = line.substr(0, index);
        value = trim(line.substr(index + 4), DEFAULT_WHITESPACE);
        if (value.empty()) continue;

        // if the property name couldn't be found in proto definition or the value has mistype,
        // add to extra properties with its name and value
        if (!table.insertField(&proto, convertToFieldName(name), value)) {
            long long token = proto.start(SystemPropertiesProto::EXTRA_PROPERTIES);
            proto.write(SystemPropertiesProto::Property::NAME, name);
            proto.write(SystemPropertiesProto::Property::VALUE, value);
            proto.end(token);
        }
    }

    if (!reader.ok(&line)) {
        fprintf(stderr, "Bad read from fd %d: %s\n", in, line.c_str());
        return -1;
    }

    if (!proto.flush(out)) {
        fprintf(stderr, "[%s]Error writing proto back\n", this->name.string());
        return -1;
    }
    fprintf(stderr, "[%s]Proto size: %zu bytes\n", this->name.string(), proto.size());
    return NO_ERROR;
}
