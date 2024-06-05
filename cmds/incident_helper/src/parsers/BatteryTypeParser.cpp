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

#include "frameworks/base/core/proto/android/os/batterytype.proto.h"
#include "ih_util.h"
#include "BatteryTypeParser.h"

using namespace android::os;

status_t
BatteryTypeParser::Parse(const int in, const int out) const
{
    Reader reader(in);
    string line;
    bool readLine = false;

    ProtoOutputStream proto;

    // parse line by line
    while (reader.readLine(&line)) {
        if (line.empty()) continue;

        if (readLine) {
            fprintf(stderr, "Multiple lines in file. Unsure what to do.\n");
            break;
        }

        proto.write(BatteryTypeProto::TYPE, line);

        readLine = true;
    }

    if (!reader.ok(&line)) {
        fprintf(stderr, "Bad read from fd %d: %s\n", in, line.c_str());
        return -1;
    }

    if (!proto.flush(out)) {
        fprintf(stderr, "[%s]Error writing proto back\n", this->name.c_str());
        return -1;
    }
    fprintf(stderr, "[%s]Proto size: %zu bytes\n", this->name.c_str(), proto.size());
    return NO_ERROR;
}
