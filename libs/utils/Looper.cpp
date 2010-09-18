//
// Copyright 2010 The Android Open Source Project
//
// A looper implementation based on epoll().
//
#define LOG_TAG "Looper"

//#define LOG_NDEBUG 0

// Debugs poll and wake interactions.
#define DEBUG_POLL_AND_WAKE 0

// Debugs callback registration and invocation.
#define DEBUG_CALLBACKS 0

#include <cutils/log.h>
#include <utils/Looper.h>
#include <utils/Timers.h>

#include <unistd.h>
#include <fcntl.h>
#include <sys/epoll.h>


namespace android {

static pthread_mutex_t gTLSMutex = PTHREAD_MUTEX_INITIALIZER;
static bool gHaveTLS = false;
static pthread_key_t gTLS = 0;

// Hint for number of file descriptors to be associated with the epoll instance.
static const int EPOLL_SIZE_HINT = 8;

// Maximum number of file descriptors for which to retrieve poll events each iteration.
static const int EPOLL_MAX_EVENTS = 16;

Looper::Looper(bool allowNonCallbacks) :
        mAllowNonCallbacks(allowNonCallbacks),
        mResponseIndex(0) {
    mEpollFd = epoll_create(EPOLL_SIZE_HINT);
    LOG_ALWAYS_FATAL_IF(mEpollFd < 0, "Could not create epoll instance.  errno=%d", errno);

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

    struct epoll_event eventItem;
    eventItem.events = EPOLLIN;
    eventItem.data.fd = mWakeReadPipeFd;
    result = epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mWakeReadPipeFd, & eventItem);
    LOG_ALWAYS_FATAL_IF(result != 0, "Could not add wake read pipe to epoll instance.  errno=%d",
            errno);
}

Looper::~Looper() {
    close(mWakeReadPipeFd);
    close(mWakeWritePipeFd);
    close(mEpollFd);
}

void Looper::threadDestructor(void *st) {
    Looper* const self = static_cast<Looper*>(st);
    if (self != NULL) {
        self->decStrong((void*)threadDestructor);
    }
}

void Looper::setForThread(const sp<Looper>& looper) {
    sp<Looper> old = getForThread(); // also has side-effect of initializing TLS

    if (looper != NULL) {
        looper->incStrong((void*)threadDestructor);
    }

    pthread_setspecific(gTLS, looper.get());

    if (old != NULL) {
        old->decStrong((void*)threadDestructor);
    }
}

sp<Looper> Looper::getForThread() {
    if (!gHaveTLS) {
        pthread_mutex_lock(&gTLSMutex);
        if (pthread_key_create(&gTLS, threadDestructor) != 0) {
            pthread_mutex_unlock(&gTLSMutex);
            return NULL;
        }
        gHaveTLS = true;
        pthread_mutex_unlock(&gTLSMutex);
    }

    return (Looper*)pthread_getspecific(gTLS);
}

sp<Looper> Looper::prepare(int opts) {
    bool allowNonCallbacks = opts & ALOOPER_PREPARE_ALLOW_NON_CALLBACKS;
    sp<Looper> looper = Looper::getForThread();
    if (looper == NULL) {
        looper = new Looper(allowNonCallbacks);
        Looper::setForThread(looper);
    }
    if (looper->getAllowNonCallbacks() != allowNonCallbacks) {
        LOGW("Looper already prepared for this thread with a different value for the "
                "ALOOPER_PREPARE_ALLOW_NON_CALLBACKS option.");
    }
    return looper;
}

bool Looper::getAllowNonCallbacks() const {
    return mAllowNonCallbacks;
}

int Looper::pollOnce(int timeoutMillis, int* outFd, int* outEvents, void** outData) {
    int result = 0;
    for (;;) {
        while (mResponseIndex < mResponses.size()) {
            const Response& response = mResponses.itemAt(mResponseIndex++);
            if (! response.request.callback) {
#if DEBUG_POLL_AND_WAKE
                LOGD("%p ~ pollOnce - returning signalled identifier %d: "
                        "fd=%d, events=0x%x, data=%p", this,
                        response.request.ident, response.request.fd,
                        response.events, response.request.data);
#endif
                if (outFd != NULL) *outFd = response.request.fd;
                if (outEvents != NULL) *outEvents = response.events;
                if (outData != NULL) *outData = response.request.data;
                return response.request.ident;
            }
        }

        if (result != 0) {
#if DEBUG_POLL_AND_WAKE
            LOGD("%p ~ pollOnce - returning result %d", this, result);
#endif
            if (outFd != NULL) *outFd = 0;
            if (outEvents != NULL) *outEvents = NULL;
            if (outData != NULL) *outData = NULL;
            return result;
        }

        result = pollInner(timeoutMillis);
    }
}

int Looper::pollInner(int timeoutMillis) {
#if DEBUG_POLL_AND_WAKE
    LOGD("%p ~ pollOnce - waiting: timeoutMillis=%d", this, timeoutMillis);
#endif
    struct epoll_event eventItems[EPOLL_MAX_EVENTS];
    int eventCount = epoll_wait(mEpollFd, eventItems, EPOLL_MAX_EVENTS, timeoutMillis);
    if (eventCount < 0) {
        if (errno == EINTR) {
            return ALOOPER_POLL_WAKE;
        }

        LOGW("Poll failed with an unexpected error, errno=%d", errno);
        return ALOOPER_POLL_ERROR;
    }

    if (eventCount == 0) {
#if DEBUG_POLL_AND_WAKE
        LOGD("%p ~ pollOnce - timeout", this);
#endif
        return ALOOPER_POLL_TIMEOUT;
    }

    int result = ALOOPER_POLL_WAKE;
    mResponses.clear();
    mResponseIndex = 0;

#if DEBUG_POLL_AND_WAKE
    LOGD("%p ~ pollOnce - handling events from %d fds", this, eventCount);
#endif
    bool acquiredLock = false;
    for (int i = 0; i < eventCount; i++) {
        int fd = eventItems[i].data.fd;
        uint32_t epollEvents = eventItems[i].events;
        if (fd == mWakeReadPipeFd) {
            if (epollEvents & EPOLLIN) {
#if DEBUG_POLL_AND_WAKE
                LOGD("%p ~ pollOnce - awoken", this);
#endif
                char buffer[16];
                ssize_t nRead;
                do {
                    nRead = read(mWakeReadPipeFd, buffer, sizeof(buffer));
                } while ((nRead == -1 && errno == EINTR) || nRead == sizeof(buffer));
            } else {
                LOGW("Ignoring unexpected epoll events 0x%x on wake read pipe.", epollEvents);
            }
        } else {
            if (! acquiredLock) {
                mLock.lock();
                acquiredLock = true;
            }

            ssize_t requestIndex = mRequests.indexOfKey(fd);
            if (requestIndex >= 0) {
                int events = 0;
                if (epollEvents & EPOLLIN) events |= ALOOPER_EVENT_INPUT;
                if (epollEvents & EPOLLOUT) events |= ALOOPER_EVENT_OUTPUT;
                if (epollEvents & EPOLLERR) events |= ALOOPER_EVENT_ERROR;
                if (epollEvents & EPOLLHUP) events |= ALOOPER_EVENT_HANGUP;

                Response response;
                response.events = events;
                response.request = mRequests.valueAt(requestIndex);
                mResponses.push(response);
            } else {
                LOGW("Ignoring unexpected epoll events 0x%x on fd %d that is "
                        "no longer registered.", epollEvents, fd);
            }
        }
    }
    if (acquiredLock) {
        mLock.unlock();
    }

    for (size_t i = 0; i < mResponses.size(); i++) {
        const Response& response = mResponses.itemAt(i);
        if (response.request.callback) {
#if DEBUG_POLL_AND_WAKE || DEBUG_CALLBACKS
            LOGD("%p ~ pollOnce - invoking callback: fd=%d, events=0x%x, data=%p", this,
                    response.request.fd, response.events, response.request.data);
#endif
            int callbackResult = response.request.callback(
                    response.request.fd, response.events, response.request.data);
            if (callbackResult == 0) {
                removeFd(response.request.fd);
            }

            result = ALOOPER_POLL_CALLBACK;
        }
    }
    return result;
}

int Looper::pollAll(int timeoutMillis, int* outFd, int* outEvents, void** outData) {
    if (timeoutMillis <= 0) {
        int result;
        do {
            result = pollOnce(timeoutMillis, outFd, outEvents, outData);
        } while (result == ALOOPER_POLL_CALLBACK);
        return result;
    } else {
        nsecs_t endTime = systemTime(SYSTEM_TIME_MONOTONIC)
                + milliseconds_to_nanoseconds(timeoutMillis);

        for (;;) {
            int result = pollOnce(timeoutMillis, outFd, outEvents, outData);
            if (result != ALOOPER_POLL_CALLBACK) {
                return result;
            }

            nsecs_t timeoutNanos = endTime - systemTime(SYSTEM_TIME_MONOTONIC);
            if (timeoutNanos <= 0) {
                return ALOOPER_POLL_TIMEOUT;
            }

            timeoutMillis = int(nanoseconds_to_milliseconds(timeoutNanos + 999999LL));
        }
    }
}

void Looper::wake() {
#if DEBUG_POLL_AND_WAKE
    LOGD("%p ~ wake", this);
#endif

    ssize_t nWrite;
    do {
        nWrite = write(mWakeWritePipeFd, "W", 1);
    } while (nWrite == -1 && errno == EINTR);

    if (nWrite != 1) {
        if (errno != EAGAIN) {
            LOGW("Could not write wake signal, errno=%d", errno);
        }
    }
}

int Looper::addFd(int fd, int ident, int events, ALooper_callbackFunc callback, void* data) {
#if DEBUG_CALLBACKS
    LOGD("%p ~ addFd - fd=%d, ident=%d, events=0x%x, callback=%p, data=%p", this, fd, ident,
            events, callback, data);
#endif

    int epollEvents = 0;
    if (events & ALOOPER_EVENT_INPUT) epollEvents |= EPOLLIN;
    if (events & ALOOPER_EVENT_OUTPUT) epollEvents |= EPOLLOUT;
    if (events & ALOOPER_EVENT_ERROR) epollEvents |= EPOLLERR;
    if (events & ALOOPER_EVENT_HANGUP) epollEvents |= EPOLLHUP;

    if (epollEvents == 0) {
        LOGE("Invalid attempt to set a callback with no selected poll events.");
        return -1;
    }

    if (! callback) {
        if (! mAllowNonCallbacks) {
            LOGE("Invalid attempt to set NULL callback but not allowed for this looper.");
            return -1;
        }

        if (ident < 0) {
            LOGE("Invalid attempt to set NULL callback with ident <= 0.");
            return -1;
        }
    }

    { // acquire lock
        AutoMutex _l(mLock);

        Request request;
        request.fd = fd;
        request.ident = ident;
        request.callback = callback;
        request.data = data;

        struct epoll_event eventItem;
        eventItem.events = epollEvents;
        eventItem.data.fd = fd;

        ssize_t requestIndex = mRequests.indexOfKey(fd);
        if (requestIndex < 0) {
            int epollResult = epoll_ctl(mEpollFd, EPOLL_CTL_ADD, fd, & eventItem);
            if (epollResult < 0) {
                LOGE("Error adding epoll events for fd %d, errno=%d", fd, errno);
                return -1;
            }
            mRequests.add(fd, request);
        } else {
            int epollResult = epoll_ctl(mEpollFd, EPOLL_CTL_MOD, fd, & eventItem);
            if (epollResult < 0) {
                LOGE("Error modifying epoll events for fd %d, errno=%d", fd, errno);
                return -1;
            }
            mRequests.replaceValueAt(requestIndex, request);
        }
    } // release lock
    return 1;
}

int Looper::removeFd(int fd) {
#if DEBUG_CALLBACKS
    LOGD("%p ~ removeFd - fd=%d", this, fd);
#endif

    { // acquire lock
        AutoMutex _l(mLock);
        ssize_t requestIndex = mRequests.indexOfKey(fd);
        if (requestIndex < 0) {
            return 0;
        }

        int epollResult = epoll_ctl(mEpollFd, EPOLL_CTL_DEL, fd, NULL);
        if (epollResult < 0) {
            LOGE("Error removing epoll events for fd %d, errno=%d", fd, errno);
            return -1;
        }

        mRequests.removeItemsAt(requestIndex);
    } // request lock
    return 1;
}

} // namespace android
