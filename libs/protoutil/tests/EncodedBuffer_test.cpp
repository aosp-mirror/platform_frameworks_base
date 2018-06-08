// Copyright (C) 2018 The Android Open Source Project
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
#include <android/util/EncodedBuffer.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

using namespace android::util;

TEST(EncodedBufferTest, ReadVarint) {
    EncodedBuffer buffer;
    uint64_t val = UINT64_C(1522865904593);
    buffer.writeRawVarint64(val);
    EXPECT_EQ(val, buffer.begin().readRawVarint());
}
