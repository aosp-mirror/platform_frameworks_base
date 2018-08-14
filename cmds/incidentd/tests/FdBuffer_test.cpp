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

#include "FdBuffer.h"
#include "incidentd_util.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <fcntl.h>
#include <gtest/gtest.h>
#include <signal.h>
#include <string.h>

using namespace android;
using namespace android::base;
using namespace android::os::incidentd;
using ::testing::Test;

const int READ_TIMEOUT = 5 * 1000;
const int BUFFER_SIZE = 16 * 1024;
const int QUICK_TIMEOUT_MS = 100;
const std::string HEAD = "[OK]";

class FdBufferTest : public Test {
public:
    virtual void SetUp() override {
        ASSERT_NE(tf.fd, -1);
        ASSERT_NE(p2cPipe.init(), -1);
        ASSERT_NE(c2pPipe.init(), -1);
    }

    void AssertBufferReadSuccessful(size_t expected) {
        EXPECT_EQ(buffer.size(), expected);
        EXPECT_FALSE(buffer.timedOut());
        EXPECT_FALSE(buffer.truncated());
    }

    void AssertBufferContent(const char* expected) {
        int i = 0;
        EncodedBuffer::iterator it = buffer.data();
        while (it.hasNext()) {
            ASSERT_EQ(it.next(), expected[i++]);
        }
        EXPECT_EQ(expected[i], '\0');
    }

    bool DoDataStream(const unique_fd& rFd, const unique_fd& wFd) {
        char buf[BUFFER_SIZE];
        ssize_t nRead;
        while ((nRead = read(rFd.get(), buf, BUFFER_SIZE)) > 0) {
            ssize_t nWritten = 0;
            while (nWritten < nRead) {
                ssize_t amt = write(wFd.get(), buf + nWritten, nRead - nWritten);
                if (amt < 0) {
                    return false;
                }
                nWritten += amt;
            }
        }
        return nRead == 0;
    }

protected:
    FdBuffer buffer;
    TemporaryFile tf;
    Fpipe p2cPipe;
    Fpipe c2pPipe;

    const std::string kTestPath = GetExecutableDirectory();
    const std::string kTestDataPath = kTestPath + "/testdata/";
};

TEST_F(FdBufferTest, ReadAndWrite) {
    std::string testdata = "FdBuffer test string";
    ASSERT_TRUE(WriteStringToFile(testdata, tf.path));
    ASSERT_EQ(NO_ERROR, buffer.read(tf.fd, READ_TIMEOUT));
    AssertBufferReadSuccessful(testdata.size());
    AssertBufferContent(testdata.c_str());
}

TEST_F(FdBufferTest, IterateEmpty) {
    EncodedBuffer::iterator it = buffer.data();
    EXPECT_FALSE(it.hasNext());
}

TEST_F(FdBufferTest, ReadAndIterate) {
    std::string testdata = "FdBuffer test string";
    ASSERT_TRUE(WriteStringToFile(testdata, tf.path));
    ASSERT_EQ(NO_ERROR, buffer.read(tf.fd, READ_TIMEOUT));

    int i = 0;
    EncodedBuffer::iterator it = buffer.data();
    while (it.hasNext()) {
        EXPECT_EQ(it.next(), (uint8_t)testdata[i++]);
    }

    it.rp()->rewind();
    it.rp()->move(buffer.size());
    EXPECT_EQ(it.bytesRead(), testdata.size());
    EXPECT_FALSE(it.hasNext());
}

TEST_F(FdBufferTest, ReadTimeout) {
    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        c2pPipe.readFd().reset();
        while (true) {
            write(c2pPipe.writeFd(), "poo", 3);
            sleep(1);
        }
        _exit(EXIT_FAILURE);
    } else {
        c2pPipe.writeFd().reset();

        status_t status = buffer.read(c2pPipe.readFd().get(), QUICK_TIMEOUT_MS);
        ASSERT_EQ(NO_ERROR, status);
        EXPECT_TRUE(buffer.timedOut());

        kill(pid, SIGKILL);  // reap the child process
    }
}

TEST_F(FdBufferTest, ReadInStreamAndWrite) {
    std::string testdata = "simply test read in stream";
    std::string expected = HEAD + testdata;
    ASSERT_TRUE(WriteStringToFile(testdata, tf.path));

    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        p2cPipe.writeFd().reset();
        c2pPipe.readFd().reset();
        ASSERT_TRUE(WriteStringToFd(HEAD, c2pPipe.writeFd()));
        ASSERT_TRUE(DoDataStream(p2cPipe.readFd(), c2pPipe.writeFd()));
        p2cPipe.readFd().reset();
        c2pPipe.writeFd().reset();
        // Must exit here otherwise the child process will continue executing the test binary.
        _exit(EXIT_SUCCESS);
    } else {
        p2cPipe.readFd().reset();
        c2pPipe.writeFd().reset();

        ASSERT_EQ(NO_ERROR,
                  buffer.readProcessedDataInStream(tf.fd, std::move(p2cPipe.writeFd()),
                                                   std::move(c2pPipe.readFd()), READ_TIMEOUT));
        AssertBufferReadSuccessful(HEAD.size() + testdata.size());
        AssertBufferContent(expected.c_str());
        wait(&pid);
    }
}

TEST_F(FdBufferTest, ReadInStreamAndWriteAllAtOnce) {
    std::string testdata = "child process flushes only after all data are read.";
    std::string expected = HEAD + testdata;
    ASSERT_TRUE(WriteStringToFile(testdata, tf.path));

    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        p2cPipe.writeFd().reset();
        c2pPipe.readFd().reset();
        std::string data;
        // wait for read finishes then write.
        ASSERT_TRUE(ReadFdToString(p2cPipe.readFd(), &data));
        data = HEAD + data;
        ASSERT_TRUE(WriteStringToFd(data, c2pPipe.writeFd()));
        p2cPipe.readFd().reset();
        c2pPipe.writeFd().reset();
        // Must exit here otherwise the child process will continue executing the test binary.
        _exit(EXIT_SUCCESS);
    } else {
        p2cPipe.readFd().reset();
        c2pPipe.writeFd().reset();

        ASSERT_EQ(NO_ERROR,
                  buffer.readProcessedDataInStream(tf.fd, std::move(p2cPipe.writeFd()),
                                                   std::move(c2pPipe.readFd()), READ_TIMEOUT));
        AssertBufferReadSuccessful(HEAD.size() + testdata.size());
        AssertBufferContent(expected.c_str());
        wait(&pid);
    }
}

TEST_F(FdBufferTest, ReadInStreamEmpty) {
    ASSERT_TRUE(WriteStringToFile("", tf.path));

    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        p2cPipe.writeFd().reset();
        c2pPipe.readFd().reset();
        ASSERT_TRUE(DoDataStream(p2cPipe.readFd(), c2pPipe.writeFd()));
        p2cPipe.readFd().reset();
        c2pPipe.writeFd().reset();
        _exit(EXIT_SUCCESS);
    } else {
        p2cPipe.readFd().reset();
        c2pPipe.writeFd().reset();

        ASSERT_EQ(NO_ERROR,
                  buffer.readProcessedDataInStream(tf.fd, std::move(p2cPipe.writeFd()),
                                                   std::move(c2pPipe.readFd()), READ_TIMEOUT));
        AssertBufferReadSuccessful(0);
        AssertBufferContent("");
        wait(&pid);
    }
}

TEST_F(FdBufferTest, ReadInStreamMoreThan4MB) {
    const std::string testFile = kTestDataPath + "morethan4MB.txt";
    size_t fourMB = (size_t)4 * 1024 * 1024;
    unique_fd fd(open(testFile.c_str(), O_RDONLY | O_CLOEXEC));
    ASSERT_NE(fd.get(), -1);
    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        p2cPipe.writeFd().reset();
        c2pPipe.readFd().reset();
        ASSERT_TRUE(DoDataStream(p2cPipe.readFd(), c2pPipe.writeFd()));
        p2cPipe.readFd().reset();
        c2pPipe.writeFd().reset();
        _exit(EXIT_SUCCESS);
    } else {
        p2cPipe.readFd().reset();
        c2pPipe.writeFd().reset();

        ASSERT_EQ(NO_ERROR,
                  buffer.readProcessedDataInStream(fd, std::move(p2cPipe.writeFd()),
                                                   std::move(c2pPipe.readFd()), READ_TIMEOUT));
        EXPECT_EQ(buffer.size(), fourMB);
        EXPECT_FALSE(buffer.timedOut());
        EXPECT_TRUE(buffer.truncated());
        wait(&pid);
        EncodedBuffer::iterator it = buffer.data();
        it.rp()->move(fourMB);
        EXPECT_EQ(it.bytesRead(), fourMB);
        EXPECT_FALSE(it.hasNext());

        it.rp()->rewind();
        while (it.hasNext()) {
            char c = 'A' + (it.bytesRead() % 64 / 8);
            ASSERT_TRUE(it.next() == c);
        }
    }
}

TEST_F(FdBufferTest, ReadInStreamTimeOut) {
    std::string testdata = "timeout test";
    ASSERT_TRUE(WriteStringToFile(testdata, tf.path));

    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        p2cPipe.writeFd().reset();
        c2pPipe.readFd().reset();
        while (true) {
            sleep(1);
        }
        _exit(EXIT_FAILURE);
    } else {
        p2cPipe.readFd().reset();
        c2pPipe.writeFd().reset();

        ASSERT_EQ(NO_ERROR,
                  buffer.readProcessedDataInStream(tf.fd, std::move(p2cPipe.writeFd()),
                                                   std::move(c2pPipe.readFd()), QUICK_TIMEOUT_MS));
        EXPECT_TRUE(buffer.timedOut());
        kill(pid, SIGKILL);  // reap the child process
    }
}
