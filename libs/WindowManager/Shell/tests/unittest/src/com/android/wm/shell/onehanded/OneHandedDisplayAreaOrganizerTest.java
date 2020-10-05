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

package com.android.wm.shell.onehanded;

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
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.DisplayController;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OneHandedDisplayAreaOrganizerTest extends OneHandedTestCase {
    static final int DISPLAY_WIDTH = 1000;
    static final int DISPLAY_HEIGHT = 1000;

    DisplayAreaInfo mDisplayAreaInfo;
    Display mDisplay;
    Handler mUpdateHandler;
    OneHandedDisplayAreaOrganizer mDisplayAreaOrganizer;
    OneHandedTutorialHandler mTutorialHandler;
    OneHandedAnimationController.OneHandedTransitionAnimator mFakeAnimator;
    WindowContainerToken mToken;
    SurfaceControl mLeash;
    TestableLooper mTestableLooper;
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
    @Mock
    WindowContainerTransaction mMockWindowContainerTransaction;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
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
        when(mMockAnimator.setOneHandedAnimationCallbacks(any())).thenReturn(mFakeAnimator);
        when(mMockAnimator.setTransitionDirection(anyInt())).thenReturn(mFakeAnimator);
        when(mMockLeash.getWidth()).thenReturn(DISPLAY_WIDTH);
        when(mMockLeash.getHeight()).thenReturn(DISPLAY_HEIGHT);

        mDisplayAreaOrganizer = new OneHandedDisplayAreaOrganizer(mContext,
                mMockDisplayController,
                mMockAnimationController,
                mTutorialHandler);
        mUpdateHandler = mDisplayAreaOrganizer.getUpdateHandler();
    }

    @Test
    public void testOnDisplayAreaAppeared() {
        mDisplayAreaOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mLeash);
        mTestableLooper.processAllMessages();

        verify(mMockAnimationController, never()).getAnimator(any(), any(), any());
    }

    @Test
    public void testOnDisplayAreaVanished() {
        mDisplayAreaOrganizer.onDisplayAreaAppeared(mDisplayAreaInfo, mLeash);
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onDisplayAreaVanished(mDisplayAreaInfo);

        assertThat(mDisplayAreaOrganizer.mDisplayAreaMap).isEmpty();
    }

    @Ignore("b/160848002")
    @Test
    public void testScheduleOffset() {
        final int xOffSet = 0;
        final int yOffSet = 100;
        mDisplayAreaOrganizer.scheduleOffset(xOffSet, yOffSet);
        mTestableLooper.processAllMessages();

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_OFFSET_ANIMATE)).isEqualTo(true);
    }

    @Test
    public void testRotation_portrait_0_to_landscape_90() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 0 -> 90
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_0, Surface.ROTATION_90,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);
    }

    @Test
    public void testRotation_portrait_0_to_seascape_270() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 0 -> 270
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_0, Surface.ROTATION_270,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);

    }

    @Test
    public void testRotation_portrait_180_to_landscape_90() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 180 -> 90
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_180, Surface.ROTATION_90,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);
    }

    @Test
    public void testRotation_portrait_180_to_seascape_270() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 180 -> 270
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_180, Surface.ROTATION_270,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);
    }

    @Test
    public void testRotation_landscape_90_to_portrait_0() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 90 -> 0
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_90, Surface.ROTATION_0,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);
    }

    @Test
    public void testRotation_landscape_90_to_portrait_180() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 90 -> 180
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_90, Surface.ROTATION_180,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);
    }

    @Test
    public void testRotation_Seascape_270_to_portrait_0() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 270 -> 0
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_270, Surface.ROTATION_0,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);
    }

    @Test
    public void testRotation_seascape_90_to_portrait_180() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 270 -> 180
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_270, Surface.ROTATION_180,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(true);
    }

    @Test
    public void testRotation_portrait_0_to_portrait_0() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 0 -> 0
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_0, Surface.ROTATION_0,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);
    }

    @Test
    public void testRotation_portrait_0_to_portrait_180() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 0 -> 180
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_0, Surface.ROTATION_180,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);
    }

    @Test
    public void testRotation_portrait_180_to_portrait_180() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 180 -> 180
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_180, Surface.ROTATION_180,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);
    }

    @Test
    public void testRotation_portrait_180_to_portrait_0() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 180 -> 0
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_180, Surface.ROTATION_0,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);
    }

    @Test
    public void testRotation_landscape_90_to_landscape_90() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 90 -> 90
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_90, Surface.ROTATION_90,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);
    }

    @Test
    public void testRotation_landscape_90_to_seascape_270() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 90 -> 270
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_90, Surface.ROTATION_270,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);
    }

    @Test
    public void testRotation_seascape_270_to_seascape_270() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 270 -> 270
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_270, Surface.ROTATION_270,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);
    }

    @Test
    public void testRotation_seascape_90_to_landscape_90() {
        when(mMockLeash.isValid()).thenReturn(false);
        // Rotate 270 -> 90
        mTestableLooper.processAllMessages();
        mDisplayAreaOrganizer.onRotateDisplay(Surface.ROTATION_270, Surface.ROTATION_90,
                mMockWindowContainerTransaction);

        assertThat(mUpdateHandler.hasMessages(
                OneHandedDisplayAreaOrganizer.MSG_RESET_IMMEDIATE)).isEqualTo(false);
    }
}
