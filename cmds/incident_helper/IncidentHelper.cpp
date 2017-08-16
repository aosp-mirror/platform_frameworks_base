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

#include "IncidentHelper.h"
#include "ih_util.h"

#include "frameworks/base/core/proto/android/os/kernelwake.pb.h"
#include "frameworks/base/core/proto/android/os/procrank.pb.h"

#include <android-base/file.h>
#include <unistd.h>
#include <string>
#include <vector>

using namespace android::base;
using namespace android::os;
using namespace google::protobuf;
using namespace std;

static bool
SetTableField(::google::protobuf::Message* message, string field_name, string field_value) {
    const Descriptor* descriptor = message->GetDescriptor();
    const Reflection* reflection = message->GetReflection();

    const FieldDescriptor* field = descriptor->FindFieldByName(field_name);
    switch (field->type()) {
        case FieldDescriptor::TYPE_STRING:
            reflection->SetString(message, field, field_value);
            return true;
        case FieldDescriptor::TYPE_INT64:
            reflection->SetInt64(message, field, atol(field_value.c_str()));
            return true;
        case FieldDescriptor::TYPE_UINT64:
            reflection->SetUInt64(message, field, atol(field_value.c_str()));
            return true;
        case FieldDescriptor::TYPE_INT32:
            reflection->SetInt32(message, field, atoi(field_value.c_str()));
            return true;
        case FieldDescriptor::TYPE_UINT32:
            reflection->SetUInt32(message, field, atoi(field_value.c_str()));
            return true;
        default:
            // Add new scalar types
            return false;
    }
}

// ================================================================================
status_t ReverseParser::Parse(const int in, const int out) const
{
    string content;
    if (!ReadFdToString(in, &content)) {
        fprintf(stderr, "[%s]Failed to read data from incidentd\n", this->name.string());
        return -1;
    }
    // reverse the content
    reverse(content.begin(), content.end());
    if (!WriteStringToFd(content, out)) {
        fprintf(stderr, "[%s]Failed to write data to incidentd\n", this->name.string());
        return -1;
    }
    return NO_ERROR;
}

// ================================================================================
static const string KERNEL_WAKEUP_LINE_DELIMITER = "\t";

status_t KernelWakesParser::Parse(const int in, const int out) const {
    Reader reader(in);
    string line;
    header_t header;  // the header of /d/wakeup_sources
    record_t record;  // retain each record
    int nline = 0;

    KernelWakeSources proto;

    // parse line by line
    while (reader.readLine(line)) {
        if (line.empty()) continue;
        // parse head line
        if (nline++ == 0) {
            header = parseHeader(line, KERNEL_WAKEUP_LINE_DELIMITER);
            continue;
        }

        // parse for each record, the line delimiter is \t only!
        record = parseRecord(line, KERNEL_WAKEUP_LINE_DELIMITER);

        if (record.size() != header.size()) {
            // TODO: log this to incident report!
            fprintf(stderr, "[%s]Line %d has missing fields\n%s\n", this->name.string(), nline, line.c_str());
            continue;
        }

        WakeupSourceProto* source = proto.add_wakeup_sources();
        for (int i=0; i<(int)record.size(); i++) {
            if (!SetTableField(source, header[i], record[i])) {
                fprintf(stderr, "[%s]Line %d has bad value %s of %s\n",
                        this->name.string(), nline, header[i].c_str(), record[i].c_str());
            }
        }
    }

    if (!reader.ok(line)) {
        fprintf(stderr, "Bad read from fd %d: %s\n", in, line.c_str());
        return -1;
    }

    if (!proto.SerializeToFileDescriptor(out)) {
        fprintf(stderr, "[%s]Error writing proto back\n", this->name.string());
        return -1;
    }
    fprintf(stderr, "[%s]Proto size: %d bytes\n", this->name.string(), proto.ByteSize());
    return NO_ERROR;
}

// ================================================================================
status_t ProcrankParser::Parse(const int in, const int out) const {
    Reader reader(in);
    string line, content;
    header_t header;  // the header of /d/wakeup_sources
    record_t record;  // retain each record
    int nline = 0;

    Procrank proto;

    // parse line by line
    while (reader.readLine(line)) {
        if (line.empty()) continue;

        // parse head line
        if (nline++ == 0) {
            header = parseHeader(line);
            continue;
        }

        record = parseRecord(line);
        if (record.size() != header.size()) {
            if (record[record.size() - 1] == "TOTAL") { // TOTAL record
                ProcessProto* total = proto.mutable_summary()->mutable_total();
                for (int i=1; i<=(int)record.size(); i++) {
                    SetTableField(total, header[header.size() - i], record[record.size() - i]);
                }
            } else if (record[0] == "ZRAM:") {
                record = parseRecord(line, ":");
                proto.mutable_summary()->mutable_zram()->set_raw_text(record[1]);
            } else if (record[0] == "RAM:") {
                record = parseRecord(line, ":");
                proto.mutable_summary()->mutable_ram()->set_raw_text(record[1]);
            } else {
                fprintf(stderr, "[%s]Line %d has missing fields\n%s\n", this->name.string(), nline,
                    line.c_str());
            }
            continue;
        }

        ProcessProto* process = proto.add_processes();
        for (int i=0; i<(int)record.size(); i++) {
            if (!SetTableField(process, header[i], record[i])) {
                fprintf(stderr, "[%s]Line %d has bad value %s of %s\n",
                        this->name.string(), nline, header[i].c_str(), record[i].c_str());
            }
        }
    }

    if (!reader.ok(line)) {
        fprintf(stderr, "Bad read from fd %d: %s\n", in, line.c_str());
        return -1;
    }

    if (!proto.SerializeToFileDescriptor(out)) {
        fprintf(stderr, "[%s]Error writing proto back\n", this->name.string());
        return -1;
    }
    fprintf(stderr, "[%s]Proto size: %d bytes\n", this->name.string(), proto.ByteSize());
    return NO_ERROR;
}