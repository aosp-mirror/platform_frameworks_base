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

#include "Section.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <android/os/IncidentReportArgs.h>
#include <android/util/protobuf.h>
#include <frameworks/base/core/proto/android/os/incident.pb.h>
#include <frameworks/base/core/proto/android/os/header.pb.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <string.h>

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace android::os;
using namespace android::os::incidentd;
using namespace android::util;
using ::testing::StrEq;
using ::testing::Test;
using ::testing::internal::CaptureStdout;
using ::testing::internal::GetCapturedStdout;

const int TIMEOUT_PARSER = -1;
const int NOOP_PARSER = 0;
const int REVERSE_PARSER = 1;

const int QUICK_TIMEOUT_MS = 100;

const std::string VARINT_FIELD_1 = "\x08\x96\x01";  // 150
const std::string STRING_FIELD_2 = "\x12\vandroidwins";
const std::string FIX64_FIELD_3 = "\x19\xff\xff\xff\xff\xff\xff\xff\xff";  // -1

// NOTICE: this test requires /system/bin/incident_helper is installed.
class SectionTest : public Test {
public:
    virtual void SetUp() override { ASSERT_NE(tf.fd, -1); }

    void printDebugString(std::string s) {
        fprintf(stderr, "size: %zu\n", s.length());
        for (size_t i = 0; i < s.length(); i++) {
            char c = s[i];
            fprintf(stderr, "\\x%x", c);
        }
        fprintf(stderr, "\n");
    }

protected:
    TemporaryFile tf;

    const std::string kTestPath = GetExecutableDirectory();
    const std::string kTestDataPath = kTestPath + "/testdata/";
};

class SimpleListener : public IIncidentReportStatusListener {
public:
    SimpleListener(){};
    virtual ~SimpleListener(){};

    virtual Status onReportStarted() { return Status::ok(); };
    virtual Status onReportSectionStatus(int /*section*/, int /*status*/)
            { return Status::ok(); };
    virtual Status onReportFinished() { return Status::ok(); };
    virtual Status onReportFailed() { return Status::ok(); };

protected:
    virtual IBinder* onAsBinder() override { return nullptr; };
};

/*
TEST_F(SectionTest, MetadataSection) {
    MetadataSection ms;

    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(STDOUT_FILENO);

    requestSet.setMainPrivacyPolicy(android::os::PRIVACY_POLICY_LOCAL);
    requestSet.editSectionStats(1)->set_success(true);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, ms.Execute(&requestSet));

    string out = GetCapturedStdout();
    IncidentProto expectedIncident;
    expectedIncident.ParseFromArray(out.data(), out.size());
    ASSERT_TRUE(expectedIncident.has_metadata());
    const IncidentMetadata& expectedMetadata = expectedIncident.metadata();
    ASSERT_EQ(IncidentMetadata::LOCAL, expectedMetadata.dest());
    ASSERT_EQ(1, expectedMetadata.sections_size());
    ASSERT_EQ(1, expectedMetadata.sections(0).id());
    ASSERT_TRUE(expectedMetadata.sections(0).has_success());
    ASSERT_TRUE(expectedMetadata.sections(0).success());
}

TEST_F(SectionTest, FileSection) {
    FileSection fs(REVERSE_PARSER, tf.path);

    ASSERT_TRUE(WriteStringToFile("iamtestdata", tf.path));

    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requestSet));
    // The input string is reversed in incident helper
    // The length is 11, in 128Varint it is "0000 1011" -> \v
    EXPECT_THAT(GetCapturedStdout(), StrEq("\xa\vatadtsetmai"));
}

TEST_F(SectionTest, FileSectionNotExist) {
    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);

    FileSection fs1(NOOP_PARSER, "notexist", QUICK_TIMEOUT_MS);
    ASSERT_EQ(NO_ERROR, fs1.Execute(&requestSet));

    FileSection fs2(NOOP_PARSER, "notexist", QUICK_TIMEOUT_MS);
    ASSERT_EQ(NO_ERROR, fs2.Execute(&requestSet));
}

TEST_F(SectionTest, FileSectionTimeout) {
    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);

    FileSection fs(TIMEOUT_PARSER, tf.path, QUICK_TIMEOUT_MS);
    ASSERT_EQ(NO_ERROR, fs.Execute(&requestSet));
    ASSERT_TRUE(requestSet.getSectionStats(TIMEOUT_PARSER)->timed_out());
}

TEST_F(SectionTest, GZipSection) {
    const std::string testFile = kTestDataPath + "kmsg.txt";
    const std::string testGzFile = testFile + ".gz";
    GZipSection gs(NOOP_PARSER, "/tmp/nonexist", testFile.c_str(), NULL);

    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(tf.fd);
    requestSet.setMainPrivacyPolicy(android::os::PRIVACY_POLICY_LOCAL);

    ASSERT_EQ(NO_ERROR, gs.Execute(&requestSet));
    std::string expected, gzFile, actual;
    ASSERT_TRUE(ReadFileToString(testGzFile, &gzFile));
    ASSERT_TRUE(ReadFileToString(tf.path, &actual));
    // generates the expected protobuf result.
    size_t fileLen = testFile.size();
    size_t totalLen = 1 + get_varint_size(fileLen) + fileLen + 3 + gzFile.size();
    uint8_t header[20];
    header[0] = '\x2';  // header 0 << 3 + 2
    uint8_t* ptr = write_raw_varint(header + 1, totalLen);
    *ptr = '\n';  // header 1 << 3 + 2
    ptr = write_raw_varint(++ptr, fileLen);
    expected.assign((const char*)header, ptr - header);
    expected += testFile + "\x12\x9F\x6" + gzFile;
    EXPECT_THAT(actual, StrEq(expected));
}

TEST_F(SectionTest, GZipSectionNoFileFound) {
    GZipSection gs(NOOP_PARSER, "/tmp/nonexist1", "/tmp/nonexist2", NULL);
    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(STDOUT_FILENO);
    ASSERT_EQ(NO_ERROR, gs.Execute(&requestSet));
}

TEST_F(SectionTest, CommandSectionConstructor) {
    CommandSection cs1(1, "echo", "\"this is a test\"", "ooo", NULL);
    CommandSection cs2(2, "single_command", NULL);
    CommandSection cs3(1, 3123, "echo", "\"this is a test\"", "ooo", NULL);
    CommandSection cs4(2, 43214, "single_command", NULL);

    EXPECT_THAT(cs1.name.string(), StrEq("cmd echo \"this is a test\" ooo"));
    EXPECT_THAT(cs2.name.string(), StrEq("cmd single_command"));
    EXPECT_EQ(3123, cs3.timeoutMs);
    EXPECT_EQ(43214, cs4.timeoutMs);
    EXPECT_THAT(cs3.name.string(), StrEq("cmd echo \"this is a test\" ooo"));
    EXPECT_THAT(cs4.name.string(), StrEq("cmd single_command"));
}

TEST_F(SectionTest, CommandSectionEcho) {
    CommandSection cs(REVERSE_PARSER, "/system/bin/echo", "about", NULL);
    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(STDOUT_FILENO);
    CaptureStdout();
    ASSERT_EQ(NO_ERROR, cs.Execute(&requestSet));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\xa\x06\ntuoba"));
}

TEST_F(SectionTest, CommandSectionCommandTimeout) {
    CommandSection cs(NOOP_PARSER, QUICK_TIMEOUT_MS, "/system/bin/yes", NULL);
    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    ASSERT_EQ(NO_ERROR, cs.Execute(&requestSet));
    ASSERT_TRUE(requestSet.getSectionStats(NOOP_PARSER)->timed_out());
}

TEST_F(SectionTest, CommandSectionIncidentHelperTimeout) {
    CommandSection cs(TIMEOUT_PARSER, QUICK_TIMEOUT_MS, "/system/bin/echo", "about", NULL);
    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(STDOUT_FILENO);
    ASSERT_EQ(NO_ERROR, cs.Execute(&requestSet));
    ASSERT_TRUE(requestSet.getSectionStats(TIMEOUT_PARSER)->timed_out());
}

TEST_F(SectionTest, CommandSectionBadCommand) {
    CommandSection cs(NOOP_PARSER, "echoo", "about", NULL);
    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    ASSERT_EQ(NAME_NOT_FOUND, cs.Execute(&requestSet));
}

TEST_F(SectionTest, CommandSectionBadCommandAndTimeout) {
    CommandSection cs(TIMEOUT_PARSER, QUICK_TIMEOUT_MS, "nonexistcommand", "-opt", NULL);
    // timeout will return first
    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    ASSERT_EQ(NO_ERROR, cs.Execute(&requestSet));
    ASSERT_TRUE(requestSet.getSectionStats(TIMEOUT_PARSER)->timed_out());
}

TEST_F(SectionTest, LogSectionBinary) {
    LogSection ls(1, LOG_ID_EVENTS);
    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(STDOUT_FILENO);
    CaptureStdout();
    ASSERT_EQ(NO_ERROR, ls.Execute(&requestSet));
    std::string results = GetCapturedStdout();
    EXPECT_FALSE(results.empty());
}

TEST_F(SectionTest, LogSectionSystem) {
    LogSection ls(1, LOG_ID_SYSTEM);
    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(STDOUT_FILENO);
    CaptureStdout();
    ASSERT_EQ(NO_ERROR, ls.Execute(&requestSet));
    std::string results = GetCapturedStdout();
    EXPECT_FALSE(results.empty());
}

TEST_F(SectionTest, TestFilterPiiTaggedFields) {
    FileSection fs(NOOP_PARSER, tf.path);

    ASSERT_TRUE(WriteStringToFile(VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3, tf.path));

    vector<sp<ReportRequest>> requests;
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requestSet));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x02\r" + STRING_FIELD_2));
}

TEST_F(SectionTest, TestBadFdRequest) {
    FileSection fs(NOOP_PARSER, tf.path);
    ASSERT_TRUE(WriteStringToFile(VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3, tf.path));

    IncidentReportArgs args;
    args.setAll(true);
    args.setPrivacyPolicy(0);
    sp<ReportRequest> badFdRequest = new ReportRequest(args, new SimpleListener(), 1234567);

    vector<sp<ReportRequest>> requests;
    requests.push_back(badFdRequest);
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requestSet));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x02\r" + STRING_FIELD_2));
    EXPECT_EQ(badFdRequest->err, -EBADF);
}

TEST_F(SectionTest, TestBadRequests) {
    FileSection fs(NOOP_PARSER, tf.path);
    ASSERT_TRUE(WriteStringToFile(VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3, tf.path));

    IncidentReportArgs args;
    args.setAll(true);
    args.setPrivacyPolicy(0);

    vector<sp<ReportRequest>> requests;
    requests.push_back(new ReportRequest(args, new SimpleListener(), -1));
    ReportRequestSet requestSet(requests);

    EXPECT_EQ(fs.Execute(&requestSet), -EBADF);
}

TEST_F(SectionTest, TestMultipleRequests) {
    TemporaryFile output1, output2, output3;
    FileSection fs(NOOP_PARSER, tf.path);

    ASSERT_TRUE(output1.fd != -1);
    ASSERT_TRUE(output2.fd != -1);
    ASSERT_TRUE(output3.fd != -1);
    ASSERT_TRUE(WriteStringToFile(VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3, tf.path));

    IncidentReportArgs args1, args2, args3;
    args1.setAll(true);
    args1.setPrivacyPolicy(android::os::PRIVACY_POLICY_LOCAL);
    args2.setAll(true);
    args2.setPrivacyPolicy(android::os::PRIVACY_POLICY_EXPLICIT);
    sp<SimpleListener> l = new SimpleListener();

    vector<sp<ReportRequest>> requests;
    requests.push_back(new ReportRequest(args1, l, output1.fd));
    requests.push_back(new ReportRequest(args2, l, output2.fd));
    requests.push_back(new ReportRequest(args3, l, output3.fd));
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requestSet));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x02\r" + STRING_FIELD_2));

    std::string content, expect;
    expect = VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3;
    char c = (char)expect.size();
    EXPECT_TRUE(ReadFileToString(output1.path, &content));
    EXPECT_THAT(content, StrEq(string("\x02") + c + expect));

    expect = STRING_FIELD_2 + FIX64_FIELD_3;
    c = (char)expect.size();
    EXPECT_TRUE(ReadFileToString(output2.path, &content));
    EXPECT_THAT(content, StrEq(string("\x02") + c + expect));

    // because args3 doesn't set section, so it should receive nothing
    EXPECT_TRUE(ReadFileToString(output3.path, &content));
    EXPECT_THAT(content, StrEq(""));
}

TEST_F(SectionTest, TestMultipleRequestsBySpec) {
    TemporaryFile output1, output2, output3;
    FileSection fs(NOOP_PARSER, tf.path);

    ASSERT_TRUE(output1.fd != -1);
    ASSERT_TRUE(output2.fd != -1);
    ASSERT_TRUE(output3.fd != -1);

    ASSERT_TRUE(WriteStringToFile(VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3, tf.path));

    IncidentReportArgs args1, args2, args3;
    args1.setAll(true);
    args1.setPrivacyPolicy(android::os::PRIVACY_POLICY_EXPLICIT);
    args2.setAll(true);
    args2.setPrivacyPolicy(android::os::PRIVACY_POLICY_EXPLICIT);
    args3.setAll(true);
    sp<SimpleListener> l = new SimpleListener();

    vector<sp<ReportRequest>> requests;
    requests.push_back(new ReportRequest(args1, l, output1.fd));
    requests.push_back(new ReportRequest(args2, l, output2.fd));
    requests.push_back(new ReportRequest(args3, l, output3.fd));
    ReportRequestSet requestSet(requests);
    requestSet.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requestSet));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x02\r" + STRING_FIELD_2));

    std::string content, expect;
    expect = STRING_FIELD_2 + FIX64_FIELD_3;
    char c = (char)expect.size();

    // output1 and output2 are the same
    EXPECT_TRUE(ReadFileToString(output1.path, &content));
    EXPECT_THAT(content, StrEq(string("\x02") + c + expect));
    EXPECT_TRUE(ReadFileToString(output2.path, &content));
    EXPECT_THAT(content, StrEq(string("\x02") + c + expect));

    // output3 has only auto field
    c = (char)STRING_FIELD_2.size();
    EXPECT_TRUE(ReadFileToString(output3.path, &content));
    EXPECT_THAT(content, StrEq(string("\x02") + c + STRING_FIELD_2));
}
*/
