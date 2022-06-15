/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.testng.AssertJUnit.assertTrue;

import android.annotation.NonNull;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link MagnificationGestureHandler}.
 */
@RunWith(AndroidJUnit4.class)
public class MagnificationGestureHandlerTest {

    private TestMagnificationGestureHandler mMgh;
    private static final int DISPLAY_0 = 0;
    private static final int FULLSCREEN_MODE =
            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;

    @Mock
    MagnificationGestureHandler.Callback mCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mMgh = new TestMagnificationGestureHandler(DISPLAY_0,
                /* detectTripleTap= */true,
                /* detectShortcutTrigger= */true,
                mCallback);
    }

    @Test
    public void onMotionEvent_isFromScreen_onMotionEventInternal() {
        final MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        downEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);

        mMgh.onMotionEvent(downEvent, downEvent, /* policyFlags= */ 0);

        try {
            assertTrue(mMgh.mIsInternalMethodCalled);
        } finally {
            downEvent.recycle();
        }
    }

    @Test
    public void onMotionEvent_downEvent_handleInteractionStart() {
        final MotionEvent downEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0, 0, 0);
        downEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);

        mMgh.onMotionEvent(downEvent, downEvent, /* policyFlags= */ 0);

        try {
            verify(mCallback).onTouchInteractionStart(eq(DISPLAY_0), eq(mMgh.getMode()));
        } finally {
            downEvent.recycle();
        }
    }

    @Test
    public void onMotionEvent_upEvent_handleInteractionEnd() {
        final MotionEvent upEvent = MotionEvent.obtain(0, 0, ACTION_UP, 0, 0, 0);
        upEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);

        mMgh.onMotionEvent(upEvent, upEvent, /* policyFlags= */ 0);

        try {
            verify(mCallback).onTouchInteractionEnd(eq(DISPLAY_0), eq(mMgh.getMode()));
        } finally {
            upEvent.recycle();
        }
    }

    @Test
    public void onMotionEvent_cancelEvent_handleInteractionEnd() {
        final MotionEvent cancelEvent = MotionEvent.obtain(0, 0, ACTION_CANCEL, 0, 0, 0);
        cancelEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);

        mMgh.onMotionEvent(cancelEvent, cancelEvent, /* policyFlags= */ 0);

        try {
            verify(mCallback).onTouchInteractionEnd(eq(DISPLAY_0), eq(mMgh.getMode()));
        } finally {
            cancelEvent.recycle();
        }
    }


    @Test
    public void notifyShortcutTriggered_callsOnShortcutTriggered() {
        mMgh.notifyShortcutTriggered();

        verify(mCallback).onShortcutTriggered(eq(DISPLAY_0), eq(mMgh.getMode()));
    }

    private static class TestMagnificationGestureHandler extends MagnificationGestureHandler {

        boolean mIsInternalMethodCalled = false;

        TestMagnificationGestureHandler(int displayId, boolean detectTripleTap,
                boolean detectShortcutTrigger, @NonNull Callback callback) {
            super(displayId, detectTripleTap, detectShortcutTrigger, callback);
        }

        @Override
        void onMotionEventInternal(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            mIsInternalMethodCalled = true;
        }

        @Override
        public void notifyShortcutTriggered() {
            super.notifyShortcutTriggered();
        }

        @Override
        public void handleShortcutTriggered() {
        }

        @Override
        public int getMode() {
            return FULLSCREEN_MODE;
        }
    }
}
