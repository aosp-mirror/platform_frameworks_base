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
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "guardrail/StatsdStats.h"
#include "statslog.h"

namespace android {
namespace os {
namespace statsd {

void writeFieldValueTreeToStream(int tagId, const std::vector<FieldValue>& values,
                                 util::ProtoOutputStream* protoOutput);
void writeDimensionToProto(const HashableDimensionKey& dimension, std::set<string> *str_set,
                           util::ProtoOutputStream* protoOutput);

void writeDimensionLeafNodesToProto(const HashableDimensionKey& dimension,
                                    const int dimensionLeafFieldId,
                                    std::set<string> *str_set,
                                    util::ProtoOutputStream* protoOutput);

void writeDimensionPathToProto(const std::vector<Matcher>& fieldMatchers,
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

int64_t NanoToMillis(const int64_t nano);

int64_t MillisToNano(const int64_t millis);

// Helper function to write PulledAtomStats to ProtoOutputStream
void writePullerStatsToStream(const std::pair<int, StatsdStats::PulledAtomStats>& pair,
                              util::ProtoOutputStream* protoOutput);

// Helper function to write AtomMetricStats to ProtoOutputStream
void writeAtomMetricStatsToStream(const std::pair<int64_t, StatsdStats::AtomMetricStats> &pair,
                                  util::ProtoOutputStream *protoOutput);

template<class T>
bool parseProtoOutputStream(util::ProtoOutputStream& protoOutput, T* message) {
    std::string pbBytes;
    sp<android::util::ProtoReader> reader = protoOutput.data();
    while (reader->readBuffer() != NULL) {
        size_t toRead = reader->currentToRead();
         pbBytes.append(reinterpret_cast<const char*>(reader->readBuffer()), toRead);
        reader->move(toRead);
    }
    return message->ParseFromArray(pbBytes.c_str(), pbBytes.size());
}

// Checks the blacklist of atoms as well as the blacklisted range of 300,000 - 304,999.
// Returns the truncated timestamp to the nearest 5 minutes if needed.
int64_t truncateTimestampIfNecessary(int atomId, int64_t timestampNs);

inline bool isVendorPulledAtom(int atomId) {
    return atomId >= StatsdStats::kVendorPulledAtomStartTag && atomId < StatsdStats::kMaxAtomTag;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
