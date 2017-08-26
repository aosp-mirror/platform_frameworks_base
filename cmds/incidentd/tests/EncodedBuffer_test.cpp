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

#include "EncodedBuffer.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <string.h>

using namespace android;
using namespace android::base;
using namespace std;
using ::testing::StrEq;
using ::testing::Test;
using ::testing::internal::CaptureStdout;
using ::testing::internal::GetCapturedStdout;

const uint8_t LOCAL = 0;
const uint8_t EXPLICIT = 1;
const uint8_t AUTOMATIC = 2;

const uint8_t OTHER_TYPE = 1;
const uint8_t STRING_TYPE = 9;
const uint8_t MESSAGE_TYPE = 11;
const string STRING_FIELD_0 = "\x02\viamtestdata";
const string VARINT_FIELD_1 = "\x08\x96\x01"; // 150
const string STRING_FIELD_2 = "\x12\vwhatthefuck";
const string FIX64_FIELD_3 = "\x19\xff\xff\xff\xff\xff\xff\xff\xff"; // -1
const string FIX32_FIELD_4 = "\x25\xff\xff\xff\xff"; // -1
const string MESSAGE_FIELD_5 = "\x2a\x10" + VARINT_FIELD_1 + STRING_FIELD_2;

class EncodedBufferTest : public Test {
public:
    virtual void SetUp() override {
        ASSERT_NE(tf.fd, -1);
    }

    void writeToFdBuffer(string str) {
        ASSERT_TRUE(WriteStringToFile(str, tf.path, false));
        ASSERT_EQ(NO_ERROR, buffer.read(tf.fd, 10000));
    }

    void assertBuffer(EncodedBuffer& buf, string expected) {
        ASSERT_EQ(buf.size(), expected.size());
        CaptureStdout();
        ASSERT_EQ(buf.flush(STDOUT_FILENO), NO_ERROR);
        ASSERT_THAT(GetCapturedStdout(), StrEq(expected));
    }

    void assertStrip(uint8_t dest, string expected, Privacy* policy) {
        PrivacySpec spec(dest);
        EncodedBuffer encodedBuf(buffer, policy);
        ASSERT_EQ(encodedBuf.strip(spec), NO_ERROR);
        assertBuffer(encodedBuf, expected);
    }

    void assertStripByFields(uint8_t dest, string expected, int size, Privacy* privacy, ...) {
        Privacy* list[size+1];
        list[0] = privacy;
        va_list args;
        va_start(args, privacy);
        for (int i=1; i<size; i++) {
            Privacy* p = va_arg(args, Privacy*);
            list[i] = p;
        }
        va_end(args);
        list[size] = NULL;
        assertStrip(dest, expected, new Privacy(300, const_cast<const Privacy**>(list)));
    }

    FdBuffer buffer;
private:
    TemporaryFile tf;
};

TEST_F(EncodedBufferTest, NullFieldPolicy) {
    writeToFdBuffer(STRING_FIELD_0);
    assertStrip(EXPLICIT, STRING_FIELD_0, new Privacy(300, NULL));
}

TEST_F(EncodedBufferTest, StripSpecNotAllowed) {
    writeToFdBuffer(STRING_FIELD_0);
    assertStripByFields(AUTOMATIC, "", 1, new Privacy(0, STRING_TYPE, EXPLICIT));
}

TEST_F(EncodedBufferTest, StripVarintField) {
    writeToFdBuffer(VARINT_FIELD_1);
    assertStripByFields(EXPLICIT, "", 1, new Privacy(1, OTHER_TYPE, LOCAL));
}

TEST_F(EncodedBufferTest, StripLengthDelimitedField_String) {
    writeToFdBuffer(STRING_FIELD_2);
    assertStripByFields(EXPLICIT, "", 1, new Privacy(2, STRING_TYPE, LOCAL));
}

TEST_F(EncodedBufferTest, StripFixed64Field) {
    writeToFdBuffer(FIX64_FIELD_3);
    assertStripByFields(EXPLICIT, "", 1, new Privacy(3, OTHER_TYPE, LOCAL));
}

TEST_F(EncodedBufferTest, StripFixed32Field) {
    writeToFdBuffer(FIX32_FIELD_4);
    assertStripByFields(EXPLICIT, "", 1, new Privacy(4, OTHER_TYPE, LOCAL));
}

TEST_F(EncodedBufferTest, StripLengthDelimitedField_Message) {
    writeToFdBuffer(MESSAGE_FIELD_5);
    assertStripByFields(EXPLICIT, "", 1, new Privacy(5, MESSAGE_TYPE, LOCAL));
}

TEST_F(EncodedBufferTest, NoStripVarintField) {
    writeToFdBuffer(VARINT_FIELD_1);
    assertStripByFields(EXPLICIT, VARINT_FIELD_1, 1, new Privacy(1, OTHER_TYPE, AUTOMATIC));
}

TEST_F(EncodedBufferTest, NoStripLengthDelimitedField_String) {
    writeToFdBuffer(STRING_FIELD_2);
    assertStripByFields(EXPLICIT, STRING_FIELD_2, 1, new Privacy(2, STRING_TYPE, AUTOMATIC));
}

TEST_F(EncodedBufferTest, NoStripFixed64Field) {
    writeToFdBuffer(FIX64_FIELD_3);
    assertStripByFields(EXPLICIT, FIX64_FIELD_3, 1, new Privacy(3, OTHER_TYPE, AUTOMATIC));
}

TEST_F(EncodedBufferTest, NoStripFixed32Field) {
    writeToFdBuffer(FIX32_FIELD_4);
    assertStripByFields(EXPLICIT, FIX32_FIELD_4, 1, new Privacy(4, OTHER_TYPE, AUTOMATIC));
}

TEST_F(EncodedBufferTest, NoStripLengthDelimitedField_Message) {
    writeToFdBuffer(MESSAGE_FIELD_5);
    assertStripByFields(EXPLICIT, MESSAGE_FIELD_5, 1, new Privacy(5, MESSAGE_TYPE, AUTOMATIC));
}

TEST_F(EncodedBufferTest, StripVarintAndString) {
    writeToFdBuffer(STRING_FIELD_0 + VARINT_FIELD_1 + STRING_FIELD_2
            + FIX64_FIELD_3 + FIX32_FIELD_4);
    string expected = STRING_FIELD_0 + FIX64_FIELD_3 + FIX32_FIELD_4;
    assertStripByFields(EXPLICIT, expected, 2,
            new Privacy(1, OTHER_TYPE, LOCAL), new Privacy(2, STRING_TYPE, LOCAL));
}

TEST_F(EncodedBufferTest, StripVarintAndFixed64) {
    writeToFdBuffer(STRING_FIELD_0 + VARINT_FIELD_1 + STRING_FIELD_2
            + FIX64_FIELD_3 + FIX32_FIELD_4);
    string expected = STRING_FIELD_0 + STRING_FIELD_2 + FIX32_FIELD_4;
    assertStripByFields(EXPLICIT, expected, 2,
            new Privacy(1, OTHER_TYPE, LOCAL), new Privacy(3, OTHER_TYPE, LOCAL));
}

TEST_F(EncodedBufferTest, StripVarintInNestedMessage) {
    writeToFdBuffer(STRING_FIELD_0 + MESSAGE_FIELD_5);
    const Privacy* list[] = { new Privacy(1, OTHER_TYPE, LOCAL), NULL };
    string expected = STRING_FIELD_0 + "\x2a\xd" + STRING_FIELD_2;
    assertStripByFields(EXPLICIT, expected, 1, new Privacy(5, list));
}

TEST_F(EncodedBufferTest, StripFix64AndVarintInNestedMessage) {
    writeToFdBuffer(STRING_FIELD_0 + FIX64_FIELD_3 + MESSAGE_FIELD_5);
    const Privacy* list[] = { new Privacy(1, OTHER_TYPE, LOCAL), NULL };
    string expected = STRING_FIELD_0 + "\x2a\xd" + STRING_FIELD_2;
    assertStripByFields(EXPLICIT, expected, 2, new Privacy(3, OTHER_TYPE, LOCAL), new Privacy(5, list));
}

TEST_F(EncodedBufferTest, ClearAndStrip) {
    string data = STRING_FIELD_0 + VARINT_FIELD_1;
    writeToFdBuffer(data);
    const Privacy* list[] = { new Privacy(1, OTHER_TYPE, LOCAL), NULL };
    EncodedBuffer encodedBuf(buffer, new Privacy(300, list));
    PrivacySpec spec1(EXPLICIT), spec2(LOCAL);

    ASSERT_EQ(encodedBuf.strip(spec1), NO_ERROR);
    assertBuffer(encodedBuf, STRING_FIELD_0);
    ASSERT_EQ(encodedBuf.strip(spec2), NO_ERROR);
    assertBuffer(encodedBuf, data);
}

TEST_F(EncodedBufferTest, BadDataInFdBuffer) {
    writeToFdBuffer("iambaddata");
    const Privacy* list[] = { new Privacy(4, OTHER_TYPE, AUTOMATIC), NULL };
    EncodedBuffer encodedBuf(buffer, new Privacy(300, list));
    PrivacySpec spec;
    ASSERT_EQ(encodedBuf.strip(spec), BAD_VALUE);
}

TEST_F(EncodedBufferTest, BadDataInNestedMessage) {
    writeToFdBuffer(STRING_FIELD_0 + MESSAGE_FIELD_5 + "aoeoe");
    const Privacy* list[] = { new Privacy(1, OTHER_TYPE, LOCAL), NULL };
    const Privacy* field5[] = { new Privacy(5, list), NULL };
    EncodedBuffer encodedBuf(buffer, new Privacy(300, field5));
    PrivacySpec spec;
    ASSERT_EQ(encodedBuf.strip(spec), BAD_VALUE);
}
