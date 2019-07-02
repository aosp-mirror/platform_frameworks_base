// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
#define DEBUG false
#include "Log.h"

#include <android-base/file.h>
#include <android/util/ProtoFileReader.h>
#include <android/util/ProtoOutputStream.h>
#include <android/util/protobuf.h>
#include <fcntl.h>
#include <gtest/gtest.h>
#include <signal.h>
#include <string.h>

#include "FdBuffer.h"
#include "incidentd_util.h"

using namespace android;
using namespace android::base;
using namespace android::os::incidentd;
using ::testing::Test;

const std::string kTestPath = GetExecutableDirectory();
const std::string kTestDataPath = kTestPath + "/testdata/";

status_t read(sp<ProtoFileReader> reader, size_t size) {
    uint8_t const* buf;
    while (size > 0 && (buf = reader->readBuffer()) != nullptr) {
        size_t amt = reader->currentToRead();
        if (size < amt) {
            amt = size;
        }
        reader->move(amt);
        size -= amt;
    }

    return NO_ERROR;
}

TEST(ProtoFileReaderTest, ParseOneLevel) {
    const std::string testFile = kTestDataPath + "protoFile.txt";
    size_t msg1Size = 10;
    size_t msg2Size = 5 * 1024;
    {
        // Create a proto file
        // TestProto {
        //    optional Section1 section1 = 1;
        //    optional Section2 section2 = 2;
        // }

        unique_fd fd(open(testFile.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR));
        ASSERT_NE(fd.get(), -1);
        ProtoOutputStream proto;
        string field1;
        field1.resize(msg1Size, 'h');
        string field2;
        field2.resize(msg2Size, 'a');
        proto.write(FIELD_TYPE_MESSAGE | 1, field1.data(), field1.length());
        proto.write(FIELD_TYPE_MESSAGE | 2, field2.data(), field2.length());
        proto.flush(fd);
    }

    int fd = open(testFile.c_str(), O_RDONLY | O_CLOEXEC);
    ASSERT_NE(fd, -1);

    status_t err;
    sp<ProtoFileReader> reader = new ProtoFileReader(fd);
    int i = 0;
    size_t msg_size[2];
    while (reader->hasNext()) {
        uint64_t fieldTag = reader->readRawVarint();
        uint32_t fieldId = read_field_id(fieldTag);
        uint8_t wireType = read_wire_type(fieldTag);
        ASSERT_EQ(WIRE_TYPE_LENGTH_DELIMITED, wireType);
        size_t sectionSize = reader->readRawVarint();
        if (i < 2) {
            msg_size[i] = sectionSize;
        }
        err = read(reader, sectionSize);
        ASSERT_EQ(NO_ERROR, err);
        i++;
    }

    ASSERT_EQ(2, i);

    ASSERT_EQ(msg1Size, msg_size[0]);
    ASSERT_EQ(msg2Size, msg_size[1]);
    close(fd);
}
