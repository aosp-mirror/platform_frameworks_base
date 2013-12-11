/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "ZipUtils_test"
#include <utils/Log.h>
#include <androidfw/ZipUtils.h>

#include <gtest/gtest.h>

#include <fcntl.h>
#include <string.h>

namespace android {

class ZipUtilsTest : public testing::Test {
protected:
    virtual void SetUp() {
    }

    virtual void TearDown() {
    }
};

TEST_F(ZipUtilsTest, ZipTimeConvertSuccess) {
    struct tm t;

    // 2011-06-29 14:40:40
    long when = 0x3EDD7514;

    ZipUtils::zipTimeToTimespec(when, &t);

    EXPECT_EQ(2011, t.tm_year + 1900)
            << "Year was improperly converted.";

    EXPECT_EQ(6, t.tm_mon)
            << "Month was improperly converted.";

    EXPECT_EQ(29, t.tm_mday)
            << "Day was improperly converted.";

    EXPECT_EQ(14, t.tm_hour)
            << "Hour was improperly converted.";

    EXPECT_EQ(40, t.tm_min)
            << "Minute was improperly converted.";

    EXPECT_EQ(40, t.tm_sec)
            << "Second was improperly converted.";
}

}
