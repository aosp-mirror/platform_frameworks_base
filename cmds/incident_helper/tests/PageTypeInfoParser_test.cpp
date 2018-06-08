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

#include "PageTypeInfoParser.h"

#include "frameworks/base/core/proto/android/os/pagetypeinfo.pb.h"

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

class PageTypeInfoParserTest : public Test {
public:
    virtual void SetUp() override {
        ASSERT_TRUE(tf.fd != -1);
    }

protected:
    TemporaryFile tf;

    const string kTestPath = GetExecutableDirectory();
    const string kTestDataPath = kTestPath + "/testdata/";
};

TEST_F(PageTypeInfoParserTest, Success) {
    const string testFile = kTestDataPath + "pagetypeinfo.txt";
    PageTypeInfoParser parser;
    PageTypeInfoProto expected;

    expected.set_page_block_order(10);
    expected.set_pages_per_block(1024);

    PageTypeInfoProto::MigrateType* mt1 = expected.add_migrate_types();
    mt1->set_node(0);
    mt1->set_zone("DMA");
    mt1->set_type("Unmovable");
    int arr1[] = { 426, 279, 226, 1, 1, 1, 0, 0, 2, 2, 0};
    for (auto i=0; i<11; i++) {
        mt1->add_free_pages_count(arr1[i]);
    }

    PageTypeInfoProto::MigrateType* mt2 = expected.add_migrate_types();
    mt2->set_node(0);
    mt2->set_zone("Normal");
    mt2->set_type("Reclaimable");
    int arr2[] = { 953, 773, 437, 154, 92, 26, 15, 14, 12, 7, 0};
    for (auto i=0; i<11; i++) {
        mt2->add_free_pages_count(arr2[i]);
    }

    PageTypeInfoProto::Block* block1 = expected.add_blocks();
    block1->set_node(0);
    block1->set_zone("DMA");
    block1->set_unmovable(74);
    block1->set_reclaimable(9);
    block1->set_movable(337);
    block1->set_cma(41);
    block1->set_reserve(1);
    block1->set_isolate(0);


    PageTypeInfoProto::Block* block2 = expected.add_blocks();
    block2->set_node(0);
    block2->set_zone("Normal");
    block2->set_unmovable(70);
    block2->set_reclaimable(12);
    block2->set_movable(423);
    block2->set_cma(0);
    block2->set_reserve(1);
    block2->set_isolate(0);

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), expected.SerializeAsString());
    close(fd);
}
