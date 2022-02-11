/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.keyguard;


import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BATTERY;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BIOMETRIC_MESSAGE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_DISCLOSURE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_OWNER_INFO;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.util.concurrency.DelayableExecutor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class KeyguardIndicationRotateTextViewControllerTest extends SysuiTestCase {

    private static final String TEST_MESSAGE = "test message";
    private static final String TEST_MESSAGE_2 = "test message two";
    private int mMsgId = 0;

    @Mock
    private DelayableExecutor mExecutor;
    @Mock
    private KeyguardIndicationTextView mView;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Captor
    private ArgumentCaptor<StatusBarStateController.StateListener> mStatusBarStateListenerCaptor;

    private KeyguardIndicationRotateTextViewController mController;
    private StatusBarStateController.StateListener mStatusBarStateListener;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mView.getTextColors()).thenReturn(ColorStateList.valueOf(Color.WHITE));
        mController = new KeyguardIndicationRotateTextViewController(mView, mExecutor,
                mStatusBarStateController);
        mController.onViewAttached();

        verify(mStatusBarStateController).addCallback(mStatusBarStateListenerCaptor.capture());
        mStatusBarStateListener = mStatusBarStateListenerCaptor.getValue();
    }

    @Test
    public void testInitialState_noIndication() {
        assertFalse(mController.hasIndications());
    }

    @Test
    public void testShowOneIndication() {
        // WHEN we add our first indication
        final KeyguardIndication indication = createIndication();
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, indication, false);

        // THEN
        // - we see controller has an indication
        // - the indication shows immediately since it's the only one
        // - no next indication is scheduled since there's only one indication
        assertTrue(mController.hasIndications());
        verify(mView).switchIndication(indication);
        verify(mExecutor, never()).executeDelayed(any(), anyLong());
    }

    @Test
    public void testShowTwoRotatingMessages() {
        // GIVEN we already have an indication message
        mController.updateIndication(
                INDICATION_TYPE_OWNER_INFO, createIndication(), false);
        reset(mView);

        // WHEN we have a new indication type to display
        final KeyguardIndication indication2 = createIndication();
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, indication2, false);

        // THEN
        // - we don't immediately see the new message until the delay
        // - next indication is scheduled
        verify(mView, never()).switchIndication(indication2);
        verify(mExecutor).executeDelayed(any(), anyLong());
    }

    @Test
    public void testUpdateCurrentMessage() {
        // GIVEN we already have an indication message
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, createIndication(), false);
        reset(mView);

        // WHEN we have a new message for this indication type to display
        final KeyguardIndication indication2 = createIndication();
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, indication2, false);

        // THEN
        // - new indication is updated immediately
        // - we don't schedule to show anything later
        verify(mView).switchIndication(indication2);
        verify(mExecutor, never()).executeDelayed(any(), anyLong());
    }

    @Test
    public void testUpdateRotatingMessageForUndisplayedIndication() {
        // GIVEN we already have two indication messages
        mController.updateIndication(
                INDICATION_TYPE_OWNER_INFO, createIndication(), false);
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, createIndication(), false);
        reset(mView);
        reset(mExecutor);

        // WHEN we have a new message for an undisplayed indication type
        final KeyguardIndication indication3 = createIndication();
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, indication3, false);

        // THEN
        // - we don't immediately update
        // - we don't schedule to show anything new
        verify(mView, never()).switchIndication(indication3);
        verify(mExecutor, never()).executeDelayed(any(), anyLong());
    }

    @Test
    public void testUpdateImmediately() {
        // GIVEN we already have three indication messages
        mController.updateIndication(
                INDICATION_TYPE_OWNER_INFO, createIndication(), false);
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, createIndication(), false);
        mController.updateIndication(
                INDICATION_TYPE_BATTERY, createIndication(), false);
        reset(mView);
        reset(mExecutor);

        // WHEN we have a new message for a currently shown type that we want to show immediately
        final KeyguardIndication indication4 = createIndication();
        mController.updateIndication(
                INDICATION_TYPE_BATTERY, indication4, true);

        // THEN
        // - we immediately update
        // - we schedule a new delayable to show the next message later
        verify(mView).switchIndication(indication4);
        verify(mExecutor).executeDelayed(any(), anyLong());

        // WHEN an already existing type is updated to show immediately
        reset(mView);
        reset(mExecutor);
        final KeyguardIndication indication5 = createIndication();
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, indication5, true);

        // THEN
        // - we immediately update
        // - we schedule a new delayable to show the next message later
        verify(mView).switchIndication(indication5);
        verify(mExecutor).executeDelayed(any(), anyLong());
    }

    @Test
    public void testSameMessage_noIndicationUpdate() {
        // GIVEN we are showing and indication with a test message
        mController.updateIndication(
                INDICATION_TYPE_OWNER_INFO, createIndication(TEST_MESSAGE), true);
        reset(mView);
        reset(mExecutor);

        // WHEN the same type tries to show the same exact message
        final KeyguardIndication sameIndication = createIndication(TEST_MESSAGE);
        mController.updateIndication(
                INDICATION_TYPE_OWNER_INFO, sameIndication, true);

        // THEN
        // - we don't update the indication b/c there's no reason the animate the same text
        verify(mView, never()).switchIndication(sameIndication);
    }

    @Test
    public void testTransientIndication() {
        // GIVEN we already have two indication messages
        mController.updateIndication(
                INDICATION_TYPE_OWNER_INFO, createIndication(), false);
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, createIndication(), false);
        reset(mView);
        reset(mExecutor);

        // WHEN we have a transient message
        mController.showTransient(TEST_MESSAGE_2);

        // THEN
        // - we immediately update
        // - we schedule a new delayable to show the next message later
        verify(mView).switchIndication(any(KeyguardIndication.class));
        verify(mExecutor).executeDelayed(any(), anyLong());
    }

    @Test
    public void testHideIndicationOneMessage() {
        // GIVEN we have one indication message
        KeyguardIndication indication = createIndication();
        mController.updateIndication(
                INDICATION_TYPE_OWNER_INFO, indication, false);
        verify(mView).switchIndication(indication);
        reset(mView);

        // WHEN we hide the current indication type
        mController.hideIndication(INDICATION_TYPE_OWNER_INFO);

        // THEN we immediately update the text to show no text
        verify(mView).switchIndication(null);
    }

    @Test
    public void testHideIndicationTwoMessages() {
        // GIVEN we have two indication messages
        final KeyguardIndication indication1 = createIndication();
        final KeyguardIndication indication2 = createIndication();
        mController.updateIndication(
                INDICATION_TYPE_OWNER_INFO, indication1, false);
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, indication2, false);
        assertTrue(mController.isNextIndicationScheduled());

        // WHEN we hide the current indication type
        mController.hideIndication(INDICATION_TYPE_OWNER_INFO);

        // THEN we show the next indication and there's no scheduled next indication
        verify(mView).switchIndication(indication2);
        assertFalse(mController.isNextIndicationScheduled());
    }

    @Test
    public void testStartDozing() {
        // GIVEN a biometric message is showing
        mController.updateIndication(INDICATION_TYPE_BIOMETRIC_MESSAGE,
                createIndication(), true);

        // WHEN the device is dozing
        mStatusBarStateListener.onDozingChanged(true);

        // THEN switch to INDICATION_TYPE_NONE
        verify(mView).switchIndication(null);
    }

    @Test
    public void testStoppedDozing() {
        // GIVEN we're dozing & we have an indication message
        mStatusBarStateListener.onDozingChanged(true);
        final KeyguardIndication indication = createIndication();
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, indication, false);
        reset(mView);
        reset(mExecutor);

        // WHEN the device is no longer dozing
        mStatusBarStateListener.onDozingChanged(false);

        // THEN show the next message
        verify(mView).switchIndication(indication);
    }

    @Test
    public void testIsDozing() {
        // GIVEN the device is dozing
        mStatusBarStateListener.onDozingChanged(true);
        reset(mView);

        // WHEN an indication is updated
        final KeyguardIndication indication = createIndication();
        mController.updateIndication(
                INDICATION_TYPE_DISCLOSURE, indication, false);

        // THEN no message is shown since we're dozing
        verify(mView, never()).switchIndication(any());
    }

    /**
     * Create an indication with a unique message.
     */
    private KeyguardIndication createIndication() {
        return createIndication(TEST_MESSAGE + " " + mMsgId++);
    }

    /**
     * Create an indication with the given message.
     */
    private KeyguardIndication createIndication(String msg) {
        return new KeyguardIndication.Builder()
                .setMessage(msg)
                .setTextColor(ColorStateList.valueOf(Color.WHITE))
                .build();
    }
}
