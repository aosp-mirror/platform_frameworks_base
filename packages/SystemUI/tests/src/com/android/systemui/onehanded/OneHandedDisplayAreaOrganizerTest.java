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

package com.android.systemui.onehanded;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_ONE_HANDED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.IWindowContainerToken;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.DisplayController;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OneHandedDisplayAreaOrganizerTest extends OneHandedTestCase {
    static final int DISPLAY_WIDTH = 1000;
    static final int DISPLAY_HEIGHT = 1000;

    DisplayAreaInfo mDisplayAreaInfo;
    Display mDisplay;
    OneHandedDisplayAreaOrganizer mDisplayAreaOrganizer;
    OneHandedAnimationController.OneHandedTransitionAnimator mFakeAnimator;
    WindowContainerToken mToken;
    SurfaceControl mLeash;
    @Mock
    IWindowContainerToken mMockRealToken;
    @Mock
    OneHandedAnimationController mMockAnimationController;
    @Mock
    OneHandedAnimationController.OneHandedTransitionAnimator mMockAnimator;
    @Mock
    OneHandedSurfaceTransactionHelper mMockSurfaceTransactionHelper;
    @Mock
    DisplayController mMockDisplayController;
    @Mock
    SurfaceControl mMockLeash;
    @Spy
    Handler mUpdateHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mToken = new WindowContainerToken(mMockRealToken);
        mLeash = new SurfaceControl();
        mDisplay = mContext.getDisplay();
        mDisplayAreaInfo = new DisplayAreaInfo(mToken, DEFAULT_DISPLAY, FEATURE_ONE_HANDED);
        mDisplayAreaInfo.configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
        when(mMockAnimationController.getAnimator(any(), any(), any())).thenReturn(null);
        when(mMockDisplayController.getDisplay(anyInt())).thenReturn(mDisplay);
        when(mMockSurfaceTransactionHelper.translate(any(), any(), anyFloat())).thenReturn(
                mMockSurfaceTransactionHelper);
        when(mMockSurfaceTransactionHelper.crop(any(), any(), any())).thenReturn(
                mMockSurfaceTransactionHelper);
        when(mMockSurfaceTransactionHelper.round(any(), any())).thenReturn(
                mMockSurfaceTransactionHelper);
        when(mMockAnimator.isRunning()).thenReturn(true);
        when(mMockAnimator.setDuration(anyInt())).thenReturn(mFakeAnimator);
        when(mMockAnimator.setOneHandedAnimationCallback(any())).thenReturn(mFakeAnimator);
        when(mMockAnimator.setTransitionDirection(anyInt())).thenReturn(mFakeAnimator);
        when(mMockLeash.getWidth()).thenReturn(DISPLAY_WIDTH);
        when(mMockLeash.getHeight()).thenReturn(DISPLAY_HEIGHT);

        mDisplayAreaOrganizer = new OneHandedDisplayAreaOrganizer(mContext,
                mMockDisplayController,
                mMockAnimationController);
        mUpdateHandler = mDisplayAreaOrganizer.getUpdateHandler();
    }

    @Test
    public void testGetDisplayAreaUpdateHandler_isNotNull() {
        assertThat(mUpdateHandler).isNotNull();
    }

    @Test
    public void testOnDisplayAreaAppeared() {
        mDisplayAreaOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mLeash);

        verify(mMockAnimationController, never()).getAnimator(any(), any(), any());
    }

    @Test
    public void testOnDisplayAreaVanished() {
        mDisplayAreaOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mLeash);
        mDisplayAreaOrganizer.onDisplayAreaVanished(mDisplayAreaInfo);
    }

    @Test
    public void testOnDisplayAreaInfoChanged_updateDisplayAreaInfo() {
        final DisplayAreaInfo newDisplayAreaInfo = new DisplayAreaInfo(mToken, DEFAULT_DISPLAY,
                FEATURE_ONE_HANDED);
        mDisplayAreaOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mLeash);
        mDisplayAreaOrganizer.onDisplayAreaInfoChanged(newDisplayAreaInfo);

        assertThat(mDisplayAreaOrganizer.mDisplayAreaMap.containsKey(mDisplayAreaInfo)).isTrue();
    }

    @Ignore("b/160848002")
    @Test
    public void testScheduleOffset() {
        final int xOffSet = 0;
        final int yOffSet = 100;

        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mLeash);
        mDisplayAreaOrganizer.scheduleOffset(xOffSet, yOffSet);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_OFFSET_ANIMATE)).isEqualTo(true);
    }

    @Ignore("b/160848002")
    @Test
    public void testRotation_portraitToLandscape() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 0 -> 90
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_0, Surface.ROTATION_90);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);

        // Rotate 0 -> 270
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_0, Surface.ROTATION_270);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);

        // Rotate 180 -> 90
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_180, Surface.ROTATION_90);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);

        // Rotate 180 -> 270
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_180, Surface.ROTATION_270);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);
    }

    @Ignore("b/160848002")
    @Test
    public void testRotation_landscapeToPortrait() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 90 -> 0
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_90, Surface.ROTATION_0);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);

        // Rotate 90 -> 180
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_90, Surface.ROTATION_180);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);

        // Rotate 270 -> 0
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_270, Surface.ROTATION_0);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);

        // Rotate 270 -> 180
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_270, Surface.ROTATION_180);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);
    }

    @Ignore("b/160848002")
    @Test
    public void testRotation_portraitToPortrait() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 0 -> 0
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_0, Surface.ROTATION_0);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);

        // Rotate 0 -> 180
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_0, Surface.ROTATION_180);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);

        // Rotate 180 -> 180
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_180, Surface.ROTATION_180);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);

        // Rotate 180 -> 180
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_180, Surface.ROTATION_0);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);
    }

    @Ignore("b/160848002")
    @Test
    public void testRotation_landscapeToLandscape() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 90 -> 90
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_90, Surface.ROTATION_90);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);

        // Rotate 90 -> 270
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_90, Surface.ROTATION_270);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);

        // Rotate 270 -> 270
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_270, Surface.ROTATION_270);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);

        // Rotate 270 -> 90
        TestableLooper.get(this).processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_270, Surface.ROTATION_90);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);
    }
}
