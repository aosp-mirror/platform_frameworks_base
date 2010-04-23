//
// Copyright 2010 The Android Open Source Project
//

#include <utils/PollLoop.h>
#include <utils/Timers.h>
#include <utils/StopWatch.h>
#include <gtest/gtest.h>
#include <unistd.h>
#include <time.h>

#include "TestHelpers.h"

// # of milliseconds to fudge stopwatch measurements
#define TIMING_TOLERANCE_MS 25

namespace android {

class Pipe {
public:
    int sendFd;
    int receiveFd;

    Pipe() {
        int fds[2];
        ::pipe(fds);

        receiveFd = fds[0];
        sendFd = fds[1];
    }

    ~Pipe() {
        ::close(sendFd);
        ::close(receiveFd);
    }

    bool writeSignal() {
        return ::write(sendFd, "*", 1) == 1;
    }

    bool readSignal() {
        char buf[1];
        return ::read(receiveFd, buf, 1) == 1;
    }
};

class DelayedWake : public DelayedTask {
    sp<PollLoop> mPollLoop;

public:
    DelayedWake(int delayMillis, const sp<PollLoop> pollLoop) :
        DelayedTask(delayMillis), mPollLoop(pollLoop) {
    }

protected:
    virtual void doTask() {
        mPollLoop->wake();
    }
};

class DelayedWriteSignal : public DelayedTask {
    Pipe* mPipe;

public:
    DelayedWriteSignal(int delayMillis, Pipe* pipe) :
        DelayedTask(delayMillis), mPipe(pipe) {
    }

protected:
    virtual void doTask() {
        mPipe->writeSignal();
    }
};

class CallbackHandler {
public:
    void setCallback(const sp<PollLoop>& pollLoop, int fd, int events) {
        pollLoop->setCallback(fd, events, staticHandler, this);
    }

protected:
    virtual ~CallbackHandler() { }

    virtual bool handler(int fd, int events) = 0;

private:
    static bool staticHandler(int fd, int events, void* data) {
        return static_cast<CallbackHandler*>(data)->handler(fd, events);
    }
};

class StubCallbackHandler : public CallbackHandler {
public:
    bool nextResult;
    int callbackCount;

    int fd;
    int events;

    StubCallbackHandler(bool nextResult) : nextResult(nextResult),
            callbackCount(0), fd(-1), events(-1) {
    }

protected:
    virtual bool handler(int fd, int events) {
        callbackCount += 1;
        this->fd = fd;
        this->events = events;
        return nextResult;
    }
};

class PollLoopTest : public testing::Test {
protected:
    sp<PollLoop> mPollLoop;

    virtual void SetUp() {
        mPollLoop = new PollLoop();
    }

    virtual void TearDown() {
        mPollLoop.clear();
    }
};


TEST_F(PollLoopTest, PollOnce_WhenNonZeroTimeoutAndNotAwoken_WaitsForTimeoutAndReturnsFalse) {
    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(100);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    EXPECT_NEAR(100, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should approx. equal timeout";
    EXPECT_FALSE(result)
            << "pollOnce result should be false because timeout occurred";
}

TEST_F(PollLoopTest, PollOnce_WhenNonZeroTimeoutAndAwokenBeforeWaiting_ImmediatelyReturnsTrue) {
    mPollLoop->wake();

    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(1000);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    EXPECT_NEAR(0, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should approx. zero because wake() was called before waiting";
    EXPECT_TRUE(result)
            << "pollOnce result should be true because loop was awoken";
}

TEST_F(PollLoopTest, PollOnce_WhenNonZeroTimeoutAndAwokenWhileWaiting_PromptlyReturnsTrue) {
    sp<DelayedWake> delayedWake = new DelayedWake(100, mPollLoop);
    delayedWake->run();

    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(1000);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    EXPECT_NEAR(100, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should approx. equal wake delay";
    EXPECT_TRUE(result)
            << "pollOnce result should be true because loop was awoken";
}

TEST_F(PollLoopTest, PollOnce_WhenZeroTimeoutAndNoRegisteredFDs_ImmediatelyReturnsFalse) {
    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(0);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    EXPECT_NEAR(0, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should be approx. zero";
    EXPECT_FALSE(result)
            << "pollOnce result should be false because timeout occurred";
}

TEST_F(PollLoopTest, PollOnce_WhenZeroTimeoutAndNoSignalledFDs_ImmediatelyReturnsFalse) {
    Pipe pipe;
    StubCallbackHandler handler(true);

    handler.setCallback(mPollLoop, pipe.receiveFd, POLL_IN);

    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(0);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    EXPECT_NEAR(0, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should be approx. zero";
    EXPECT_FALSE(result)
            << "pollOnce result should be false because timeout occurred";
    EXPECT_EQ(0, handler.callbackCount)
            << "callback should not have been invoked because FD was not signalled";
}

TEST_F(PollLoopTest, PollOnce_WhenZeroTimeoutAndSignalledFD_ImmediatelyInvokesCallbackAndReturnsTrue) {
    Pipe pipe;
    StubCallbackHandler handler(true);

    ASSERT_TRUE(pipe.writeSignal());
    handler.setCallback(mPollLoop, pipe.receiveFd, POLL_IN);

    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(0);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    EXPECT_NEAR(0, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should be approx. zero";
    EXPECT_TRUE(result)
            << "pollOnce result should be true because FD was signalled";
    EXPECT_EQ(1, handler.callbackCount)
            << "callback should be invoked exactly once";
    EXPECT_EQ(pipe.receiveFd, handler.fd)
            << "callback should have received pipe fd as parameter";
    EXPECT_EQ(POLL_IN, handler.events)
            << "callback should have received POLL_IN as events";
}

TEST_F(PollLoopTest, PollOnce_WhenNonZeroTimeoutAndNoSignalledFDs_WaitsForTimeoutAndReturnsFalse) {
    Pipe pipe;
    StubCallbackHandler handler(true);

    handler.setCallback(mPollLoop, pipe.receiveFd, POLL_IN);

    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(100);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    EXPECT_NEAR(100, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should approx. equal timeout";
    EXPECT_FALSE(result)
            << "pollOnce result should be false because timeout occurred";
    EXPECT_EQ(0, handler.callbackCount)
            << "callback should not have been invoked because FD was not signalled";
}

TEST_F(PollLoopTest, PollOnce_WhenNonZeroTimeoutAndSignalledFDBeforeWaiting_ImmediatelyInvokesCallbackAndReturnsTrue) {
    Pipe pipe;
    StubCallbackHandler handler(true);

    pipe.writeSignal();
    handler.setCallback(mPollLoop, pipe.receiveFd, POLL_IN);

    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(100);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    ASSERT_TRUE(pipe.readSignal())
            << "signal should actually have been written";
    EXPECT_NEAR(0, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should be approx. zero";
    EXPECT_TRUE(result)
            << "pollOnce result should be true because FD was signalled";
    EXPECT_EQ(1, handler.callbackCount)
            << "callback should be invoked exactly once";
    EXPECT_EQ(pipe.receiveFd, handler.fd)
            << "callback should have received pipe fd as parameter";
    EXPECT_EQ(POLL_IN, handler.events)
            << "callback should have received POLL_IN as events";
}

TEST_F(PollLoopTest, PollOnce_WhenNonZeroTimeoutAndSignalledFDWhileWaiting_PromptlyInvokesCallbackAndReturnsTrue) {
    Pipe pipe;
    StubCallbackHandler handler(true);
    sp<DelayedWriteSignal> delayedWriteSignal = new DelayedWriteSignal(100, & pipe);

    handler.setCallback(mPollLoop, pipe.receiveFd, POLL_IN);
    delayedWriteSignal->run();

    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(1000);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    ASSERT_TRUE(pipe.readSignal())
            << "signal should actually have been written";
    EXPECT_NEAR(100, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should approx. equal signal delay";
    EXPECT_TRUE(result)
            << "pollOnce result should be true because FD was signalled";
    EXPECT_EQ(1, handler.callbackCount)
            << "callback should be invoked exactly once";
    EXPECT_EQ(pipe.receiveFd, handler.fd)
            << "callback should have received pipe fd as parameter";
    EXPECT_EQ(POLL_IN, handler.events)
            << "callback should have received POLL_IN as events";
}

TEST_F(PollLoopTest, PollOnce_WhenCallbackAddedThenRemoved_CallbackShouldNotBeInvoked) {
    Pipe pipe;
    StubCallbackHandler handler(true);

    handler.setCallback(mPollLoop, pipe.receiveFd, POLL_IN);
    pipe.writeSignal(); // would cause FD to be considered signalled
    mPollLoop->removeCallback(pipe.receiveFd);

    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(100);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    ASSERT_TRUE(pipe.readSignal())
            << "signal should actually have been written";
    EXPECT_NEAR(100, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should approx. equal timeout because FD was no longer registered";
    EXPECT_FALSE(result)
            << "pollOnce result should be false because timeout occurred";
    EXPECT_EQ(0, handler.callbackCount)
            << "callback should not be invoked";
}

TEST_F(PollLoopTest, PollOnce_WhenCallbackReturnsFalse_CallbackShouldNotBeInvokedAgainLater) {
    Pipe pipe;
    StubCallbackHandler handler(false);

    handler.setCallback(mPollLoop, pipe.receiveFd, POLL_IN);

    // First loop: Callback is registered and FD is signalled.
    pipe.writeSignal();

    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(0);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    ASSERT_TRUE(pipe.readSignal())
            << "signal should actually have been written";
    EXPECT_NEAR(0, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should approx. equal zero because FD was already signalled";
    EXPECT_TRUE(result)
            << "pollOnce result should be true because FD was signalled";
    EXPECT_EQ(1, handler.callbackCount)
            << "callback should be invoked";

    // Second loop: Callback is no longer registered and FD is signalled.
    pipe.writeSignal();

    stopWatch.reset();
    result = mPollLoop->pollOnce(0);
    elapsedMillis = ns2ms(stopWatch.elapsedTime());

    ASSERT_TRUE(pipe.readSignal())
            << "signal should actually have been written";
    EXPECT_NEAR(0, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should approx. equal zero because timeout was zero";
    EXPECT_FALSE(result)
            << "pollOnce result should be false because timeout occurred";
    EXPECT_EQ(1, handler.callbackCount)
            << "callback should not be invoked this time";
}

TEST_F(PollLoopTest, RemoveCallback_WhenCallbackNotAdded_ReturnsFalse) {
    bool result = mPollLoop->removeCallback(1);

    EXPECT_FALSE(result)
            << "removeCallback should return false because FD not registered";
}

TEST_F(PollLoopTest, RemoveCallback_WhenCallbackAddedThenRemovedTwice_ReturnsTrueFirstTimeAndReturnsFalseSecondTime) {
    Pipe pipe;
    StubCallbackHandler handler(false);
    handler.setCallback(mPollLoop, pipe.receiveFd, POLL_IN);

    // First time.
    bool result = mPollLoop->removeCallback(pipe.receiveFd);

    EXPECT_TRUE(result)
            << "removeCallback should return true first time because FD was registered";

    // Second time.
    result = mPollLoop->removeCallback(pipe.receiveFd);

    EXPECT_FALSE(result)
            << "removeCallback should return false second time because FD was no longer registered";
}

TEST_F(PollLoopTest, PollOnce_WhenCallbackAddedTwice_OnlySecondCallbackShouldBeInvoked) {
    Pipe pipe;
    StubCallbackHandler handler1(true);
    StubCallbackHandler handler2(true);

    handler1.setCallback(mPollLoop, pipe.receiveFd, POLL_IN);
    handler2.setCallback(mPollLoop, pipe.receiveFd, POLL_IN); // replace it
    pipe.writeSignal(); // would cause FD to be considered signalled

    StopWatch stopWatch("pollOnce");
    bool result = mPollLoop->pollOnce(100);
    int32_t elapsedMillis = ns2ms(stopWatch.elapsedTime());

    ASSERT_TRUE(pipe.readSignal())
            << "signal should actually have been written";
    EXPECT_NEAR(0, elapsedMillis, TIMING_TOLERANCE_MS)
            << "elapsed time should approx. zero because FD was already signalled";
    EXPECT_TRUE(result)
            << "pollOnce result should be true because FD was signalled";
    EXPECT_EQ(0, handler1.callbackCount)
            << "original handler callback should not be invoked because it was replaced";
    EXPECT_EQ(1, handler2.callbackCount)
            << "replacement handler callback should be invoked";
}


} // namespace android
