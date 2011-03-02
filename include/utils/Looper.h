/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef UTILS_LOOPER_H
#define UTILS_LOOPER_H

#include <utils/threads.h>
#include <utils/RefBase.h>
#include <utils/KeyedVector.h>
#include <utils/Timers.h>

#include <android/looper.h>

// When defined, uses epoll_wait() for polling, otherwise uses poll().
#define LOOPER_USES_EPOLL

// When defined, logs performance statistics for tuning and debugging purposes.
//#define LOOPER_STATISTICS

#ifdef LOOPER_USES_EPOLL
#include <sys/epoll.h>
#else
#include <sys/poll.h>
#endif

/*
 * Declare a concrete type for the NDK's looper forward declaration.
 */
struct ALooper {
};

namespace android {

/**
 * A message that can be posted to a Looper.
 */
struct Message {
    Message() : what(0) { }
    Message(int what) : what(what) { }

    /* The message type. (interpretation is left up to the handler) */
    int what;
};


/**
 * Interface for a Looper message handler.
 *
 * The Looper holds a strong reference to the message handler whenever it has
 * a message to deliver to it.  Make sure to call Looper::removeMessages
 * to remove any pending messages destined for the handler so that the handler
 * can be destroyed.
 */
class MessageHandler : public virtual RefBase {
protected:
    virtual ~MessageHandler() { }

public:
    /**
     * Handles a message.
     */
    virtual void handleMessage(const Message& message) = 0;
};


/**
 * A simple proxy that holds a weak reference to a message handler.
 */
class WeakMessageHandler : public MessageHandler {
public:
    WeakMessageHandler(const wp<MessageHandler>& handler);
    virtual void handleMessage(const Message& message);

private:
    wp<MessageHandler> mHandler;
};


/**
 * A polling loop that supports monitoring file descriptor events, optionally
 * using callbacks.  The implementation uses epoll() internally.
 *
 * A looper can be associated with a thread although there is no requirement that it must be.
 */
class Looper : public ALooper, public RefBase {
protected:
    virtual ~Looper();

public:
    /**
     * Creates a looper.
     *
     * If allowNonCallbaks is true, the looper will allow file descriptors to be
     * registered without associated callbacks.  This assumes that the caller of
     * pollOnce() is prepared to handle callback-less events itself.
     */
    Looper(bool allowNonCallbacks);

    /**
     * Returns whether this looper instance allows the registration of file descriptors
     * using identifiers instead of callbacks.
     */
    bool getAllowNonCallbacks() const;

    /**
     * Waits for events to be available, with optional timeout in milliseconds.
     * Invokes callbacks for all file descriptors on which an event occurred.
     *
     * If the timeout is zero, returns immediately without blocking.
     * If the timeout is negative, waits indefinitely until an event appears.
     *
     * Returns ALOOPER_POLL_WAKE if the poll was awoken using wake() before
     * the timeout expired and no callbacks were invoked and no other file
     * descriptors were ready.
     *
     * Returns ALOOPER_POLL_CALLBACK if one or more callbacks were invoked.
     *
     * Returns ALOOPER_POLL_TIMEOUT if there was no data before the given
     * timeout expired.
     *
     * Returns ALOOPER_POLL_ERROR if an error occurred.
     *
     * Returns a value >= 0 containing an identifier if its file descriptor has data
     * and it has no callback function (requiring the caller here to handle it).
     * In this (and only this) case outFd, outEvents and outData will contain the poll
     * events and data associated with the fd, otherwise they will be set to NULL.
     *
     * This method does not return until it has finished invoking the appropriate callbacks
     * for all file descriptors that were signalled.
     */
    int pollOnce(int timeoutMillis, int* outFd, int* outEvents, void** outData);
    inline int pollOnce(int timeoutMillis) {
        return pollOnce(timeoutMillis, NULL, NULL, NULL);
    }

    /**
     * Like pollOnce(), but performs all pending callbacks until all
     * data has been consumed or a file descriptor is available with no callback.
     * This function will never return ALOOPER_POLL_CALLBACK.
     */
    int pollAll(int timeoutMillis, int* outFd, int* outEvents, void** outData);
    inline int pollAll(int timeoutMillis) {
        return pollAll(timeoutMillis, NULL, NULL, NULL);
    }

    /**
     * Wakes the poll asynchronously.
     *
     * This method can be called on any thread.
     * This method returns immediately.
     */
    void wake();

    /**
     * Adds a new file descriptor to be polled by the looper.
     * If the same file descriptor was previously added, it is replaced.
     *
     * "fd" is the file descriptor to be added.
     * "ident" is an identifier for this event, which is returned from ALooper_pollOnce().
     * The identifier must be >= 0, or ALOOPER_POLL_CALLBACK if providing a non-NULL callback.
     * "events" are the poll events to wake up on.  Typically this is ALOOPER_EVENT_INPUT.
     * "callback" is the function to call when there is an event on the file descriptor.
     * "data" is a private data pointer to supply to the callback.
     *
     * There are two main uses of this function:
     *
     * (1) If "callback" is non-NULL, then this function will be called when there is
     * data on the file descriptor.  It should execute any events it has pending,
     * appropriately reading from the file descriptor.  The 'ident' is ignored in this case.
     *
     * (2) If "callback" is NULL, the 'ident' will be returned by ALooper_pollOnce
     * when its file descriptor has data available, requiring the caller to take
     * care of processing it.
     *
     * Returns 1 if the file descriptor was added, 0 if the arguments were invalid.
     *
     * This method can be called on any thread.
     * This method may block briefly if it needs to wake the poll.
     */
    int addFd(int fd, int ident, int events, ALooper_callbackFunc callback, void* data);

    /**
     * Removes a previously added file descriptor from the looper.
     *
     * When this method returns, it is safe to close the file descriptor since the looper
     * will no longer have a reference to it.  However, it is possible for the callback to
     * already be running or for it to run one last time if the file descriptor was already
     * signalled.  Calling code is responsible for ensuring that this case is safely handled.
     * For example, if the callback takes care of removing itself during its own execution either
     * by returning 0 or by calling this method, then it can be guaranteed to not be invoked
     * again at any later time unless registered anew.
     *
     * Returns 1 if the file descriptor was removed, 0 if none was previously registered.
     *
     * This method can be called on any thread.
     * This method may block briefly if it needs to wake the poll.
     */
    int removeFd(int fd);

    /**
     * Enqueues a message to be processed by the specified handler.
     *
     * The handler must not be null.
     * This method can be called on any thread.
     */
    void sendMessage(const sp<MessageHandler>& handler, const Message& message);

    /**
     * Enqueues a message to be processed by the specified handler after all pending messages
     * after the specified delay.
     *
     * The time delay is specified in uptime nanoseconds.
     * The handler must not be null.
     * This method can be called on any thread.
     */
    void sendMessageDelayed(nsecs_t uptimeDelay, const sp<MessageHandler>& handler,
            const Message& message);

    /**
     * Enqueues a message to be processed by the specified handler after all pending messages
     * at the specified time.
     *
     * The time is specified in uptime nanoseconds.
     * The handler must not be null.
     * This method can be called on any thread.
     */
    void sendMessageAtTime(nsecs_t uptime, const sp<MessageHandler>& handler,
            const Message& message);

    /**
     * Removes all messages for the specified handler from the queue.
     *
     * The handler must not be null.
     * This method can be called on any thread.
     */
    void removeMessages(const sp<MessageHandler>& handler);

    /**
     * Removes all messages of a particular type for the specified handler from the queue.
     *
     * The handler must not be null.
     * This method can be called on any thread.
     */
    void removeMessages(const sp<MessageHandler>& handler, int what);

    /**
     * Prepares a looper associated with the calling thread, and returns it.
     * If the thread already has a looper, it is returned.  Otherwise, a new
     * one is created, associated with the thread, and returned.
     *
     * The opts may be ALOOPER_PREPARE_ALLOW_NON_CALLBACKS or 0.
     */
    static sp<Looper> prepare(int opts);

    /**
     * Sets the given looper to be associated with the calling thread.
     * If another looper is already associated with the thread, it is replaced.
     *
     * If "looper" is NULL, removes the currently associated looper.
     */
    static void setForThread(const sp<Looper>& looper);

    /**
     * Returns the looper associated with the calling thread, or NULL if
     * there is not one.
     */
    static sp<Looper> getForThread();

private:
    struct Request {
        int fd;
        int ident;
        ALooper_callbackFunc callback;
        void* data;
    };

    struct Response {
        int events;
        Request request;
    };

    struct MessageEnvelope {
        MessageEnvelope() : uptime(0) { }

        MessageEnvelope(nsecs_t uptime, const sp<MessageHandler> handler,
                const Message& message) : uptime(uptime), handler(handler), message(message) {
        }

        nsecs_t uptime;
        sp<MessageHandler> handler;
        Message message;
    };

    const bool mAllowNonCallbacks; // immutable

    int mWakeReadPipeFd;  // immutable
    int mWakeWritePipeFd; // immutable
    Mutex mLock;

    Vector<MessageEnvelope> mMessageEnvelopes; // guarded by mLock
    bool mSendingMessage; // guarded by mLock

#ifdef LOOPER_USES_EPOLL
    int mEpollFd; // immutable

    // Locked list of file descriptor monitoring requests.
    KeyedVector<int, Request> mRequests;  // guarded by mLock
#else
    // The lock guards state used to track whether there is a poll() in progress and whether
    // there are any other threads waiting in wakeAndLock().  The condition variables
    // are used to transfer control among these threads such that all waiters are
    // serviced before a new poll can begin.
    // The wakeAndLock() method increments mWaiters, wakes the poll, blocks on mAwake
    // until mPolling becomes false, then decrements mWaiters again.
    // The poll() method blocks on mResume until mWaiters becomes 0, then sets
    // mPolling to true, blocks until the poll completes, then resets mPolling to false
    // and signals mResume if there are waiters.
    bool mPolling;      // guarded by mLock
    uint32_t mWaiters;  // guarded by mLock
    Condition mAwake;   // guarded by mLock
    Condition mResume;  // guarded by mLock

    Vector<struct pollfd> mRequestedFds;  // must hold mLock and mPolling must be false to modify
    Vector<Request> mRequests;            // must hold mLock and mPolling must be false to modify

    ssize_t getRequestIndexLocked(int fd);
    void wakeAndLock();
#endif

#ifdef LOOPER_STATISTICS
    static const int SAMPLED_WAKE_CYCLES_TO_AGGREGATE = 100;
    static const int SAMPLED_POLLS_TO_AGGREGATE = 1000;

    nsecs_t mPendingWakeTime;
    int mPendingWakeCount;

    int mSampledWakeCycles;
    int mSampledWakeCountSum;
    nsecs_t mSampledWakeLatencySum;

    int mSampledPolls;
    int mSampledZeroPollCount;
    int mSampledZeroPollLatencySum;
    int mSampledTimeoutPollCount;
    int mSampledTimeoutPollLatencySum;
#endif

    // This state is only used privately by pollOnce and does not require a lock since
    // it runs on a single thread.
    Vector<Response> mResponses;
    size_t mResponseIndex;
    nsecs_t mNextMessageUptime; // set to LLONG_MAX when none

    int pollInner(int timeoutMillis);
    void awoken();
    void pushResponse(int events, const Request& request);

    static void initTLSKey();
    static void threadDestructor(void *st);
};

} // namespace android

#endif // UTILS_LOOPER_H
