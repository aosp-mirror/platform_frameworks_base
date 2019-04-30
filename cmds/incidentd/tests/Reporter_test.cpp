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

#include "Reporter.h"

#include <android/os/BnIncidentReportStatusListener.h>
#include <frameworks/base/core/proto/android/os/header.pb.h>

#include <dirent.h>
#include <string.h>

#include <android-base/file.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace android::os;
using namespace android::os::incidentd;
using namespace std;
using ::testing::StrEq;
using ::testing::Test;

namespace {
/*
void getHeaderData(const IncidentHeaderProto& headerProto, vector<uint8_t>* out) {
    out->clear();
    auto serialized = headerProto.SerializeAsString();
    if (serialized.empty()) return;
    out->resize(serialized.length());
    std::copy(serialized.begin(), serialized.end(), out->begin());
}
*/
}

class TestListener : public IIncidentReportStatusListener {
public:
    int startInvoked;
    int finishInvoked;
    int failedInvoked;
    map<int, int> startSections;
    map<int, int> finishSections;

    TestListener() : startInvoked(0), finishInvoked(0), failedInvoked(0){};
    virtual ~TestListener(){};

    virtual Status onReportStarted() {
        startInvoked++;
        return Status::ok();
    };
    virtual Status onReportSectionStatus(int section, int status) {
        switch (status) {
            case IIncidentReportStatusListener::STATUS_STARTING:
                if (startSections.count(section) == 0) startSections[section] = 0;
                startSections[section] = startSections[section] + 1;
                break;
            case IIncidentReportStatusListener::STATUS_FINISHED:
                if (finishSections.count(section) == 0) finishSections[section] = 0;
                finishSections[section] = finishSections[section] + 1;
                break;
        }
        return Status::ok();
    };
    virtual Status onReportFinished() {
        finishInvoked++;
        return Status::ok();
    };
    virtual Status onReportFailed() {
        failedInvoked++;
        return Status::ok();
    };

    int sectionStarted(int sectionId) const {
        map<int, int>::const_iterator found = startSections.find(sectionId);
        if (found != startSections.end()) {
            return found->second;
        } else {
            return 0;
        }
    };

    int sectionFinished(int sectionId) const {
        map<int, int>::const_iterator found = finishSections.find(sectionId);
        if (found != finishSections.end()) {
            return found->second;
        } else {
            return 0;
        }
    };

protected:
    virtual IBinder* onAsBinder() override { return nullptr; };
};

class ReporterTest : public Test {
public:
    virtual void SetUp() {
        listener = new TestListener();
    }

    vector<string> InspectFiles() {
        DIR* dir;
        struct dirent* entry;
        vector<string> results;

        string dirbase = string(td.path) + "/";
        dir = opendir(td.path);

        while ((entry = readdir(dir)) != NULL) {
            if (entry->d_name[0] == '.') {
                continue;
            }
            string filename = dirbase + entry->d_name;
            string content;
            ReadFileToString(filename, &content);
            results.push_back(content);
        }
        return results;
    }

protected:
    TemporaryDir td;
    sp<TestListener> listener;
    size_t size;
};

TEST_F(ReporterTest, IncidentReportArgs) {
    IncidentReportArgs args1, args2;
    args1.addSection(1);
    args2.addSection(3);

    args1.merge(args2);
    ASSERT_TRUE(args1.containsSection(1, false));
    ASSERT_FALSE(args1.containsSection(2, false));
    ASSERT_TRUE(args1.containsSection(3, false));
}

/*
TEST_F(ReporterTest, RunReportEmpty) {
    vector<sp<ReportRequest>> requests;
    sp<Reporter> reporter = new Reporter(requests, td.path);

    ASSERT_EQ(Reporter::REPORT_FINISHED, reporter->runReport(&size));
    EXPECT_EQ(0, listener->startInvoked);
    EXPECT_EQ(0, listener->finishInvoked);
    EXPECT_TRUE(listener->startSections.empty());
    EXPECT_TRUE(listener->finishSections.empty());
    EXPECT_EQ(0, listener->failedInvoked);
}

TEST_F(ReporterTest, RunReportWithHeaders) {
    TemporaryFile tf;
    IncidentReportArgs args1, args2;
    args1.addSection(1);
    args2.addSection(2);
    IncidentHeaderProto header;
    header.set_alert_id(12);

    vector<uint8_t> out;
    getHeaderData(header, &out);
    args2.addHeader(out);

    sp<WorkDirectory> workDirectory = new WorkDirectory(td.path);
    sp<ReportBatch> batch = new ReportBatch();
    batch->addStreamingReport(args1, listener, tf.fd);
    sp<Reporter> reporter = new Reporter(workDirectory, batch);

    ASSERT_EQ(Reporter::REPORT_FINISHED, reporter->runReport(&size));

    string result;
    ReadFileToString(tf.path, &result);
    EXPECT_THAT(result, StrEq("\n\x2"
                              "\b\f"));

    EXPECT_EQ(listener->startInvoked, 1);
    EXPECT_EQ(listener->finishInvoked, 1);
    EXPECT_FALSE(listener->startSections.empty());
    EXPECT_FALSE(listener->finishSections.empty());
    EXPECT_EQ(listener->failedInvoked, 0);
}

TEST_F(ReporterTest, RunReportToGivenDirectory) {
    IncidentReportArgs args;
    IncidentHeaderProto header1, header2;
    header1.set_alert_id(12);
    header2.set_reason("abcd");

    vector<uint8_t> out;
    getHeaderData(header1, &out);
    args.addHeader(out);
    getHeaderData(header2, &out);
    args.addHeader(out);

    vector<sp<ReportRequest>> requests;
    requests.push_back(new ReportRequest(args, listener, -1));
    sp<Reporter> reporter = new Reporter(requests, td.path);

    ASSERT_EQ(Reporter::REPORT_FINISHED, reporter->runReport(&size));
    vector<string> results = InspectFiles();
    ASSERT_EQ(results.size(), 1UL);
    EXPECT_EQ(results[0],
              "\n\x2"
              "\b\f\n\x6"
              "\x12\x4"
              "abcd");
}

TEST_F(ReporterTest, ReportMetadata) {
    IncidentReportArgs args;
    args.addSection(1);
    args.setPrivacyPolicy(android::os::PRIVACY_POLICY_EXPLICIT);
    vector<sp<ReportRequest>> requests;
    requests.push_back(new ReportRequest(args, listener, -1));
    sp<Reporter> reporter = new Reporter(requests, td.path);

    ASSERT_EQ(Reporter::REPORT_FINISHED, reporter->runReport(&size));
    IncidentMetadata metadata = reporter->metadata();
    EXPECT_EQ(IncidentMetadata_Destination_EXPLICIT, metadata.dest());
    EXPECT_EQ(1, metadata.request_size());
    EXPECT_TRUE(metadata.use_dropbox());
    EXPECT_EQ(0, metadata.sections_size());
}

TEST_F(ReporterTest, RunReportLocal_1_2) {
    IncidentReportArgs args;
    args.addSection(1);
    args.addSection(2);
    args.setPrivacyPolicy(android::os::PRIVACY_POLICY_LOCAL);

    vector<sp<ReportRequest>> requests;
    requests.push_back(new ReportRequest(args, listener, -1));
    sp<Reporter> reporter = new Reporter(requests, td.path);

    ASSERT_EQ(Reporter::REPORT_FINISHED, reporter->runReport(&size));

    EXPECT_EQ(1, listener->sectionStarted(1));
    EXPECT_EQ(1, listener->sectionFinished(1));
    EXPECT_EQ(1, listener->sectionStarted(2));
    EXPECT_EQ(1, listener->sectionFinished(2));

    // TODO: validate that a file was created in the directory
}
*/
