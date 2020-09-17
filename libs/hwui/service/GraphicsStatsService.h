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

#include <string>

#include "JankTracker.h"
#include "utils/Macros.h"
#include <stats_pull_atom_callback.h>

namespace android {
namespace uirenderer {
namespace protos {
class GraphicsStatsProto;
}

/*
 * The exported entry points used by GraphicsStatsService.java in f/b/services/core
 *
 * NOTE: Avoid exporting a requirement on the protobuf itself. Keep the usage
 * of the generated protobuf classes internal to libhwui.so to minimize library
 * bloat.
 */
class GraphicsStatsService {
public:
    class Dump;
    enum class DumpType {
        Text,
        Protobuf,
        ProtobufStatsd,
    };

    ANDROID_API static void saveBuffer(const std::string& path, const std::string& package,
                                       int64_t versionCode, int64_t startTime, int64_t endTime,
                                       const ProfileData* data);

    ANDROID_API static Dump* createDump(int outFd, DumpType type);
    ANDROID_API static void addToDump(Dump* dump, const std::string& path,
                                      const std::string& package, int64_t versionCode,
                                      int64_t startTime, int64_t endTime, const ProfileData* data);
    ANDROID_API static void addToDump(Dump* dump, const std::string& path);
    ANDROID_API static void finishDump(Dump* dump);
    ANDROID_API static void finishDumpInMemory(Dump* dump, AStatsEventList* data,
                                               bool lastFullDay);

    // Visible for testing
    static bool parseFromFile(const std::string& path, protos::GraphicsStatsProto* output);
};

} /* namespace uirenderer */
} /* namespace android */
