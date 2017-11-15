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
#include <google/protobuf/message.h>
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

    string getSerializedString(::google::protobuf::Message& message) {
        string expectedStr;
        message.SerializeToFileDescriptor(tf.fd);
        ReadFileToString(tf.path, &expectedStr);
        return expectedStr;
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

    expected.set_aaudio_hw_burst_min_usec(2000);
    expected.set_aaudio_mmap_exclusive_policy(2);
    expected.set_dalvik_vm_appimageformat("lz4");
    expected.set_gsm_operator_isroaming(false);
    expected.set_init_svc_vendor_imsqmidaemon(SystemPropertiesProto_Status_STATUS_RUNNING);
    expected.set_init_svc_vendor_init_radio_sh(SystemPropertiesProto_Status_STATUS_STOPPED);
    expected.set_net_dns1("2001:4860:4860::8844");
    expected.add_net_tcp_buffersize_wifi(524288);
    expected.add_net_tcp_buffersize_wifi(2097152);
    expected.add_net_tcp_buffersize_wifi(4194304);
    expected.add_net_tcp_buffersize_wifi(262144);
    expected.add_net_tcp_buffersize_wifi(524288);
    expected.add_net_tcp_buffersize_wifi(1048576);
    expected.set_nfc_initialized(true);
    expected.set_persist_radio_vt_enable(1);
    expected.add_ro_boot_boottime("1BLL:85");
    expected.add_ro_boot_boottime("1BLE:898");
    expected.add_ro_boot_boottime("2BLL:0");
    expected.add_ro_boot_boottime("2BLE:862");
    expected.add_ro_boot_boottime("SW:6739");
    expected.add_ro_boot_boottime("KL:340");
    expected.set_ro_bootimage_build_date_utc(1509394807LL);
    expected.set_ro_bootimage_build_fingerprint("google/marlin/marlin:P/MASTER/jinyithu10301320:eng/dev-keys");

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), getSerializedString(expected));
    close(fd);
}
