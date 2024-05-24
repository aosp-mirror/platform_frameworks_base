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

import static android.accessibilityservice.MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN;

import static com.android.server.accessibility.magnification.FullScreenMagnificationController.MagnificationInfoChangedCallback;
import static com.android.server.accessibility.magnification.MockMagnificationConnection.TEST_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import android.accessibilityservice.MagnificationConfig;
import android.animation.TimeAnimator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManagerInternal;
import android.os.Looper;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.view.DisplayInfo;
import android.view.MagnificationSpec;
import android.view.accessibility.MagnificationAnimationCallback;
import android.widget.Scroller;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.accessibility.Flags;
import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerInternal.MagnificationCallbacks;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.testng.Assert;

import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class FullScreenMagnificationControllerTest {
    static final Rect INITIAL_MAGNIFICATION_BOUNDS = new Rect(0, 0, 100, 200);
    static final PointF INITIAL_MAGNIFICATION_BOUNDS_CENTER = new PointF(
            INITIAL_MAGNIFICATION_BOUNDS.centerX(), INITIAL_MAGNIFICATION_BOUNDS.centerY());
    static final PointF INITIAL_BOUNDS_UPPER_LEFT_2X_CENTER = new PointF(25, 50);
    static final PointF INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER = new PointF(75, 150);
    static final Rect OTHER_MAGNIFICATION_BOUNDS = new Rect(100, 200, 500, 600);
    static final Rect OTHER_MAGNIFICATION_BOUNDS_COMPAT = new Rect(50, 100, 450, 500);
    static final PointF OTHER_BOUNDS_LOWER_RIGHT_2X_CENTER = new PointF(400, 500);
    static final Region INITIAL_MAGNIFICATION_REGION = new Region(INITIAL_MAGNIFICATION_BOUNDS);
    static final Region OTHER_REGION_COMPAT = new Region(OTHER_MAGNIFICATION_BOUNDS_COMPAT);
    static final Region OTHER_REGION = new Region(OTHER_MAGNIFICATION_BOUNDS);
    static final int SERVICE_ID_1 = 1;
    static final int SERVICE_ID_2 = 2;
    static final int DISPLAY_0 = 0;
    static final int DISPLAY_1 = 1;
    static final int DISPLAY_COUNT = 2;
    static final int INVALID_DISPLAY = 2;
    private static final int CURRENT_USER_ID = UserHandle.USER_SYSTEM;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    final FullScreenMagnificationController.ControllerContext mMockControllerCtx =
            mock(FullScreenMagnificationController.ControllerContext.class);
    final Context mMockContext = mock(Context.class);
    final AccessibilityTraceManager mMockTraceManager = mock(AccessibilityTraceManager.class);
    final WindowManagerInternal mMockWindowManager = mock(WindowManagerInternal.class);
    private final MagnificationAnimationCallback mAnimationCallback = mock(
            MagnificationAnimationCallback.class);
    private final MagnificationInfoChangedCallback mRequestObserver = mock(
            MagnificationInfoChangedCallback.class);
    private final MessageCapturingHandler mMessageCapturingHandler = new MessageCapturingHandler(
            null);
    private MagnificationScaleProvider mScaleProvider;
    private MockContentResolver mResolver;
    private final MagnificationThumbnail mMockThumbnail = mock(MagnificationThumbnail.class);
    private final Scroller mMockScroller = mock(Scroller.class);

    private final ArgumentCaptor<MagnificationConfig> mConfigCaptor = ArgumentCaptor.forClass(
            MagnificationConfig.class);

    ValueAnimator mMockValueAnimator;
    ValueAnimator.AnimatorUpdateListener mTargetAnimationListener;
    ValueAnimator.AnimatorListener mStateListener;

    private final TimeAnimator mMockTimeAnimator = mock(TimeAnimator.class);

    FullScreenMagnificationController mFullScreenMagnificationController;

    public DisplayManagerInternal mDisplayManagerInternalMock = mock(DisplayManagerInternal.class);

    private float mOriginalMagnificationPersistedScale;

    @Before
    public void setUp() {
        Context realContext = InstrumentationRegistry.getContext();
        Looper looper = realContext.getMainLooper();
        // Pretending ID of the Thread associated with looper as main thread ID in controller
        when(mMockContext.getMainLooper()).thenReturn(looper);
        when(mMockControllerCtx.getContext()).thenReturn(mMockContext);
        when(mMockControllerCtx.getTraceManager()).thenReturn(mMockTraceManager);
        when(mMockControllerCtx.getWindowManager()).thenReturn(mMockWindowManager);
        when(mMockControllerCtx.getHandler()).thenReturn(mMessageCapturingHandler);
        when(mMockControllerCtx.getAnimationDuration()).thenReturn(1000L);
        mResolver = new MockContentResolver();
        mResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mMockContext.getContentResolver()).thenReturn(mResolver);
        mOriginalMagnificationPersistedScale = Settings.Secure.getFloatForUser(mResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, 2.0f,
                CURRENT_USER_ID);
        Settings.Secure.putFloatForUser(mResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, 2.0f,
                CURRENT_USER_ID);
        initMockWindowManager();

        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalDensityDpi = 300;
        doReturn(displayInfo).when(mDisplayManagerInternalMock).getDisplayInfo(anyInt());
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternalMock);

        mScaleProvider = new MagnificationScaleProvider(mMockContext);

        mFullScreenMagnificationController =
                new FullScreenMagnificationController(
                        mMockControllerCtx,
                        new Object(),
                        mRequestObserver,
                        mScaleProvider,
                        () -> mMockThumbnail,
                        ConcurrentUtils.DIRECT_EXECUTOR,
                        () -> mMockScroller,
                        () -> mMockTimeAnimator);
    }

    @After
    public void tearDown() {
        mMessageCapturingHandler.removeAllMessages();
        Settings.Secure.putFloatForUser(mResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                mOriginalMagnificationPersistedScale,
                CURRENT_USER_ID);
    }


    @Test
    public void testRegister_WindowManagerAndContextRegisterListeners() {
        register(DISPLAY_0);
        register(DISPLAY_1);
        register(INVALID_DISPLAY);
        verify(mMockContext).registerReceiver(
                any(BroadcastReceiver.class), any(IntentFilter.class));
        verify(mMockWindowManager).setMagnificationCallbacks(
                eq(DISPLAY_0), any(MagnificationCallbacks.class));
        verify(mMockWindowManager).setMagnificationCallbacks(
                eq(DISPLAY_1), any(MagnificationCallbacks.class));
        verify(mMockWindowManager).setMagnificationCallbacks(
                eq(INVALID_DISPLAY), any(MagnificationCallbacks.class));
        assertTrue(mFullScreenMagnificationController.isRegistered(DISPLAY_0));
        assertTrue(mFullScreenMagnificationController.isRegistered(DISPLAY_1));
        assertFalse(mFullScreenMagnificationController.isRegistered(INVALID_DISPLAY));
    }

    @Test
    public void testRegister_WindowManagerAndContextUnregisterListeners() {
        register(DISPLAY_0);
        register(DISPLAY_1);
        mFullScreenMagnificationController.unregister(DISPLAY_0);
        verify(mMockContext, times(0)).unregisterReceiver(any(BroadcastReceiver.class));
        mFullScreenMagnificationController.unregister(DISPLAY_1);
        verify(mMockContext).unregisterReceiver(any(BroadcastReceiver.class));
        verify(mMockWindowManager).setMagnificationCallbacks(eq(DISPLAY_0), eq(null));
        verify(mMockWindowManager).setMagnificationCallbacks(eq(DISPLAY_1), eq(null));
        assertFalse(mFullScreenMagnificationController.isRegistered(DISPLAY_0));
        assertFalse(mFullScreenMagnificationController.isRegistered(DISPLAY_1));

        // Once for each display on unregister
        verify(mMockThumbnail, times(2)).hideThumbnail();
    }

    @Test
    public void testInitialState_noMagnificationAndMagnificationRegionReadFromWindowManager() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            initialState_noMagnificationAndMagnificationRegionReadFromWindowManager(i);
            resetMockWindowManager();
        }
    }

    private void initialState_noMagnificationAndMagnificationRegionReadFromWindowManager(
            int displayId) {
        register(displayId);
        MagnificationSpec expectedInitialSpec = getMagnificationSpec(1.0f, 0.0f, 0.0f);
        Region initialMagRegion = new Region();
        Rect initialBounds = new Rect();

        assertEquals(expectedInitialSpec, getCurrentMagnificationSpec(displayId));
        mFullScreenMagnificationController.getMagnificationRegion(displayId, initialMagRegion);
        mFullScreenMagnificationController.getMagnificationBounds(displayId, initialBounds);
        assertEquals(INITIAL_MAGNIFICATION_REGION, initialMagRegion);
        assertEquals(INITIAL_MAGNIFICATION_BOUNDS, initialBounds);
        assertEquals(INITIAL_MAGNIFICATION_BOUNDS.centerX(),
                mFullScreenMagnificationController.getCenterX(displayId), 0.0f);
        assertEquals(INITIAL_MAGNIFICATION_BOUNDS.centerY(),
                mFullScreenMagnificationController.getCenterY(displayId), 0.0f);
    }

    @Test
    public void testNotRegistered_publicMethodsShouldBeBenign() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            notRegistered_publicMethodsShouldBeBenign(i);
            resetMockWindowManager();
        }
    }

    private void notRegistered_publicMethodsShouldBeBenign(int displayId) {
        checkActivatedAndMagnifying(/* activated= */ false, /* magnifying= */ false, displayId);

        assertFalse(
                mFullScreenMagnificationController.magnificationRegionContains(displayId, 100,
                        100));
        assertFalse(mFullScreenMagnificationController.reset(displayId, true));
        assertFalse(mFullScreenMagnificationController.setScale(displayId, 2, 100, 100, true, 0));
        assertFalse(mFullScreenMagnificationController.setCenter(displayId, 100, 100, false, 1));
        assertFalse(mFullScreenMagnificationController.setScaleAndCenter(displayId,
                1.5f, 100, 100, false, 2));
        assertTrue(mFullScreenMagnificationController.getIdOfLastServiceToMagnify(displayId) < 0);

        mFullScreenMagnificationController.getMagnificationRegion(displayId, new Region());
        mFullScreenMagnificationController.getMagnificationBounds(displayId, new Rect());
        mFullScreenMagnificationController.getScale(displayId);
        mFullScreenMagnificationController.getOffsetX(displayId);
        mFullScreenMagnificationController.getOffsetY(displayId);
        mFullScreenMagnificationController.getCenterX(displayId);
        mFullScreenMagnificationController.getCenterY(displayId);
        mFullScreenMagnificationController.offsetMagnifiedRegion(displayId, 50, 50, 1);
        mFullScreenMagnificationController.unregister(displayId);
    }

    @Test
    public void testSetScale_noAnimation_shouldGoStraightToWindowManagerAndUpdateState() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            setScale_noAnimation_shouldGoStraightToWindowManagerAndUpdateState(i);
            resetMockWindowManager();
        }
    }

    private void setScale_noAnimation_shouldGoStraightToWindowManagerAndUpdateState(int displayId) {
        register(displayId);
        final float scale = 2.0f;
        final PointF center = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        final PointF offsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, center, scale);
        assertTrue(mFullScreenMagnificationController
                .setScale(displayId, scale, center.x, center.y, false, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        final MagnificationSpec expectedSpec = getMagnificationSpec(scale, offsets);
        verify(mMockWindowManager).setMagnificationSpec(
                eq(displayId), argThat(closeTo(expectedSpec)));
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(expectedSpec));
        assertEquals(center.x, mFullScreenMagnificationController.getCenterX(displayId), 0.0);
        assertEquals(center.y, mFullScreenMagnificationController.getCenterY(displayId), 0.0);
        verify(mMockValueAnimator, times(0)).start();
    }

    @Test
    public void testSetScale_withPivotAndAnimation_stateChangesAndAnimationHappens() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            setScale_withPivotAndAnimation_stateChangesAndAnimationHappens(i);
            resetMockWindowManager();
        }
    }

    private void setScale_withPivotAndAnimation_stateChangesAndAnimationHappens(int displayId) {
        register(displayId);
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        float scale = 2.0f;
        PointF pivotPoint = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        assertTrue(mFullScreenMagnificationController
                .setScale(displayId, scale, pivotPoint.x, pivotPoint.y, true, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        // New center should be halfway between original center and pivot
        PointF newCenter = new PointF((pivotPoint.x + INITIAL_MAGNIFICATION_BOUNDS.centerX()) / 2,
                (pivotPoint.y + INITIAL_MAGNIFICATION_BOUNDS.centerY()) / 2);
        PointF offsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        MagnificationSpec endSpec = getMagnificationSpec(scale, offsets);

        assertEquals(newCenter.x, mFullScreenMagnificationController.getCenterX(displayId), 0.5);
        assertEquals(newCenter.y, mFullScreenMagnificationController.getCenterY(displayId), 0.5);
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(endSpec));
        verify(mMockValueAnimator).start();

        // Initial value
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(0.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId), eq(startSpec));

        // Intermediate point
        Mockito.reset(mMockWindowManager);
        float fraction = 0.5f;
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(fraction);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(getInterpolatedMagSpec(startSpec, endSpec, fraction))));

        // Final value
        Mockito.reset(mMockWindowManager);
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(1.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId), argThat(closeTo(endSpec)));
    }

    @Test
    public void testSetCenter_whileMagnifying_noAnimation_centerMoves() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            setCenter_whileMagnifying_noAnimation_centerMoves(i);
            resetMockWindowManager();
        }
    }

    private void setCenter_whileMagnifying_noAnimation_centerMoves(int displayId) {
        register(displayId);
        // First zoom in
        float scale = 2.0f;
        assertTrue(mFullScreenMagnificationController.setScale(displayId, scale,
                INITIAL_MAGNIFICATION_BOUNDS.centerX(), INITIAL_MAGNIFICATION_BOUNDS.centerY(),
                false, SERVICE_ID_1));
        Mockito.reset(mMockWindowManager);

        PointF newCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        assertTrue(mFullScreenMagnificationController
                .setCenter(displayId, newCenter.x, newCenter.y, false, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();
        PointF expectedOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        MagnificationSpec expectedSpec = getMagnificationSpec(scale, expectedOffsets);

        verify(mMockWindowManager).setMagnificationSpec(
                eq(displayId), argThat(closeTo(expectedSpec)));
        assertEquals(newCenter.x, mFullScreenMagnificationController.getCenterX(displayId), 0.0);
        assertEquals(newCenter.y, mFullScreenMagnificationController.getCenterY(displayId), 0.0);
        verify(mMockValueAnimator, times(0)).start();
    }

    @Test
    public void testSetScaleAndCenter_animated_stateChangesAndAnimationHappens() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            setScaleAndCenter_animated_stateChangesAndAnimationHappens(i);
            resetMockWindowManager();
        }
    }

    private void setScaleAndCenter_animated_stateChangesAndAnimationHappens(int displayId) {
        register(displayId);
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        float scale = 2.5f;
        PointF newCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        final MagnificationConfig config = buildConfig(scale, newCenter.x, newCenter.y);
        PointF offsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        MagnificationSpec endSpec = getMagnificationSpec(scale, offsets);

        assertTrue(mFullScreenMagnificationController.setScaleAndCenter(displayId, scale,
                newCenter.x, newCenter.y, mAnimationCallback, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        assertEquals(newCenter.x, mFullScreenMagnificationController.getCenterX(displayId), 0.5);
        assertEquals(newCenter.y, mFullScreenMagnificationController.getCenterY(displayId), 0.5);
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(endSpec));
        verify(mRequestObserver).onFullScreenMagnificationChanged(eq(displayId),
                eq(INITIAL_MAGNIFICATION_REGION), mConfigCaptor.capture());
        assertConfigEquals(config, mConfigCaptor.getValue());
        verify(mMockValueAnimator).start();
        verify(mRequestObserver).onRequestMagnificationSpec(displayId, SERVICE_ID_1);

        // Initial value
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(0.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId), eq(startSpec));

        // Intermediate point
        Mockito.reset(mMockWindowManager);
        float fraction = 0.33f;
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(fraction);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(getInterpolatedMagSpec(startSpec, endSpec, fraction))));

        // Final value
        Mockito.reset(mMockWindowManager);
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(1.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        mStateListener.onAnimationEnd(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId), argThat(closeTo(endSpec)));
        verify(mAnimationCallback).onResult(eq(true), any());
    }

    @Test
    public void testSetScaleAndCenterWithAnimation_sameSpec_noAnimationButInvokeEndCallback() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            setScaleAndCenter_sameSpec_noAnimationButInvokeCallbacks(i);
        }
    }

    private void setScaleAndCenter_sameSpec_noAnimationButInvokeCallbacks(int displayId) {
        register(displayId);
        final PointF center = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        final float targetScale = 2.0f;
        assertTrue(mFullScreenMagnificationController.setScaleAndCenter(displayId,
                targetScale, center.x, center.y, false, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        assertFalse(mFullScreenMagnificationController.setScaleAndCenter(displayId,
                targetScale, center.x, center.y, mAnimationCallback, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        verify(mMockValueAnimator, never()).start();
        verify(mAnimationCallback).onResult(eq(true), any());
    }

    @Test
    public void testSetScaleAndCenter_scaleOutOfBounds_cappedAtLimits() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            setScaleAndCenter_scaleOutOfBounds_cappedAtLimits(i);
            resetMockWindowManager();
        }
    }

    private void setScaleAndCenter_scaleOutOfBounds_cappedAtLimits(int displayId) {
        register(displayId);
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        PointF newCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        PointF offsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter,
                MagnificationScaleProvider.MAX_SCALE);
        MagnificationSpec endSpec = getMagnificationSpec(
                MagnificationScaleProvider.MAX_SCALE, offsets);

        assertTrue(mFullScreenMagnificationController.setScaleAndCenter(displayId,
                MagnificationScaleProvider.MAX_SCALE + 1.0f,
                newCenter.x, newCenter.y, false, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        assertEquals(newCenter.x, mFullScreenMagnificationController.getCenterX(displayId), 0.5);
        assertEquals(newCenter.y, mFullScreenMagnificationController.getCenterY(displayId), 0.5);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId), argThat(closeTo(endSpec)));
        Mockito.reset(mMockWindowManager);

        // Verify that we can't zoom below 1x
        assertTrue(mFullScreenMagnificationController.setScaleAndCenter(displayId, 0.5f,
                INITIAL_MAGNIFICATION_BOUNDS_CENTER.x, INITIAL_MAGNIFICATION_BOUNDS_CENTER.y,
                false, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        assertEquals(INITIAL_MAGNIFICATION_BOUNDS_CENTER.x,
                mFullScreenMagnificationController.getCenterX(displayId), 0.5);
        assertEquals(INITIAL_MAGNIFICATION_BOUNDS_CENTER.y,
                mFullScreenMagnificationController.getCenterY(displayId), 0.5);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId), argThat(closeTo(startSpec)));
    }

    @Test
    public void testSetScaleAndCenter_centerOutOfBounds_cappedAtLimits() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            setScaleAndCenter_centerOutOfBounds_cappedAtLimits(i);
            resetMockWindowManager();
        }
    }

    private void setScaleAndCenter_centerOutOfBounds_cappedAtLimits(int displayId) {
        register(displayId);
        float scale = 2.0f;

        // Off the edge to the top and left
        assertTrue(mFullScreenMagnificationController.setScaleAndCenter(displayId,
                scale, -100f, -200f, false, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        PointF newCenter = INITIAL_BOUNDS_UPPER_LEFT_2X_CENTER;
        PointF newOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        assertEquals(newCenter.x, mFullScreenMagnificationController.getCenterX(displayId), 0.5);
        assertEquals(newCenter.y, mFullScreenMagnificationController.getCenterY(displayId), 0.5);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(getMagnificationSpec(scale, newOffsets))));
        Mockito.reset(mMockWindowManager);

        // Off the edge to the bottom and right
        assertTrue(mFullScreenMagnificationController.setScaleAndCenter(displayId, scale,
                INITIAL_MAGNIFICATION_BOUNDS.right + 1, INITIAL_MAGNIFICATION_BOUNDS.bottom + 1,
                false, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();
        newCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        newOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        assertEquals(newCenter.x, mFullScreenMagnificationController.getCenterX(displayId), 0.5);
        assertEquals(newCenter.y, mFullScreenMagnificationController.getCenterY(displayId), 0.5);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(getMagnificationSpec(scale, newOffsets))));
    }

    @Test
    public void testMagnificationRegionChanged_serviceNotified() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            magnificationRegionChanged_serviceNotified(i);
            resetMockWindowManager();
        }
    }

    private void magnificationRegionChanged_serviceNotified(int displayId) {
        register(displayId);
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        callbacks.onMagnificationRegionChanged(OTHER_REGION);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationConfig config = buildConfig(1.0f, OTHER_MAGNIFICATION_BOUNDS.centerX(),
                OTHER_MAGNIFICATION_BOUNDS.centerY());
        verify(mRequestObserver).onFullScreenMagnificationChanged(eq(displayId), eq(OTHER_REGION),
                mConfigCaptor.capture());
        assertConfigEquals(config, mConfigCaptor.getValue());

        // The first time is triggered when the thumbnail is just created.
        // The second time is triggered when the magnification region changed.
        verify(mMockThumbnail, times(2)).setThumbnailBounds(
                /* currentBounds= */ any(),
                /* scale= */ anyFloat(),
                /* centerX= */ anyFloat(),
                /* centerY= */ anyFloat()
        );
    }

    @Test
    public void testOffsetMagnifiedRegion_whileMagnifying_offsetsMove() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            offsetMagnifiedRegion_whileMagnifying_offsetsMove(i);
            resetMockWindowManager();
        }
    }

    private void offsetMagnifiedRegion_whileMagnifying_offsetsMove(int displayId) {
        register(displayId);
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        float scale = 2.0f;
        PointF startOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, startCenter, scale);
        // First zoom in
        assertTrue(mFullScreenMagnificationController
                .setScaleAndCenter(displayId, scale, startCenter.x, startCenter.y, false,
                        SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();
        Mockito.reset(mMockWindowManager);

        PointF newCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        PointF newOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        mFullScreenMagnificationController.offsetMagnifiedRegion(displayId,
                startOffsets.x - newOffsets.x, startOffsets.y - newOffsets.y,
                SERVICE_ID_1);
        mMessageCapturingHandler.sendAllMessages();

        MagnificationSpec expectedSpec = getMagnificationSpec(scale, newOffsets);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(expectedSpec)));
        assertEquals(newCenter.x, mFullScreenMagnificationController.getCenterX(displayId), 0.0);
        assertEquals(newCenter.y, mFullScreenMagnificationController.getCenterY(displayId), 0.0);
        verify(mMockValueAnimator, times(0)).start();
    }

    @Test
    public void testOffsetMagnifiedRegion_whileNotMagnifying_hasNoEffect() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            offsetMagnifiedRegion_whileNotMagnifying_hasNoEffect(i);
            resetMockWindowManager();
        }
    }

    private void offsetMagnifiedRegion_whileNotMagnifying_hasNoEffect(int displayId) {
        register(displayId);
        Mockito.reset(mMockWindowManager);
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        mFullScreenMagnificationController.offsetMagnifiedRegion(displayId, 10, 10, SERVICE_ID_1);
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(startSpec));
        mFullScreenMagnificationController.offsetMagnifiedRegion(displayId, -10, -10, SERVICE_ID_1);
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(startSpec));
        verifyNoMoreInteractions(mMockWindowManager);
    }

    @Test
    public void testOffsetMagnifiedRegion_whileMagnifyingButAtEdge_hasNoEffect() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            offsetMagnifiedRegion_whileMagnifyingButAtEdge_hasNoEffect(i);
            resetMockWindowManager();
        }
    }

    private void offsetMagnifiedRegion_whileMagnifyingButAtEdge_hasNoEffect(int displayId) {
        register(displayId);
        float scale = 2.0f;

        // Upper left edges
        PointF ulCenter = INITIAL_BOUNDS_UPPER_LEFT_2X_CENTER;
        assertTrue(mFullScreenMagnificationController
                .setScaleAndCenter(displayId, scale, ulCenter.x, ulCenter.y, false,
                        SERVICE_ID_1));
        Mockito.reset(mMockWindowManager);
        MagnificationSpec ulSpec = getCurrentMagnificationSpec(displayId);
        mFullScreenMagnificationController.offsetMagnifiedRegion(displayId, -10, -10,
                SERVICE_ID_1);
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(ulSpec));
        verifyNoMoreInteractions(mMockWindowManager);

        // Lower right edges
        PointF lrCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        assertTrue(mFullScreenMagnificationController
                .setScaleAndCenter(displayId, scale, lrCenter.x, lrCenter.y, false,
                        SERVICE_ID_1));
        Mockito.reset(mMockWindowManager);
        MagnificationSpec lrSpec = getCurrentMagnificationSpec(displayId);
        mFullScreenMagnificationController.offsetMagnifiedRegion(displayId, 10, 10,
                SERVICE_ID_1);
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(lrSpec));
        verifyNoMoreInteractions(mMockWindowManager);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FULLSCREEN_FLING_GESTURE)
    public void testStartFling_whileMagnifying_flings() throws InterruptedException {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            startFling_whileMagnifying_flings(i);
            resetMockWindowManager();
        }
    }

    private void startFling_whileMagnifying_flings(int displayId) throws InterruptedException {
        register(displayId);
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        float scale = 2.0f;
        // First zoom in
        assertTrue(mFullScreenMagnificationController
                .setScaleAndCenter(displayId, scale, startCenter.x, startCenter.y, false,
                        SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        PointF newCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        PointF newOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        mFullScreenMagnificationController.startFling(displayId,
                /* xPixelsPerSecond= */ 400f,
                /* yPixelsPerSecond= */ 100f,
                SERVICE_ID_1
        );
        mMessageCapturingHandler.sendAllMessages();

        verify(mMockTimeAnimator).start();
        verify(mMockScroller).fling(
                /* startX= */ eq((int) newOffsets.x / 2),
                /* startY= */ eq((int) newOffsets.y / 2),
                /* velocityX= */ eq(400),
                /* velocityY= */ eq(100),
                /* minX= */ anyInt(),
                /* minY= */ anyInt(),
                /* maxX= */ anyInt(),
                /* maxY= */ anyInt()
        );
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FULLSCREEN_FLING_GESTURE)
    public void testStopFling_whileMagnifyingAndFlinging_stops() throws InterruptedException {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            stopFling_whileMagnifyingAndFlinging_stops(i);
            resetMockWindowManager();
        }
    }

    private void stopFling_whileMagnifyingAndFlinging_stops(int displayId)
            throws InterruptedException {
        register(displayId);
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        float scale = 2.0f;
        PointF startOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, startCenter, scale);
        // First zoom in
        assertTrue(mFullScreenMagnificationController
                .setScaleAndCenter(displayId, scale, startCenter.x, startCenter.y, false,
                        SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        mFullScreenMagnificationController.startFling(displayId,
                /* xPixelsPerSecond= */ 400f,
                /* yPixelsPerSecond= */ 100f,
                SERVICE_ID_1
        );
        mMessageCapturingHandler.sendAllMessages();

        when(mMockTimeAnimator.isRunning()).thenReturn(true);

        mFullScreenMagnificationController.cancelFling(displayId, SERVICE_ID_1);
        mMessageCapturingHandler.sendAllMessages();

        verify(mMockTimeAnimator).cancel();
        // Can't verify forceFinished() because it's final
//        verify(mMockScroller).forceFinished(eq(true));
    }

    @Test
    public void testGetIdOfLastServiceToChange_returnsCorrectValue() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            getIdOfLastServiceToChange_returnsCorrectValue(i);
            resetMockWindowManager();
        }
    }

    private void getIdOfLastServiceToChange_returnsCorrectValue(int displayId) {
        register(displayId);
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        assertTrue(mFullScreenMagnificationController
                .setScale(displayId, 2.0f, startCenter.x, startCenter.y, false,
                        SERVICE_ID_1));
        assertEquals(SERVICE_ID_1,
                mFullScreenMagnificationController.getIdOfLastServiceToMagnify(displayId));
        assertTrue(mFullScreenMagnificationController
                .setScale(displayId, 1.5f, startCenter.x, startCenter.y, false,
                        SERVICE_ID_2));
        assertEquals(SERVICE_ID_2,
                mFullScreenMagnificationController.getIdOfLastServiceToMagnify(displayId));
    }

    @Test
    public void testResetIfNeeded_resetsOnlyIfLastMagnifyingServiceIsDisabled() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            resetIfNeeded_resetsOnlyIfLastMagnifyingServiceIsDisabled(i);
            resetMockWindowManager();
        }
    }

    private void resetIfNeeded_resetsOnlyIfLastMagnifyingServiceIsDisabled(int displayId) {
        register(displayId);
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        mFullScreenMagnificationController
                .setScale(displayId, 2.0f, startCenter.x, startCenter.y, false,
                        SERVICE_ID_1);
        mFullScreenMagnificationController
                .setScale(displayId, 1.5f, startCenter.x, startCenter.y, false,
                        SERVICE_ID_2);
        assertFalse(mFullScreenMagnificationController.resetIfNeeded(displayId, SERVICE_ID_1));
        checkActivatedAndMagnifying(/* activated= */ true, /* magnifying= */ true, displayId);
        assertTrue(mFullScreenMagnificationController.resetIfNeeded(displayId, SERVICE_ID_2));
        checkActivatedAndMagnifying(/* activated= */ false, /* magnifying= */ false, displayId);

        // Once on init before it's activated and once for reset
        verify(mMockThumbnail, times(2)).hideThumbnail();
    }

    @Test
    public void testResetIfNeeded_doesWhatItSays() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            testResetIfNeeded_doesWhatItSays(i);
            resetMockWindowManager();
        }
    }

    private void testResetIfNeeded_doesWhatItSays(int displayId) {
        register(displayId);
        zoomIn2xToMiddle(displayId);
        mMessageCapturingHandler.sendAllMessages();
        reset(mRequestObserver);
        assertTrue(mFullScreenMagnificationController.resetIfNeeded(displayId, false));
        verify(mRequestObserver).onFullScreenMagnificationChanged(eq(displayId),
                eq(INITIAL_MAGNIFICATION_REGION), any(MagnificationConfig.class));
        checkActivatedAndMagnifying(/* activated= */ false, /* magnifying= */ false, displayId);
        assertFalse(mFullScreenMagnificationController.resetIfNeeded(displayId, false));
    }

    @Test
    public void testReset_notMagnifying_noStateChangeButInvokeCallback() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            reset_notMagnifying_noStateChangeButInvokeCallback(i);
        }
    }

    private void reset_notMagnifying_noStateChangeButInvokeCallback(int displayId) {
        register(displayId);

        assertFalse(mFullScreenMagnificationController.reset(displayId, mAnimationCallback));
        mMessageCapturingHandler.sendAllMessages();

        verify(mRequestObserver, never()).onFullScreenMagnificationChanged(eq(displayId),
                any(Region.class), any(MagnificationConfig.class));
        verify(mAnimationCallback).onResult(eq(true), any());
    }

    @Test
    public void testReset_Magnifying_resetsMagnificationAndInvokeCallbacks() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            reset_Magnifying_resetsMagnificationAndInvokeCallbacks(i);
        }
    }

    private void reset_Magnifying_resetsMagnificationAndInvokeCallbacks(int displayId) {
        register(displayId);
        float scale = 2.5f;
        PointF firstCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        assertTrue(mFullScreenMagnificationController.setScaleAndCenter(displayId,
                scale, firstCenter.x, firstCenter.y, mAnimationCallback, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();
        Mockito.reset(mMockValueAnimator);
        // Stubs the logic after the animation is started.
        doAnswer(invocation -> {
            mStateListener.onAnimationCancel(mMockValueAnimator);
            mStateListener.onAnimationEnd(mMockValueAnimator);
            return null;
        }).when(mMockValueAnimator).cancel();
        when(mMockValueAnimator.isRunning()).thenReturn(true);
        // Intermediate point
        float fraction = 0.33f;
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(fraction);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        MagnificationAnimationCallback lastAnimationCallback = Mockito.mock(
                MagnificationAnimationCallback.class);

        assertTrue(mFullScreenMagnificationController.reset(displayId, lastAnimationCallback));
        mMessageCapturingHandler.sendAllMessages();

        // Verify expected actions.
        verify(mAnimationCallback).onResult(eq(false), any());
        verify(mMockValueAnimator).start();
        verify(mMockValueAnimator).cancel();

        // Fast-forward the animation to the end.
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(1.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        mStateListener.onAnimationEnd(mMockValueAnimator);

        checkActivatedAndMagnifying(/* activated= */ false, /* magnifying= */ false, displayId);
        verify(lastAnimationCallback).onResult(eq(true), any());
    }

    @Test
    public void testTurnScreenOff_resetsMagnification() {
        register(DISPLAY_0);
        register(DISPLAY_1);
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext).registerReceiver(
                broadcastReceiverCaptor.capture(), any(IntentFilter.class));
        BroadcastReceiver br = broadcastReceiverCaptor.getValue();
        zoomIn2xToMiddle(DISPLAY_0);
        zoomIn2xToMiddle(DISPLAY_1);
        mMessageCapturingHandler.sendAllMessages();
        br.onReceive(mMockContext, null);
        mMessageCapturingHandler.sendAllMessages();
        checkActivatedAndMagnifying(/* activated= */ false, /* magnifying= */ false, DISPLAY_0);
        checkActivatedAndMagnifying(/* activated= */ false, /* magnifying= */ false, DISPLAY_1);

        // Twice for each display: once on init before it's activated and once for screen off
        verify(mMockThumbnail, times(4)).hideThumbnail();
    }

    @Test
    public void testUserContextChange_magnifierActivated_resetMagnification() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            contextChange_expectedValues(
                    /* displayId= */ i,
                    /* isMagnifierActivated= */ true,
                    /* isAlwaysOnEnabled= */ false,
                    /* expectedActivated= */ false);
            resetMockWindowManager();
        }
    }

    @Test
    public void testUserContextChange_magnifierActivatedAndAlwaysOnEnabled_stayActivated() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            contextChange_expectedValues(
                    /* displayId= */ i,
                    /* isMagnifierActivated= */ true,
                    /* isAlwaysOnEnabled= */ true,
                    /* expectedActivated= */ true);
            resetMockWindowManager();
        }
    }

    @Test
    public void testUserContextChange_magnifierDeactivated_stayDeactivated() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            contextChange_expectedValues(
                    /* displayId= */ i,
                    /* isMagnifierActivated= */ false,
                    /* isAlwaysOnEnabled= */ false,
                    /* expectedActivated= */ false);
            resetMockWindowManager();
        }
    }

    @Test
    public void testUserContextChange_magnifierDeactivatedAndAlwaysOnEnabled_stayDeactivated() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            contextChange_expectedValues(
                    /* displayId= */ i,
                    /* isMagnifierActivated= */ false,
                    /* isAlwaysOnEnabled= */ true,
                    /* expectedActivated= */ false);
            resetMockWindowManager();
        }
    }

    private void contextChange_expectedValues(
            int displayId, boolean isMagnifierActivated, boolean isAlwaysOnEnabled,
            boolean expectedActivated) {
        mFullScreenMagnificationController.setAlwaysOnMagnificationEnabled(isAlwaysOnEnabled);
        register(displayId);
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        if (isMagnifierActivated) {
            zoomIn2xToMiddle(displayId);
            mMessageCapturingHandler.sendAllMessages();
        }
        callbacks.onUserContextChanged();
        mMessageCapturingHandler.sendAllMessages();
        checkActivatedAndMagnifying(
                /* activated= */ expectedActivated, /* magnifying= */ false, displayId);

        if (expectedActivated) {
            verify(mMockThumbnail, times(2)).setThumbnailBounds(
                    /* currentBounds= */ any(),
                    /* scale= */ anyFloat(),
                    /* centerX= */ anyFloat(),
                    /* centerY= */ anyFloat()
            );
        }
    }

    @Test
    public void testDisplaySizeChanged_resetsMagnification() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            changeDisplaySize_resetsMagnification(i);
            resetMockWindowManager();
        }
    }

    private void changeDisplaySize_resetsMagnification(int displayId) {
        register(displayId);
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        zoomIn2xToMiddle(displayId);
        mMessageCapturingHandler.sendAllMessages();
        checkActivatedAndMagnifying(/* activated= */ true, /* magnifying= */ true, displayId);
        callbacks.onDisplaySizeChanged();
        mMessageCapturingHandler.sendAllMessages();
        checkActivatedAndMagnifying(/* activated= */ false, /* magnifying= */ false, DISPLAY_0);
    }

    @Test
    public void testBoundsChange_whileMagnifyingWithCompatibleSpec_noSpecChange() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            boundsChange_whileMagnifyingWithCompatibleSpec_noSpecChange(i);
            resetMockWindowManager();
        }
    }

    private void boundsChange_whileMagnifyingWithCompatibleSpec_noSpecChange(int displayId) {
        // Going from a small region to a large one leads to no issues
        register(displayId);
        zoomIn2xToMiddle(displayId);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        Mockito.reset(mMockWindowManager);
        callbacks.onMagnificationRegionChanged(OTHER_REGION_COMPAT);
        mMessageCapturingHandler.sendAllMessages();
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(startSpec));
        verifyNoMoreInteractions(mMockWindowManager);
    }

    @Test
    public void testBoundsChange_whileZoomingWithCompatibleSpec_noSpecChange() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            boundsChange_whileZoomingWithCompatibleSpec_noSpecChange(i);
            resetMockWindowManager();
        }
    }

    private void boundsChange_whileZoomingWithCompatibleSpec_noSpecChange(int displayId) {
        register(displayId);
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        float scale = 2.0f;
        // setting animate parameter to true is differ from zoomIn2xToMiddle()
        mFullScreenMagnificationController.setScale(displayId, scale, startCenter.x, startCenter.y,
                true, SERVICE_ID_1);
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        Mockito.reset(mMockWindowManager);
        callbacks.onMagnificationRegionChanged(OTHER_REGION_COMPAT);
        mMessageCapturingHandler.sendAllMessages();
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(startSpec));
        verifyNoMoreInteractions(mMockWindowManager);

        verify(mMockThumbnail)
                .updateThumbnail(eq(scale), eq(startCenter.x), eq(startCenter.y));
    }

    @Test
    public void testBoundsChange_whileMagnifyingWithIncompatibleSpec_offsetsConstrained() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            boundsChange_whileMagnifyingWithIncompatibleSpec_offsetsConstrained(i);
            resetMockWindowManager();
        }
    }

    private void boundsChange_whileMagnifyingWithIncompatibleSpec_offsetsConstrained(
            int displayId) {
        // In a large region, pan to the farthest point possible
        register(displayId);
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        callbacks.onMagnificationRegionChanged(OTHER_REGION);
        mMessageCapturingHandler.sendAllMessages();
        PointF startCenter = OTHER_BOUNDS_LOWER_RIGHT_2X_CENTER;
        float scale = 2.0f;
        mFullScreenMagnificationController.setScale(displayId, scale, startCenter.x, startCenter.y,
                false, SERVICE_ID_1);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId), argThat(closeTo(startSpec)));
        Mockito.reset(mMockWindowManager);

        callbacks.onMagnificationRegionChanged(INITIAL_MAGNIFICATION_REGION);
        mMessageCapturingHandler.sendAllMessages();

        MagnificationSpec endSpec = getCurrentMagnificationSpec(displayId);
        assertThat(endSpec, CoreMatchers.not(closeTo(startSpec)));
        PointF expectedOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS,
                INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER, scale);
        assertThat(endSpec, closeTo(getMagnificationSpec(scale, expectedOffsets)));
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId), argThat(closeTo(endSpec)));

        verify(mMockThumbnail, atLeastOnce()).setThumbnailBounds(
                /* currentBounds= */ any(),
                eq(scale),
                /* centerX= */ anyFloat(),
                /* centerY= */ anyFloat()
        );
    }

    @Test
    public void testBoundsChange_whileZoomingWithIncompatibleSpec_jumpsToCompatibleSpec() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            boundsChange_whileZoomingWithIncompatibleSpec_jumpsToCompatibleSpec(i);
            resetMockWindowManager();
        }
    }

    private void boundsChange_whileZoomingWithIncompatibleSpec_jumpsToCompatibleSpec(
            int displayId) {
        register(displayId);
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        callbacks.onMagnificationRegionChanged(OTHER_REGION);
        mMessageCapturingHandler.sendAllMessages();
        PointF startCenter = OTHER_BOUNDS_LOWER_RIGHT_2X_CENTER;
        float scale = 2.0f;
        mFullScreenMagnificationController.setScale(displayId, scale, startCenter.x, startCenter.y,
                true, SERVICE_ID_1);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        when(mMockValueAnimator.isRunning()).thenReturn(true);

        callbacks.onMagnificationRegionChanged(INITIAL_MAGNIFICATION_REGION);
        mMessageCapturingHandler.sendAllMessages();
        verify(mMockValueAnimator).cancel();

        MagnificationSpec endSpec = getCurrentMagnificationSpec(displayId);
        assertThat(endSpec, CoreMatchers.not(closeTo(startSpec)));
        PointF expectedOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS,
                INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER, scale);
        assertThat(endSpec, closeTo(getMagnificationSpec(scale, expectedOffsets)));
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId), argThat(closeTo(endSpec)));

        verify(mMockThumbnail, atLeastOnce()).setThumbnailBounds(
                /* currentBounds= */ any(),
                eq(scale),
                /* centerX= */ anyFloat(),
                /* centerY= */ anyFloat()
        );
    }

    @Test
    public void testRequestRectOnScreen_rectAlreadyOnScreen_doesNothing() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            requestRectOnScreen_rectAlreadyOnScreen_doesNothing(i);
            resetMockWindowManager();
        }
    }

    private void requestRectOnScreen_rectAlreadyOnScreen_doesNothing(int displayId) {
        register(displayId);
        zoomIn2xToMiddle(displayId);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        Mockito.reset(mMockWindowManager);
        int centerX = (int) INITIAL_MAGNIFICATION_BOUNDS_CENTER.x;
        int centerY = (int) INITIAL_MAGNIFICATION_BOUNDS_CENTER.y;
        callbacks.onRectangleOnScreenRequested(centerX - 1, centerY - 1, centerX + 1, centerY - 1);
        mMessageCapturingHandler.sendAllMessages();
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(startSpec));
        verifyNoMoreInteractions(mMockWindowManager);
    }

    @Test
    public void requestRectOnScreen_disabledByPrefSetting_doesNothing() {
        register(DISPLAY_0);
        zoomIn2xToMiddle(DISPLAY_0);
        Mockito.reset(mMockWindowManager);
        MagnificationSpec startSpec = getCurrentMagnificationSpec(DISPLAY_0);
        MagnificationSpec expectedEndSpec = getMagnificationSpec(2.0f, 0, 0);
        mFullScreenMagnificationController.setMagnificationFollowTypingEnabled(false);

        mFullScreenMagnificationController.onRectangleOnScreenRequested(DISPLAY_0, 0, 0, 1, 1);

        assertThat(getCurrentMagnificationSpec(DISPLAY_0), closeTo(startSpec));
        verify(mMockWindowManager, never()).setMagnificationSpec(eq(DISPLAY_0),
                argThat(closeTo(expectedEndSpec)));
    }

    @Test
    public void testRequestRectOnScreen_rectCanFitOnScreen_pansToGetRectOnScreen() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            requestRectOnScreen_rectCanFitOnScreen_pansToGetRectOnScreen(i);
            resetMockWindowManager();
        }
    }

    private void requestRectOnScreen_rectCanFitOnScreen_pansToGetRectOnScreen(int displayId) {
        register(displayId);
        zoomIn2xToMiddle(displayId);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        Mockito.reset(mMockWindowManager);
        callbacks.onRectangleOnScreenRequested(0, 0, 1, 1);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationSpec expectedEndSpec = getMagnificationSpec(2.0f, 0, 0);
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(expectedEndSpec));
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(expectedEndSpec)));
    }

    @Test
    public void testRequestRectOnScreen_garbageInput_doesNothing() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            requestRectOnScreen_garbageInput_doesNothing(i);
            resetMockWindowManager();
        }
    }

    private void requestRectOnScreen_garbageInput_doesNothing(int displayId) {
        register(displayId);
        zoomIn2xToMiddle(displayId);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        Mockito.reset(mMockWindowManager);
        callbacks.onRectangleOnScreenRequested(0, 0, -50, -50);
        mMessageCapturingHandler.sendAllMessages();
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(startSpec));
        verifyNoMoreInteractions(mMockWindowManager);
    }

    @Test
    public void testRequestRectOnScreen_rectTooWide_pansToGetStartOnScreenBasedOnLocale() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            requestRectOnScreen_rectTooWide_pansToGetStartOnScreenBasedOnLocale(i);
            resetMockWindowManager();
        }
    }

    private void requestRectOnScreen_rectTooWide_pansToGetStartOnScreenBasedOnLocale(
            int displayId) {
        Locale.setDefault(new Locale("en", "us"));
        register(displayId);
        zoomIn2xToMiddle(displayId);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        Mockito.reset(mMockWindowManager);
        Rect wideRect = new Rect(0, 50, 100, 51);
        callbacks.onRectangleOnScreenRequested(
                wideRect.left, wideRect.top, wideRect.right, wideRect.bottom);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationSpec expectedEndSpec = getMagnificationSpec(2.0f, 0, startSpec.offsetY);
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(expectedEndSpec));
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(expectedEndSpec)));
        Mockito.reset(mMockWindowManager);

        // Repeat with RTL
        Locale.setDefault(new Locale("he", "il"));
        callbacks.onRectangleOnScreenRequested(
                wideRect.left, wideRect.top, wideRect.right, wideRect.bottom);
        mMessageCapturingHandler.sendAllMessages();
        expectedEndSpec = getMagnificationSpec(2.0f, -100, startSpec.offsetY);
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(expectedEndSpec));
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(expectedEndSpec)));
    }

    @Test
    public void testRequestRectOnScreen_rectTooTall_pansMinimumToGetTopOnScreen() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            requestRectOnScreen_rectTooTall_pansMinimumToGetTopOnScreen(i);
            resetMockWindowManager();
        }
    }

    private void requestRectOnScreen_rectTooTall_pansMinimumToGetTopOnScreen(int displayId) {
        register(displayId);
        zoomIn2xToMiddle(displayId);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationCallbacks callbacks = getMagnificationCallbacks(displayId);
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        Mockito.reset(mMockWindowManager);
        Rect tallRect = new Rect(50, 0, 51, 100);
        callbacks.onRectangleOnScreenRequested(
                tallRect.left, tallRect.top, tallRect.right, tallRect.bottom);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationSpec expectedEndSpec = getMagnificationSpec(2.0f, startSpec.offsetX, 0);
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(expectedEndSpec));
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(expectedEndSpec)));
    }

    @Test
    public void testChangeMagnification_duringAnimation_animatesToNewValue() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            changeMagnification_duringAnimation_animatesToNewValue(i);
            resetMockWindowManager();
        }
    }

    private void changeMagnification_duringAnimation_animatesToNewValue(int displayId) {
        register(displayId);
        MagnificationSpec startSpec = getCurrentMagnificationSpec(displayId);
        float scale = 2.5f;
        PointF firstCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        final MagnificationConfig config = buildConfig(scale, firstCenter.x, firstCenter.y);
        MagnificationSpec firstEndSpec = getMagnificationSpec(
                scale, computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, firstCenter, scale));

        assertTrue(mFullScreenMagnificationController.setScaleAndCenter(displayId,
                scale, firstCenter.x, firstCenter.y, true, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        assertEquals(firstCenter.x, mFullScreenMagnificationController.getCenterX(displayId), 0.5);
        assertEquals(firstCenter.y, mFullScreenMagnificationController.getCenterY(displayId), 0.5);
        assertThat(getCurrentMagnificationSpec(displayId), closeTo(firstEndSpec));
        verify(mMockValueAnimator, times(1)).start();

        // Initial value
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(0.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId), eq(startSpec));
        verify(mRequestObserver).onFullScreenMagnificationChanged(eq(displayId),
                eq(INITIAL_MAGNIFICATION_REGION), mConfigCaptor.capture());
        assertConfigEquals(config, mConfigCaptor.getValue());
        Mockito.reset(mMockWindowManager);

        // Intermediate point
        float fraction = 0.33f;
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(fraction);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        MagnificationSpec intermediateSpec1 =
                getInterpolatedMagSpec(startSpec, firstEndSpec, fraction);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(intermediateSpec1)));
        Mockito.reset(mMockWindowManager);

        PointF newCenter = INITIAL_BOUNDS_UPPER_LEFT_2X_CENTER;
        final MagnificationConfig newConfig = buildConfig(scale, newCenter.x, newCenter.y);
        MagnificationSpec newEndSpec = getMagnificationSpec(
                scale, computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale));
        assertTrue(mFullScreenMagnificationController.setCenter(displayId,
                newCenter.x, newCenter.y, true, SERVICE_ID_1));
        mMessageCapturingHandler.sendAllMessages();

        // Animation should have been restarted
        verify(mMockValueAnimator, times(2)).start();
        verify(mRequestObserver, times(2)).onFullScreenMagnificationChanged(eq(displayId),
                eq(INITIAL_MAGNIFICATION_REGION), mConfigCaptor.capture());
        assertConfigEquals(newConfig, mConfigCaptor.getValue());

        // New starting point should be where we left off
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(0.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(intermediateSpec1)));
        Mockito.reset(mMockWindowManager);

        // Second intermediate point
        fraction = 0.5f;
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(fraction);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(getInterpolatedMagSpec(intermediateSpec1, newEndSpec, fraction))));
        Mockito.reset(mMockWindowManager);

        // Final value should be the new center
        Mockito.reset(mMockWindowManager);
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(1.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(eq(displayId),
                argThat(closeTo(newEndSpec)));
    }

    @Test
    public void testZoomTo1x_shouldActivatedAndForceShowMagnifiableBounds() {
        register(DISPLAY_0);
        final float scale = 1.0f;
        mFullScreenMagnificationController.setScaleAndCenter(
                DISPLAY_0, scale, Float.NaN, Float.NaN, true, SERVICE_ID_1);

        checkActivatedAndMagnifying(/* activated= */ true, /* magnifying= */ false, DISPLAY_0);
        verify(mMockWindowManager).setFullscreenMagnificationActivated(DISPLAY_0, true);
    }

    @Test
    public void testSetScale_toMagnifying_shouldNotifyActivatedState() {
        setScaleToMagnifying();

        verify(mRequestObserver).onFullScreenMagnificationActivationState(eq(DISPLAY_0), eq(true));
    }

    @Test
    public void testReset_afterMagnifying_shouldNotifyDeactivatedState() {
        setScaleToMagnifying();

        mFullScreenMagnificationController.reset(DISPLAY_0, mAnimationCallback);
        verify(mRequestObserver).onFullScreenMagnificationActivationState(eq(DISPLAY_0), eq(false));
    }

    @Test
    public void testImeWindowIsShown_serviceNotified() {
        register(DISPLAY_0);
        MagnificationCallbacks callbacks = getMagnificationCallbacks(DISPLAY_0);
        callbacks.onImeWindowVisibilityChanged(true);
        mMessageCapturingHandler.sendAllMessages();
        verify(mRequestObserver).onImeWindowVisibilityChanged(eq(DISPLAY_0), eq(true));

        verify(mMockThumbnail, atLeastOnce()).setThumbnailBounds(
                /* currentBounds= */ any(),
                /* scale= */ anyFloat(),
                /* centerX= */ anyFloat(),
                /* centerY= */ anyFloat()
        );
    }

    @Test
    public void persistScale_setValueWhenScaleIsOne_nothingChanged() {
        final float persistedScale =
                mFullScreenMagnificationController.getPersistedScale(TEST_DISPLAY);

        PointF pivotPoint = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        mFullScreenMagnificationController.setScale(DISPLAY_0, 1.0f, pivotPoint.x, pivotPoint.y,
                false, SERVICE_ID_1);
        mFullScreenMagnificationController.persistScale(TEST_DISPLAY);

        Assert.assertEquals(mFullScreenMagnificationController.getPersistedScale(TEST_DISPLAY),
                persistedScale);
    }

    @Test
    public void testOnContextChanged_alwaysOnFeatureDisabled_resetMagnification() {
        setScaleToMagnifying();

        mFullScreenMagnificationController.setAlwaysOnMagnificationEnabled(false);
        mFullScreenMagnificationController.onUserContextChanged(DISPLAY_0);

        // the magnifier should be deactivated.
        verify(mRequestObserver).onFullScreenMagnificationActivationState(eq(DISPLAY_0), eq(false));
        assertFalse(mFullScreenMagnificationController.isZoomedOutFromService(DISPLAY_0));

        verify(mMockThumbnail).setThumbnailBounds(
                /* currentBounds= */ any(),
                /* scale= */ anyFloat(),
                /* centerX= */ anyFloat(),
                /* centerY= */ anyFloat()
        );

        // Once on init before it's activated and once for reset
        verify(mMockThumbnail, times(2)).hideThumbnail();
    }

    @Test
    public void testOnContextChanged_alwaysOnFeatureEnabled_setScaleTo1xAndStayActivated() {
        setScaleToMagnifying();

        mFullScreenMagnificationController.setAlwaysOnMagnificationEnabled(true);
        mFullScreenMagnificationController.onUserContextChanged(DISPLAY_0);

        // the magnifier should be zoomed out and keep activated by service action.
        assertEquals(1.0f, mFullScreenMagnificationController.getScale(DISPLAY_0), 0);
        assertTrue(mFullScreenMagnificationController.isActivated(DISPLAY_0));
        assertTrue(mFullScreenMagnificationController.isZoomedOutFromService(DISPLAY_0));

        verify(mMockThumbnail).setThumbnailBounds(
                /* currentBounds= */ any(),
                /* scale= */ anyFloat(),
                /* centerX= */ anyFloat(),
                /* centerY= */ anyFloat()
        );
    }

    private void setScaleToMagnifying() {
        register(DISPLAY_0);
        float scale = 2.0f;
        PointF pivotPoint = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;

        mFullScreenMagnificationController.setScale(DISPLAY_0, scale, pivotPoint.x, pivotPoint.y,
                false, SERVICE_ID_1);
    }

    private void initMockWindowManager() {
        for (int i = 0; i < DISPLAY_COUNT; i++) {
            when(mMockWindowManager.setMagnificationCallbacks(eq(i), any())).thenReturn(true);
        }
        doAnswer((Answer<Void>) invocationOnMock -> {
            Object[] args = invocationOnMock.getArguments();
            Region regionArg = (Region) args[1];
            regionArg.set(INITIAL_MAGNIFICATION_REGION);
            return null;
        }).when(mMockWindowManager).getMagnificationRegion(anyInt(), any(Region.class));
    }

    private void resetMockWindowManager() {
        Mockito.reset(mMockWindowManager);
        Mockito.reset(mMockThumbnail);
        Mockito.reset(mMockScroller);
        Mockito.reset(mMockTimeAnimator);
        initMockWindowManager();
    }

    private void register(int displayId) {
        mMockValueAnimator = mock(ValueAnimator.class);
        when(mMockControllerCtx.newValueAnimator()).thenReturn(mMockValueAnimator);
        mFullScreenMagnificationController.register(displayId);
        ArgumentCaptor<ValueAnimator.AnimatorUpdateListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(ValueAnimator.AnimatorUpdateListener.class);
        verify(mMockValueAnimator).addUpdateListener(listenerArgumentCaptor.capture());
        mTargetAnimationListener = listenerArgumentCaptor.getValue();
        ArgumentCaptor<ValueAnimator.AnimatorListener> animatorListenerArgumentCaptor =
                ArgumentCaptor.forClass(ValueAnimator.AnimatorListener.class);
        verify(mMockValueAnimator).addListener(animatorListenerArgumentCaptor.capture());
        mStateListener = animatorListenerArgumentCaptor.getValue();
        Mockito.reset(mMockValueAnimator); // Ignore other initialization
        Mockito.reset(mAnimationCallback);
    }

    private void zoomIn2xToMiddle(int displayId) {
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        float scale = 2.0f;
        mFullScreenMagnificationController.setScale(displayId, scale, startCenter.x, startCenter.y,
                false, SERVICE_ID_1);
        checkActivatedAndMagnifying(/* activated= */ true, /* magnifying= */ true, displayId);
    }

    private void checkActivatedAndMagnifying(boolean activated, boolean magnifying, int displayId) {
        final boolean isActivated = mFullScreenMagnificationController.isActivated(displayId);
        final boolean isMagnifying = mFullScreenMagnificationController.getScale(displayId) > 1.0f;
        assertTrue(isActivated == activated);
        assertTrue(isMagnifying == magnifying);
    }

    private MagnificationCallbacks getMagnificationCallbacks(int displayId) {
        ArgumentCaptor<MagnificationCallbacks> magnificationCallbacksCaptor =
                ArgumentCaptor.forClass(MagnificationCallbacks.class);
        verify(mMockWindowManager)
                .setMagnificationCallbacks(eq(displayId), magnificationCallbacksCaptor.capture());
        return magnificationCallbacksCaptor.getValue();
    }

    private PointF computeOffsets(Rect magnifiedBounds, PointF center, float scale) {
        return new PointF(
                magnifiedBounds.centerX() - scale * center.x,
                magnifiedBounds.centerY() - scale * center.y);
    }

    private MagnificationConfig buildConfig(float scale, float centerX, float centerY) {
        return new MagnificationConfig.Builder().setMode(
                MAGNIFICATION_MODE_FULLSCREEN).setScale(scale).setCenterX(centerX).setCenterY(
                centerY).build();
    }

    private void assertConfigEquals(MagnificationConfig expected, MagnificationConfig result) {
        assertEquals(expected.getMode(), result.getMode());
        assertEquals(expected.getScale(), result.getScale(), 0f);
        assertEquals(expected.getCenterX(), result.getCenterX(), 0f);
        assertEquals(expected.getCenterY(), result.getCenterY(), 0f);
    }

    private MagnificationSpec getInterpolatedMagSpec(MagnificationSpec start, MagnificationSpec end,
            float fraction) {
        MagnificationSpec interpolatedSpec = new MagnificationSpec();
        interpolatedSpec.scale = start.scale + fraction * (end.scale - start.scale);
        interpolatedSpec.offsetX = start.offsetX + fraction * (end.offsetX - start.offsetX);
        interpolatedSpec.offsetY = start.offsetY + fraction * (end.offsetY - start.offsetY);
        return interpolatedSpec;
    }

    private MagnificationSpec getMagnificationSpec(float scale, PointF offsets) {
        return getMagnificationSpec(scale, offsets.x, offsets.y);
    }

    private MagnificationSpec getMagnificationSpec(float scale, float offsetX, float offsetY) {
        MagnificationSpec spec = new MagnificationSpec();
        spec.scale = scale;
        spec.offsetX = offsetX;
        spec.offsetY = offsetY;
        return spec;
    }

    private MagnificationSpec getCurrentMagnificationSpec(int displayId) {
        return getMagnificationSpec(mFullScreenMagnificationController.getScale(displayId),
                mFullScreenMagnificationController.getOffsetX(displayId),
                mFullScreenMagnificationController.getOffsetY(displayId));
    }

    private MagSpecMatcher closeTo(MagnificationSpec spec) {
        return new MagSpecMatcher(spec, 0.01f, 0.5f);
    }

    private class MagSpecMatcher extends TypeSafeMatcher<MagnificationSpec> {
        final MagnificationSpec mMagSpec;
        final float mScaleTolerance;
        final float mOffsetTolerance;

        MagSpecMatcher(MagnificationSpec spec, float scaleTolerance, float offsetTolerance) {
            mMagSpec = spec;
            mScaleTolerance = scaleTolerance;
            mOffsetTolerance = offsetTolerance;
        }

        @Override
        protected boolean matchesSafely(MagnificationSpec magnificationSpec) {
            if (Math.abs(mMagSpec.scale - magnificationSpec.scale) > mScaleTolerance) {
                return false;
            }
            if (Math.abs(mMagSpec.offsetX - magnificationSpec.offsetX) > mOffsetTolerance) {
                return false;
            }
            if (Math.abs(mMagSpec.offsetY - magnificationSpec.offsetY) > mOffsetTolerance) {
                return false;
            }
            return true;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("Match spec: " + mMagSpec);
        }
    }
}
