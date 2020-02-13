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
#include <incident/incident_report.h>

#include <gtest/gtest.h>

namespace android {
namespace os {
namespace statsd {

TEST(IncidentReportRequestTest, testWrite) {
    IncidentReportRequest request;
    request.setAll(0);
    request.addSection(1000);
    request.addSection(1001);

    vector<uint8_t> header1;
    header1.push_back(0x1);
    header1.push_back(0x2);
    vector<uint8_t> header2;
    header1.push_back(0x22);
    header1.push_back(0x33);

    request.addHeader(header1);
    request.addHeader(header2);

    request.setPrivacyPolicy(1);

    request.setReceiverPackage("com.android.os");
    request.setReceiverClass("com.android.os.Receiver");

    IncidentReportArgs* args = reinterpret_cast<IncidentReportArgs*>(request.getImpl());

    EXPECT_EQ(0, args->all());
    set<int> sections;
    sections.insert(1000);
    sections.insert(1001);
    EXPECT_EQ(sections, args->sections());
    EXPECT_EQ(1, args->getPrivacyPolicy());

    EXPECT_EQ(string("com.android.os"), args->receiverPkg());
    EXPECT_EQ(string("com.android.os.Receiver"), args->receiverCls());

    vector<vector<uint8_t>> headers;
    headers.push_back(header1);
    headers.push_back(header2);
    EXPECT_EQ(headers, args->headers());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
