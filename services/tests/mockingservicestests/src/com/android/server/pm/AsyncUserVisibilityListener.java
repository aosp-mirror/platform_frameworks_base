/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.pm;

import static org.junit.Assert.fail;

import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.pm.UserManagerInternal.UserVisibilityListener;

import com.google.common.truth.Expect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
/**
 * {@link UserVisibilityListener} implementation that expects callback events to be asynchronously
 * received.
 */
public final class AsyncUserVisibilityListener implements UserVisibilityListener {

    private static final String TAG = AsyncUserVisibilityListener.class.getSimpleName();

    private static final long WAIT_TIMEOUT_MS = 2_000;
    private static final long WAIT_NO_EVENTS_TIMEOUT_MS = 100;

    private static int sNextId;

    private final Object mLock = new Object();
    private final Expect mExpect;
    private final int mId = ++sNextId;
    private final Thread mExpectedReceiverThread;
    private final CountDownLatch mLatch;
    private final List<UserVisibilityChangedEvent> mExpectedEvents;

    @GuardedBy("mLock")
    private final List<UserVisibilityChangedEvent> mReceivedEvents = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<String> mErrors = new ArrayList<>();

    private AsyncUserVisibilityListener(Expect expect, Thread expectedReceiverThread,
            List<UserVisibilityChangedEvent> expectedEvents) {
        mExpect = expect;
        mExpectedReceiverThread = expectedReceiverThread;
        mExpectedEvents = expectedEvents;
        mLatch = new CountDownLatch(expectedEvents.size());
    }

    @Override
    public void onUserVisibilityChanged(int userId, boolean visible) {
        UserVisibilityChangedEvent event = new UserVisibilityChangedEvent(userId, visible);
        Thread callingThread = Thread.currentThread();
        Log.d(TAG, "Received event (" + event + ") on thread " + callingThread);

        if (callingThread != mExpectedReceiverThread) {
            addError("event %s received in on thread %s but was expected on thread %s",
                    event, callingThread, mExpectedReceiverThread);
        }
        synchronized (mLock) {
            mReceivedEvents.add(event);
            mLatch.countDown();
        }
    }

    /**
     * Verifies the expected events were called.
     */
    public void verify() throws InterruptedException {
        waitForEventsAndCheckErrors();

        List<UserVisibilityChangedEvent> receivedEvents = getReceivedEvents();

        if (receivedEvents.isEmpty()) {
            mExpect.withMessage("received events").that(receivedEvents).isEmpty();
            return;
        }

        // NOTE: check "inOrder" might be too harsh in some cases (for example, if the fg user
        // has 2 profiles, the order of the events on the profiles wouldn't matter), but we
        // still need some dependency (like "user A became invisible before user B became
        // visible", so this is fine for now (but eventually we might need to add more
        // sophisticated assertions)
        mExpect.withMessage("received events").that(receivedEvents)
                .containsExactlyElementsIn(mExpectedEvents).inOrder();
    }

    @Override
    public String toString() {
        List<UserVisibilityChangedEvent> receivedEvents = getReceivedEvents();
        return "[" + getClass().getSimpleName() + ": id=" + mId
                + ", creationThread=" + mExpectedReceiverThread
                + ", received=" + receivedEvents.size()
                + ", events=" + receivedEvents + "]";
    }

    private List<UserVisibilityChangedEvent> getReceivedEvents() {
        synchronized (mLock) {
            return Collections.unmodifiableList(mReceivedEvents);
        }
    }

    private void waitForEventsAndCheckErrors() throws InterruptedException {
        waitForEvents();
        synchronized (mLock) {
            if (!mErrors.isEmpty()) {
                fail(mErrors.size() + " errors on received events: " + mErrors);
            }
        }
    }

    private void waitForEvents() throws InterruptedException {
        if (mExpectedEvents.isEmpty()) {
            Log.v(TAG, "Sleeping " + WAIT_NO_EVENTS_TIMEOUT_MS + "ms to make sure no event is "
                    + "received");
            Thread.sleep(WAIT_NO_EVENTS_TIMEOUT_MS);
            return;
        }

        int expectedNumberEvents = mExpectedEvents.size();
        Log.v(TAG, "Waiting up to " + WAIT_TIMEOUT_MS + "ms until " + expectedNumberEvents
                + " events are received");
        if (!mLatch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            List<UserVisibilityChangedEvent> receivedEvents = getReceivedEvents();
            addError("Timed out (%d ms) waiting for %d events; received %d so far (%s), "
                    + "but expecting %d (%s)", WAIT_NO_EVENTS_TIMEOUT_MS, expectedNumberEvents,
                    receivedEvents.size(), receivedEvents, expectedNumberEvents, mExpectedEvents);
        }
    }

    @SuppressWarnings("AnnotateFormatMethod")
    private void addError(String format, Object...args) {
        synchronized (mLock) {
            mErrors.add(String.format(format, args));
        }
    }

    /**
     * Factory for {@link AsyncUserVisibilityListener} objects.
     */
    public static final class Factory {
        private final Expect mExpect;
        private final Thread mExpectedReceiverThread;

        public Factory(Expect expect, Thread expectedReceiverThread) {
            mExpect = expect;
            mExpectedReceiverThread = expectedReceiverThread;
        }

        /**
         * Creates a {@link AsyncUserVisibilityListener} that is expecting the given events.
         */
        public AsyncUserVisibilityListener forEvents(UserVisibilityChangedEvent...expectedEvents) {
            return new AsyncUserVisibilityListener(mExpect, mExpectedReceiverThread,
                    Arrays.asList(expectedEvents));
        }

        /**
         * Creates a {@link AsyncUserVisibilityListener} that is expecting no events.
         */
        public AsyncUserVisibilityListener forNoEvents() {
            return new AsyncUserVisibilityListener(mExpect, mExpectedReceiverThread,
                    Collections.emptyList());
        }
    }
}
