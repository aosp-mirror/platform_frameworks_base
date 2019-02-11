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

#include <android/os/IncidentReportArgs.h>

#include <gtest/gtest.h>

namespace android {
namespace os {
namespace statsd {

TEST(IncidentReportArgsTest, testSerialization) {
    IncidentReportArgs args;
    args.setAll(0);
    args.addSection(1000);
    args.addSection(1001);

    vector<uint8_t> header1;
    header1.push_back(0x1);
    header1.push_back(0x2);
    vector<uint8_t> header2;
    header1.push_back(0x22);
    header1.push_back(0x33);

    args.addHeader(header1);
    args.addHeader(header2);

    args.setPrivacyPolicy(1);

    args.setReceiverPkg("com.android.os");
    args.setReceiverCls("com.android.os.Receiver");

    Parcel out;
    status_t err = args.writeToParcel(&out);
    EXPECT_EQ(NO_ERROR, err);

    out.setDataPosition(0);

    IncidentReportArgs args2;
    err = args2.readFromParcel(&out);
    EXPECT_EQ(NO_ERROR, err);

    EXPECT_EQ(0, args2.all());
    set<int> sections;
    sections.insert(1000);
    sections.insert(1001);
    EXPECT_EQ(sections, args2.sections());
    EXPECT_EQ(1, args2.getPrivacyPolicy());

    EXPECT_EQ(string("com.android.os"), args2.receiverPkg());
    EXPECT_EQ(string("com.android.os.Receiver"), args2.receiverCls());

    vector<vector<uint8_t>> headers;
    headers.push_back(header1);
    headers.push_back(header2);
    EXPECT_EQ(headers, args2.headers());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
