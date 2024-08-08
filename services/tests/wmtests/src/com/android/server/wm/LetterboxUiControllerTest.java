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

package com.android.server.wm;

import static android.view.InsetsSource.FLAG_INSETS_ROUNDED_CORNER;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.res.Resources;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.RoundedCorner;
import android.view.RoundedCorners;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

/**
 * Test class for {@link LetterboxUiController}.
 *
 * Build/Install/Run:
 * atest WmTests:LetterboxUiControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class LetterboxUiControllerTest extends WindowTestsBase {
    private static final int TASKBAR_COLLAPSED_HEIGHT = 10;
    private static final int TASKBAR_EXPANDED_HEIGHT = 20;
    private static final int SCREEN_WIDTH = 200;
    private static final int SCREEN_HEIGHT = 100;
    private static final Rect TASKBAR_COLLAPSED_BOUNDS = new Rect(0,
            SCREEN_HEIGHT - TASKBAR_COLLAPSED_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT);
    private static final Rect TASKBAR_EXPANDED_BOUNDS = new Rect(0,
            SCREEN_HEIGHT - TASKBAR_EXPANDED_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT);

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private ActivityRecord mActivity;
    private Task mTask;
    private DisplayContent mDisplayContent;
    private LetterboxUiController mController;
    private AppCompatConfiguration mAppCompatConfiguration;
    private final Rect mLetterboxedPortraitTaskBounds = new Rect();

    @Before
    public void setUp() throws Exception {
        mActivity = setUpActivityWithComponent();

        mAppCompatConfiguration = mWm.mAppCompatConfiguration;
        spyOn(mAppCompatConfiguration);

        mController = new LetterboxUiController(mWm, mActivity);
    }

    @Test
    public void testGetCropBoundsIfNeeded_handleCropForTransparentActivityBasedOnOpaqueBounds() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                WindowInsets.Type.navigationBars());
        taskbar.setFlags(FLAG_INSETS_ROUNDED_CORNER, FLAG_INSETS_ROUNDED_CORNER);
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);
        final Rect opaqueBounds = new Rect(0, 0, 500, 300);
        doReturn(opaqueBounds).when(mActivity).getBounds();
        // Activity is translucent
        spyOn(mActivity.mAppCompatController.getTransparentPolicy());
        when(mActivity.mAppCompatController.getTransparentPolicy()
                .isRunning()).thenReturn(true);

        // Makes requested sizes different
        mainWindow.mRequestedWidth = opaqueBounds.width() - 1;
        mainWindow.mRequestedHeight = opaqueBounds.height() - 1;
        assertNull(mActivity.mLetterboxUiController.getCropBoundsIfNeeded(mainWindow));

        // Makes requested sizes equals
        mainWindow.mRequestedWidth = opaqueBounds.width();
        mainWindow.mRequestedHeight = opaqueBounds.height();
        assertNotNull(mActivity.mLetterboxUiController.getCropBoundsIfNeeded(mainWindow));
    }

    @Test
    public void testGetCropBoundsIfNeeded_noCrop() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                WindowInsets.Type.navigationBars());
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);

        // Do not apply crop if taskbar is collapsed
        taskbar.setFrame(TASKBAR_COLLAPSED_BOUNDS);
        assertNull(mController.getExpandedTaskbarOrNull(mainWindow));

        mLetterboxedPortraitTaskBounds.set(SCREEN_WIDTH / 4, SCREEN_HEIGHT / 4,
                SCREEN_WIDTH - SCREEN_WIDTH / 4, SCREEN_HEIGHT - SCREEN_HEIGHT / 4);

        final Rect noCrop = mController.getCropBoundsIfNeeded(mainWindow);
        assertNotEquals(null, noCrop);
        assertEquals(0, noCrop.left);
        assertEquals(0, noCrop.top);
        assertEquals(mLetterboxedPortraitTaskBounds.width(), noCrop.right);
        assertEquals(mLetterboxedPortraitTaskBounds.height(), noCrop.bottom);
    }

    @Test
    public void testGetCropBoundsIfNeeded_appliesCrop() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                WindowInsets.Type.navigationBars());
        taskbar.setFlags(FLAG_INSETS_ROUNDED_CORNER, FLAG_INSETS_ROUNDED_CORNER);
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);

        // Apply crop if taskbar is expanded
        taskbar.setFrame(TASKBAR_EXPANDED_BOUNDS);
        assertNotNull(mController.getExpandedTaskbarOrNull(mainWindow));

        mLetterboxedPortraitTaskBounds.set(SCREEN_WIDTH / 4, 0, SCREEN_WIDTH - SCREEN_WIDTH / 4,
                SCREEN_HEIGHT);

        final Rect crop = mController.getCropBoundsIfNeeded(mainWindow);
        assertNotEquals(null, crop);
        assertEquals(0, crop.left);
        assertEquals(0, crop.top);
        assertEquals(mLetterboxedPortraitTaskBounds.width(), crop.right);
        assertEquals(mLetterboxedPortraitTaskBounds.height() - TASKBAR_EXPANDED_HEIGHT,
                crop.bottom);
    }

    @Test
    public void testGetCropBoundsIfNeeded_appliesCropWithSizeCompatScaling() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                WindowInsets.Type.navigationBars());
        taskbar.setFlags(FLAG_INSETS_ROUNDED_CORNER, FLAG_INSETS_ROUNDED_CORNER);
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);
        final float scaling = 2.0f;

        // Apply crop if taskbar is expanded
        taskbar.setFrame(TASKBAR_EXPANDED_BOUNDS);
        assertNotNull(mController.getExpandedTaskbarOrNull(mainWindow));
        // With SizeCompat scaling
        doReturn(true).when(mActivity).inSizeCompatMode();
        mainWindow.mInvGlobalScale = scaling;

        mLetterboxedPortraitTaskBounds.set(SCREEN_WIDTH / 4, 0, SCREEN_WIDTH - SCREEN_WIDTH / 4,
                SCREEN_HEIGHT);

        final int appWidth = mLetterboxedPortraitTaskBounds.width();
        final int appHeight = mLetterboxedPortraitTaskBounds.height();

        final Rect crop = mController.getCropBoundsIfNeeded(mainWindow);
        assertNotEquals(null, crop);
        assertEquals(0, crop.left);
        assertEquals(0, crop.top);
        assertEquals((int) (appWidth * scaling), crop.right);
        assertEquals((int) ((appHeight - TASKBAR_EXPANDED_HEIGHT) * scaling), crop.bottom);
    }

    @Test
    public void testGetRoundedCornersRadius_withRoundedCornersFromInsets() {
        final float invGlobalScale = 0.5f;
        final int expectedRadius = 7;
        final int configurationRadius = 15;

        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(/*taskbar=*/ null);
        mainWindow.mInvGlobalScale = invGlobalScale;
        final InsetsState insets = mainWindow.getInsetsState();

        RoundedCorners roundedCorners = new RoundedCorners(
                /*topLeft=*/ null,
                /*topRight=*/ null,
                /*bottomRight=*/ new RoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT,
                    configurationRadius, /*centerX=*/ 1, /*centerY=*/ 1),
                /*bottomLeft=*/ new RoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT,
                    configurationRadius * 2 /*2 is to test selection of the min radius*/,
                    /*centerX=*/ 1, /*centerY=*/ 1)
        );
        insets.setRoundedCorners(roundedCorners);
        mAppCompatConfiguration.setLetterboxActivityCornersRadius(-1);

        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));
    }

    @Test
    public void testGetRoundedCornersRadius_withLetterboxActivityCornersRadius() {
        final float invGlobalScale = 0.5f;
        final int expectedRadius = 7;
        final int configurationRadius = 15;

        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(/*taskbar=*/ null);
        mainWindow.mInvGlobalScale = invGlobalScale;
        mAppCompatConfiguration.setLetterboxActivityCornersRadius(configurationRadius);

        doReturn(true).when(mActivity).isInLetterboxAnimation();
        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));

        doReturn(false).when(mActivity).isInLetterboxAnimation();
        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));

        doReturn(false).when(mActivity).isVisibleRequested();
        doReturn(false).when(mActivity).isVisible();
        assertEquals(0, mController.getRoundedCornersRadius(mainWindow));

        doReturn(true).when(mActivity).isInLetterboxAnimation();
        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));
    }

    @Test
    public void testGetRoundedCornersRadius_noScalingApplied() {
        final int configurationRadius = 15;

        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(/*taskbar=*/ null);
        mAppCompatConfiguration.setLetterboxActivityCornersRadius(configurationRadius);

        mainWindow.mInvGlobalScale = -1f;
        assertEquals(configurationRadius, mController.getRoundedCornersRadius(mainWindow));

        mainWindow.mInvGlobalScale = 0f;
        assertEquals(configurationRadius, mController.getRoundedCornersRadius(mainWindow));

        mainWindow.mInvGlobalScale = 1f;
        assertEquals(configurationRadius, mController.getRoundedCornersRadius(mainWindow));
    }

    private WindowState mockForGetCropBoundsAndRoundedCorners(@Nullable InsetsSource taskbar) {
        final WindowState mainWindow = mock(WindowState.class);
        final InsetsState insets = new InsetsState();
        final Resources resources = mWm.mContext.getResources();
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();

        mainWindow.mInvGlobalScale = 1f;
        spyOn(resources);
        spyOn(mActivity);
        spyOn(mActivity.mAppCompatController.getAppCompatAspectRatioPolicy());

        if (taskbar != null) {
            taskbar.setVisible(true);
            insets.addSource(taskbar);
        }
        doReturn(mLetterboxedPortraitTaskBounds).when(mActivity).getBounds();
        doReturn(false).when(mActivity).isInLetterboxAnimation();
        doReturn(true).when(mActivity).isVisible();
        doReturn(true).when(mActivity.mAppCompatController
                .getAppCompatAspectRatioPolicy()).isLetterboxedForFixedOrientationAndAspectRatio();
        doReturn(insets).when(mainWindow).getInsetsState();
        doReturn(attrs).when(mainWindow).getAttrs();
        doReturn(true).when(mainWindow).isDrawn();
        doReturn(true).when(mainWindow).isOnScreen();
        doReturn(false).when(mainWindow).isLetterboxedForDisplayCutout();
        doReturn(true).when(mainWindow).areAppWindowBoundsLetterboxed();
        doReturn(true).when(mAppCompatConfiguration).isLetterboxActivityCornersRounded();
        doReturn(TASKBAR_EXPANDED_HEIGHT).when(resources).getDimensionPixelSize(
                R.dimen.taskbar_frame_height);

        // Need to reinitialise due to the change in resources getDimensionPixelSize output.
        mController = new LetterboxUiController(mWm, mActivity);

        return mainWindow;
    }

    @Test
    public void testIsLetterboxEducationEnabled() {
        mController.isLetterboxEducationEnabled();
        verify(mAppCompatConfiguration).getIsEducationEnabled();
    }

    private ActivityRecord setUpActivityWithComponent() {
        mDisplayContent = new TestDisplayContent
                .Builder(mAtm, /* dw */ 1000, /* dh */ 2000).build();
        mTask = new TaskBuilder(mSupervisor).setDisplay(mDisplayContent).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setOnTop(true)
                .setTask(mTask)
                // Set the component to be that of the test class in order to enable compat changes
                .setComponent(ComponentName.createRelative(mContext,
                        com.android.server.wm.LetterboxUiControllerTest.class.getName()))
                .build();
        spyOn(activity.mAppCompatController.getAppCompatCameraOverrides());
        return activity;
    }
}
