/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <android-base/macros.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "protos/graphicsstats.pb.h"
#include "service/GraphicsStatsService.h"

using namespace android;
using namespace android::uirenderer;

std::string findRootPath() {
    char path[1024];
    ssize_t r = readlink("/proc/self/exe", path, 1024);
    // < 1023 because we need room for the null terminator
    if (r <= 0 || r > 1023) {
        int err = errno;
        fprintf(stderr, "Failed to read from /proc/self/exe; r=%zd, err=%d (%s)\n", r, err,
                strerror(err));
        exit(EXIT_FAILURE);
    }
    while (--r > 0) {
        if (path[r] == '/') {
            path[r] = '\0';
            return std::string(path);
        }
    }
    return std::string();
}

// No code left untested
TEST(GraphicsStats, findRootPath) {
    // Different tools/infrastructure seem to push this to different locations. It shouldn't really
    // matter where the binary is, so add new locations here as needed. This test still seems good
    // as it's nice to understand the possibility space, and ensure findRootPath continues working
    // as expected.
    std::string acceptableLocations[] = {"/data/nativetest/hwui_unit_tests",
                                         "/data/nativetest64/hwui_unit_tests",
                                         "/data/local/tmp/nativetest/hwui_unit_tests/" ABI_STRING};
    EXPECT_THAT(acceptableLocations, ::testing::Contains(findRootPath()));
}

TEST(GraphicsStats, saveLoad) {
    std::string path = findRootPath() + "/test_saveLoad";
    std::string packageName = "com.test.saveLoad";
    MockProfileData mockData;
    mockData.editJankFrameCount() = 20;
    mockData.editTotalFrameCount() = 100;
    mockData.editStatStartTime() = 10000;
    // Fill with patterned data we can recognize but which won't map to a
    // memset or basic for iteration count
    for (size_t i = 0; i < mockData.editFrameCounts().size(); i++) {
        mockData.editFrameCounts()[i] = ((i % 10) + 1) * 2;
    }
    for (size_t i = 0; i < mockData.editSlowFrameCounts().size(); i++) {
        mockData.editSlowFrameCounts()[i] = (i % 5) + 1;
    }
    GraphicsStatsService::saveBuffer(path, packageName, 5, 3000, 7000, &mockData);
    protos::GraphicsStatsProto loadedProto;
    EXPECT_TRUE(GraphicsStatsService::parseFromFile(path, &loadedProto));
    // Clean up the file
    unlink(path.c_str());

    EXPECT_EQ(packageName, loadedProto.package_name());
    EXPECT_EQ(5, loadedProto.version_code());
    EXPECT_EQ(3000, loadedProto.stats_start());
    EXPECT_EQ(7000, loadedProto.stats_end());
    // ASSERT here so we don't continue with a nullptr deref crash if this is false
    ASSERT_TRUE(loadedProto.has_summary());
    EXPECT_EQ(20, loadedProto.summary().janky_frames());
    EXPECT_EQ(100, loadedProto.summary().total_frames());
    EXPECT_EQ(mockData.editFrameCounts().size() + mockData.editSlowFrameCounts().size(),
              (size_t)loadedProto.histogram_size());
    for (size_t i = 0; i < (size_t)loadedProto.histogram_size(); i++) {
        int expectedCount, expectedBucket;
        if (i < mockData.editFrameCounts().size()) {
            expectedCount = ((i % 10) + 1) * 2;
            expectedBucket = ProfileData::frameTimeForFrameCountIndex(i);
        } else {
            int temp = i - mockData.editFrameCounts().size();
            expectedCount = (temp % 5) + 1;
            expectedBucket = ProfileData::frameTimeForSlowFrameCountIndex(temp);
        }
        EXPECT_EQ(expectedCount, loadedProto.histogram().Get(i).frame_count());
        EXPECT_EQ(expectedBucket, loadedProto.histogram().Get(i).render_millis());
    }
}

TEST(GraphicsStats, merge) {
    std::string path = findRootPath() + "/test_merge";
    std::string packageName = "com.test.merge";
    MockProfileData mockData;
    mockData.editJankFrameCount() = 20;
    mockData.editTotalFrameCount() = 100;
    mockData.editStatStartTime() = 10000;
    // Fill with patterned data we can recognize but which won't map to a
    // memset or basic for iteration count
    for (size_t i = 0; i < mockData.editFrameCounts().size(); i++) {
        mockData.editFrameCounts()[i] = ((i % 10) + 1) * 2;
    }
    for (size_t i = 0; i < mockData.editSlowFrameCounts().size(); i++) {
        mockData.editSlowFrameCounts()[i] = (i % 5) + 1;
    }
    GraphicsStatsService::saveBuffer(path, packageName, 5, 3000, 7000, &mockData);
    mockData.editJankFrameCount() = 50;
    mockData.editTotalFrameCount() = 500;
    for (size_t i = 0; i < mockData.editFrameCounts().size(); i++) {
        mockData.editFrameCounts()[i] = (i % 5) + 1;
    }
    for (size_t i = 0; i < mockData.editSlowFrameCounts().size(); i++) {
        mockData.editSlowFrameCounts()[i] = ((i % 10) + 1) * 2;
    }
    GraphicsStatsService::saveBuffer(path, packageName, 5, 7050, 10000, &mockData);

    protos::GraphicsStatsProto loadedProto;
    EXPECT_TRUE(GraphicsStatsService::parseFromFile(path, &loadedProto));
    // Clean up the file
    unlink(path.c_str());

    EXPECT_EQ(packageName, loadedProto.package_name());
    EXPECT_EQ(5, loadedProto.version_code());
    EXPECT_EQ(3000, loadedProto.stats_start());
    EXPECT_EQ(10000, loadedProto.stats_end());
    // ASSERT here so we don't continue with a nullptr deref crash if this is false
    ASSERT_TRUE(loadedProto.has_summary());
    EXPECT_EQ(20 + 50, loadedProto.summary().janky_frames());
    EXPECT_EQ(100 + 500, loadedProto.summary().total_frames());
    EXPECT_EQ(mockData.editFrameCounts().size() + mockData.editSlowFrameCounts().size(),
              (size_t)loadedProto.histogram_size());
    for (size_t i = 0; i < (size_t)loadedProto.histogram_size(); i++) {
        int expectedCount, expectedBucket;
        if (i < mockData.editFrameCounts().size()) {
            expectedCount = ((i % 10) + 1) * 2;
            expectedCount += (i % 5) + 1;
            expectedBucket = ProfileData::frameTimeForFrameCountIndex(i);
        } else {
            int temp = i - mockData.editFrameCounts().size();
            expectedCount = (temp % 5) + 1;
            expectedCount += ((temp % 10) + 1) * 2;
            expectedBucket = ProfileData::frameTimeForSlowFrameCountIndex(temp);
        }
        EXPECT_EQ(expectedCount, loadedProto.histogram().Get(i).frame_count());
        EXPECT_EQ(expectedBucket, loadedProto.histogram().Get(i).render_millis());
    }
}
