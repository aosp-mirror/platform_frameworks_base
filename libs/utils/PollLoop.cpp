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

static pthread_mutex_t gTLSMutex = PTHREAD_MUTEX_INITIALIZER;
static bool gHaveTLS = false;
static pthread_key_t gTLS = 0;

PollLoop::PollLoop(bool allowNonCallbacks) :
        mAllowNonCallbacks(allowNonCallbacks), mPolling(false),
        mWaiters(0), mPendingFdsPos(0) {
    openWakePipe();
}

PollLoop::~PollLoop() {
    closeWakePipe();
}

void PollLoop::threadDestructor(void *st) {
    PollLoop* const self = static_cast<PollLoop*>(st);
    if (self != NULL) {
        self->decStrong((void*)threadDestructor);
    }
}

void PollLoop::setForThread(const sp<PollLoop>& pollLoop) {
    sp<PollLoop> old = getForThread();
    
    if (pollLoop != NULL) {
        pollLoop->incStrong((void*)threadDestructor);
    }
    
    pthread_setspecific(gTLS, pollLoop.get());
    
    if (old != NULL) {
        old->decStrong((void*)threadDestructor);
    }
}
    
sp<PollLoop> PollLoop::getForThread() {
    if (!gHaveTLS) {
        pthread_mutex_lock(&gTLSMutex);
        if (pthread_key_create(&gTLS, threadDestructor) != 0) {
            pthread_mutex_unlock(&gTLSMutex);
            return NULL;
        }
        gHaveTLS = true;
        pthread_mutex_unlock(&gTLSMutex);
    }
    
    return (PollLoop*)pthread_getspecific(gTLS);
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
    requestedCallback.looperCallback = NULL;
    requestedCallback.data = NULL;
    mRequestedCallbacks.insertAt(requestedCallback, 0);
}

void PollLoop::closeWakePipe() {
    close(mWakeReadPipeFd);
    close(mWakeWritePipeFd);

    // Note: We don't need to remove the poll structure or callback entry because this
    //       method is currently only called by the destructor.
}

int32_t PollLoop::pollOnce(int timeoutMillis, int* outEvents, void** outData) {
    // If there are still pending fds from the last call, dispatch those
    // first, to avoid an earlier fd from starving later ones.
    const size_t pendingFdsCount = mPendingFds.size();
    if (mPendingFdsPos < pendingFdsCount) {
        const PendingCallback& pending = mPendingFds.itemAt(mPendingFdsPos);
        mPendingFdsPos++;
        if (outEvents != NULL) *outEvents = pending.events;
        if (outData != NULL) *outData = pending.data;
        return pending.fd;
    }
    
    mLock.lock();
    while (mWaiters != 0) {
        mResume.wait(mLock);
    }
    mPolling = true;
    mLock.unlock();

    int32_t result;
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
        result = POLL_TIMEOUT;
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
        result = POLL_ERROR;
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
    mPendingFds.clear();
    mPendingFdsPos = 0;
    if (outEvents != NULL) *outEvents = 0;
    if (outData != NULL) *outData = NULL;
    
    result = POLL_CALLBACK;
    for (size_t i = 0; i < requestedCount; i++) {
        const struct pollfd& requestedFd = mRequestedFds.itemAt(i);

        short revents = requestedFd.revents;
        if (revents) {
            const RequestedCallback& requestedCallback = mRequestedCallbacks.itemAt(i);
            PendingCallback pending;
            pending.fd = requestedFd.fd;
            pending.events = revents;
            pending.callback = requestedCallback.callback;
            pending.looperCallback = requestedCallback.looperCallback;
            pending.data = requestedCallback.data;

            if (pending.callback || pending.looperCallback) {
                mPendingCallbacks.push(pending);
            } else if (pending.fd != mWakeReadPipeFd) {
                if (result == POLL_CALLBACK) {
                    result = pending.fd;
                    if (outEvents != NULL) *outEvents = pending.events;
                    if (outData != NULL) *outData = pending.data;
                } else {
                    mPendingFds.push(pending);
                }
            } else {
#if DEBUG_POLL_AND_WAKE
                LOGD("%p ~ pollOnce - awoken", this);
#endif
                char buffer[16];
                ssize_t nRead;
                do {
                    nRead = read(mWakeReadPipeFd, buffer, sizeof(buffer));
                } while (nRead == sizeof(buffer));
            }

            respondedCount -= 1;
            if (respondedCount == 0) {
                break;
            }
        }
    }

Done:
    mLock.lock();
    mPolling = false;
    if (mWaiters != 0) {
        mAwake.broadcast();
    }
    mLock.unlock();

    if (result == POLL_CALLBACK || result >= 0) {
        size_t pendingCount = mPendingCallbacks.size();
        for (size_t i = 0; i < pendingCount; i++) {
            const PendingCallback& pendingCallback = mPendingCallbacks.itemAt(i);
#if DEBUG_POLL_AND_WAKE || DEBUG_CALLBACKS
            LOGD("%p ~ pollOnce - invoking callback for fd %d", this, pendingCallback.fd);
#endif

            bool keep = true;
            if (pendingCallback.callback != NULL) {
                keep = pendingCallback.callback(pendingCallback.fd, pendingCallback.events,
                        pendingCallback.data);
            } else {
                keep = pendingCallback.looperCallback(pendingCallback.fd, pendingCallback.events,
                        pendingCallback.data) != 0;
            }
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

bool PollLoop::getAllowNonCallbacks() const {
    return mAllowNonCallbacks;
}

void PollLoop::setCallback(int fd, int events, Callback callback, void* data) {
    setCallbackCommon(fd, events, callback, NULL, data);
}

void PollLoop::setLooperCallback(int fd, int events, ALooper_callbackFunc* callback,
        void* data) {
    setCallbackCommon(fd, events, NULL, callback, data);
}

void PollLoop::setCallbackCommon(int fd, int events, Callback callback,
        ALooper_callbackFunc* looperCallback, void* data) {

#if DEBUG_CALLBACKS
    LOGD("%p ~ setCallback - fd=%d, events=%d", this, fd, events);
#endif

    if (! events) {
        LOGE("Invalid attempt to set a callback with no selected poll events.");
        removeCallback(fd);
        return;
    }

    if (! callback && ! looperCallback && ! mAllowNonCallbacks) {
        LOGE("Invalid attempt to set NULL callback but not allowed.");
        removeCallback(fd);
        return;
    }
    
    wakeAndLock();

    struct pollfd requestedFd;
    requestedFd.fd = fd;
    requestedFd.events = events;

    RequestedCallback requestedCallback;
    requestedCallback.callback = callback;
    requestedCallback.looperCallback = looperCallback;
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
