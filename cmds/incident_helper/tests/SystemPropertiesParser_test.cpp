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

#include "SystemPropertiesParser.h"

#include "frameworks/base/core/proto/android/os/system_properties.pb.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <gmock/gmock.h>
#include <google/protobuf/message_lite.h>
#include <gtest/gtest.h>
#include <string.h>
#include <fcntl.h>

using namespace android::base;
using namespace android::os;
using namespace std;
using ::testing::StrEq;
using ::testing::Test;
using ::testing::internal::CaptureStderr;
using ::testing::internal::CaptureStdout;
using ::testing::internal::GetCapturedStderr;
using ::testing::internal::GetCapturedStdout;

class SystemPropertiesParserTest : public Test {
public:
    virtual void SetUp() override {
        ASSERT_TRUE(tf.fd != -1);
    }

protected:
    TemporaryFile tf;

    const string kTestPath = GetExecutableDirectory();
    const string kTestDataPath = kTestPath + "/testdata/";
};

TEST_F(SystemPropertiesParserTest, HasSwapInfo) {
    const string testFile = kTestDataPath + "system_properties.txt";
    SystemPropertiesParser parser;
    SystemPropertiesProto expected;

    expected.mutable_aac_drc()->set_cut(123);
    expected.mutable_aaudio()->set_hw_burst_min_usec(2000);
    expected.mutable_aaudio()->set_mmap_exclusive_policy(2);
    expected.mutable_dalvik_vm()->set_appimageformat("lz4");
    expected.set_drm_64bit_enabled(false);
    expected.mutable_init_svc()->set_adbd(
        SystemPropertiesProto_InitSvc_Status_STATUS_RUNNING);
    expected.mutable_init_svc()->set_lmkd(
        SystemPropertiesProto_InitSvc_Status_STATUS_STOPPED);
    expected.set_media_mediadrmservice_enable(true);

    SystemPropertiesProto::Ro* ro = expected.mutable_ro();
    ro->mutable_boot()->add_boottime("1BLL:85");
    ro->mutable_boot()->add_boottime("1BLE:898");
    ro->mutable_boot()->add_boottime("2BLL:0");
    ro->mutable_boot()->add_boottime("2BLE:862");
    ro->mutable_boot()->add_boottime("SW:6739");
    ro->mutable_boot()->add_boottime("KL:340");
    ro->mutable_bootimage()->set_build_date_utc(1509394807LL);
    ro->mutable_bootimage()->set_build_fingerprint(
        "google/marlin/marlin:P/MASTER/jinyithu10301320:eng/dev-keys");
    ro->mutable_hardware()->set_value("marlin");
    ro->mutable_hardware()->set_power("marlin-profile");
    ro->mutable_product()->add_cpu_abilist("arm64-v8a");
    ro->mutable_product()->add_cpu_abilist("armeabi-v7a");
    ro->mutable_product()->add_cpu_abilist("armeabi");
    ro->mutable_product()->mutable_vendor()->set_brand("google");

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), expected.SerializeAsString());
    close(fd);
}
