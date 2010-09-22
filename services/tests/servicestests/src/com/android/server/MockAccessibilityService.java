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

package com.android.server;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Message;
import android.view.accessibility.AccessibilityEvent;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import junit.framework.TestCase;

/**
 * This is the base class for mock {@link AccessibilityService}s.
 */
public abstract class MockAccessibilityService extends AccessibilityService {

    /**
     * The event this service expects to receive.
     */
    private final Queue<AccessibilityEvent> mExpectedEvents = new LinkedList<AccessibilityEvent>();

    /**
     * Interruption call this service expects to receive.
     */
    private boolean mExpectedInterrupt;

    /**
     * Flag if the mock is currently replaying.
     */
    private boolean mReplaying;

    /**
     * Flag if the system is bound as a client to this service.
     */
    private boolean mIsSystemBoundAsClient;

    /**
     * Creates an {@link AccessibilityServiceInfo} populated with default
     * values.
     *
     * @return The default info.
     */
    public static AccessibilityServiceInfo createDefaultInfo() {
        AccessibilityServiceInfo defaultInfo = new AccessibilityServiceInfo();
        defaultInfo.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED;
        defaultInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_AUDIBLE;
        defaultInfo.flags = 0;
        defaultInfo.notificationTimeout = 0;
        defaultInfo.packageNames = new String[] {
            "foo.bar.baz"
        };

        return defaultInfo;
    }

    /**
     * Starts replaying the mock.
     */
    public void replay() {
        mReplaying = true;
    }

    /**
     * Verifies if all expected service methods have been called.
     */
    public void verify() {
        if (!mReplaying) {
            throw new IllegalStateException("Did you forget to call replay()");
        }

        if (mExpectedInterrupt) {
            throw new IllegalStateException("Expected call to #interrupt() not received");
        }
        if (!mExpectedEvents.isEmpty()) {
            throw new IllegalStateException("Expected a call to onAccessibilityEvent() for "
                    + "events \"" + mExpectedEvents + "\" not received");
        }
    }

    /**
     * Resets this instance so it can be reused.
     */
    public void reset() {
        mExpectedEvents.clear();
        mExpectedInterrupt = false;
        mReplaying = false;
    }

    /**
     * Sets an expected call to
     * {@link #onAccessibilityEvent(AccessibilityEvent)} with given event as
     * argument.
     *
     * @param expectedEvent The expected event argument.
     */
    public void expectEvent(AccessibilityEvent expectedEvent) {
        mExpectedEvents.add(expectedEvent);
    }

    /**
     * Sets an expected call of {@link #onInterrupt()}.
     */
    public void expectInterrupt() {
        mExpectedInterrupt = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent receivedEvent) {
        if (!mReplaying) {
            return;
        }

        if (mExpectedEvents.isEmpty()) {
            throw new IllegalStateException("Unexpected event: " + receivedEvent);
        }

        AccessibilityEvent expectedEvent = mExpectedEvents.poll();
        assertEqualsAccessiblityEvent(expectedEvent, receivedEvent);
    }

    @Override
    public void onInterrupt() {
        if (!mReplaying) {
            return;
        }

        if (!mExpectedInterrupt) {
            throw new IllegalStateException("Unexpected call to onInterrupt()");
        }

        mExpectedInterrupt = false;
    }

    @Override
    protected void onServiceConnected() {
        mIsSystemBoundAsClient = true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mIsSystemBoundAsClient = false;
        return false;
    }

    /**
     * Returns if the system is bound as client to this service.
     *
     * @return True if the system is bound, false otherwise.
     */
    public boolean isSystemBoundAsClient() {
        return mIsSystemBoundAsClient;
    }

    /**
     * Compares all properties of the <code>expectedEvent</code> and the
     * <code>receviedEvent</code> to verify that the received event is the one
     * that is expected.
     */
    private void assertEqualsAccessiblityEvent(AccessibilityEvent expectedEvent,
            AccessibilityEvent receivedEvent) {
        TestCase.assertEquals("addedCount has incorrect value", expectedEvent.getAddedCount(),
                receivedEvent.getAddedCount());
        TestCase.assertEquals("beforeText has incorrect value", expectedEvent.getBeforeText(),
                receivedEvent.getBeforeText());
        TestCase.assertEquals("checked has incorrect value", expectedEvent.isChecked(),
                receivedEvent.isChecked());
        TestCase.assertEquals("className has incorrect value", expectedEvent.getClassName(),
                receivedEvent.getClassName());
        TestCase.assertEquals("contentDescription has incorrect value", expectedEvent
                .getContentDescription(), receivedEvent.getContentDescription());
        TestCase.assertEquals("currentItemIndex has incorrect value", expectedEvent
                .getCurrentItemIndex(), receivedEvent.getCurrentItemIndex());
        TestCase.assertEquals("enabled has incorrect value", expectedEvent.isEnabled(),
                receivedEvent.isEnabled());
        TestCase.assertEquals("eventType has incorrect value", expectedEvent.getEventType(),
                receivedEvent.getEventType());
        TestCase.assertEquals("fromIndex has incorrect value", expectedEvent.getFromIndex(),
                receivedEvent.getFromIndex());
        TestCase.assertEquals("fullScreen has incorrect value", expectedEvent.isFullScreen(),
                receivedEvent.isFullScreen());
        TestCase.assertEquals("itemCount has incorrect value", expectedEvent.getItemCount(),
                receivedEvent.getItemCount());
        assertEqualsNotificationAsParcelableData(expectedEvent, receivedEvent);
        TestCase.assertEquals("password has incorrect value", expectedEvent.isPassword(),
                receivedEvent.isPassword());
        TestCase.assertEquals("removedCount has incorrect value", expectedEvent.getRemovedCount(),
                receivedEvent.getRemovedCount());
        assertEqualsText(expectedEvent, receivedEvent);
    }

    /**
     * Compares the {@link android.os.Parcelable} data of the
     * <code>expectedEvent</code> and <code>receivedEvent</code> to verify that
     * the received event is the one that is expected.
     */
    private void assertEqualsNotificationAsParcelableData(AccessibilityEvent expectedEvent,
            AccessibilityEvent receivedEvent) {
        String message = "parcelableData has incorrect value";
        Message expectedMessage = (Message) expectedEvent.getParcelableData();
        Message receivedMessage = (Message) receivedEvent.getParcelableData();

        if (expectedMessage == null) {
            if (receivedMessage == null) {
                return;
            }
        }

        TestCase.assertNotNull(message, receivedMessage);

        // we do a very simple sanity check since we do not test Message
        TestCase.assertEquals(message, expectedMessage.what, receivedMessage.what);
    }

    /**
     * Compares the text of the <code>expectedEvent</code> and
     * <code>receivedEvent</code> by comparing the string representation of the
     * corresponding {@link CharSequence}s.
     */
    private void assertEqualsText(AccessibilityEvent expectedEvent,
            AccessibilityEvent receivedEvent) {
        String message = "text has incorrect value";
        List<CharSequence> expectedText = expectedEvent.getText();
        List<CharSequence> receivedText = receivedEvent.getText();

        TestCase.assertEquals(message, expectedText.size(), receivedText.size());

        Iterator<CharSequence> expectedTextIterator = expectedText.iterator();
        Iterator<CharSequence> receivedTextIterator = receivedText.iterator();

        for (int i = 0; i < expectedText.size(); i++) {
            // compare the string representation
            TestCase.assertEquals(message, expectedTextIterator.next().toString(),
                    receivedTextIterator.next().toString());
        }
    }
}
