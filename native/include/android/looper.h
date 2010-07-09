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


#ifndef ANDROID_LOOPER_H
#define ANDROID_LOOPER_H

#include <poll.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * ALooper
 *
 * A looper is the state tracking an event loop for a thread.
 * Loopers do not define event structures or other such things; rather
 * they are a lower-level facility to attach one or more discrete objects
 * listening for an event.  An "event" here is simply data available on
 * a file descriptor: each attached object has an associated file descriptor,
 * and waiting for "events" means (internally) polling on all of these file
 * descriptors until one or more of them have data available.
 *
 * A thread can have only one ALooper associated with it.
 */
struct ALooper;
typedef struct ALooper ALooper;

/**
 * For callback-based event loops, this is the prototype of the function
 * that is called.  It is given the file descriptor it is associated with,
 * a bitmask of the poll events that were triggered (typically POLLIN), and
 * the data pointer that was originally supplied.
 *
 * Implementations should return 1 to continue receiving callbacks, or 0
 * to have this file descriptor and callback unregistered from the looper.
 */
typedef int ALooper_callbackFunc(int fd, int events, void* data);

/**
 * Return the ALooper associated with the calling thread, or NULL if
 * there is not one.
 */
ALooper* ALooper_forThread();

enum {
    /**
     * Option for ALooper_prepare: this ALooper will accept calls to
     * ALooper_addFd() that do not have a callback (that is provide NULL
     * for the callback).  In this case the caller of ALooper_pollOnce()
     * or ALooper_pollAll() MUST check the return from these functions to
     * discover when data is available on such fds and process it.
     */
    ALOOPER_PREPARE_ALLOW_NON_CALLBACKS = 1<<0
};

/**
 * Prepare an ALooper associated with the calling thread, and return it.
 * If the thread already has an ALooper, it is returned.  Otherwise, a new
 * one is created, associated with the thread, and returned.
 *
 * The opts may be ALOOPER_PREPARE_ALLOW_NON_CALLBACKS or 0.
 */
ALooper* ALooper_prepare(int32_t opts);

enum {
    /**
     * Result from ALooper_pollOnce() and ALooper_pollAll(): one or
     * more callbacks were executed.
     */
    ALOOPER_POLL_CALLBACK = -1,
    
    /**
     * Result from ALooper_pollOnce() and ALooper_pollAll(): the
     * timeout expired.
     */
    ALOOPER_POLL_TIMEOUT = -2,
    
    /**
     * Result from ALooper_pollOnce() and ALooper_pollAll(): an error
     * occurred.
     */
    ALOOPER_POLL_ERROR = -3,
};

/**
 * Wait for events to be available, with optional timeout in milliseconds.
 * Invokes callbacks for all file descriptors on which an event occurred.
 *
 * If the timeout is zero, returns immediately without blocking.
 * If the timeout is negative, waits indefinitely until an event appears.
 *
 * Returns ALOOPER_POLL_CALLBACK if a callback was invoked.
 *
 * Returns ALOOPER_POLL_TIMEOUT if there was no data before the given
 * timeout expired.
 *
 * Returns ALOPER_POLL_ERROR if an error occurred.
 *
 * Returns a value >= 0 containing a file descriptor if it has data
 * and it has no callback function (requiring the caller here to handle it).
 * In this (and only this) case outEvents and outData will contain the poll
 * events and data associated with the fd.
 *
 * This method does not return until it has finished invoking the appropriate callbacks
 * for all file descriptors that were signalled.
 */
int32_t ALooper_pollOnce(int timeoutMillis, int* outEvents, void** outData);

/**
 * Like ALooper_pollOnce(), but performs all pending callbacks until all
 * data has been consumed or a file descriptor is available with no callback.
 * This function will never return ALOOPER_POLL_CALLBACK.
 */
int32_t ALooper_pollAll(int timeoutMillis, int* outEvents, void** outData);

/**
 * Acquire a reference on the given ALooper object.  This prevents the object
 * from being deleted until the reference is removed.  This is only needed
 * to safely hand an ALooper from one thread to another.
 */
void ALooper_acquire(ALooper* looper);

/**
 * Remove a reference that was previously acquired with ALooper_acquire().
 */
void ALooper_release(ALooper* looper);

/**
 * Add a new file descriptor to be polled by the looper.  If the same file
 * descriptor was previously added, it is replaced.
 *
 * "fd" is the file descriptor to be added.
 * "events" are the poll events to wake up on.  Typically this is POLLIN.
 * "callback" is the function to call when there is an event on the file
 * descriptor.
 * "id" is an identifier to associated with this file descriptor, or 0.
 * "data" is a private data pointer to supply to the callback.
 *
 * There are two main uses of this function:
 *
 * (1) If "callback" is non-NULL, then
 * this function will be called when there is data on the file descriptor.  It
 * should execute any events it has pending, appropriately reading from the
 * file descriptor.
 *
 * (2) If "callback" is NULL, the fd will be returned by ALooper_pollOnce
 * when it has data available, requiring the caller to take care of processing
 * it.
 */
void ALooper_addFd(ALooper* looper, int fd, int events,
        ALooper_callbackFunc* callback, void* data);

/**
 * Remove a previously added file descriptor from the looper.
 */
int32_t ALooper_removeFd(ALooper* looper, int fd);

#ifdef __cplusplus
};
#endif

#endif // ANDROID_NATIVE_WINDOW_H
