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

#include "EventLogTagsParser.h"

#include "frameworks/base/core/proto/android/util/event_log_tags.pb.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <gmock/gmock.h>
#include <google/protobuf/message_lite.h>
#include <gtest/gtest.h>
#include <string.h>
#include <fcntl.h>

using namespace android::base;
using namespace android::util;
using namespace std;
using ::testing::StrEq;
using ::testing::Test;
using ::testing::internal::CaptureStderr;
using ::testing::internal::CaptureStdout;
using ::testing::internal::GetCapturedStderr;
using ::testing::internal::GetCapturedStdout;

class EventLogTagsParserTest : public Test {
public:
    virtual void SetUp() override {
        ASSERT_TRUE(tf.fd != -1);
    }

protected:
    TemporaryFile tf;

    const string kTestPath = GetExecutableDirectory();
    const string kTestDataPath = kTestPath + "/testdata/";
};

TEST_F(EventLogTagsParserTest, Success) {
    const string testFile = kTestDataPath + "event-log-tags.txt";

    EventLogTagsParser parser;
    EventLogTagMapProto expected;

    EventLogTag* eventLogTag;
    EventLogTag::ValueDescriptor* desp;

    eventLogTag = expected.add_event_log_tags();
    eventLogTag->set_tag_number(42);
    eventLogTag->set_tag_name("answer");
    desp = eventLogTag->add_value_descriptors();
    desp->set_name("to life the universe etc");
    desp->set_type(EventLogTag_ValueDescriptor_DataType_STRING);

    eventLogTag = expected.add_event_log_tags();
    eventLogTag->set_tag_number(314);
    eventLogTag->set_tag_name("pi");

    eventLogTag = expected.add_event_log_tags();
    eventLogTag->set_tag_number(1004);
    eventLogTag->set_tag_name("chatty");
    desp = eventLogTag->add_value_descriptors();
    desp->set_name("dropped");
    desp->set_type(EventLogTag_ValueDescriptor_DataType_STRING);

    eventLogTag = expected.add_event_log_tags();
    eventLogTag->set_tag_number(1005);
    eventLogTag->set_tag_name("tag_def");
    desp = eventLogTag->add_value_descriptors();
    desp->set_name("tag");
    desp->set_type(EventLogTag_ValueDescriptor_DataType_INT);
    desp = eventLogTag->add_value_descriptors();
    desp->set_name("name");
    desp->set_type(EventLogTag_ValueDescriptor_DataType_STRING);
    desp = eventLogTag->add_value_descriptors();
    desp->set_name("format");
    desp->set_type(EventLogTag_ValueDescriptor_DataType_STRING);

    eventLogTag = expected.add_event_log_tags();
    eventLogTag->set_tag_number(2747);
    eventLogTag->set_tag_name("contacts_aggregation");
    desp = eventLogTag->add_value_descriptors();
    desp->set_name("aggregation time");
    desp->set_type(EventLogTag_ValueDescriptor_DataType_LONG);
    desp->set_unit(EventLogTag_ValueDescriptor_DataUnit_MILLISECONDS);
    desp = eventLogTag->add_value_descriptors();
    desp->set_name("count");
    desp->set_type(EventLogTag_ValueDescriptor_DataType_INT);
    desp->set_unit(EventLogTag_ValueDescriptor_DataUnit_OBJECTS);

    eventLogTag = expected.add_event_log_tags();
    eventLogTag->set_tag_number(1397638484);
    eventLogTag->set_tag_name("snet_event_log");
    desp = eventLogTag->add_value_descriptors();
    desp->set_name("subtag");
    desp->set_type(EventLogTag_ValueDescriptor_DataType_STRING);
    desp = eventLogTag->add_value_descriptors();
    desp->set_name("uid");
    desp->set_type(EventLogTag_ValueDescriptor_DataType_INT);
    desp = eventLogTag->add_value_descriptors();
    desp->set_name("message");
    desp->set_type(EventLogTag_ValueDescriptor_DataType_STRING);
    desp->set_unit(EventLogTag_ValueDescriptor_DataUnit_SECONDS);

    int fd = open(testFile.c_str(), O_RDONLY);
    ASSERT_TRUE(fd != -1);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, parser.Parse(fd, STDOUT_FILENO));
    EXPECT_EQ(GetCapturedStdout(), expected.SerializeAsString());
    close(fd);
}
