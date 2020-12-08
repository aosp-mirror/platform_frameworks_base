/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM;
import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_RIGHT;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;

import static com.android.systemui.ScreenDecorations.rectsToRegion;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.VectorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.filters.SmallTest;

import com.android.systemui.R.dimen;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

@RunWithLooper
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ScreenDecorationsTest extends SysuiTestCase {

    private static final Rect ZERO_RECT = new Rect();

    private TestableLooper mTestableLooper;
    private ScreenDecorations mScreenDecorations;
    private WindowManager mWindowManager;
    private DisplayManager mDisplayManager;
    private Handler mMainHandler;
    @Mock
    private TunerService mTunerService;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);
        mMainHandler = new Handler(mTestableLooper.getLooper());

        mWindowManager = mock(WindowManager.class);
        WindowMetrics metrics = mContext.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics();
        when(mWindowManager.getMaximumWindowMetrics()).thenReturn(metrics);
        mContext.addMockSystemService(WindowManager.class, mWindowManager);

        mDisplayManager = mock(DisplayManager.class);
        Display display = mContext.getSystemService(DisplayManager.class)
                .getDisplay(DEFAULT_DISPLAY);
        when(mDisplayManager.getDisplay(anyInt())).thenReturn(display);
        mContext.addMockSystemService(DisplayManager.class, mDisplayManager);

        mScreenDecorations = spy(new ScreenDecorations(mContext, mMainHandler,
                mBroadcastDispatcher, mTunerService) {
            @Override
            public void start() {
                super.start();
                mTestableLooper.processAllMessages();
            }

            @Override
            Handler startHandlerThread() {
                return new Handler(mTestableLooper.getLooper());
            }

            @Override
            protected void onConfigurationChanged(Configuration newConfig) {
                super.onConfigurationChanged(newConfig);
                mTestableLooper.processAllMessages();
            }

            @Override
            public void onTuningChanged(String key, String newValue) {
                super.onTuningChanged(key, newValue);
                mTestableLooper.processAllMessages();
            }
        });
        reset(mTunerService);
    }

    @Test
    public void testNoRounding_NoCutout() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_top, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_bottom, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // No views added.
        verify(mWindowManager, never()).addView(any(), any());
        // No Tuners tuned.
        verify(mTunerService, never()).addTunable(any(), any());
    }

    @Test
    public void testRounding_NoCutout() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 20);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 20);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();

        // Top and bottom windows are created for rounded corners.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]), any());
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]), any());

        // Left and right window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]);
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);

        // One tunable.
        verify(mTunerService, times(1)).addTunable(any(), any());
    }

    @Test
    public void testRoundingRadius_NoCutout() {
        final int testRadius = 1;
        final Point testRadiusPoint = new Point(1, 1);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, testRadius);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_top, testRadius);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_bottom, testRadius);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Size of corner view should same as rounded_corner_radius{_top|_bottom}
        assertThat(mScreenDecorations.mRoundedDefault).isEqualTo(testRadiusPoint);
        assertThat(mScreenDecorations.mRoundedDefaultTop).isEqualTo(testRadiusPoint);
        assertThat(mScreenDecorations.mRoundedDefaultBottom).isEqualTo(testRadiusPoint);
    }

    @Test
    public void testRoundingTopBottomRadius_OnTopBottomOverlay() {
        final int testTopRadius = 1;
        final int testBottomRadius = 5;
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, testTopRadius);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_top, testTopRadius);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_bottom, testBottomRadius);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        View leftRoundedCorner =
                mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].findViewById(R.id.left);
        View rightRoundedCorner =
                mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].findViewById(R.id.right);
        verify(mScreenDecorations, atLeastOnce())
                .setSize(leftRoundedCorner, new Point(testTopRadius, testTopRadius));
        verify(mScreenDecorations, atLeastOnce())
                .setSize(rightRoundedCorner, new Point(testTopRadius, testTopRadius));
        leftRoundedCorner =
                mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].findViewById(R.id.left);
        rightRoundedCorner =
                mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].findViewById(R.id.right);
        verify(mScreenDecorations, atLeastOnce())
                .setSize(leftRoundedCorner, new Point(testBottomRadius, testBottomRadius));
        verify(mScreenDecorations, atLeastOnce())
                .setSize(rightRoundedCorner, new Point(testBottomRadius, testBottomRadius));
    }

    @Test
    public void testRoundingTopBottomRadius_OnLeftRightOverlay() {
        final int testTopRadius = 1;
        final int testBottomRadius = 5;
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, testTopRadius);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_top, testTopRadius);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_bottom, testBottomRadius);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // left cutout
        doReturn(new DisplayCutout(
                Insets.of(0, 10, 0, 0),
                new Rect(0, 200, 1, 210),
                ZERO_RECT,
                ZERO_RECT,
                ZERO_RECT,
                Insets.NONE)).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        View leftRoundedCorner =
                mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT].findViewById(R.id.left);
        View rightRoundedCorner =
                mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT].findViewById(R.id.right);
        verify(mScreenDecorations, atLeastOnce())
                .setSize(leftRoundedCorner, new Point(testTopRadius, testTopRadius));
        verify(mScreenDecorations, atLeastOnce())
                .setSize(rightRoundedCorner, new Point(testBottomRadius, testBottomRadius));
        leftRoundedCorner =
                mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT].findViewById(R.id.left);
        rightRoundedCorner =
                mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT].findViewById(R.id.right);
        verify(mScreenDecorations, atLeastOnce())
                .setSize(leftRoundedCorner, new Point(testTopRadius, testTopRadius));
        verify(mScreenDecorations, atLeastOnce())
                .setSize(rightRoundedCorner, new Point(testBottomRadius, testBottomRadius));
    }

    @Test
    public void testRoundingMultipleRadius_NoCutout() {
        final VectorDrawable d = (VectorDrawable) mContext.getDrawable(R.drawable.rounded);
        final Point multipleRadiusSize = new Point(d.getIntrinsicWidth(), d.getIntrinsicHeight());

        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 9999);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 9999);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, true);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top and bottom windows are created for rounded corners.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]), any());
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]), any());

        // Left and right window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]);
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);

        // One tunable.
        verify(mTunerService, times(1)).addTunable(any(), any());

        // Size of corner view should exactly match max(width, height) of R.drawable.rounded
        assertThat(mScreenDecorations.mRoundedDefault).isEqualTo(multipleRadiusSize);
        assertThat(mScreenDecorations.mRoundedDefaultTop).isEqualTo(multipleRadiusSize);
        assertThat(mScreenDecorations.mRoundedDefaultBottom).isEqualTo(multipleRadiusSize);
    }

    @Test
    public void testNoRounding_CutoutShortEdge() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, true);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_top, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_bottom, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // top cutout
        doReturn(new DisplayCutout(
                        Insets.of(0, 10, 0, 0),
                        ZERO_RECT,
                        new Rect(9, 0, 10, 1),
                        ZERO_RECT,
                        ZERO_RECT,
                        Insets.NONE)).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top window is created for top cutout.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]), any());
        // Bottom window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]);
        // Left window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]);
        // Right window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);
    }

    @Test
    public void testNoRounding_CutoutLongEdge() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, true);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_top, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_bottom, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // left cutout
        doReturn(new DisplayCutout(
                Insets.of(0, 10, 0, 0),
                new Rect(0, 200, 1, 210),
                ZERO_RECT,
                ZERO_RECT,
                ZERO_RECT,
                Insets.NONE)).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Left window is created for left cutout.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]), any());
        // Bottom window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]);
        // Top window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]);
        // Right window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);
    }

    @Test
    public void testRounding_CutoutShortEdge() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, true);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 20);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 20);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // top cutout
        doReturn(new DisplayCutout(
                Insets.of(0, 10, 0, 0),
                ZERO_RECT,
                new Rect(9, 0, 10, 1),
                ZERO_RECT,
                ZERO_RECT,
                Insets.NONE)).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top window is created for rouned corner and top cutout.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]), any());
        // Bottom window is created for rouned corner.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]), any());
        // Left window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]);
        // Right window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);
    }

    @Test
    public void testRounding_CutoutLongEdge() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, true);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 20);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 20);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // left cutout
        doReturn(new DisplayCutout(
                Insets.of(0, 10, 0, 0),
                new Rect(0, 200, 1, 210),
                ZERO_RECT,
                ZERO_RECT,
                ZERO_RECT,
                Insets.NONE)).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Left window is created for rouned corner and left cutout.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]), any());
        // Right window is created for rouned corner.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]), any());
        // Top window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]);
        // Bottom window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]);
    }

    @Test
    public void testRounding_CutoutShortAndLongEdge() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, true);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 20);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 20);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // top and left cutout
        doReturn(new DisplayCutout(
                Insets.of(0, 10, 0, 0),
                new Rect(0, 200, 1, 210),
                new Rect(9, 0, 10, 1),
                ZERO_RECT,
                ZERO_RECT,
                Insets.NONE)).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top window is created for rouned corner and top cutout.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]), any());
        // Bottom window is created for rouned corner.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]), any());
        // Left window is created for left cutout.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]), any());
        // Right window should be null.
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);
    }

    @Test
    public void testNoRounding_SwitchFrom_ShortEdgeCutout_To_LongCutout() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, true);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_top, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_bottom, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // Set to short edge cutout(top).
        doReturn(new DisplayCutout(
                Insets.of(0, 10, 0, 0),
                ZERO_RECT,
                new Rect(9, 0, 10, 1),
                ZERO_RECT,
                ZERO_RECT,
                Insets.NONE)).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]), any());
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]);
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]);

        // Switch to long edge cutout(left).
        // left cutout
        doReturn(new DisplayCutout(
                Insets.of(0, 10, 0, 0),
                new Rect(0, 200, 1, 210),
                ZERO_RECT,
                ZERO_RECT,
                ZERO_RECT,
                Insets.NONE)).when(mScreenDecorations).getCutout();

        mScreenDecorations.onConfigurationChanged(new Configuration());
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]), any());
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]);
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]);
    }

    @Test
    public void testDelayedCutout() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_top, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_bottom, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        // top cutout
        doReturn(new DisplayCutout(
                Insets.of(0, 10, 0, 0),
                ZERO_RECT,
                new Rect(9, 0, 10, 1),
                ZERO_RECT,
                ZERO_RECT,
                Insets.NONE)).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        assertNull(mScreenDecorations.mOverlays);

        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, true);
        mScreenDecorations.onConfigurationChanged(new Configuration());

        // Only top windows should be added.
        verify(mWindowManager, times(1))
                .addView(eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]), any());
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]);
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]);
        assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);
    }

    @Test
    public void hasRoundedCornerOverlayFlagSet() {
        assertThat(mScreenDecorations.getWindowLayoutParams(1).privateFlags
                        & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY,
                is(PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY));
    }

    @Test
    public void testUpdateRoundedCorners() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 20);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_roundedCornerMultipleRadius, false);

        mScreenDecorations.start();
        assertEquals(mScreenDecorations.mRoundedDefault, new Point(20, 20));

        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 5);
        mScreenDecorations.onConfigurationChanged(null);
        assertEquals(mScreenDecorations.mRoundedDefault, new Point(5, 5));
    }


    @Test
    public void testBoundingRectsToRegion() throws Exception {
        Rect rect = new Rect(1, 2, 3, 4);
        assertThat(rectsToRegion(Collections.singletonList(rect)).getBounds(), is(rect));
    }

    @Test
    public void testRegistration_From_NoOverlay_To_HasOverlays() {
        doReturn(false).when(mScreenDecorations).hasOverlays();
        mScreenDecorations.start();
        verify(mTunerService, times(0)).addTunable(any(), any());
        verify(mTunerService, times(1)).removeTunable(any());
        assertThat(mScreenDecorations.mIsRegistered, is(false));
        reset(mTunerService);

        doReturn(true).when(mScreenDecorations).hasOverlays();
        mScreenDecorations.onConfigurationChanged(new Configuration());
        verify(mTunerService, times(1)).addTunable(any(), any());
        verify(mTunerService, times(0)).removeTunable(any());
        assertThat(mScreenDecorations.mIsRegistered, is(true));
    }

    @Test
    public void testRegistration_From_HasOverlays_To_HasOverlays() {
        doReturn(true).when(mScreenDecorations).hasOverlays();

        mScreenDecorations.start();
        verify(mTunerService, times(1)).addTunable(any(), any());
        verify(mTunerService, times(0)).removeTunable(any());
        assertThat(mScreenDecorations.mIsRegistered, is(true));
        reset(mTunerService);

        mScreenDecorations.onConfigurationChanged(new Configuration());
        verify(mTunerService, times(0)).addTunable(any(), any());
        verify(mTunerService, times(0)).removeTunable(any());
        assertThat(mScreenDecorations.mIsRegistered, is(true));
    }

    @Test
    public void testRegistration_From_HasOverlays_To_NoOverlay() {
        doReturn(true).when(mScreenDecorations).hasOverlays();

        mScreenDecorations.start();
        verify(mTunerService, times(1)).addTunable(any(), any());
        verify(mTunerService, times(0)).removeTunable(any());
        assertThat(mScreenDecorations.mIsRegistered, is(true));
        reset(mTunerService);

        doReturn(false).when(mScreenDecorations).hasOverlays();
        mScreenDecorations.onConfigurationChanged(new Configuration());
        verify(mTunerService, times(0)).addTunable(any(), any());
        verify(mTunerService, times(1)).removeTunable(any());
        assertThat(mScreenDecorations.mIsRegistered, is(false));
    }
}
