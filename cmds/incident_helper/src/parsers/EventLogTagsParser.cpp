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

#include "frameworks/base/core/proto/android/util/event_log_tags.proto.h"
#include "ih_util.h"
#include "EventLogTagsParser.h"

status_t
EventLogTagsParser::Parse(const int in, const int out) const
{
    Reader reader(in);
    string line;

    ProtoOutputStream proto;

    // parse line by line
    while (reader.readLine(&line)) {
        if (line.empty()) continue;
        string debug = line;
        string tagNumber = behead(&line, ' ');
        string tagName = behead(&line, ' ');
        if (tagNumber == "" || tagName == "") {
            fprintf(stderr, "Bad line, expect at least two parts: %s[%s, %s]\n",
                debug.c_str(), tagNumber.c_str(), tagName.c_str());
            continue;
        }

        uint64_t token = proto.start(EventLogTagMapProto::EVENT_LOG_TAGS);
        proto.write(EventLogTag::TAG_NUMBER, toInt(tagNumber));
        proto.write(EventLogTag::TAG_NAME, tagName);

        record_t valueDescriptors = parseRecord(line, PARENTHESES_DELIMITER);
        for (size_t i = 0; i < valueDescriptors.size(); i++) {
            record_t valueDescriptor = parseRecord(valueDescriptors[i], PIPE_DELIMITER);
            if (valueDescriptor.size() != 2 && valueDescriptor.size() != 3) {
                // If the parts doesn't contains pipe, then skips it.
                continue;
            }
            uint64_t descriptorToken = proto.start(EventLogTag::VALUE_DESCRIPTORS);
            proto.write(EventLogTag::ValueDescriptor::NAME, valueDescriptor[0]);
            proto.write(EventLogTag::ValueDescriptor::TYPE, toInt(valueDescriptor[1]));
            if (valueDescriptor.size() == 3) {
                char c = valueDescriptor[2][0];
                int unit = 0;
                if (c < '0' || c > '9') {
                    unit = (int) c;
                } else {
                    unit = toInt(valueDescriptor[2]);
                }
                proto.write(EventLogTag::ValueDescriptor::UNIT, unit);
            }
            proto.end(descriptorToken);
        }
        proto.end(token);
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
