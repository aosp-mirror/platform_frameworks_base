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

#pragma once

#include <android/util/ProtoOutputStream.h>
#include "FieldValue.h"
#include "HashableDimensionKey.h"
#include "frameworks/base/cmds/statsd/src/stats_log_common.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "guardrail/StatsdStats.h"

namespace android {
namespace os {
namespace statsd {

void writeFieldValueTreeToStream(int tagId, const std::vector<FieldValue>& values,
                                 util::ProtoOutputStream* protoOutput);
void writeDimensionToProto(const HashableDimensionKey& dimension,
                           util::ProtoOutputStream* protoOutput);

// Convert the TimeUnit enum to the bucket size in millis with a guardrail on
// bucket size.
int64_t TimeUnitToBucketSizeInMillisGuardrailed(int uid, TimeUnit unit);

// Convert the TimeUnit enum to the bucket size in millis.
int64_t TimeUnitToBucketSizeInMillis(TimeUnit unit);

// Gets the elapsed timestamp in ns.
int64_t getElapsedRealtimeNs();

// Gets the elapsed timestamp in millis.
int64_t getElapsedRealtimeMillis();

// Gets the elapsed timestamp in seconds.
int64_t getElapsedRealtimeSec();

// Gets the wall clock timestamp in ns.
int64_t getWallClockNs();

// Gets the wall clock timestamp in millis.
int64_t getWallClockMillis();

// Gets the wall clock timestamp in seconds.
int64_t getWallClockSec();

// Helper function to write PulledAtomStats to ProtoOutputStream
void writePullerStatsToStream(const std::pair<int, StatsdStats::PulledAtomStats>& pair,
                              util::ProtoOutputStream* protoOutput);

template<class T>
bool parseProtoOutputStream(util::ProtoOutputStream& protoOutput, T* message) {
    std::string pbBytes;
    auto iter = protoOutput.data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
         pbBytes.append(reinterpret_cast<const char*>(iter.readBuffer()), toRead);
        iter.rp()->move(toRead);
    }
    return message->ParseFromArray(pbBytes.c_str(), pbBytes.size());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
