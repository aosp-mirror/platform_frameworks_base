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

#ifndef UTILS_POLL_LOOP_H
#define UTILS_POLL_LOOP_H

#include <utils/Vector.h>
#include <utils/threads.h>

#include <poll.h>

namespace android {

/**
 * A basic file descriptor polling loop based on poll() with callbacks.
 */
class PollLoop : public RefBase {
protected:
    virtual ~PollLoop();

public:
    PollLoop();

    /**
     * A callback that it to be invoked when an event occurs on a file descriptor.
     * Specifies the events that were triggered and the user data provided when the
     * callback was set.
     *
     * Returns true if the callback should be kept, false if it should be removed automatically
     * after the callback returns.
     */
    typedef bool (*Callback)(int fd, int events, void* data);

    /**
     * Performs a single call to poll() with optional timeout in milliseconds.
     * Invokes callbacks for all file descriptors on which an event occurred.
     *
     * If the timeout is zero, returns immediately without blocking.
     * If the timeout is negative, waits indefinitely until awoken.
     *
     * Returns true if a callback was invoked or if the loop was awoken by wake().
     * Returns false if a timeout or error occurred.
     *
     * This method must only be called on the main thread.
     * This method blocks until either a file descriptor is signalled, a timeout occurs,
     * or wake() is called.
     * This method does not return until it has finished invoking the appropriate callbacks
     * for all file descriptors that were signalled.
     */
    bool pollOnce(int timeoutMillis);

    /**
     * Wakes the loop asynchronously.
     *
     * This method can be called on any thread.
     * This method returns immediately.
     */
    void wake();

    /**
     * Sets the callback for a file descriptor, replacing the existing one, if any.
     * It is an error to call this method with events == 0 or callback == NULL.
     *
     * Note that a callback can be invoked with the POLLERR, POLLHUP or POLLNVAL events
     * even if it is not explicitly requested when registered.
     *
     * This method can be called on any thread.
     * This method may block briefly if it needs to wake the poll loop.
     */
    void setCallback(int fd, int events, Callback callback, void* data = NULL);

    /**
     * Removes the callback for a file descriptor, if one exists.
     *
     * When this method returns, it is safe to close the file descriptor since the poll loop
     * will no longer have a reference to it.  However, it is possible for the callback to
     * already be running or for it to run one last time if the file descriptor was already
     * signalled.  Calling code is responsible for ensuring that this case is safely handled.
     * For example, if the callback takes care of removing itself during its own execution either
     * by returning false or calling this method, then it can be guaranteed to not be invoked
     * again at any later time unless registered anew.
     *
     * This method can be called on any thread.
     * This method may block briefly if it needs to wake the poll loop.
     *
     * Returns true if a callback was actually removed, false if none was registered.
     */
    bool removeCallback(int fd);

private:
    struct RequestedCallback {
        Callback callback;
        void* data;
    };

    struct PendingCallback {
        int fd;
        int events;
        Callback callback;
        void* data;
    };

    Mutex mLock;
    Condition mAwake;
    bool mPolling;

    int mWakeReadPipeFd;
    int mWakeWritePipeFd;

    Vector<struct pollfd> mRequestedFds;
    Vector<RequestedCallback> mRequestedCallbacks;

    Vector<PendingCallback> mPendingCallbacks; // used privately by pollOnce

    void openWakePipe();
    void closeWakePipe();

    ssize_t getRequestIndexLocked(int fd);
    void wakeAndLock();
};

} // namespace android

#endif // UTILS_POLL_LOOP_H
