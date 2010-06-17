//
// Copyright 2010 The Android Open Source Project
//
// A select loop implementation.
//
#define LOG_TAG "PollLoop"

//#define LOG_NDEBUG 0

// Debugs poll and wake interactions.
#define DEBUG_POLL_AND_WAKE 0

// Debugs callback registration and invocation.
#define DEBUG_CALLBACKS 0

#include <cutils/log.h>
#include <utils/PollLoop.h>

#include <unistd.h>
#include <fcntl.h>

namespace android {

PollLoop::PollLoop() :
        mPolling(false), mWaiters(0) {
    openWakePipe();
}

PollLoop::~PollLoop() {
    closeWakePipe();
}

void PollLoop::openWakePipe() {
    int wakeFds[2];
    int result = pipe(wakeFds);
    LOG_ALWAYS_FATAL_IF(result != 0, "Could not create wake pipe.  errno=%d", errno);

    mWakeReadPipeFd = wakeFds[0];
    mWakeWritePipeFd = wakeFds[1];

    result = fcntl(mWakeReadPipeFd, F_SETFL, O_NONBLOCK);
    LOG_ALWAYS_FATAL_IF(result != 0, "Could not make wake read pipe non-blocking.  errno=%d",
            errno);

    result = fcntl(mWakeWritePipeFd, F_SETFL, O_NONBLOCK);
    LOG_ALWAYS_FATAL_IF(result != 0, "Could not make wake write pipe non-blocking.  errno=%d",
            errno);

    // Add the wake pipe to the head of the request list with a null callback.
    struct pollfd requestedFd;
    requestedFd.fd = mWakeReadPipeFd;
    requestedFd.events = POLLIN;
    mRequestedFds.insertAt(requestedFd, 0);

    RequestedCallback requestedCallback;
    requestedCallback.callback = NULL;
    requestedCallback.data = NULL;
    mRequestedCallbacks.insertAt(requestedCallback, 0);
}

void PollLoop::closeWakePipe() {
    close(mWakeReadPipeFd);
    close(mWakeWritePipeFd);

    // Note: We don't need to remove the poll structure or callback entry because this
    //       method is currently only called by the destructor.
}

bool PollLoop::pollOnce(int timeoutMillis) {
    mLock.lock();
    while (mWaiters != 0) {
        mResume.wait(mLock);
    }
    mPolling = true;
    mLock.unlock();

    bool result;
    size_t requestedCount = mRequestedFds.size();

#if DEBUG_POLL_AND_WAKE
    LOGD("%p ~ pollOnce - waiting on %d fds", this, requestedCount);
    for (size_t i = 0; i < requestedCount; i++) {
        LOGD("  fd %d - events %d", mRequestedFds[i].fd, mRequestedFds[i].events);
    }
#endif

    int respondedCount = poll(mRequestedFds.editArray(), requestedCount, timeoutMillis);

    if (respondedCount == 0) {
        // Timeout
#if DEBUG_POLL_AND_WAKE
        LOGD("%p ~ pollOnce - timeout", this);
#endif
        result = false;
        goto Done;
    }

    if (respondedCount < 0) {
        // Error
#if DEBUG_POLL_AND_WAKE
        LOGD("%p ~ pollOnce - error, errno=%d", this, errno);
#endif
        if (errno != EINTR) {
            LOGW("Poll failed with an unexpected error, errno=%d", errno);
        }
        result = false;
        goto Done;
    }

#if DEBUG_POLL_AND_WAKE
    LOGD("%p ~ pollOnce - handling responses from %d fds", this, respondedCount);
    for (size_t i = 0; i < requestedCount; i++) {
        LOGD("  fd %d - events %d, revents %d", mRequestedFds[i].fd, mRequestedFds[i].events,
                mRequestedFds[i].revents);
    }
#endif

    mPendingCallbacks.clear();
    for (size_t i = 0; i < requestedCount; i++) {
        const struct pollfd& requestedFd = mRequestedFds.itemAt(i);

        short revents = requestedFd.revents;
        if (revents) {
            const RequestedCallback& requestedCallback = mRequestedCallbacks.itemAt(i);
            Callback callback = requestedCallback.callback;

            if (callback) {
                PendingCallback pendingCallback;
                pendingCallback.fd = requestedFd.fd;
                pendingCallback.events = requestedFd.revents;
                pendingCallback.callback = callback;
                pendingCallback.data = requestedCallback.data;
                mPendingCallbacks.push(pendingCallback);
            } else {
                if (requestedFd.fd == mWakeReadPipeFd) {
#if DEBUG_POLL_AND_WAKE
                    LOGD("%p ~ pollOnce - awoken", this);
#endif
                    char buffer[16];
                    ssize_t nRead;
                    do {
                        nRead = read(mWakeReadPipeFd, buffer, sizeof(buffer));
                    } while (nRead == sizeof(buffer));
                } else {
#if DEBUG_POLL_AND_WAKE || DEBUG_CALLBACKS
                    LOGD("%p ~ pollOnce - fd %d has no callback!", this, requestedFd.fd);
#endif
                }
            }

            respondedCount -= 1;
            if (respondedCount == 0) {
                break;
            }
        }
    }
    result = true;

Done:
    mLock.lock();
    mPolling = false;
    if (mWaiters != 0) {
        mAwake.broadcast();
    }
    mLock.unlock();

    if (result) {
        size_t pendingCount = mPendingCallbacks.size();
        for (size_t i = 0; i < pendingCount; i++) {
            const PendingCallback& pendingCallback = mPendingCallbacks.itemAt(i);
#if DEBUG_POLL_AND_WAKE || DEBUG_CALLBACKS
            LOGD("%p ~ pollOnce - invoking callback for fd %d", this, pendingCallback.fd);
#endif

            bool keep = pendingCallback.callback(pendingCallback.fd, pendingCallback.events,
                    pendingCallback.data);
            if (! keep) {
                removeCallback(pendingCallback.fd);
            }
        }
    }

#if DEBUG_POLL_AND_WAKE
    LOGD("%p ~ pollOnce - done", this);
#endif
    return result;
}

void PollLoop::wake() {
#if DEBUG_POLL_AND_WAKE
    LOGD("%p ~ wake", this);
#endif

    ssize_t nWrite = write(mWakeWritePipeFd, "W", 1);
    if (nWrite != 1) {
        if (errno != EAGAIN) {
            LOGW("Could not write wake signal, errno=%d", errno);
        }
    }
}

void PollLoop::setCallback(int fd, int events, Callback callback, void* data) {
#if DEBUG_CALLBACKS
    LOGD("%p ~ setCallback - fd=%d, events=%d", this, fd, events);
#endif

    if (! events || ! callback) {
        LOGE("Invalid attempt to set a callback with no selected poll events or no callback.");
        removeCallback(fd);
        return;
    }

    wakeAndLock();

    struct pollfd requestedFd;
    requestedFd.fd = fd;
    requestedFd.events = events;

    RequestedCallback requestedCallback;
    requestedCallback.callback = callback;
    requestedCallback.data = data;

    ssize_t index = getRequestIndexLocked(fd);
    if (index < 0) {
        mRequestedFds.push(requestedFd);
        mRequestedCallbacks.push(requestedCallback);
    } else {
        mRequestedFds.replaceAt(requestedFd, size_t(index));
        mRequestedCallbacks.replaceAt(requestedCallback, size_t(index));
    }

    mLock.unlock();
}

bool PollLoop::removeCallback(int fd) {
#if DEBUG_CALLBACKS
    LOGD("%p ~ removeCallback - fd=%d", this, fd);
#endif

    wakeAndLock();

    ssize_t index = getRequestIndexLocked(fd);
    if (index >= 0) {
        mRequestedFds.removeAt(size_t(index));
        mRequestedCallbacks.removeAt(size_t(index));
    }

    mLock.unlock();
    return index >= 0;
}

ssize_t PollLoop::getRequestIndexLocked(int fd) {
    size_t requestCount = mRequestedFds.size();

    for (size_t i = 0; i < requestCount; i++) {
        if (mRequestedFds.itemAt(i).fd == fd) {
            return i;
        }
    }

    return -1;
}

void PollLoop::wakeAndLock() {
    mLock.lock();
    mWaiters += 1;
    while (mPolling) {
        wake();
        mAwake.wait(mLock);
    }
    mWaiters -= 1;
    if (mWaiters == 0) {
        mResume.signal();
    }
}

} // namespace android
