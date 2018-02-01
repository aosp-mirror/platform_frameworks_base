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
#include "Log.h"

#include "Section.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <android/os/IncidentReportArgs.h>
#include <frameworks/base/libs/incident/proto/android/os/header.pb.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <string.h>

const int TIMEOUT_PARSER = -1;
const int NOOP_PARSER = 0;
const int REVERSE_PARSER = 1;

const int QUICK_TIMEOUT_MS = 100;

const string VARINT_FIELD_1 = "\x08\x96\x01";  // 150
const string STRING_FIELD_2 = "\x12\vwhatthefuck";
const string FIX64_FIELD_3 = "\x19\xff\xff\xff\xff\xff\xff\xff\xff";  // -1

using namespace android::base;
using namespace android::binder;
using namespace android::os;
using namespace std;
using ::testing::StrEq;
using ::testing::internal::CaptureStdout;
using ::testing::internal::GetCapturedStdout;

// NOTICE: this test requires /system/bin/incident_helper is installed.

class SimpleListener : public IIncidentReportStatusListener {
public:
    SimpleListener(){};
    virtual ~SimpleListener(){};

    virtual Status onReportStarted() { return Status::ok(); };
    virtual Status onReportSectionStatus(int /*section*/, int /*status*/) { return Status::ok(); };
    virtual Status onReportFinished() { return Status::ok(); };
    virtual Status onReportFailed() { return Status::ok(); };

protected:
    virtual IBinder* onAsBinder() override { return nullptr; };
};

TEST(SectionTest, HeaderSection) {
    TemporaryFile output2;
    HeaderSection hs;
    ReportRequestSet requests;

    IncidentReportArgs args1, args2;
    args1.addSection(1);
    args1.addSection(2);
    args2.setAll(true);

    IncidentHeaderProto head1, head2;
    head1.set_reason("axe");
    head2.set_reason("pup");

    args1.addHeader(head1);
    args1.addHeader(head2);
    args2.addHeader(head2);

    requests.add(new ReportRequest(args1, new SimpleListener(), -1));
    requests.add(new ReportRequest(args2, new SimpleListener(), output2.fd));
    requests.setMainFd(STDOUT_FILENO);

    string content;
    CaptureStdout();
    ASSERT_EQ(NO_ERROR, hs.Execute(&requests));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\n\x5"
                                           "\x12\x3"
                                           "axe\n\x05\x12\x03pup"));

    EXPECT_TRUE(ReadFileToString(output2.path, &content));
    EXPECT_THAT(content, StrEq("\n\x05\x12\x03pup"));
}

TEST(SectionTest, MetadataSection) {
    MetadataSection ms;
    ReportRequestSet requests;

    requests.setMainFd(STDOUT_FILENO);
    requests.sectionStats(1)->set_success(true);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, ms.Execute(&requests));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x12\b\x18\x1\"\x4\b\x1\x10\x1"));
}

TEST(SectionTest, FileSection) {
    TemporaryFile tf;
    FileSection fs(REVERSE_PARSER, tf.path);
    ReportRequestSet requests;

    ASSERT_TRUE(tf.fd != -1);
    ASSERT_TRUE(WriteStringToFile("iamtestdata", tf.path));

    requests.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requests));
    // The input string is reversed in incident helper
    // The length is 11, in 128Varint it is "0000 1011" -> \v
    EXPECT_THAT(GetCapturedStdout(), StrEq("\xa\vatadtsetmai"));
}

TEST(SectionTest, FileSectionTimeout) {
    TemporaryFile tf;
    // id -1 is timeout parser
    FileSection fs(TIMEOUT_PARSER, tf.path, QUICK_TIMEOUT_MS);
    ReportRequestSet requests;
    ASSERT_EQ(NO_ERROR, fs.Execute(&requests));
}

TEST(SectionTest, CommandSectionConstructor) {
    CommandSection cs1(1, "echo", "\"this is a test\"", "ooo", NULL);
    CommandSection cs2(2, "single_command", NULL);
    CommandSection cs3(1, 3123, "echo", "\"this is a test\"", "ooo", NULL);
    CommandSection cs4(2, 43214, "single_command", NULL);

    EXPECT_THAT(cs1.name.string(), StrEq("echo \"this is a test\" ooo"));
    EXPECT_THAT(cs2.name.string(), StrEq("single_command"));
    EXPECT_EQ(3123, cs3.timeoutMs);
    EXPECT_EQ(43214, cs4.timeoutMs);
    EXPECT_THAT(cs3.name.string(), StrEq("echo \"this is a test\" ooo"));
    EXPECT_THAT(cs4.name.string(), StrEq("single_command"));
}

TEST(SectionTest, CommandSectionEcho) {
    CommandSection cs(REVERSE_PARSER, "/system/bin/echo", "about", NULL);
    ReportRequestSet requests;
    requests.setMainFd(STDOUT_FILENO);
    CaptureStdout();
    ASSERT_EQ(NO_ERROR, cs.Execute(&requests));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\xa\x06\ntuoba"));
}

TEST(SectionTest, CommandSectionCommandTimeout) {
    CommandSection cs(NOOP_PARSER, QUICK_TIMEOUT_MS, "/system/bin/yes", NULL);
    ReportRequestSet requests;
    ASSERT_EQ(NO_ERROR, cs.Execute(&requests));
}

TEST(SectionTest, CommandSectionIncidentHelperTimeout) {
    CommandSection cs(TIMEOUT_PARSER, QUICK_TIMEOUT_MS, "/system/bin/echo", "about", NULL);
    ReportRequestSet requests;
    requests.setMainFd(STDOUT_FILENO);
    ASSERT_EQ(NO_ERROR, cs.Execute(&requests));
}

TEST(SectionTest, CommandSectionBadCommand) {
    CommandSection cs(NOOP_PARSER, "echoo", "about", NULL);
    ReportRequestSet requests;
    ASSERT_EQ(NAME_NOT_FOUND, cs.Execute(&requests));
}

TEST(SectionTest, CommandSectionBadCommandAndTimeout) {
    CommandSection cs(TIMEOUT_PARSER, QUICK_TIMEOUT_MS, "nonexistcommand", "-opt", NULL);
    ReportRequestSet requests;
    // timeout will return first
    ASSERT_EQ(NO_ERROR, cs.Execute(&requests));
}

TEST(SectionTest, LogSectionBinary) {
    LogSection ls(1, LOG_ID_EVENTS);
    ReportRequestSet requests;
    requests.setMainFd(STDOUT_FILENO);
    CaptureStdout();
    ASSERT_EQ(NO_ERROR, ls.Execute(&requests));
    string results = GetCapturedStdout();
    EXPECT_FALSE(results.empty());
}

TEST(SectionTest, LogSectionSystem) {
    LogSection ls(1, LOG_ID_SYSTEM);
    ReportRequestSet requests;
    requests.setMainFd(STDOUT_FILENO);
    CaptureStdout();
    ASSERT_EQ(NO_ERROR, ls.Execute(&requests));
    string results = GetCapturedStdout();
    EXPECT_FALSE(results.empty());
}

TEST(SectionTest, TestFilterPiiTaggedFields) {
    TemporaryFile tf;
    FileSection fs(NOOP_PARSER, tf.path);
    ReportRequestSet requests;

    ASSERT_TRUE(tf.fd != -1);
    ASSERT_TRUE(WriteStringToFile(VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3, tf.path));

    requests.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requests));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x02\r" + STRING_FIELD_2));
}

TEST(SectionTest, TestBadFdRequest) {
    TemporaryFile input;
    FileSection fs(NOOP_PARSER, input.path);
    ReportRequestSet requests;
    ASSERT_TRUE(WriteStringToFile(VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3, input.path));

    IncidentReportArgs args;
    args.setAll(true);
    args.setDest(0);
    sp<ReportRequest> badFdRequest = new ReportRequest(args, new SimpleListener(), 1234567);
    requests.add(badFdRequest);
    requests.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requests));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x02\r" + STRING_FIELD_2));
    EXPECT_EQ(badFdRequest->err, -EBADF);
}

TEST(SectionTest, TestBadRequests) {
    TemporaryFile input;
    FileSection fs(NOOP_PARSER, input.path);
    ReportRequestSet requests;
    ASSERT_TRUE(WriteStringToFile(VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3, input.path));

    IncidentReportArgs args;
    args.setAll(true);
    args.setDest(0);
    requests.add(new ReportRequest(args, new SimpleListener(), -1));
    EXPECT_EQ(fs.Execute(&requests), -EBADF);
}

TEST(SectionTest, TestMultipleRequests) {
    TemporaryFile input, output1, output2, output3;
    FileSection fs(NOOP_PARSER, input.path);
    ReportRequestSet requests;

    ASSERT_TRUE(input.fd != -1);
    ASSERT_TRUE(output1.fd != -1);
    ASSERT_TRUE(output2.fd != -1);
    ASSERT_TRUE(output3.fd != -1);
    ASSERT_TRUE(WriteStringToFile(VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3, input.path));

    IncidentReportArgs args1, args2, args3;
    args1.setAll(true);
    args1.setDest(android::os::DEST_LOCAL);
    args2.setAll(true);
    args2.setDest(android::os::DEST_EXPLICIT);
    sp<SimpleListener> l = new SimpleListener();
    requests.add(new ReportRequest(args1, l, output1.fd));
    requests.add(new ReportRequest(args2, l, output2.fd));
    requests.add(new ReportRequest(args3, l, output3.fd));
    requests.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requests));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x02\r" + STRING_FIELD_2));

    string content, expect;
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

TEST(SectionTest, TestMultipleRequestsBySpec) {
    TemporaryFile input, output1, output2, output3;
    FileSection fs(NOOP_PARSER, input.path);
    ReportRequestSet requests;

    ASSERT_TRUE(input.fd != -1);
    ASSERT_TRUE(output1.fd != -1);
    ASSERT_TRUE(output2.fd != -1);
    ASSERT_TRUE(output3.fd != -1);

    ASSERT_TRUE(WriteStringToFile(VARINT_FIELD_1 + STRING_FIELD_2 + FIX64_FIELD_3, input.path));

    IncidentReportArgs args1, args2, args3;
    args1.setAll(true);
    args1.setDest(android::os::DEST_EXPLICIT);
    args2.setAll(true);
    args2.setDest(android::os::DEST_EXPLICIT);
    args3.setAll(true);
    sp<SimpleListener> l = new SimpleListener();
    requests.add(new ReportRequest(args1, l, output1.fd));
    requests.add(new ReportRequest(args2, l, output2.fd));
    requests.add(new ReportRequest(args3, l, output3.fd));
    requests.setMainFd(STDOUT_FILENO);

    CaptureStdout();
    ASSERT_EQ(NO_ERROR, fs.Execute(&requests));
    EXPECT_THAT(GetCapturedStdout(), StrEq("\x02\r" + STRING_FIELD_2));

    string content, expect;
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