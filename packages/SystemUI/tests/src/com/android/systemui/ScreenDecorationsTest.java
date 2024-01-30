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
import static android.view.DisplayCutout.BOUNDS_POSITION_RIGHT;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.systemui.dump.LogBufferHelperKt.logcatLogBuffer;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.IdRes;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.graphics.common.DisplayDecorationSupport;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.PathParser;
import android.util.Size;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.decor.CornerDecorProvider;
import com.android.systemui.decor.CutoutDecorProviderFactory;
import com.android.systemui.decor.CutoutDecorProviderImpl;
import com.android.systemui.decor.DebugRoundedCornerModel;
import com.android.systemui.decor.DecorProvider;
import com.android.systemui.decor.DecorProviderFactory;
import com.android.systemui.decor.FaceScanningOverlayProviderImpl;
import com.android.systemui.decor.FaceScanningProviderFactory;
import com.android.systemui.decor.OverlayWindow;
import com.android.systemui.decor.PrivacyDotCornerDecorProviderImpl;
import com.android.systemui.decor.PrivacyDotDecorProviderFactory;
import com.android.systemui.decor.RoundedCornerResDelegate;
import com.android.systemui.log.ScreenDecorationsLogger;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.commandline.CommandRegistry;
import com.android.systemui.statusbar.events.PrivacyDotViewController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.concurrency.FakeThreadFactory;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;

@RunWithLooper
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ScreenDecorationsTest extends SysuiTestCase {

    private ScreenDecorations mScreenDecorations;
    private WindowManager mWindowManager;
    private DisplayManager mDisplayManager;
    private SecureSettings mSecureSettings;
    private FakeExecutor mExecutor;
    private final FakeDisplayTracker mDisplayTracker = new FakeDisplayTracker(mContext);
    private FakeThreadFactory mThreadFactory;
    private ArrayList<DecorProvider> mPrivacyDecorProviders;
    private ArrayList<DecorProvider> mFaceScanningProviders;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private AuthController mAuthController;
    @Mock
    private Display mDisplay;
    @Mock
    private CommandRegistry mCommandRegistry;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private PrivacyDotViewController mDotViewController;
    @Mock
    private TypedArray mMockTypedArray;
    @Mock
    private PrivacyDotDecorProviderFactory mPrivacyDotDecorProviderFactory;
    @Mock
    private FaceScanningProviderFactory mFaceScanningProviderFactory;
    @Mock
    private FaceScanningOverlayProviderImpl mFaceScanningDecorProvider;
    @Mock
    private CornerDecorProvider mPrivacyDotTopLeftDecorProvider;
    @Mock
    private CornerDecorProvider mPrivacyDotTopRightDecorProvider;
    @Mock
    private CornerDecorProvider mPrivacyDotBottomLeftDecorProvider;
    @Mock
    private CornerDecorProvider mPrivacyDotBottomRightDecorProvider;
    @Mock
    private Display.Mode mDisplayMode;
    @Mock
    private DisplayInfo mDisplayInfo;
    private PrivacyDotViewController.ShowingListener mPrivacyDotShowingListener;
    @Mock
    private CutoutDecorProviderFactory mCutoutFactory;
    @Captor
    private ArgumentCaptor<AuthController.Callback> mAuthControllerCallback;
    private List<DecorProvider> mMockCutoutList;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        Handler mainHandler = new Handler(TestableLooper.get(this).getLooper());
        mSecureSettings = new FakeSettings();
        mExecutor = new FakeExecutor(new FakeSystemClock());
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
        doReturn(mDisplayMode).when(mDisplayInfo).getMode();

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

        // Default no cutout
        mMockCutoutList = new ArrayList<>();
        doAnswer(it -> !(mMockCutoutList.isEmpty())).when(mCutoutFactory).getHasProviders();
        doReturn(mMockCutoutList).when(mCutoutFactory).getProviders();

        mFaceScanningDecorProvider = spy(new FaceScanningOverlayProviderImpl(
                BOUNDS_POSITION_TOP,
                mAuthController,
                mStatusBarStateController,
                mKeyguardUpdateMonitor,
                mExecutor,
                new ScreenDecorationsLogger(logcatLogBuffer("TestLogBuffer"))));

        mScreenDecorations = spy(new ScreenDecorations(mContext, mSecureSettings,
                mCommandRegistry, mUserTracker, mDisplayTracker, mDotViewController,
                mThreadFactory,
                mPrivacyDotDecorProviderFactory, mFaceScanningProviderFactory,
                new ScreenDecorationsLogger(logcatLogBuffer("TestLogBuffer")),
                mAuthController) {
            @Override
            public void start() {
                super.start();
                mExecutor.runAllReady();
            }

            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                super.onConfigurationChanged(newConfig);
                mExecutor.runAllReady();
            }

            @Override
            protected void updateOverlayWindowVisibilityIfViewExists(@Nullable View view) {
                super.updateOverlayWindowVisibilityIfViewExists(view);
                mExecutor.runAllReady();
            }

            @Override
            protected CutoutDecorProviderFactory getCutoutFactory() {
                return ScreenDecorationsTest.this.mCutoutFactory;
            }
        });
        mScreenDecorations.mDisplayInfo = mDisplayInfo;
        // Make sure tests are never run starting in debug mode
        mScreenDecorations.setDebug(false);
        doReturn(1f).when(mScreenDecorations).getPhysicalPixelDisplaySizeRatio();
        doNothing().when(mScreenDecorations).updateOverlayProviderViews(any());

        try {
            mPrivacyDotShowingListener = mScreenDecorations.mPrivacyDotShowingListener.getClass()
                    .getDeclaredConstructor(ScreenDecorations.class)
                    .newInstance(mScreenDecorations);
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    @NonNull
    private int[] getRoundCornerIdsFromOverlayId(@DisplayCutout.BoundsPosition int overlayId) {
        switch (overlayId) {
            case BOUNDS_POSITION_LEFT:
                return new int[] {
                        R.id.rounded_corner_top_left,
                        R.id.rounded_corner_top_left };
            case BOUNDS_POSITION_TOP:
                return new int[] {
                        R.id.rounded_corner_top_left,
                        R.id.rounded_corner_top_right };
            case BOUNDS_POSITION_RIGHT:
                return new int[] {
                        R.id.rounded_corner_top_right,
                        R.id.rounded_corner_bottom_right };
            case BOUNDS_POSITION_BOTTOM:
                return new int[] {
                        R.id.rounded_corner_bottom_left,
                        R.id.rounded_corner_bottom_right };
            default:
                throw new IllegalArgumentException("unknown overlayId: " + overlayId);
        }
    }

    private void verifyRoundedCornerViewsExist(
            @DisplayCutout.BoundsPosition final int overlayId,
            @View.Visibility final boolean isExist) {
        final View overlay = mScreenDecorations.mOverlays[overlayId].getRootView();
        for (int id: getRoundCornerIdsFromOverlayId(overlayId)) {
            final View view = overlay.findViewById(id);
            if (isExist) {
                assertNotNull(view);
                assertThat(view.getVisibility()).isEqualTo(View.VISIBLE);
            } else {
                assertNull(view);
            }
        }
    }

    private void verifyFaceScanningViewExists(final boolean exists) {
        final View overlay = mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].getRootView();
        final View view = overlay.findViewById(mFaceScanningDecorProvider.getViewId());
        if (exists) {
            assertNotNull(view);
        } else {
            assertNull(view);
        }
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

    private void verifyOverlaysExistAndAdded(boolean left, boolean top, boolean right,
            boolean bottom, @Nullable Integer visibilityIfExist) {
        if (left || top || right || bottom) {
            assertNotNull(mScreenDecorations.mOverlays);
        } else {
            assertNull(mScreenDecorations.mOverlays);
            return;
        }

        if (left) {
            final OverlayWindow overlay = mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT];
            assertNotNull(overlay);
            verify(mWindowManager, times(1)).addView(
                    eq(overlay.getRootView()), any());
            if (visibilityIfExist != null) {
                assertEquals(visibilityIfExist.intValue(), overlay.getRootView().getVisibility());
            }
        } else {
            assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT]);
        }

        if (top) {
            final OverlayWindow overlay = mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP];
            assertNotNull(overlay);
            verify(mWindowManager, times(1)).addView(
                    eq(overlay.getRootView()), any());
            if (visibilityIfExist != null) {
                assertEquals(visibilityIfExist.intValue(), overlay.getRootView().getVisibility());
            }
        } else {
            assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP]);
        }

        if (right) {
            final OverlayWindow overlay = mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT];
            assertNotNull(overlay);
            verify(mWindowManager, times(1)).addView(
                    eq(overlay.getRootView()), any());
            if (visibilityIfExist != null) {
                assertEquals(visibilityIfExist.intValue(), overlay.getRootView().getVisibility());
            }
        } else {
            assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT]);
        }

        if (bottom) {
            final OverlayWindow overlay = mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM];
            assertNotNull(overlay);
            verify(mWindowManager, times(1)).addView(
                    eq(overlay.getRootView()), any());
            if (visibilityIfExist != null) {
                assertEquals(visibilityIfExist.intValue(), overlay.getRootView().getVisibility());
            }
        } else {
            assertNull(mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM]);
        }
    }

    @Test
    public void testNoRounding_NoCutout_NoPrivacyDot_NoFaceScanning() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        // no cutout (default)

        mScreenDecorations.start();
        // No views added.
        verifyOverlaysExistAndAdded(false, false, false, false, null);
        // No dot controller init
        verify(mDotViewController, never()).initialize(any(), any(), any(), any());
    }

    @Test
    public void testNoRounding_NoCutout_PrivacyDot_NoFaceScanning() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // no cutout (default)

        mScreenDecorations.start();

        // Top and bottom windows are created with INVISIBLE because of privacy dot only
        // Left and right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true, View.INVISIBLE);
        verify(mDotViewController, times(1)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(1)).setShowingListener(
                mScreenDecorations.mPrivacyDotShowingListener);

        // Rounded corner views shall not exist
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_TOP, false);
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_BOTTOM, false);

        // Privacy dots shall exist but invisible
        verifyDotViewsVisibility(View.INVISIBLE);

        // Face scanning doesn't exist
        verifyFaceScanningViewExists(false);

        // Dot controller init
        verify(mDotViewController, times(1)).initialize(
                isA(View.class), isA(View.class), isA(View.class), isA(View.class));
    }

    @Test
    public void testRounding_NoCutout_NoPrivacyDot_NoFaceScanning() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                20 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        // no cutout (default)

        mScreenDecorations.start();

        // Top and bottom windows are created for rounded corners.
        // Left and right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true, View.VISIBLE);

        // Rounded corner views shall exist
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_TOP, true);
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_BOTTOM, true);

        // Privacy dots shall not exist
        verifyDotViewsNullable(true);

        // Face scanning doesn't exist
        verifyFaceScanningViewExists(false);

        // No dot controller init
        verify(mDotViewController, never()).initialize(any(), any(), any(), any());
    }

    @Test
    public void testRounding_NoCutout_PrivacyDot_NoFaceScanning() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                20 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // no cutout (default)

        mScreenDecorations.start();

        // Top and bottom windows are created for rounded corners.
        // Left and right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true, View.VISIBLE);
        verify(mDotViewController, times(1)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(1)).setShowingListener(null);

        // Rounded corner views shall exist
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_TOP, true);
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_BOTTOM, true);

        // Privacy dots shall exist but invisible
        verifyDotViewsVisibility(View.INVISIBLE);

        // Face scanning doesn't exist
        verifyFaceScanningViewExists(false);

        // Dot controller init
        verify(mDotViewController, times(1)).initialize(
                isA(View.class), isA(View.class), isA(View.class), isA(View.class));
    }

    @Test
    public void testRoundingRadius_NoCutout() {
        final Size testRadiusPoint = new Size(3, 3);
        setupResources(1 /* radius */, 1 /* radiusTop */, 1 /* radiusBottom */,
                getTestsDrawable(com.android.systemui.tests.R.drawable.rounded3px)
                /* roundedTopDrawable */,
                getTestsDrawable(com.android.systemui.tests.R.drawable.rounded3px)
                /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // no cutout (default)

        mScreenDecorations.start();
        // Size of corner view should same as rounded_corner_radius{_top|_bottom}
        final RoundedCornerResDelegate resDelegate = mScreenDecorations.mRoundedCornerResDelegate;
        assertThat(resDelegate.getTopRoundedSize()).isEqualTo(testRadiusPoint);
        assertThat(resDelegate.getBottomRoundedSize()).isEqualTo(testRadiusPoint);
    }

    @Test
    public void testRoundingTopBottomRadius_OnTopBottomOverlay() {
        setupResources(1 /* radius */, 1 /* radiusTop */, 1 /* radiusBottom */,
                getTestsDrawable(com.android.systemui.tests.R.drawable.rounded4px)
                /* roundedTopDrawable */,
                getTestsDrawable(com.android.systemui.tests.R.drawable.rounded3px)
                /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // no cutout (default)

        mScreenDecorations.start();
        View leftRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].getRootView()
                .findViewById(R.id.rounded_corner_top_left);
        View rightRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].getRootView()
                .findViewById(R.id.rounded_corner_top_right);
        ViewGroup.LayoutParams leftParams = leftRoundedCorner.getLayoutParams();
        ViewGroup.LayoutParams rightParams = rightRoundedCorner.getLayoutParams();
        assertEquals(4, leftParams.width);
        assertEquals(4, leftParams.height);
        assertEquals(4, rightParams.width);
        assertEquals(4, rightParams.height);

        leftRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView()
                .findViewById(R.id.rounded_corner_bottom_left);
        rightRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView()
                .findViewById(R.id.rounded_corner_bottom_right);
        leftParams = leftRoundedCorner.getLayoutParams();
        rightParams = rightRoundedCorner.getLayoutParams();
        assertEquals(3, leftParams.width);
        assertEquals(3, leftParams.height);
        assertEquals(3, rightParams.width);
        assertEquals(3, rightParams.height);
    }

    @Test
    public void testRoundingTopBottomRadius_OnLeftRightOverlay() {
        setupResources(1 /* radius */, 1 /* radiusTop */, 1 /* radiusBottom */,
                getTestsDrawable(com.android.systemui.tests.R.drawable.rounded3px)
                /* roundedTopDrawable */,
                getTestsDrawable(com.android.systemui.tests.R.drawable.rounded5px)
                /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // left cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_LEFT));

        mScreenDecorations.start();
        View topRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT].getRootView()
                .findViewById(R.id.rounded_corner_top_left);
        View bottomRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_LEFT].getRootView()
                .findViewById(R.id.rounded_corner_bottom_left);
        ViewGroup.LayoutParams topParams = topRoundedCorner.getLayoutParams();
        ViewGroup.LayoutParams bottomParams = bottomRoundedCorner.getLayoutParams();
        assertEquals(3, topParams.width);
        assertEquals(3, topParams.height);
        assertEquals(5, bottomParams.width);
        assertEquals(5, bottomParams.height);

        topRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT].getRootView()
                .findViewById(R.id.rounded_corner_top_right);
        bottomRoundedCorner = mScreenDecorations.mOverlays[BOUNDS_POSITION_RIGHT].getRootView()
                .findViewById(R.id.rounded_corner_bottom_right);
        topParams = topRoundedCorner.getLayoutParams();
        bottomParams = bottomRoundedCorner.getLayoutParams();
        assertEquals(3, topParams.width);
        assertEquals(3, topParams.height);
        assertEquals(5, bottomParams.width);
        assertEquals(5, bottomParams.height);
    }

    @Test
    public void testNoRounding_CutoutShortEdge_NoPrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        // top cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();
        // Top window is created for top cutout.
        // Bottom, left, or right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, false, View.VISIBLE);

        // Privacy dots shall not exist because of no privacy
        verifyDotViewsNullable(true);

        // No dot controller init
        verify(mDotViewController, never()).initialize(any(), any(), any(), any());
    }

    @Test
    public void testNoRounding_CutoutShortEdge_PrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // top cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();
        // Top window is created for top cutout.
        // Bottom window is created for privacy dot.
        // Left or right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true, View.VISIBLE);
        verify(mDotViewController, times(1)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(1)).setShowingListener(null);

        // Top rounded corner views shall exist because of cutout
        // but be gone because of no rounded corner
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_TOP, false);
        // Bottom rounded corner views shall exist because of privacy dot
        // but be gone because of no rounded corner
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_BOTTOM, false);

        // Privacy dots shall exist but invisible
        verifyDotViewsVisibility(View.INVISIBLE);

        // Dot controller init
        verify(mDotViewController, times(1)).initialize(
                isA(View.class), isA(View.class), isA(View.class), isA(View.class));
    }

    @Test
    public void testNoRounding_CutoutLongEdge_NoPrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        // left cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_LEFT));

        mScreenDecorations.start();
        // Left window is created for left cutout.
        // Bottom, top, or right window should be null.
        verifyOverlaysExistAndAdded(true, false, false, false, View.VISIBLE);

        // Left rounded corner views shall exist because of cutout
        // but be gone because of no rounded corner
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_LEFT, false);

        // Top privacy dots shall not exist because of no privacy
        verifyDotViewsNullable(true);

        // No dot controller init
        verify(mDotViewController, never()).initialize(any(), any(), any(), any());
    }

    @Test
    public void testNoRounding_CutoutLongEdge_PrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // left cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_LEFT));

        mScreenDecorations.start();
        // Left window is created for left cutout.
        // Right window is created for privacy.
        // Bottom, or top window should be null.
        verifyOverlaysExistAndAdded(true, false, true, false, View.VISIBLE);
        verify(mDotViewController, times(1)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(1)).setShowingListener(null);

        // Privacy dots shall exist but invisible
        verifyDotViewsVisibility(View.INVISIBLE);

        // Dot controller init
        verify(mDotViewController, times(1)).initialize(
                isA(View.class), isA(View.class), isA(View.class), isA(View.class));
    }

    @Test
    public void testRounding_CutoutShortEdge_NoPrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                20 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        // top cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();
        // Top window is created for rounded corner and top cutout.
        // Bottom window is created for rounded corner.
        // Left, or right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true, View.VISIBLE);

        // Rounded corner views shall exist
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_TOP, true);
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_BOTTOM, true);

        // Top privacy dots shall not exist because of no privacy dot
        verifyDotViewsNullable(true);

        // No dot controller init
        verify(mDotViewController, never()).initialize(any(), any(), any(), any());
    }

    @Test
    public void testRounding_CutoutShortEdge_PrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                20 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // top cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();
        // Top window is created for rounded corner and top cutout.
        // Bottom window is created for rounded corner.
        // Left, or right window should be null.
        verifyOverlaysExistAndAdded(false, true, false, true, View.VISIBLE);
        verify(mDotViewController, times(1)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(1)).setShowingListener(null);

        // Rounded corner views shall exist
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_TOP, true);
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_BOTTOM, true);

        // Top privacy dots shall exist but invisible
        verifyDotViewsVisibility(View.INVISIBLE);

        // Dot controller init
        verify(mDotViewController, times(1)).initialize(
                isA(View.class), isA(View.class), isA(View.class), isA(View.class));
    }

    @Test
    public void testRounding_CutoutLongEdge_NoPrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                20 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        // left cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_LEFT));

        mScreenDecorations.start();
        // Left window is created for rounded corner and left cutout.
        // Right window is created for rounded corner.
        // Top, or bottom window should be null.
        verifyOverlaysExistAndAdded(true, false, true, false, View.VISIBLE);
    }

    @Test
    public void testRounding_CutoutLongEdge_PrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                20 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // left cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_LEFT));

        mScreenDecorations.start();
        // Left window is created for rounded corner, left cutout, and privacy.
        // Right window is created for rounded corner and privacy dot.
        // Top, or bottom window should be null.
        verifyOverlaysExistAndAdded(true, false, true, false, View.VISIBLE);
        verify(mDotViewController, times(1)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(1)).setShowingListener(null);
    }

    @Test
    public void testRounding_CutoutShortAndLongEdge_NoPrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                20 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        // top and left cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_LEFT));
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();
        // Top window is created for rounded corner and top cutout.
        // Bottom window is created for rounded corner.
        // Left window is created for left cutout.
        // Right window should be null.
        verifyOverlaysExistAndAdded(true, true, false, true, View.VISIBLE);
    }

    @Test
    public void testRounding_CutoutShortAndLongEdge_PrivacyDot() {
        setupResources(20 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                20 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // top and left cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_LEFT));
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();
        // Top window is created for rounded corner and top cutout.
        // Bottom window is created for rounded corner.
        // Left window is created for left cutout.
        // Right window should be null.
        verifyOverlaysExistAndAdded(true, true, false, true, View.VISIBLE);
        verify(mDotViewController, times(1)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(1)).setShowingListener(null);
    }

    @Test
    public void testNoRounding_SwitchFrom_ShortEdgeCutout_To_LongCutout_NoPrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        // Set to short edge cutout(top).
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();
        verifyOverlaysExistAndAdded(false, true, false, false, View.VISIBLE);

        // Switch to long edge cutout(left).
        mMockCutoutList.set(0, new CutoutDecorProviderImpl(BOUNDS_POSITION_LEFT));

        mScreenDecorations.onConfigurationChanged(new Configuration());
        verifyOverlaysExistAndAdded(true, false, false, false, View.VISIBLE);
    }

    @Test
    public void testNoRounding_SwitchFrom_ShortEdgeCutout_To_LongCutout_PrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // Set to short edge cutout(top).
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();
        verifyOverlaysExistAndAdded(false, true, false, true, View.VISIBLE);
        verify(mDotViewController, times(1)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(1)).setShowingListener(null);

        // Switch to long edge cutout(left).
        mMockCutoutList.set(0, new CutoutDecorProviderImpl(BOUNDS_POSITION_LEFT));

        mScreenDecorations.onConfigurationChanged(new Configuration());
        verifyOverlaysExistAndAdded(true, false, true, false, View.VISIBLE);
        verify(mDotViewController, times(2)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(2)).setShowingListener(null);

        // Verify each privacy dot id appears only once
        mPrivacyDecorProviders.stream().map(DecorProvider::getViewId).forEach(viewId -> {
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
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        // No cutout (default)

        mScreenDecorations.start();
        verifyOverlaysExistAndAdded(false, false, false, false, null);

        // top cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.onConfigurationChanged(new Configuration());

        // Only top windows should be added.
        verifyOverlaysExistAndAdded(false, true, false, false, View.VISIBLE);
    }

    @Test
    public void testDelayedCutout_PrivacyDot() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        // no cutout (default)

        mScreenDecorations.start();
        // Both top and bottom windows should be added with INVISIBLE because of only privacy dot,
        // but rounded corners visibility shall be gone because of no rounding.
        verifyOverlaysExistAndAdded(false, true, false, true, View.INVISIBLE);
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_TOP, false);
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_BOTTOM, false);
        verify(mDotViewController, times(1)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(1)).setShowingListener(
                mScreenDecorations.mPrivacyDotShowingListener);

        // top cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.onConfigurationChanged(new Configuration());

        // Both top and bottom windows should be added with VISIBLE because of privacy dot and
        // cutout, but rounded corners visibility shall be gone because of no rounding.
        verifyOverlaysExistAndAdded(false, true, false, true, View.VISIBLE);
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_TOP, false);
        verifyRoundedCornerViewsExist(BOUNDS_POSITION_BOTTOM, false);
        verify(mDotViewController, times(2)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(1)).setShowingListener(null);
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
                getTestsDrawable(com.android.systemui.tests.R.drawable.rounded3px)
                /* roundedTopDrawable */,
                getTestsDrawable(com.android.systemui.tests.R.drawable.rounded4px)
                /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning*/);
        mDisplayInfo.rotation = Surface.ROTATION_0;

        mScreenDecorations.start();

        final RoundedCornerResDelegate resDelegate = mScreenDecorations.mRoundedCornerResDelegate;
        assertEquals(new Size(3, 3), resDelegate.getTopRoundedSize());
        assertEquals(new Size(4, 4), resDelegate.getBottomRoundedSize());

        doReturn(2f).when(mScreenDecorations).getPhysicalPixelDisplaySizeRatio();
        mDisplayInfo.rotation = Surface.ROTATION_270;

        mScreenDecorations.onConfigurationChanged(null);

        assertEquals(new Size(6, 6), resDelegate.getTopRoundedSize());
        assertEquals(new Size(8, 8), resDelegate.getBottomRoundedSize());
    }

    @Test
    public void testOnlyRoundedCornerRadiusTop() {
        setupResources(0 /* radius */, 10 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        mScreenDecorations.start();

        final RoundedCornerResDelegate resDelegate = mScreenDecorations.mRoundedCornerResDelegate;
        assertEquals(true, resDelegate.getHasTop());
        assertEquals(false, resDelegate.getHasBottom());
        assertEquals(getDrawableIntrinsicSize(R.drawable.rounded_corner_top),
                resDelegate.getTopRoundedSize());

        final DecorProviderFactory mRoundedCornerFactory = mScreenDecorations.mRoundedCornerFactory;
        assertEquals(true, mRoundedCornerFactory.getHasProviders());
        final List<DecorProvider> providers = mRoundedCornerFactory.getProviders();
        assertEquals(2, providers.size());
        assertEquals(true, providers.get(0).getAlignedBounds().contains(BOUNDS_POSITION_TOP));
        assertEquals(true, providers.get(1).getAlignedBounds().contains(BOUNDS_POSITION_TOP));    }

    @Test
    public void testOnlyRoundedCornerRadiusBottom() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 20 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        mScreenDecorations.start();

        final RoundedCornerResDelegate resDelegate = mScreenDecorations.mRoundedCornerResDelegate;
        assertEquals(false, resDelegate.getHasTop());
        assertEquals(true, resDelegate.getHasBottom());
        assertEquals(getDrawableIntrinsicSize(R.drawable.rounded_corner_bottom),
                resDelegate.getBottomRoundedSize());

        final DecorProviderFactory mRoundedCornerFactory = mScreenDecorations.mRoundedCornerFactory;
        assertEquals(true, mRoundedCornerFactory.getHasProviders());
        final List<DecorProvider> providers = mRoundedCornerFactory.getProviders();
        assertEquals(2, providers.size());
        assertEquals(true, providers.get(0).getAlignedBounds().contains(BOUNDS_POSITION_BOTTOM));
        assertEquals(true, providers.get(1).getAlignedBounds().contains(BOUNDS_POSITION_BOTTOM));
    }

    @Test
    public void testDebugRoundedCorners_noDeviceCornersSet() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        mScreenDecorations.start();
        // No rounded corners exist at this point
        verifyOverlaysExistAndAdded(false, false, false, false, View.VISIBLE);

        // Path from rounded.xml, scaled by 10x to produce 80x80 corners
        Path debugPath = PathParser.createPathFromPathData("M8,0H0v8C0,3.6,3.6,0,8,0z");
        // WHEN debug corners are added to the delegate
        DebugRoundedCornerModel debugCorner = new DebugRoundedCornerModel(
                debugPath,
                80,
                80,
                10f,
                10f
        );
        mScreenDecorations.mDebugRoundedCornerDelegate
                .applyNewDebugCorners(debugCorner, debugCorner);

        // AND debug mode is entered
        mScreenDecorations.setDebug(true);
        mExecutor.runAllReady();

        // THEN the debug corners provide decor
        List<DecorProvider> providers = mScreenDecorations.getProviders(false);
        assertEquals(4, providers.size());

        // Top and bottom overlays contain the debug rounded corners
        verifyOverlaysExistAndAdded(false, true, false, true, View.VISIBLE);
    }

    @Test
    public void testDebugRoundedCornersRemoved_noDeviceCornersSet() {
        // GIVEN a device with no rounded corners defined
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        mScreenDecorations.start();
        // No rounded corners exist at this point
        verifyOverlaysExistAndAdded(false, false, false, false, View.VISIBLE);

        // Path from rounded.xml, scaled by 10x to produce 80x80 corners
        Path debugPath = PathParser.createPathFromPathData("M8,0H0v8C0,3.6,3.6,0,8,0z");
        // WHEN debug corners are added to the delegate
        DebugRoundedCornerModel debugCorner = new DebugRoundedCornerModel(
                debugPath,
                80,
                80,
                10f,
                10f
        );
        mScreenDecorations.mDebugRoundedCornerDelegate
                .applyNewDebugCorners(debugCorner, debugCorner);

        // AND debug mode is entered
        mScreenDecorations.setDebug(true);
        mExecutor.runAllReady();

        // Top and bottom overlays contain the debug rounded corners
        verifyOverlaysExistAndAdded(false, true, false, true, View.VISIBLE);

        // WHEN debug is exited
        mScreenDecorations.setDebug(false);
        mExecutor.runAllReady();

        // THEN the decor is removed
        verifyOverlaysExistAndAdded(false, false, false, false, View.VISIBLE);
        assertThat(mScreenDecorations.mDebugRoundedCornerDelegate.getHasBottom()).isFalse();
        assertThat(mScreenDecorations.mDebugRoundedCornerDelegate.getHasTop()).isFalse();
    }

    @Test
    public void testRegistration_From_NoOverlay_To_HasOverlays() {
        doReturn(false).when(mScreenDecorations).hasOverlays();
        mScreenDecorations.start();
        assertThat(mScreenDecorations.mIsRegistered, is(false));

        doReturn(true).when(mScreenDecorations).hasOverlays();
        mScreenDecorations.onConfigurationChanged(new Configuration());
        assertThat(mScreenDecorations.mIsRegistered, is(true));
    }

    @Test
    public void testRegistration_From_HasOverlays_To_HasOverlays() {
        doReturn(true).when(mScreenDecorations).hasOverlays();

        mScreenDecorations.start();
        assertThat(mScreenDecorations.mIsRegistered, is(true));

        mScreenDecorations.onConfigurationChanged(new Configuration());
        assertThat(mScreenDecorations.mIsRegistered, is(true));
    }

    @Test
    public void testRegistration_From_HasOverlays_To_NoOverlay() {
        doReturn(true).when(mScreenDecorations).hasOverlays();

        mScreenDecorations.start();
        assertThat(mScreenDecorations.mIsRegistered, is(true));

        doReturn(false).when(mScreenDecorations).hasOverlays();
        mScreenDecorations.onConfigurationChanged(new Configuration());
        assertThat(mScreenDecorations.mIsRegistered, is(false));
    }

    @Test
    public void testSupportHwcLayer_SwitchFrom_NotSupport() {
        setupResources(0 /* radius */, 10 /* radiusTop */, 20 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        // top cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();
        // should only inflate mOverlays when the hwc doesn't support screen decoration
        assertNull(mScreenDecorations.mScreenDecorHwcWindow);
        verifyOverlaysExistAndAdded(false, true, false, true, View.VISIBLE);

        final DisplayDecorationSupport decorationSupport = new DisplayDecorationSupport();
        decorationSupport.format = PixelFormat.R_8;
        doReturn(decorationSupport).when(mDisplay).getDisplayDecorationSupport();
        // Trigger the support hwc screen decoration change by changing the display unique id
        mScreenDecorations.mDisplayUniqueId = "test";
        mScreenDecorations.mDisplayListener.onDisplayChanged(1);

        // should only inflate hwc layer when the hwc supports screen decoration
        assertNotNull(mScreenDecorations.mScreenDecorHwcWindow);
        verifyOverlaysExistAndAdded(false, false, false, false, null);
    }

    @Test
    public void testNotSupportHwcLayer_SwitchFrom_Support() {
        setupResources(0 /* radius */, 10 /* radiusTop */, 20 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);
        final DisplayDecorationSupport decorationSupport = new DisplayDecorationSupport();
        decorationSupport.format = PixelFormat.R_8;
        doReturn(decorationSupport).when(mDisplay).getDisplayDecorationSupport();

        // top cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();
        // should only inflate hwc layer when the hwc supports screen decoration
        assertNotNull(mScreenDecorations.mScreenDecorHwcWindow);
        verifyOverlaysExistAndAdded(false, false, false, false, null);

        doReturn(null).when(mDisplay).getDisplayDecorationSupport();
        // Trigger the support hwc screen decoration change by changing the display unique id
        mScreenDecorations.mDisplayUniqueId = "test";
        mScreenDecorations.mDisplayListener.onDisplayChanged(1);

        // should only inflate mOverlays when the hwc doesn't support screen decoration
        assertNull(mScreenDecorations.mScreenDecorHwcWindow);
        verifyOverlaysExistAndAdded(false, true, false, true, View.VISIBLE);
    }

    @Test
    public void faceSensorLocationChangesReloadsFaceScanningOverlay() {
        mFaceScanningProviders = new ArrayList<>();
        mFaceScanningProviders.add(mFaceScanningDecorProvider);
        when(mFaceScanningProviderFactory.getProviders()).thenReturn(mFaceScanningProviders);
        when(mFaceScanningProviderFactory.getHasProviders()).thenReturn(true);
        ScreenDecorations screenDecorations = new ScreenDecorations(mContext,
                mSecureSettings, mCommandRegistry, mUserTracker, mDisplayTracker,
                mDotViewController,
                mThreadFactory, mPrivacyDotDecorProviderFactory, mFaceScanningProviderFactory,
                new ScreenDecorationsLogger(logcatLogBuffer("TestLogBuffer")), mAuthController);
        screenDecorations.start();
        verify(mAuthController).addCallback(mAuthControllerCallback.capture());
        when(mContext.getDisplay()).thenReturn(mDisplay);
        when(mDisplay.getDisplayInfo(any())).thenAnswer(new Answer<Boolean>() {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                DisplayInfo displayInfo = invocation.getArgument(0);
                int modeId = 1;
                displayInfo.modeId = modeId;
                displayInfo.supportedModes = new Display.Mode[]{new Display.Mode(modeId, 1024, 1024,
                        90)};
                return false;
            }
        });
        mExecutor.runAllReady();
        clearInvocations(mFaceScanningDecorProvider);

        AuthController.Callback callback = mAuthControllerCallback.getValue();
        callback.onFaceSensorLocationChanged();
        mExecutor.runAllReady();

        verify(mFaceScanningDecorProvider).onReloadResAndMeasure(any(),
                anyInt(),
                anyInt(),
                anyInt(),
                any());
    }

    @Test
    public void testPrivacyDotShowingListenerWorkWellWithNullParameter() {
        mPrivacyDotShowingListener.onPrivacyDotShown(null);
        mPrivacyDotShowingListener.onPrivacyDotHidden(null);
    }

    @Test
    public void testAutoShowHideOverlayWindowWhenSupportHwcLayer() {
        setupResources(0 /* radius */, 10 /* radiusTop */, 20 /* radiusBottom */,
                getTestsDrawable(com.android.systemui.tests.R.drawable.rounded3px)
                /* roundedTopDrawable */,
                getTestsDrawable(com.android.systemui.tests.R.drawable.rounded4px)
                /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, true /* faceScanning */);
        final DisplayDecorationSupport decorationSupport = new DisplayDecorationSupport();
        decorationSupport.format = PixelFormat.R_8;
        doReturn(decorationSupport).when(mDisplay).getDisplayDecorationSupport();

        // top cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();
        // Inflate top and bottom overlay with INVISIBLE because of only privacy dots on sw layer
        verifyOverlaysExistAndAdded(false, true, false, true, View.INVISIBLE);

        // Make sure view found and window visibility changed as well
        final View view = mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView()
                .findViewById(R.id.privacy_dot_bottom_right_container);
        view.setVisibility(View.VISIBLE);
        mPrivacyDotShowingListener.onPrivacyDotShown(view);
        assertEquals(View.VISIBLE,
                mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView().getVisibility());
        view.setVisibility(View.INVISIBLE);
        mPrivacyDotShowingListener.onPrivacyDotHidden(view);
        assertEquals(View.INVISIBLE,
                mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView().getVisibility());

        // Make sure face scanning view found and window visibility updates on camera protection
        // update
        final View faceScanView = mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].getRootView()
                .findViewById(mFaceScanningDecorProvider.getViewId());
        when(mFaceScanningProviderFactory.shouldShowFaceScanningAnim()).thenReturn(true);
        faceScanView.setVisibility(View.VISIBLE);
        mScreenDecorations.showCameraProtection(new Path(), new Rect());
        mExecutor.runAllReady();
        assertEquals(View.VISIBLE,
                mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].getRootView().getVisibility());
    }

    @Test
    public void testAutoShowHideOverlayWindowWhenNoRoundedAndNoCutout() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, true /* faceScanning */);

        // no cutout (default)

        mScreenDecorations.start();
        // Inflate top and bottom overlay with INVISIBLE because of only privacy dots on sw layer
        verifyOverlaysExistAndAdded(false, true, false, true, View.INVISIBLE);

        // Make sure view found and window visibility changed as well
        final View view = mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView()
                .findViewById(R.id.privacy_dot_bottom_right_container);
        view.setVisibility(View.VISIBLE);
        mPrivacyDotShowingListener.onPrivacyDotShown(view);
        assertEquals(View.VISIBLE,
                mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView().getVisibility());
        view.setVisibility(View.INVISIBLE);
        mPrivacyDotShowingListener.onPrivacyDotHidden(view);
        assertEquals(View.INVISIBLE,
                mScreenDecorations.mOverlays[BOUNDS_POSITION_BOTTOM].getRootView().getVisibility());

        // Make sure face scanning view found and window visibility updates on camera protection
        // update
        final View faceScanView = mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].getRootView()
                .findViewById(mFaceScanningDecorProvider.getViewId());
        faceScanView.setVisibility(View.VISIBLE);
        when(mFaceScanningProviderFactory.shouldShowFaceScanningAnim()).thenReturn(true);
        mScreenDecorations.showCameraProtection(new Path(), new Rect());
        mExecutor.runAllReady();
        assertEquals(View.VISIBLE,
                mScreenDecorations.mOverlays[BOUNDS_POSITION_TOP].getRootView().getVisibility());
    }

    @Test
    public void testHwcLayer_noPrivacyDot_noFaceScanning() {
        setupResources(0 /* radius */, 10 /* radiusTop */, 20 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);
        final DisplayDecorationSupport decorationSupport = new DisplayDecorationSupport();
        decorationSupport.format = PixelFormat.R_8;
        doReturn(decorationSupport).when(mDisplay).getDisplayDecorationSupport();

        // top cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();

        // Should only inflate hwc layer.
        assertNotNull(mScreenDecorations.mScreenDecorHwcWindow);
        verifyOverlaysExistAndAdded(false, false, false, false, null);
    }

    @Test
    public void testHwcLayer_PrivacyDot_FaceScanning() {
        setupResources(0 /* radius */, 10 /* radiusTop */, 20 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, true /* faceScanning */);
        final DisplayDecorationSupport decorationSupport = new DisplayDecorationSupport();
        decorationSupport.format = PixelFormat.R_8;
        doReturn(decorationSupport).when(mDisplay).getDisplayDecorationSupport();

        // top cutout
        mMockCutoutList.add(new CutoutDecorProviderImpl(BOUNDS_POSITION_TOP));

        mScreenDecorations.start();

        assertNotNull(mScreenDecorations.mScreenDecorHwcWindow);
        // mOverlays are inflated but the visibility should be INVISIBLE.
        verifyOverlaysExistAndAdded(false, true, false, true, View.INVISIBLE);
        verify(mDotViewController, times(1)).initialize(any(), any(), any(), any());
        verify(mDotViewController, times(1)).setShowingListener(
                mScreenDecorations.mPrivacyDotShowingListener);

        verifyFaceScanningViewExists(true);
    }

    @Test
    public void testHasSameProvidersWithNullOverlays() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, false /* privacyDot */, false /* faceScanning */);

        mScreenDecorations.start();

        final ArrayList<DecorProvider> newProviders = new ArrayList<>();
        assertTrue(mScreenDecorations.hasSameProviders(newProviders));

        newProviders.add(mPrivacyDotTopLeftDecorProvider);
        assertFalse(mScreenDecorations.hasSameProviders(newProviders));

        newProviders.add(mPrivacyDotTopRightDecorProvider);
        assertFalse(mScreenDecorations.hasSameProviders(newProviders));
    }

    @Test
    public void testHasSameProvidersWithPrivacyDots() {
        setupResources(0 /* radius */, 0 /* radiusTop */, 0 /* radiusBottom */,
                null /* roundedTopDrawable */, null /* roundedBottomDrawable */,
                0 /* roundedPadding */, true /* privacyDot */, false /* faceScanning */);

        mScreenDecorations.start();

        final ArrayList<DecorProvider> newProviders = new ArrayList<>();
        assertFalse(mScreenDecorations.hasSameProviders(newProviders));

        newProviders.add(mPrivacyDotTopLeftDecorProvider);
        assertFalse(mScreenDecorations.hasSameProviders(newProviders));

        newProviders.add(mPrivacyDotTopRightDecorProvider);
        assertFalse(mScreenDecorations.hasSameProviders(newProviders));

        newProviders.add(mPrivacyDotBottomLeftDecorProvider);
        assertFalse(mScreenDecorations.hasSameProviders(newProviders));

        newProviders.add(mPrivacyDotBottomRightDecorProvider);
        assertTrue(mScreenDecorations.hasSameProviders(newProviders));
    }

    private Size getDrawableIntrinsicSize(@DrawableRes int drawableResId) {
        final Drawable d = mContext.getDrawable(drawableResId);
        return new Size(d.getIntrinsicWidth(), d.getIntrinsicHeight());
    }

    @Nullable
    private Drawable getTestsDrawable(@DrawableRes int drawableId) {
        try {
            return mContext.createPackageContext("com.android.systemui.tests", 0)
                    .getDrawable(drawableId);
        } catch (PackageManager.NameNotFoundException exception) {
            return null;
        }
    }

    private void setupResources(int radius, int radiusTop, int radiusBottom,
            @Nullable Drawable roundedTopDrawable, @Nullable Drawable roundedBottomDrawable,
            int roundedPadding, boolean privacyDot, boolean faceScanning) {
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
                com.android.internal.R.dimen.rounded_corner_radius, radius);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_top, radiusTop);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_bottom, radiusBottom);
        if (roundedTopDrawable != null) {
            mContext.getOrCreateTestableResources().addOverride(
                    R.drawable.rounded_corner_top,
                    roundedTopDrawable);
        }
        if (roundedBottomDrawable != null) {
            mContext.getOrCreateTestableResources().addOverride(
                    R.drawable.rounded_corner_bottom,
                    roundedBottomDrawable);
        }
        mContext.getOrCreateTestableResources().addOverride(
                R.dimen.rounded_corner_content_padding, roundedPadding);

        mPrivacyDecorProviders = new ArrayList<>();
        if (privacyDot) {
            mPrivacyDecorProviders.add(mPrivacyDotTopLeftDecorProvider);
            mPrivacyDecorProviders.add(mPrivacyDotTopRightDecorProvider);
            mPrivacyDecorProviders.add(mPrivacyDotBottomLeftDecorProvider);
            mPrivacyDecorProviders.add(mPrivacyDotBottomRightDecorProvider);
        }
        when(mPrivacyDotDecorProviderFactory.getProviders()).thenReturn(mPrivacyDecorProviders);
        when(mPrivacyDotDecorProviderFactory.getHasProviders()).thenReturn(privacyDot);

        mFaceScanningProviders = new ArrayList<>();
        if (faceScanning) {
            mFaceScanningProviders.add(mFaceScanningDecorProvider);
        }
        when(mFaceScanningProviderFactory.getProviders()).thenReturn(mFaceScanningProviders);
        when(mFaceScanningProviderFactory.getHasProviders()).thenReturn(faceScanning);
    }
}
