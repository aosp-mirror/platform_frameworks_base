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
import static android.view.MotionEvent.ACTION_HOVER_MOVE;
import static android.view.MotionEvent.ACTION_UP;

import static junit.framework.Assert.assertFalse;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.testng.AssertJUnit.assertTrue;

import android.annotation.NonNull;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.accessibility.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link MagnificationGestureHandler}.
 */
@RunWith(AndroidJUnit4.class)
public class MagnificationGestureHandlerTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private TestMagnificationGestureHandler mMgh;
    private static final int DISPLAY_0 = 0;
    private static final int FULLSCREEN_MODE =
            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;

    @Mock
    AccessibilityTraceManager mTraceManager;
    @Mock
    MagnificationGestureHandler.Callback mCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mMgh = new TestMagnificationGestureHandler(DISPLAY_0,
                /* detectSingleFingerTripleTap= */true,
                /* detectTwoFingerTripleTap= */true,
                /* detectShortcutTrigger= */true,
                mTraceManager,
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
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_MAGNIFICATION_FOLLOWS_MOUSE)
    public void onMotionEvent_isFromMouse_handleMouseOrStylusEvent() {
        final MotionEvent mouseEvent = MotionEvent.obtain(0, 0, ACTION_HOVER_MOVE, 0, 0, 0);
        mouseEvent.setSource(InputDevice.SOURCE_MOUSE);

        mMgh.onMotionEvent(mouseEvent, mouseEvent, /* policyFlags= */ 0);

        try {
            assertTrue(mMgh.mIsHandleMouseOrStylusEventCalled);
        } finally {
            mouseEvent.recycle();
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_MAGNIFICATION_FOLLOWS_MOUSE)
    public void onMotionEvent_isFromStylus_handleMouseOrStylusEvent() {
        final MotionEvent stylusEvent = MotionEvent.obtain(0, 0, ACTION_HOVER_MOVE, 0, 0, 0);
        stylusEvent.setSource(InputDevice.SOURCE_STYLUS);

        mMgh.onMotionEvent(stylusEvent, stylusEvent, /* policyFlags= */ 0);

        try {
            assertTrue(mMgh.mIsHandleMouseOrStylusEventCalled);
        } finally {
            stylusEvent.recycle();
        }
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_MAGNIFICATION_FOLLOWS_MOUSE)
    public void onMotionEvent_isFromMouse_handleMouseOrStylusEventNotCalled() {
        final MotionEvent mouseEvent = MotionEvent.obtain(0, 0, ACTION_HOVER_MOVE, 0, 0, 0);
        mouseEvent.setSource(InputDevice.SOURCE_MOUSE);

        mMgh.onMotionEvent(mouseEvent, mouseEvent, /* policyFlags= */ 0);

        try {
            assertFalse(mMgh.mIsHandleMouseOrStylusEventCalled);
        } finally {
            mouseEvent.recycle();
        }
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_MAGNIFICATION_FOLLOWS_MOUSE)
    public void onMotionEvent_isFromStylus_handleMouseOrStylusEventNotCalled() {
        final MotionEvent stylusEvent = MotionEvent.obtain(0, 0, ACTION_HOVER_MOVE, 0, 0, 0);
        stylusEvent.setSource(InputDevice.SOURCE_STYLUS);

        mMgh.onMotionEvent(stylusEvent, stylusEvent, /* policyFlags= */ 0);

        try {
            assertFalse(mMgh.mIsHandleMouseOrStylusEventCalled);
        } finally {
            stylusEvent.recycle();
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

    private static class TestMagnificationGestureHandler extends MagnificationGestureHandler {

        boolean mIsInternalMethodCalled = false;
        boolean mIsHandleMouseOrStylusEventCalled = false;

        TestMagnificationGestureHandler(int displayId, boolean detectSingleFingerTripleTap,
                boolean detectTwoFingerTripleTap,
                boolean detectShortcutTrigger, @NonNull AccessibilityTraceManager trace,
                @NonNull Callback callback) {
            super(displayId, detectSingleFingerTripleTap, detectTwoFingerTripleTap,
                    detectShortcutTrigger, trace, callback);
        }

        @Override
        void handleMouseOrStylusEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            mIsHandleMouseOrStylusEventCalled = true;
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
