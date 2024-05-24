/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.platform.test.ravenwood.nativesubstitution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class MessageQueue_host {
    private static final AtomicLong sNextId = new AtomicLong(1);
    private static final Map<Long, MessageQueue_host> sInstances = new ConcurrentHashMap<>();

    private boolean mDeleted = false;

    private final Object mPoller = new Object();
    private volatile boolean mPolling;
    private volatile boolean mPendingWake;

    private void validate() {
        if (mDeleted) {
            // TODO: Put more info
            throw new RuntimeException("MessageQueue already destroyed");
        }
    }

    private static MessageQueue_host getInstance(long id) {
        MessageQueue_host q = sInstances.get(id);
        if (q == null) {
            throw new RuntimeException("MessageQueue doesn't exist with id=" + id);
        }
        q.validate();
        return q;
    }

    public static long nativeInit() {
        final long id = sNextId.getAndIncrement();
        final MessageQueue_host q = new MessageQueue_host();
        sInstances.put(id, q);
        return id;
    }

    public static void nativeDestroy(long ptr) {
        getInstance(ptr).mDeleted = true;
        sInstances.remove(ptr);
    }

    public static void nativePollOnce(android.os.MessageQueue queue, long ptr, int timeoutMillis) {
        var q = getInstance(ptr);
        synchronized (q.mPoller) {
            q.mPolling = true;
            try {
                if (q.mPendingWake) {
                    // Calling with pending wake returns immediately
                } else if (timeoutMillis == 0) {
                    // Calling epoll_wait() with 0 returns immediately
                } else if (timeoutMillis == -1) {
                    q.mPoller.wait();
                } else {
                    q.mPoller.wait(timeoutMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Any reason for returning counts as a "wake", so clear pending
            q.mPendingWake = false;
            q.mPolling = false;
        }
    }

    public static void nativeWake(long ptr) {
        var q = getInstance(ptr);
        synchronized (q.mPoller) {
            q.mPendingWake = true;
            q.mPoller.notifyAll();
        }
    }

    public static boolean nativeIsPolling(long ptr) {
        var q = getInstance(ptr);
        return q.mPolling;
    }

    public static void nativeSetFileDescriptorEvents(long ptr, int fd, int events) {
        throw new UnsupportedOperationException();
    }
}
