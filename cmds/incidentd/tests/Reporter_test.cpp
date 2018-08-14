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
#include <frameworks/base/libs/incident/proto/android/os/header.pb.h>

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <dirent.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <string.h>

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace android::os;
using namespace android::os::incidentd;
using namespace std;
using ::testing::StrEq;
using ::testing::Test;

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

protected:
    virtual IBinder* onAsBinder() override { return nullptr; };
};

class ReporterTest : public Test {
public:
    virtual void SetUp() {
        reporter = new Reporter(td.path);
        l = new TestListener();
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
    ReportRequestSet requests;
    sp<Reporter> reporter;
    sp<TestListener> l;
    size_t size;
};

TEST_F(ReporterTest, IncidentReportArgs) {
    IncidentReportArgs args1, args2;
    args1.addSection(1);
    args2.addSection(3);

    args1.merge(args2);
    ASSERT_TRUE(args1.containsSection(1));
    ASSERT_FALSE(args1.containsSection(2));
    ASSERT_TRUE(args1.containsSection(3));
}

TEST_F(ReporterTest, ReportRequestSetEmpty) {
    requests.setMainFd(STDOUT_FILENO);
    ASSERT_EQ(requests.mainFd(), STDOUT_FILENO);
}

TEST_F(ReporterTest, RunReportEmpty) {
    ASSERT_EQ(Reporter::REPORT_FINISHED, reporter->runReport(&size));
    EXPECT_EQ(l->startInvoked, 0);
    EXPECT_EQ(l->finishInvoked, 0);
    EXPECT_TRUE(l->startSections.empty());
    EXPECT_TRUE(l->finishSections.empty());
    EXPECT_EQ(l->failedInvoked, 0);
}

TEST_F(ReporterTest, RunReportWithHeaders) {
    TemporaryFile tf;
    IncidentReportArgs args1, args2;
    args1.addSection(1);
    args2.addSection(2);
    IncidentHeaderProto header;
    header.set_alert_id(12);
    args2.addHeader(header);
    sp<ReportRequest> r1 = new ReportRequest(args1, l, tf.fd);
    sp<ReportRequest> r2 = new ReportRequest(args2, l, tf.fd);

    reporter->batch.add(r1);
    reporter->batch.add(r2);

    ASSERT_EQ(Reporter::REPORT_FINISHED, reporter->runReport(&size));

    string result;
    ReadFileToString(tf.path, &result);
    EXPECT_THAT(result, StrEq("\n\x2"
                              "\b\f"));

    EXPECT_EQ(l->startInvoked, 2);
    EXPECT_EQ(l->finishInvoked, 2);
    EXPECT_TRUE(l->startSections.empty());
    EXPECT_TRUE(l->finishSections.empty());
    EXPECT_EQ(l->failedInvoked, 0);
}

TEST_F(ReporterTest, RunReportToGivenDirectory) {
    IncidentReportArgs args;
    IncidentHeaderProto header1, header2;
    header1.set_alert_id(12);
    header2.set_reason("abcd");
    args.addHeader(header1);
    args.addHeader(header2);
    sp<ReportRequest> r = new ReportRequest(args, l, -1);
    reporter->batch.add(r);

    ASSERT_EQ(Reporter::REPORT_FINISHED, reporter->runReport(&size));
    vector<string> results = InspectFiles();
    ASSERT_EQ((int)results.size(), 1);
    EXPECT_EQ(results[0],
              "\n\x2"
              "\b\f\n\x6"
              "\x12\x4"
              "abcd");
}

TEST_F(ReporterTest, ReportMetadata) {
    IncidentReportArgs args;
    args.addSection(1);
    args.setDest(android::os::DEST_EXPLICIT);
    sp<ReportRequest> r = new ReportRequest(args, l, -1);
    reporter->batch.add(r);

    ASSERT_EQ(Reporter::REPORT_FINISHED, reporter->runReport(&size));
    IncidentMetadata metadata = reporter->batch.metadata();
    EXPECT_EQ(IncidentMetadata_Destination_EXPLICIT, metadata.dest());
    EXPECT_EQ(1, metadata.request_size());
    EXPECT_TRUE(metadata.use_dropbox());
    EXPECT_EQ(0, metadata.sections_size());
}