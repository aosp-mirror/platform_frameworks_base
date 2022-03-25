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

import static android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM;
import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_LENGTH;
import static android.view.DisplayCutout.BOUNDS_POSITION_RIGHT;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.IdRes;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.VectorDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.graphics.common.DisplayDecorationSupport;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.RotationUtils;
import android.util.Size;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.decor.CornerDecorProvider;
import com.android.systemui.decor.DecorProvider;
import com.android.systemui.decor.OverlayWindow;
import com.android.systemui.decor.PrivacyDotCornerDecorProviderImpl;
import com.android.systemui.decor.PrivacyDotDecorProviderFactory;
import com.android.systemui.decor.RoundedCornerResDelegate;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.events.PrivacyDotViewController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.concurrency.FakeThreadFactory;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@RunWithLooper
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ScreenDecorationsTest extends SysuiTestCase {

    private ScreenDecorations mScreenDecorations;
    private WindowManager mWindowManager;
    private DisplayManager mDisplayManager;
    private SecureSettings mSecureSettings;
    private final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
    private FakeThreadFactory mThreadFactory;
    private ArrayList<DecorProvider> mDecorProviders;
    @Mock
    private Display mDisplay;
    @Mock
    private TunerService mTunerService;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private PrivacyDotViewController mDotViewController;
    @Mock
    private TypedArray mMockTypedArray;
    @Mock
    private PrivacyDotDecorProviderFactory mPrivacyDotDecorProviderFactory;
    @Mock
    private CornerDecorProvider mPrivacyDotTopLeftDecorProvider;
    @Mock
    private CornerDecorProvider mPrivacyDotTopRightDecorProvider;
    @Mock
    private CornerDecorProvider mPrivacyDotBottomLeftDecorProvider;
    @Mock
    private CornerDecorProvider mPrivacyDotBottomRightDecorProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        Handler mainHandler = new Handler(TestableLooper.get(this).getLooper());
        mSecureSettings = new FakeSettings();
        mThreadFactory = new FakeThreadFactory(mExecutor);
        mThreadFactory.setHandler(mainHandler);

        mWindowManager = mock(WindowManager.class);
        WindowMetrics metrics = mContext.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics();
        when(mWindowManager.getMaximumWindowMetrics()).thenReturn(metrics);
        mContext.addMockSystemService(WindowManager.class, mWindowManager);
        mDisplayManager = mock(DisplayManager.class);
        mContext.addMockSystemService(DisplayManager.class, mDisplayManager);

        spyOn(mContext);
        when(mContext.getDisplay()).thenReturn(mDisplay);
        // Not support hwc layer by default
        doReturn(null).when(mDisplay).getDisplayDecorationSupport();

        when(mMockTypedArray.length()).thenReturn(0);
        mPrivacyDotTopLeftDecorProvider = spy(new PrivacyDotCornerDecorProviderImpl(
                R.id.privacy_dot_top_left_container,
                DisplayCutout.BOUNDS_POSITION_TOP,
                DisplayCutout.BOUNDS_POSITION_LEFT,
                R.layout.privacy_dot_top_left));

        mPrivacyDotTopRightDecorProvider = spy(new PrivacyDotCornerDecorProviderImpl(
                R.id.privacy_dot_top_right_container,
                DisplayCutout.BOUNDS_POSITION_TOP,
                DisplayCutout.BOUNDS_POSITION_RIGHT,
                R.layout.privacy_dot_top_right));

        mPrivacyDotBottomLeftDecorProvider = spy(new PrivacyDotCornerDecorProviderImpl(
                R.id.privacy_dot_bottom_left_container,
                DisplayCutout.BOUNDS_POSITION_BOTTOM,
                DisplayCutout.BOUNDS_POSITION_LEFT,
                R.layout.privacy_dot_bottom_left));

        mPrivacyDotBottomRightDecorProvider = spy(new PrivacyDotCornerDecorProviderImpl(
                R.id.privacy_dot_bottom_right_container,
                DisplayCutout.BOUNDS_POSITION_BOTTOM,
                DisplayCutout.BOUNDS_POSITION_RIGHT,
                R.layout.privacy_dot_bottom_right));

        mScreenDecorations = spy(new ScreenDecorations(mContext, mExecutor, mSecureSettings,
                mBroadcastDispatcher, mTunerService, mUserTracker, mDotViewController,
                mThreadFactory, mPrivacyDotDecorProviderFactory) {
            @Override
            public void start() {
                super.start();
                mExecutor.runAllReady();
            }

            @Override
            protected void onConfigurationChanged(Configuration newConfig) {
                super.onConfigurationChanged(newConfig);
                mExecutor.runAllReady();
            }

            @Override
            public void onTuningChanged(String key, String newValue) {
                super.onTuningChanged(key, newValue);
                mExecutor.runAllReady();
            }
        });
        reset(mTunerService);
    }


    private void verifyRoundedCornerViewsVisibility(
            @DisplayCutout.BoundsPosition final int overlayId,
            @View.Visibility final int visibility) {
        final View overlay = mScreenDecorations.mOverlays[overlayId].getRootView();
        final View left = overlay.findViewById(R.id.left);
        final View right = overlay.findViewById(R.id.right);
        assertNotNull(left);
        assertNotNull(right);
        assertThat(left.getVisibility()).isEqualTo(visibility);
        assertThat(right.getVisibility()).isEqualTo(visibility);
    }

    @Nullable
    private View findViewFromOverlays(@IdRes int id) {
        for (OverlayWindow overlay: mScreenDecorations.mOverlays) {
            if (overlay == null) {
                continue;
            }

            View view = overlay.getRootView().findViewById(id);
            if (view != null) {
                return view;
            }
        }
        return null;
    }

    private void verifyTopDotViewsNullable(final boolean isAssertNull) {
        View tl = findViewFromOverlays(R.id.privacy_dot_top_left_container);
        View tr = findViewFromOverlays(R.id.privacy_dot_top_right_container);
        if (isAssertNull) {
            assertNull(tl);
            assertNull(tr);
        } else {
            assertNotNull(tl);
            assertNotNull(tr);
        }
    }

    private void verifyBottomDotViewsNullable(final boolean isAssertNull) {
        View bl = findViewFromOverlays(R.id.privacy_dot_bottom_left_container);
        View br = findViewFromOverlays(R.id.privacy_dot_bottom_right_container);
        if (isAssertNull) {
            assertNull(bl);
            assertNull(br);
        } else {
            assertNotNull(bl);
            assertNotNull(br);
        }
    }

    private void verifyDotViewsNullable(final boolean isAssertNull) {
        verifyTopDotViewsNullable(isAssertNull);
        verifyBottomDotViewsNullable(isAssertNull);
    }

    private void verifyTopDotViewsVisibility(@View.Visibility final int visibility) {
        verifyTopDotViewsNullable(false);
        View tl = findViewFromOverlays(R.id.privacy_dot_top_left_container);
        View tr = findViewFromOverlays(R.id.privacy_dot_top_right_container);
        assertThat(tl.getVisibility()).isEqualTo(visibility);
        assertThat(tr.getVisibility()).isEqualTo(visibility);
    }

    private void verifyBottomDotViewsVisibility(@View.Visibility final int visibility) {
        verifyBottomDotViewsNullable(false);
        View bl = findViewFromOverlays(R.id.privacy_dot_bottom_left_container);
        View br = findViewFromOverlays(R.id.privacy_dot_bottom_right_container);
        assertThat(bl.getVisibility()).isEqualTo(visibility);
        assertThat(br.getVisibility()).isEqualTo(visibility);
    }

    private void verifyDotViewsVisibility(@View.Visibility final int visibility) {
        verifyTopDotViewsVisibility(visibility);
        verifyBottomDotViewsVisibility(visibility);
    }

    private void verifyOverlaysExistAndAdded(final boolean left, final boolean top,
            final boolean right, final boolean bottom) {
        if (left || top || right || bottom) {
            assertNotNull(mScreenDecorations.mOverlays);
        } else {
            verify(mWindowManager, never()).addView(any(), any());
            return;
        }

        if (left) {
            assertNotNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]);
            verify(mWindowManager, times(1)).addView(
                    eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT].getRootView()), any());
        } else {
            assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]);
        }

        if (top) {
            assertNotNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]);
            verify(mWindowManager, times(1)).addView(
                    eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].getRootView()), any());
        } else {
            assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]);
        }

        if (right) {
            assertNotNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);
            verify(mWindowManager, times(1)).addView(
                    eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT].getRootView()), any());
        } else {
            assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);
        }

        if (bottom) {
            assertNotNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]);
            verify(mWindowManager, times(1)).addView(
                    eq(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView()), any());
        } else {
            assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]);
        }
    }

    @Test
    public void testNoRounding_NoCutout_NoPrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                false /* fillCutout */, false /* privacyDot */);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // No views added.
        verifyOverlaysExistAndAdded(false, false, false, false);
        // No Tuners tuned.
        verify(mTunerService, never()).addTunable(any(), any());
        // No dot controller init
        verify(mDotViewController, never()).initialize(any(), any(), any(), any());
    }

    @Test
    public void testNoRounding_NoCutout_PrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                false /* fillCutout */, true /* privacyDot */);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();

        // Top and bottom windows are created for privacy dot.
        // Left and right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true);

        // Rounded corner views shall not exist
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_TOP, View.GONE);
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_BOTTOM, View.GONE);

        // Privacy dots shall exist but invisible
        verifyDotViewsVisibility(View.INVISIBLE);

        // One tunable.
        verify(mTunerService, times(1)).addTunable(any(), any());
        // Dot controller init
        verify(mDotViewController, times(1)).initialize(
                isA(View.class), isA(View.class), isA(View.class), isA(View.class));
    }

    @Test
    public void testRounding_NoCutout_NoPrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                20 /* roundedPadding */, false /* multipleRadius */,
                false /* fillCutout */, false /* privacyDot */);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();

        // Top and bottom windows are created for rounded corners.
        // Left and right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true);

        // Rounded corner views shall exist
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_TOP, View.VISIBLE);
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_BOTTOM, View.VISIBLE);

        // Privacy dots shall not exist
        verifyDotViewsNullable(true);

        // One tunable.
        verify(mTunerService, times(1)).addTunable(any(), any());
        // No dot controller init
        verify(mDotViewController, never()).initialize(any(), any(), any(), any());
    }

    @Test
    public void testRounding_NoCutout_PrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                20 /* roundedPadding */, false /* multipleRadius */,
                false /* fillCutout */, true /* privacyDot */);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();

        // Top and bottom windows are created for rounded corners.
        // Left and right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true);

        // Rounded corner views shall exist
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_TOP, View.VISIBLE);
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_BOTTOM, View.VISIBLE);

        // Privacy dots shall exist but invisible
        verifyDotViewsVisibility(View.INVISIBLE);

        // One tunable.
        verify(mTunerService, times(1)).addTunable(any(), any());
        // Dot controller init
        verify(mDotViewController, times(1)).initialize(
                isA(View.class), isA(View.class), isA(View.class), isA(View.class));
    }

    @Test
    public void testRoundingRadius_NoCutout() {
        final Size testRadiusPoint = new Size(1, 1);
        setupResources(1 /* radius */, 1 /* radiusTop */, 1 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                false /* fillCutout */, true /* privacyDot */);
        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Size of corner view should same as rounded_corner_radius{_top|_bottom}
        final RoundedCornerResDelegate resDelegate = mScreenDecorations.mRoundedCornerResDelegate;
        assertThat(resDelegate.getTopRoundedSize()).isEqualTo(testRadiusPoint);
        assertThat(resDelegate.getBottomRoundedSize()).isEqualTo(testRadiusPoint);
    }

    @Test
    public void testRoundingTopBottomRadius_OnTopBottomOverlay() {
        final int testTopRadius = 1;
        final int testBottomRadius = 5;
        setupResources(testTopRadius, testTopRadius, testBottomRadius, 0 /* roundedPadding */,
                false /* multipleRadius */, false /* fillCutout */, true /* privacyDot */);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        View leftRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].getRootView()
                .findViewById(R.id.left);
        View rightRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].getRootView()
                .findViewById(R.id.right);
        verify(mScreenDecorations, atLeastOnce())
                .setSize(leftRoundedCorner, new Size(testTopRadius, testTopRadius));
        verify(mScreenDecorations, atLeastOnce())
                .setSize(rightRoundedCorner, new Size(testTopRadius, testTopRadius));
        leftRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView()
                .findViewById(R.id.left);
        rightRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView()
                .findViewById(R.id.right);
        verify(mScreenDecorations, atLeastOnce())
                .setSize(leftRoundedCorner, new Size(testBottomRadius, testBottomRadius));
        verify(mScreenDecorations, atLeastOnce())
                .setSize(rightRoundedCorner, new Size(testBottomRadius, testBottomRadius));
    }

    @Test
    public void testRoundingTopBottomRadius_OnLeftRightOverlay() {
        final int testTopRadius = 1;
        final int testBottomRadius = 5;
        setupResources(testTopRadius, testTopRadius, testBottomRadius, 0 /* roundedPadding */,
                false /* multipleRadius */, false /* fillCutout */, true /* privacyDot */);

        // left cutout
        final Rect[] bounds = {new Rect(0, 50, 1, 60), null, null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(1, 0, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        final Size topRadius = new Size(testTopRadius, testTopRadius);
        final Size bottomRadius = new Size(testBottomRadius, testBottomRadius);
        View leftRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT].getRootView()
                .findViewById(R.id.left);
        boolean isTop = mScreenDecorations.isTopRoundedCorner(BOUNDS_POSITION_LEFT, R.id.left);
        verify(mScreenDecorations, atLeastOnce())
                .setSize(leftRoundedCorner, isTop ? topRadius : bottomRadius);

        View rightRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT].getRootView()
                .findViewById(R.id.right);
        isTop = mScreenDecorations.isTopRoundedCorner(BOUNDS_POSITION_LEFT, R.id.right);
        verify(mScreenDecorations, atLeastOnce())
                .setSize(rightRoundedCorner, isTop ? topRadius : bottomRadius);

        leftRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT].getRootView()
                .findViewById(R.id.left);
        isTop = mScreenDecorations.isTopRoundedCorner(BOUNDS_POSITION_RIGHT, R.id.left);
        verify(mScreenDecorations, atLeastOnce())
                .setSize(leftRoundedCorner, isTop ? topRadius : bottomRadius);

        rightRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT].getRootView()
                .findViewById(R.id.right);
        isTop = mScreenDecorations.isTopRoundedCorner(BOUNDS_POSITION_RIGHT, R.id.right);
        verify(mScreenDecorations, atLeastOnce())
                .setSize(rightRoundedCorner, isTop ? topRadius : bottomRadius);
    }

    @Test
    public void testRoundingMultipleRadius_NoCutout_NoPrivacyDot() {
        final VectorDrawable d = (VectorDrawable) mContext.getDrawable(R.drawable.rounded);
        final Size multipleRadiusSize = new Size(d.getIntrinsicWidth(), d.getIntrinsicHeight());
        setupResources(9999 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                9999 /* roundedPadding */, true /* multipleRadius */,
                false /* fillCutout */, false /* privacyDot */);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top and bottom windows are created for rounded corners.
        // Left and right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true);

        // Rounded corner views shall exist
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_TOP, View.VISIBLE);
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_BOTTOM, View.VISIBLE);

        // Privacy dots shall not exist
        verifyDotViewsNullable(true);

        // One tunable.
        verify(mTunerService, times(1)).addTunable(any(), any());
        // No dot controller init
        verify(mDotViewController, never()).initialize(any(), any(), any(), any());

        // Size of corner view should exactly match max(width, height) of R.drawable.rounded
        final RoundedCornerResDelegate resDelegate = mScreenDecorations.mRoundedCornerResDelegate;
        assertThat(resDelegate.getTopRoundedSize()).isEqualTo(multipleRadiusSize);
        assertThat(resDelegate.getBottomRoundedSize()).isEqualTo(multipleRadiusSize);
    }

    @Test
    public void testRoundingMultipleRadius_NoCutout_PrivacyDot() {
        final VectorDrawable d = (VectorDrawable) mContext.getDrawable(R.drawable.rounded);
        final Size multipleRadiusSize = new Size(d.getIntrinsicWidth(), d.getIntrinsicHeight());
        setupResources(9999 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                9999 /* roundedPadding */, true /* multipleRadius */,
                false /* fillCutout */, true /* privacyDot */);

        // no cutout
        doReturn(null).when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top and bottom windows are created for rounded corners.
        // Left and right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true);

        // Rounded corner views shall exist
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_TOP, View.VISIBLE);
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_BOTTOM, View.VISIBLE);

        // Privacy dots shall exist but invisible
        verifyDotViewsVisibility(View.INVISIBLE);

        // One tunable.
        verify(mTunerService, times(1)).addTunable(any(), any());
        // Dot controller init
        verify(mDotViewController, times(1)).initialize(
                isA(View.class), isA(View.class), isA(View.class), isA(View.class));

        // Size of corner view should exactly match max(width, height) of R.drawable.rounded
        final RoundedCornerResDelegate resDelegate = mScreenDecorations.mRoundedCornerResDelegate;
        assertThat(resDelegate.getTopRoundedSize()).isEqualTo(multipleRadiusSize);
        assertThat(resDelegate.getBottomRoundedSize()).isEqualTo(multipleRadiusSize);
    }

    @Test
    public void testNoRounding_CutoutShortEdge_NoPrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, false /* privacyDot */);

        // top cutout
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top window is created for top cutout.
        // Bottom, left, or right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, false);

        // Privacy dots shall not exist because of no privacy
        verifyDotViewsNullable(true);

        // No dot controller init
        verify(mDotViewController, never()).initialize(any(), any(), any(), any());
    }

    @Test
    public void testNoRounding_CutoutShortEdge_PrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, true /* privacyDot */);

        // top cutout
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top window is created for top cutout.
        // Bottom window is created for privacy dot.
        // Left or right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true);

        // Top rounded corner views shall exist because of cutout
        // but be gone because of no rounded corner
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_TOP, View.GONE);
        // Bottom rounded corner views shall exist because of privacy dot
        // but be gone because of no rounded corner
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_BOTTOM, View.GONE);

        // Privacy dots shall exist but invisible
        verifyDotViewsVisibility(View.INVISIBLE);

        // Dot controller init
        verify(mDotViewController, times(1)).initialize(
                isA(View.class), isA(View.class), isA(View.class), isA(View.class));
    }

    @Test
    public void testNoRounding_CutoutLongEdge_NoPrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, false /* privacyDot */);

        // left cutout
        final Rect[] bounds = {new Rect(0, 50, 1, 60), null, null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(1, 0, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Left window is created for left cutout.
        // Bottom, top, or right window should be null.
        verifyOverlaysExistAndAdded(true, false, false, false);

        // Left rounded corner views shall exist because of cutout
        // but be gone because of no rounded corner
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_LEFT, View.GONE);

        // Top privacy dots shall not exist because of no privacy
        verifyDotViewsNullable(true);

        // No dot controller init
        verify(mDotViewController, never()).initialize(any(), any(), any(), any());
    }

    @Test
    public void testNoRounding_CutoutLongEdge_PrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, true /* privacyDot */);

        // left cutout
        final Rect[] bounds = {new Rect(0, 50, 1, 60), null, null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(1, 0, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Left window is created for left cutout.
        // Right window is created for privacy.
        // Bottom, or top window should be null.
        verifyOverlaysExistAndAdded(true, false, true, false);

        // Privacy dots shall exist but invisible
        verifyDotViewsVisibility(View.INVISIBLE);

        // Dot controller init
        verify(mDotViewController, times(1)).initialize(
                isA(View.class), isA(View.class), isA(View.class), isA(View.class));
    }

    @Test
    public void testRounding_CutoutShortEdge_NoPrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                20 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, false /* privacyDot */);

        // top cutout
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top window is created for rounded corner and top cutout.
        // Bottom window is created for rounded corner.
        // Left, or right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true);

        // Rounded corner views shall exist
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_TOP, View.VISIBLE);
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_BOTTOM, View.VISIBLE);

        // Top privacy dots shall not exist because of no privacy dot
        verifyDotViewsNullable(true);

        // No dot controller init
        verify(mDotViewController, never()).initialize(any(), any(), any(), any());
    }

    @Test
    public void testRounding_CutoutShortEdge_PrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                20 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, true /* privacyDot */);

        // top cutout
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top window is created for rounded corner and top cutout.
        // Bottom window is created for rounded corner.
        // Left, or right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true);

        // Rounded corner views shall exist
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_TOP, View.VISIBLE);
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_BOTTOM, View.VISIBLE);

        // Top privacy dots shall exist but invisible
        verifyDotViewsVisibility(View.INVISIBLE);

        // Dot controller init
        verify(mDotViewController, times(1)).initialize(
                isA(View.class), isA(View.class), isA(View.class), isA(View.class));
    }

    @Test
    public void testRounding_CutoutLongEdge_NoPrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                20 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, false /* privacyDot */);

        // left cutout
        final Rect[] bounds = {new Rect(0, 50, 1, 60), null, null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(1, 0, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Left window is created for rounded corner and left cutout.
        // Right window is created for rounded corner.
        // Top, or bottom window should be null.
        verifyOverlaysExistAndAdded(true, false, true, false);
    }

    @Test
    public void testRounding_CutoutLongEdge_PrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                20 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, true /* privacyDot */);

        // left cutout
        final Rect[] bounds = {new Rect(0, 50, 1, 60), null, null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(1, 0, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Left window is created for rounded corner, left cutout, and privacy.
        // Right window is created for rounded corner and privacy dot.
        // Top, or bottom window should be null.
        verifyOverlaysExistAndAdded(true, false, true, false);
    }

    @Test
    public void testRounding_CutoutShortAndLongEdge_NoPrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                20 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, false /* privacyDot */);

        // top and left cutout
        final Rect[] bounds = {new Rect(0, 50, 1, 60), new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(1, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top window is created for rounded corner and top cutout.
        // Bottom window is created for rounded corner.
        // Left window is created for left cutout.
        // Right window should be null.
        verifyOverlaysExistAndAdded(true, true, false, true);
    }

    @Test
    public void testRounding_CutoutShortAndLongEdge_PrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                20 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, true /* privacyDot */);

        // top and left cutout
        final Rect[] bounds = {new Rect(0, 50, 1, 60), new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(1, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Top window is created for rounded corner and top cutout.
        // Bottom window is created for rounded corner.
        // Left window is created for left cutout.
        // Right window should be null.
        verifyOverlaysExistAndAdded(true, true, false, true);
    }

    @Test
    public void testNoRounding_SwitchFrom_ShortEdgeCutout_To_LongCutout_NoPrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, false /* privacyDot */);

        // Set to short edge cutout(top).
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        verifyOverlaysExistAndAdded(false, true, false, false);

        // Switch to long edge cutout(left).
        final Rect[] newBounds = {new Rect(0, 50, 1, 60), null, null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(1, 0, 0, 0), newBounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.onConfigurationChanged(new Configuration());
        verifyOverlaysExistAndAdded(true, false, false, false);
    }

    @Test
    public void testNoRounding_SwitchFrom_ShortEdgeCutout_To_LongCutout_PrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, true /* privacyDot */);

        // Set to short edge cutout(top).
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        verifyOverlaysExistAndAdded(false, true, false, true);

        // Switch to long edge cutout(left).
        final Rect[] newBounds = {new Rect(0, 50, 1, 60), null, null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(1, 0, 0, 0), newBounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.onConfigurationChanged(new Configuration());
        verifyOverlaysExistAndAdded(true, false, true, false);

        // Verify each privacy dot id appears only once
        mDecorProviders.stream().map(DecorProvider::getViewId).forEach(viewId -> {
            int findCount = 0;
            for (OverlayWindow overlay: mScreenDecorations.mOverlays) {
                if (overlay == null) {
                    continue;
                }
                final View view = overlay.getRootView().findViewById(viewId);
                if (view != null) {
                    findCount++;
                }
            }
            assertEquals(1, findCount);
        });

    }

    @Test
    public void testDelayedCutout_NoPrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                false /* fillCutout */, false /* privacyDot */);

        // top cutout
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        assertNull(mScreenDecorations.mOverlays);

        when(mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout))
                .thenReturn(true);
        mScreenDecorations.onConfigurationChanged(new Configuration());

        // Only top windows should be added.
        verifyOverlaysExistAndAdded(false, true, false, false);
    }

    @Test
    public void testDelayedCutout_PrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                false /* fillCutout */, true /* privacyDot */);

        // top cutout
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // Both top and bottom windows should be added because of privacy dot,
        // but their visibility shall be gone because of no rounding.
        verifyOverlaysExistAndAdded(false, true, false, true);
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_TOP, View.GONE);
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_BOTTOM, View.GONE);

        when(mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout))
                .thenReturn(true);
        mScreenDecorations.onConfigurationChanged(new Configuration());

        assertNotNull(mScreenDecorations.mOverlays);
        // Both top and bottom windows should be added because of privacy dot,
        // but their visibility shall be gone because of no rounding.
        verifyOverlaysExistAndAdded(false, true, false, true);
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_TOP, View.GONE);
        verifyRoundedCornerViewsVisibility(BOUNDS_POSITION_BOTTOM, View.GONE);
    }

    @Test
    public void hasRoundedCornerOverlayFlagSet() {
        assertThat(mScreenDecorations.getWindowLayoutParams(1).privateFlags
                        & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY,
                is(PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY));
    }

    @Test
    public void testUpdateRoundedCorners() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                false /* fillCutout */, true /* privacyDot */);

        mScreenDecorations.start();
        final RoundedCornerResDelegate resDelegate = mScreenDecorations.mRoundedCornerResDelegate;
        assertEquals(resDelegate.getTopRoundedSize(), new Size(20, 20));
        assertEquals(resDelegate.getBottomRoundedSize(), new Size(20, 20));

        when(mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.rounded_corner_radius)).thenReturn(5);
        mScreenDecorations.onConfigurationChanged(null);
        assertEquals(resDelegate.getTopRoundedSize(), new Size(5, 5));
        assertEquals(resDelegate.getBottomRoundedSize(), new Size(5, 5));
    }

    @Test
    public void testOnlyRoundedCornerRadiusTop() {
        setupResources(0 /* radius */, 10 /* radiusTop */, 0 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                false /* fillCutout */, true /* privacyDot */);

        mScreenDecorations.start();
        final RoundedCornerResDelegate resDelegate = mScreenDecorations.mRoundedCornerResDelegate;
        assertEquals(new Size(10, 10), resDelegate.getTopRoundedSize());
        assertEquals(new Size(0, 0), resDelegate.getBottomRoundedSize());
    }

    @Test
    public void testOnlyRoundedCornerRadiusBottom() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 20 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                false /* fillCutout */, true /* privacyDot */);

        mScreenDecorations.start();
        final RoundedCornerResDelegate resDelegate = mScreenDecorations.mRoundedCornerResDelegate;
        assertEquals(new Size(0, 0), resDelegate.getTopRoundedSize());
        assertEquals(new Size(20, 20), resDelegate.getBottomRoundedSize());
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

    @Test
    public void testSupportHwcLayer_SwitchFrom_NotSupport() {
        setupResources(0 /* radius */, 10 /* radiusTop */, 20 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, false /* privacyDot */);

        // top cutout
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // should only inflate mOverlays when the hwc doesn't support screen decoration
        assertNull(mScreenDecorations.mScreenDecorHwcWindow);
        assertNotNull(mScreenDecorations.mOverlays);
        assertNotNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]);
        assertNotNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]);

        final DisplayDecorationSupport decorationSupport = new DisplayDecorationSupport();
        decorationSupport.format = PixelFormat.R_8;
        doReturn(decorationSupport).when(mDisplay).getDisplayDecorationSupport();
        // Trigger the support hwc screen decoration change by changing the display unique id
        mScreenDecorations.mDisplayUniqueId = "test";
        mScreenDecorations.mDisplayListener.onDisplayChanged(1);

        // should only inflate hwc layer when the hwc supports screen decoration
        assertNotNull(mScreenDecorations.mScreenDecorHwcWindow);
        assertNull(mScreenDecorations.mOverlays);
    }

    @Test
    public void testNotSupportHwcLayer_SwitchFrom_Support() {
        setupResources(0 /* radius */, 10 /* radiusTop */, 20 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, false /* privacyDot */);
        final DisplayDecorationSupport decorationSupport = new DisplayDecorationSupport();
        decorationSupport.format = PixelFormat.R_8;
        doReturn(decorationSupport).when(mDisplay).getDisplayDecorationSupport();

        // top cutout
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();
        // should only inflate hwc layer when the hwc supports screen decoration
        assertNotNull(mScreenDecorations.mScreenDecorHwcWindow);
        assertNull(mScreenDecorations.mOverlays);

        doReturn(null).when(mDisplay).getDisplayDecorationSupport();
        // Trigger the support hwc screen decoration change by changing the display unique id
        mScreenDecorations.mDisplayUniqueId = "test";
        mScreenDecorations.mDisplayListener.onDisplayChanged(1);

        // should only inflate mOverlays when the hwc doesn't support screen decoration
        assertNull(mScreenDecorations.mScreenDecorHwcWindow);
        assertNotNull(mScreenDecorations.mOverlays);
        assertNotNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]);
        assertNotNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]);
    }

    @Test
    public void testHwcLayer_noPrivacyDot() {
        setupResources(0 /* radius */, 10 /* radiusTop */, 20 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, false /* privacyDot */);
        final DisplayDecorationSupport decorationSupport = new DisplayDecorationSupport();
        decorationSupport.format = PixelFormat.R_8;
        doReturn(decorationSupport).when(mDisplay).getDisplayDecorationSupport();

        // top cutout
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();

        // Should only inflate hwc layer.
        assertNotNull(mScreenDecorations.mScreenDecorHwcWindow);
        assertNull(mScreenDecorations.mOverlays);
    }

    @Test
    public void testHwcLayer_PrivacyDot() {
        setupResources(0 /* radius */, 10 /* radiusTop */, 20 /* radiusBottom */,
                0 /* roundedPadding */, false /* multipleRadius */,
                true /* fillCutout */, true /* privacyDot */);
        final DisplayDecorationSupport decorationSupport = new DisplayDecorationSupport();
        decorationSupport.format = PixelFormat.R_8;
        doReturn(decorationSupport).when(mDisplay).getDisplayDecorationSupport();

        // top cutout
        final Rect[] bounds = {null, new Rect(9, 0, 10, 1), null, null};
        doReturn(getDisplayCutoutForRotation(Insets.of(0, 1, 0, 0), bounds))
                .when(mScreenDecorations).getCutout();

        mScreenDecorations.start();

        assertNotNull(mScreenDecorations.mScreenDecorHwcWindow);
        // mOverlays are inflated but the visibility should be GONE.
        assertNotNull(mScreenDecorations.mOverlays);
        final View topOverlay = mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].getRootView();
        final View botOverlay = mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView();
        assertEquals(topOverlay.getVisibility(), View.INVISIBLE);
        assertEquals(botOverlay.getVisibility(), View.INVISIBLE);

    }

    private void setupResources(int radius, int radiusTop, int radiusBottom, int roundedPadding,
            boolean multipleRadius, boolean fillCutout, boolean privacyDot) {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.array.config_displayUniqueIdArray,
                new String[]{});
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.array.config_roundedCornerRadiusArray,
                mMockTypedArray);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.array.config_roundedCornerTopRadiusArray,
                mMockTypedArray);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.array.config_roundedCornerBottomRadiusArray,
                mMockTypedArray);
        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_roundedCornerDrawableArray,
                mMockTypedArray);
        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_roundedCornerTopDrawableArray,
                mMockTypedArray);
        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_roundedCornerBottomDrawableArray,
                mMockTypedArray);
        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_roundedCornerMultipleRadiusArray,
                mMockTypedArray);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, radius);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_top, radiusTop);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_bottom, radiusBottom);
        mContext.getOrCreateTestableResources().addOverride(
                R.dimen.rounded_corner_content_padding, roundedPadding);
        mContext.getOrCreateTestableResources().addOverride(
                R.bool.config_roundedCornerMultipleRadius, multipleRadius);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, fillCutout);

        mDecorProviders = new ArrayList<>();
        if (privacyDot) {
            mDecorProviders.add(mPrivacyDotTopLeftDecorProvider);
            mDecorProviders.add(mPrivacyDotTopRightDecorProvider);
            mDecorProviders.add(mPrivacyDotBottomLeftDecorProvider);
            mDecorProviders.add(mPrivacyDotBottomRightDecorProvider);
        }
        when(mPrivacyDotDecorProviderFactory.getProviders()).thenReturn(mDecorProviders);
        when(mPrivacyDotDecorProviderFactory.getHasProviders()).thenReturn(privacyDot);
    }

    private DisplayCutout getDisplayCutoutForRotation(Insets safeInsets, Rect[] cutoutBounds) {
        final int rotation = mContext.getDisplay().getRotation();
        final Insets insets = RotationUtils.rotateInsets(safeInsets, rotation);
        final Rect[] sorted = new Rect[BOUNDS_POSITION_LENGTH];
        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            final int rotatedPos = ScreenDecorations.getBoundPositionFromRotation(i, rotation);
            if (cutoutBounds[i] != null) {
                RotationUtils.rotateBounds(cutoutBounds[i], new Rect(0, 0, 100, 200), rotation);
            }
            sorted[rotatedPos] = cutoutBounds[i];
        }
        return new DisplayCutout(insets, sorted[BOUNDS_POSITION_LEFT], sorted[BOUNDS_POSITION_TOP],
                sorted[BOUNDS_POSITION_RIGHT], sorted[BOUNDS_POSITION_BOTTOM]);
    }
}
