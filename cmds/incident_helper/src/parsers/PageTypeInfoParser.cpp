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

#include "frameworks/base/core/proto/android/os/pagetypeinfo.proto.h"
#include "ih_util.h"
#include "PageTypeInfoParser.h"

using namespace android::os;

status_t
PageTypeInfoParser::Parse(const int in, const int out) const
{
    Reader reader(in);
    string line;
    bool migrateTypeSession = false;
    int pageBlockOrder;
    header_t blockHeader;

    ProtoOutputStream proto;
    Table table(PageTypeInfoProto::Block::_FIELD_NAMES,
            PageTypeInfoProto::Block::_FIELD_IDS,
            PageTypeInfoProto::Block::_FIELD_COUNT);

    while (reader.readLine(&line)) {
        if (line.empty()) {
            migrateTypeSession = false;
            blockHeader.clear();
            continue;
        }

        if (stripPrefix(&line, "Page block order:")) {
            pageBlockOrder = toInt(line);
            proto.write(PageTypeInfoProto::PAGE_BLOCK_ORDER, pageBlockOrder);
            continue;
        }
        if (stripPrefix(&line, "Pages per block:")) {
            proto.write(PageTypeInfoProto::PAGES_PER_BLOCK, toInt(line));
            continue;
        }
        if (stripPrefix(&line, "Free pages count per migrate type at order")) {
            migrateTypeSession = true;
            continue;
        }
        if (stripPrefix(&line, "Number of blocks type")) {
            blockHeader = parseHeader(line);
            continue;
        }

        record_t record = parseRecord(line, COMMA_DELIMITER);
        if (migrateTypeSession && record.size() == 3) {
            uint64_t token = proto.start(PageTypeInfoProto::MIGRATE_TYPES);
            // expect part 0 starts with "Node"
            if (stripPrefix(&record[0], "Node")) {
                proto.write(PageTypeInfoProto::MigrateType::NODE, toInt(record[0]));
            } else return BAD_VALUE;
            // expect part 1 starts with "zone"
            if (stripPrefix(&record[1], "zone")) {
                proto.write(PageTypeInfoProto::MigrateType::ZONE, record[1]);
            } else return BAD_VALUE;
            // expect part 2 starts with "type"
            if (stripPrefix(&record[2], "type")) {
                // An example looks like:
                // header line:      type    0   1   2 3 4 5 6 7 8 9 10
                // record line: Unmovable  426 279 226 1 1 1 0 0 2 2  0
                record_t pageCounts = parseRecord(record[2]);

                proto.write(PageTypeInfoProto::MigrateType::TYPE, pageCounts[0]);
                for (size_t i=1; i<pageCounts.size(); i++) {
                    proto.write(PageTypeInfoProto::MigrateType::FREE_PAGES_COUNT, toInt(pageCounts[i]));
                }
            } else return BAD_VALUE;

            proto.end(token);
        } else if (!blockHeader.empty() && record.size() == 2) {
            uint64_t token = proto.start(PageTypeInfoProto::BLOCKS);
            if (stripPrefix(&record[0], "Node")) {
                proto.write(PageTypeInfoProto::Block::NODE, toInt(record[0]));
            } else return BAD_VALUE;

            if (stripPrefix(&record[1], "zone")) {
                record_t blockCounts = parseRecord(record[1]);
                proto.write(PageTypeInfoProto::Block::ZONE, blockCounts[0]);

                for (size_t i=0; i<blockHeader.size(); i++) {
                    if (!table.insertField(&proto, blockHeader[i], blockCounts[i+1])) {
                        fprintf(stderr, "Header %s has bad data %s\n", blockHeader[i].c_str(),
                            blockCounts[i+1].c_str());
                    }
                }
            } else return BAD_VALUE;
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
