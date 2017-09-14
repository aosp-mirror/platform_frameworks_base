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
#include "frameworks/base/core/proto/android/os/pagetypeinfo.pb.h"
#include "frameworks/base/core/proto/android/os/procrank.pb.h"

#include <android-base/file.h>
#include <unistd.h>
#include <string>
#include <vector>

using namespace android::base;
using namespace android::os;
using namespace google::protobuf;
using namespace std;


static const string TAB_DELIMITER = "\t";
static const string COMMA_DELIMITER = ",";

static inline int toInt(const string& s) {
    return atoi(s.c_str());
}

static inline long toLong(const string& s) {
    return atol(s.c_str());
}

/**
 * Sets the given protobuf message when the field name matches one of the
 * fields. It is useful to set values to proto from table-like plain texts.
 */
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
            reflection->SetInt64(message, field, toLong(field_value));
            return true;
        case FieldDescriptor::TYPE_UINT64:
            reflection->SetUInt64(message, field, toLong(field_value));
            return true;
        case FieldDescriptor::TYPE_INT32:
            reflection->SetInt32(message, field, toInt(field_value));
            return true;
        case FieldDescriptor::TYPE_UINT32:
            reflection->SetUInt32(message, field, toInt(field_value));
            return true;
        default:
            // Add new scalar types
            return false;
    }
}

// ================================================================================
status_t NoopParser::Parse(const int in, const int out) const
{
    string content;
    if (!ReadFdToString(in, &content)) {
        fprintf(stderr, "[%s]Failed to read data from incidentd\n", this->name.string());
        return -1;
    }
    if (!WriteStringToFd(content, out)) {
        fprintf(stderr, "[%s]Failed to write data to incidentd\n", this->name.string());
        return -1;
    }
    return NO_ERROR;
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
status_t KernelWakesParser::Parse(const int in, const int out) const {
    Reader reader(in);
    string line;
    header_t header;  // the header of /d/wakeup_sources
    record_t record;  // retain each record
    int nline = 0;

    KernelWakeSources proto;

    // parse line by line
    while (reader.readLine(&line)) {
        if (line.empty()) continue;
        // parse head line
        if (nline++ == 0) {
            header = parseHeader(line, TAB_DELIMITER);
            continue;
        }

        // parse for each record, the line delimiter is \t only!
        record = parseRecord(line, TAB_DELIMITER);

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

    if (!reader.ok(&line)) {
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
    string line;
    header_t header;  // the header of /d/wakeup_sources
    record_t record;  // retain each record
    int nline = 0;

    Procrank proto;

    // parse line by line
    while (reader.readLine(&line)) {
        if (line.empty()) continue;

        // parse head line
        if (nline++ == 0) {
            header = parseHeader(line);
            continue;
        }

        if (hasPrefix(&line, "ZRAM:")) {
            proto.mutable_summary()->mutable_zram()->set_raw_text(line);
            continue;
        }
        if (hasPrefix(&line, "RAM:")) {
            proto.mutable_summary()->mutable_ram()->set_raw_text(line);
            continue;
        }

        record = parseRecord(line);
        if (record.size() != header.size()) {
            if (record[record.size() - 1] == "TOTAL") { // TOTAL record
                ProcessProto* total = proto.mutable_summary()->mutable_total();
                for (int i=1; i<=(int)record.size(); i++) {
                    SetTableField(total, header[header.size() - i], record[record.size() - i]);
                }
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

    if (!reader.ok(&line)) {
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
status_t PageTypeInfoParser::Parse(const int in, const int out) const {
    Reader reader(in);
    string line;
    bool migrateTypeSession = false;
    int pageBlockOrder;
    header_t blockHeader;

    PageTypeInfo pageTypeInfo;

    while (reader.readLine(&line)) {
        if (line.empty()) {
            migrateTypeSession = false;
            blockHeader.clear();
            continue;
        }

        if (hasPrefix(&line, "Page block order:")) {
            pageBlockOrder = toInt(line);
            pageTypeInfo.set_page_block_order(pageBlockOrder);
            continue;
        }
        if (hasPrefix(&line, "Pages per block:")) {
            pageTypeInfo.set_pages_per_block(toInt(line));
            continue;
        }
        if (hasPrefix(&line, "Free pages count per migrate type at order")) {
            migrateTypeSession = true;
            continue;
        }
        if (hasPrefix(&line, "Number of blocks type")) {
            blockHeader = parseHeader(line);
            continue;
        }

        record_t record = parseRecord(line, COMMA_DELIMITER);
        if (migrateTypeSession && record.size() == 3) {
            MigrateTypeProto* migrateType = pageTypeInfo.add_migrate_types();
            // expect part 0 starts with "Node"
            if (hasPrefix(&record[0], "Node")) {
                migrateType->set_node(toInt(record[0]));
            } else goto ERROR;
            // expect part 1 starts with "zone"
            if (hasPrefix(&record[1], "zone")) {
                migrateType->set_zone(record[1]);
            } else goto ERROR;
            // expect part 2 starts with "type"
            if (hasPrefix(&record[2], "type")) {
                // expect the rest of part 2 has number of (pageBlockOrder + 2) parts
                // An example looks like:
                // header line:      type    0   1   2 3 4 5 6 7 8 9 10
                // record line: Unmovable  426 279 226 1 1 1 0 0 2 2  0
                // The pageBlockOrder = 10 and it's zero-indexed. so total parts
                // are 10 + 1(zero-indexed) + 1(the type part) = 12.
                record_t pageCounts = parseRecord(record[2]);
                int pageCountsSize = pageBlockOrder + 2;
                if ((int)pageCounts.size() != pageCountsSize) goto ERROR;

                migrateType->set_type(pageCounts[0]);
                for (auto i=1; i<pageCountsSize; i++) {
                    migrateType->add_free_pages_count(toInt(pageCounts[i]));
                }
            } else goto ERROR;
            continue;
        }

        if (!blockHeader.empty() && record.size() == 2) {
            BlockProto* block = pageTypeInfo.add_blocks();

            if (hasPrefix(&record[0], "Node")) {
                block->set_node(toInt(record[0]));
            } else goto ERROR;

            if (hasPrefix(&record[1], "zone")) {
                record_t blockCounts = parseRecord(record[1]);
                block->set_zone(blockCounts[0]);
                for (size_t i=0; i<blockHeader.size(); i++) {
                    if (!SetTableField(block, blockHeader[i], blockCounts[i+1])) goto ERROR;
                }
            } else goto ERROR;

            continue;
        }

ERROR:  // print out error for this single line and continue parsing
        fprintf(stderr, "[%s]Bad line: %s\n", this->name.string(), line.c_str());
    }

    if (!reader.ok(&line)) {
        fprintf(stderr, "Bad read from fd %d: %s\n", in, line.c_str());
        return -1;
    }

    if (!pageTypeInfo.SerializeToFileDescriptor(out)) {
        fprintf(stderr, "[%s]Error writing proto back\n", this->name.string());
        return -1;
    }

    fprintf(stderr, "[%s]Proto size: %d bytes\n", this->name.string(), pageTypeInfo.ByteSize());
    return NO_ERROR;
}
