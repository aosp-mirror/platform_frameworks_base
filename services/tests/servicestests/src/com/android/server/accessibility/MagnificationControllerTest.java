/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.accessibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.MagnificationSpec;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerInternal.MagnificationCallbacks;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class MagnificationControllerTest {
    static final Rect INITIAL_MAGNIFICATION_BOUNDS = new Rect(0, 0, 100, 200);
    static final PointF INITIAL_MAGNIFICATION_BOUNDS_CENTER = new PointF(
            INITIAL_MAGNIFICATION_BOUNDS.centerX(), INITIAL_MAGNIFICATION_BOUNDS.centerY());
    static final PointF INITIAL_BOUNDS_UPPER_LEFT_2X_CENTER = new PointF(25, 50);
    static final PointF INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER = new PointF(75, 150);
    static final Rect OTHER_MAGNIFICATION_BOUNDS = new Rect(100, 200, 500, 600);
    static final PointF OTHER_BOUNDS_LOWER_RIGHT_2X_CENTER = new PointF(400, 500);
    static final Region INITIAL_MAGNIFICATION_REGION = new Region(INITIAL_MAGNIFICATION_BOUNDS);
    static final Region OTHER_REGION = new Region(OTHER_MAGNIFICATION_BOUNDS);
    static final int SERVICE_ID_1 = 1;
    static final int SERVICE_ID_2 = 2;

    final Context mMockContext = mock(Context.class);
    final AccessibilityManagerService mMockAms = mock(AccessibilityManagerService.class);
    final WindowManagerInternal mMockWindowManager = mock(WindowManagerInternal.class);
    final MessageCapturingHandler mMessageCapturingHandler =
            new MessageCapturingHandler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            return mMagnificationController.handleMessage(msg);
        }
    });
    final ArgumentCaptor<MagnificationSpec> mMagnificationSpecCaptor =
            ArgumentCaptor.forClass(MagnificationSpec.class);
    final ValueAnimator mMockValueAnimator = mock(ValueAnimator.class);
    MagnificationController.SettingsBridge mMockSettingsBridge;


    MagnificationController mMagnificationController;
    ValueAnimator.AnimatorUpdateListener mTargetAnimationListener;

    @BeforeClass
    public static void oneTimeInitialization() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
    }

    @Before
    public void setUp() {
        when(mMockContext.getMainLooper()).thenReturn(Looper.myLooper());
        Resources mockResources = mock(Resources.class);
        when(mMockContext.getResources()).thenReturn(mockResources);
        when(mockResources.getInteger(R.integer.config_longAnimTime))
                .thenReturn(1000);
        mMockSettingsBridge = mock(MagnificationController.SettingsBridge.class);
        mMagnificationController = new MagnificationController(mMockContext, mMockAms, new Object(),
                mMessageCapturingHandler, mMockWindowManager, mMockValueAnimator,
                mMockSettingsBridge);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocationOnMock) throws Throwable {
                Object[] args = invocationOnMock.getArguments();
                Region regionArg = (Region) args[0];
                regionArg.set(INITIAL_MAGNIFICATION_REGION);
                return null;
            }
        }).when(mMockWindowManager).getMagnificationRegion((Region) anyObject());

        ArgumentCaptor<ValueAnimator.AnimatorUpdateListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(ValueAnimator.AnimatorUpdateListener.class);
        verify(mMockValueAnimator).addUpdateListener(listenerArgumentCaptor.capture());
        mTargetAnimationListener = listenerArgumentCaptor.getValue();
        Mockito.reset(mMockValueAnimator); // Ignore other initialization
    }

    @Test
    public void testRegister_WindowManagerAndContextRegisterListeners() {
        mMagnificationController.register();
        verify(mMockContext).registerReceiver(
                (BroadcastReceiver) anyObject(), (IntentFilter) anyObject());
        verify(mMockWindowManager).setMagnificationCallbacks((MagnificationCallbacks) anyObject());
        assertTrue(mMagnificationController.isRegisteredLocked());
    }

    @Test
    public void testRegister_WindowManagerAndContextUnregisterListeners() {
        mMagnificationController.register();
        mMagnificationController.unregister();

        verify(mMockContext).unregisterReceiver((BroadcastReceiver) anyObject());
        verify(mMockWindowManager).setMagnificationCallbacks(null);
        assertFalse(mMagnificationController.isRegisteredLocked());
    }

    @Test
    public void testInitialState_noMagnificationAndMagnificationRegionReadFromWindowManager() {
        mMagnificationController.register();
        MagnificationSpec expectedInitialSpec = getMagnificationSpec(1.0f, 0.0f, 0.0f);
        Region initialMagRegion = new Region();
        Rect initialBounds = new Rect();

        assertEquals(expectedInitialSpec, getCurrentMagnificationSpec());
        mMagnificationController.getMagnificationRegion(initialMagRegion);
        mMagnificationController.getMagnificationBounds(initialBounds);
        assertEquals(INITIAL_MAGNIFICATION_REGION, initialMagRegion);
        assertEquals(INITIAL_MAGNIFICATION_BOUNDS, initialBounds);
        assertEquals(INITIAL_MAGNIFICATION_BOUNDS.centerX(),
                mMagnificationController.getCenterX(), 0.0f);
        assertEquals(INITIAL_MAGNIFICATION_BOUNDS.centerY(),
                mMagnificationController.getCenterY(), 0.0f);
    }

    @Test
    public void testNotRegistered_publicMethodsShouldBeBenign() {
        assertFalse(mMagnificationController.isMagnifying());
        assertFalse(mMagnificationController.magnificationRegionContains(100, 100));
        assertFalse(mMagnificationController.reset(true));
        assertFalse(mMagnificationController.setScale(2, 100, 100, true, 0));
        assertFalse(mMagnificationController.setCenter(100, 100, false, 1));
        assertFalse(mMagnificationController.setScaleAndCenter(1.5f, 100, 100, false, 2));
        assertTrue(mMagnificationController.getIdOfLastServiceToMagnify() < 0);

        mMagnificationController.getMagnificationRegion(new Region());
        mMagnificationController.getMagnificationBounds(new Rect());
        mMagnificationController.getScale();
        mMagnificationController.getOffsetX();
        mMagnificationController.getOffsetY();
        mMagnificationController.getCenterX();
        mMagnificationController.getCenterY();
        mMagnificationController.offsetMagnifiedRegion(50, 50, 1);
        mMagnificationController.unregister();
    }

    @Test
    public void testSetScale_noAnimation_shouldGoStraightToWindowManagerAndUpdateState() {
        mMagnificationController.register();
        final float scale = 2.0f;
        final PointF center = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        final PointF offsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, center, scale);
        assertTrue(mMagnificationController
                .setScale(scale, center.x, center.y, false, SERVICE_ID_1));

        final MagnificationSpec expectedSpec = getMagnificationSpec(scale, offsets);
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(expectedSpec)));
        assertThat(getCurrentMagnificationSpec(), closeTo(expectedSpec));
        assertEquals(center.x, mMagnificationController.getCenterX(), 0.0);
        assertEquals(center.y, mMagnificationController.getCenterY(), 0.0);
        verify(mMockValueAnimator, times(0)).start();
    }

    @Test
    public void testSetScale_withPivotAndAnimation_stateChangesAndAnimationHappens() {
        mMagnificationController.register();
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        float scale = 2.0f;
        PointF pivotPoint = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        assertTrue(mMagnificationController
                .setScale(scale, pivotPoint.x, pivotPoint.y, true, SERVICE_ID_1));

        // New center should be halfway between original center and pivot
        PointF newCenter = new PointF((pivotPoint.x + INITIAL_MAGNIFICATION_BOUNDS.centerX()) / 2,
                (pivotPoint.y + INITIAL_MAGNIFICATION_BOUNDS.centerY()) / 2);
        PointF offsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        MagnificationSpec endSpec = getMagnificationSpec(scale, offsets);

        assertEquals(newCenter.x, mMagnificationController.getCenterX(), 0.5);
        assertEquals(newCenter.y, mMagnificationController.getCenterY(), 0.5);
        assertThat(getCurrentMagnificationSpec(), closeTo(endSpec));
        verify(mMockValueAnimator).start();

        // Initial value
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(0.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(startSpec);

        // Intermediate point
        Mockito.reset(mMockWindowManager);
        float fraction = 0.5f;
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(fraction);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(
                argThat(closeTo(getInterpolatedMagSpec(startSpec, endSpec, fraction))));

        // Final value
        Mockito.reset(mMockWindowManager);
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(1.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(endSpec)));
    }

    @Test
    public void testSetCenter_whileMagnifying_noAnimation_centerMoves() {
        mMagnificationController.register();
        // First zoom in
        float scale = 2.0f;
        assertTrue(mMagnificationController.setScale(scale,
                INITIAL_MAGNIFICATION_BOUNDS.centerX(), INITIAL_MAGNIFICATION_BOUNDS.centerY(),
                false, SERVICE_ID_1));
        Mockito.reset(mMockWindowManager);

        PointF newCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        assertTrue(mMagnificationController
                .setCenter(newCenter.x, newCenter.y, false, SERVICE_ID_1));
        PointF expectedOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        MagnificationSpec expectedSpec = getMagnificationSpec(scale, expectedOffsets);

        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(expectedSpec)));
        assertEquals(newCenter.x, mMagnificationController.getCenterX(), 0.0);
        assertEquals(newCenter.y, mMagnificationController.getCenterY(), 0.0);
        verify(mMockValueAnimator, times(0)).start();
    }

    @Test
    public void testSetScaleAndCenter_animated_stateChangesAndAnimationHappens() {
        mMagnificationController.register();
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        float scale = 2.5f;
        PointF newCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        PointF offsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        MagnificationSpec endSpec = getMagnificationSpec(scale, offsets);

        assertTrue(mMagnificationController.setScaleAndCenter(scale, newCenter.x, newCenter.y,
                true, SERVICE_ID_1));

        assertEquals(newCenter.x, mMagnificationController.getCenterX(), 0.5);
        assertEquals(newCenter.y, mMagnificationController.getCenterY(), 0.5);
        assertThat(getCurrentMagnificationSpec(), closeTo(endSpec));
        verify(mMockAms).notifyMagnificationChanged(
                INITIAL_MAGNIFICATION_REGION, scale, newCenter.x, newCenter.y);
        verify(mMockValueAnimator).start();

        // Initial value
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(0.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(startSpec);

        // Intermediate point
        Mockito.reset(mMockWindowManager);
        float fraction = 0.33f;
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(fraction);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(
                argThat(closeTo(getInterpolatedMagSpec(startSpec, endSpec, fraction))));

        // Final value
        Mockito.reset(mMockWindowManager);
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(1.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(endSpec)));
    }

    @Test
    public void testSetScaleAndCenter_scaleOutOfBounds_cappedAtLimits() {
        mMagnificationController.register();
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        PointF newCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        PointF offsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter,
                MagnificationController.MAX_SCALE);
        MagnificationSpec endSpec = getMagnificationSpec(
                MagnificationController.MAX_SCALE, offsets);

        assertTrue(mMagnificationController.setScaleAndCenter(
                MagnificationController.MAX_SCALE + 1.0f,
                newCenter.x, newCenter.y, false, SERVICE_ID_1));

        assertEquals(newCenter.x, mMagnificationController.getCenterX(), 0.5);
        assertEquals(newCenter.y, mMagnificationController.getCenterY(), 0.5);
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(endSpec)));
        Mockito.reset(mMockWindowManager);

        // Verify that we can't zoom below 1x
        assertTrue(mMagnificationController.setScaleAndCenter(0.5f,
                INITIAL_MAGNIFICATION_BOUNDS_CENTER.x, INITIAL_MAGNIFICATION_BOUNDS_CENTER.y,
                false, SERVICE_ID_1));

        assertEquals(INITIAL_MAGNIFICATION_BOUNDS_CENTER.x,
                mMagnificationController.getCenterX(), 0.5);
        assertEquals(INITIAL_MAGNIFICATION_BOUNDS_CENTER.y,
                mMagnificationController.getCenterY(), 0.5);
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(startSpec)));
    }

    @Test
    public void testSetScaleAndCenter_centerOutOfBounds_cappedAtLimits() {
        mMagnificationController.register();
        float scale = 2.0f;

        // Off the edge to the top and left
        assertTrue(mMagnificationController.setScaleAndCenter(
                scale, -100f, -200f, false, SERVICE_ID_1));

        PointF newCenter = INITIAL_BOUNDS_UPPER_LEFT_2X_CENTER;
        PointF newOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        assertEquals(newCenter.x, mMagnificationController.getCenterX(), 0.5);
        assertEquals(newCenter.y, mMagnificationController.getCenterY(), 0.5);
        verify(mMockWindowManager).setMagnificationSpec(
                argThat(closeTo(getMagnificationSpec(scale, newOffsets))));
        Mockito.reset(mMockWindowManager);

        // Off the edge to the bottom and right
        assertTrue(mMagnificationController.setScaleAndCenter(scale,
                INITIAL_MAGNIFICATION_BOUNDS.right + 1, INITIAL_MAGNIFICATION_BOUNDS.bottom + 1,
                false, SERVICE_ID_1));
        newCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        newOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        assertEquals(newCenter.x, mMagnificationController.getCenterX(), 0.5);
        assertEquals(newCenter.y, mMagnificationController.getCenterY(), 0.5);
        verify(mMockWindowManager).setMagnificationSpec(
                argThat(closeTo(getMagnificationSpec(scale, newOffsets))));
    }

    @Test
    public void testMagnificationRegionChanged_serviceNotified() {
        mMagnificationController.register();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        callbacks.onMagnificationRegionChanged(OTHER_REGION);
        mMessageCapturingHandler.sendAllMessages();
        verify(mMockAms).notifyMagnificationChanged(OTHER_REGION, 1.0f,
                OTHER_MAGNIFICATION_BOUNDS.centerX(), OTHER_MAGNIFICATION_BOUNDS.centerY());
    }

    @Test
    public void testOffsetMagnifiedRegion_whileMagnifying_offsetsMove() {
        mMagnificationController.register();
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        float scale = 2.0f;
        PointF startOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, startCenter, scale);
        // First zoom in
        assertTrue(mMagnificationController
                .setScaleAndCenter(scale, startCenter.x, startCenter.y, false, SERVICE_ID_1));
        Mockito.reset(mMockWindowManager);

        PointF newCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        PointF newOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale);
        mMagnificationController.offsetMagnifiedRegion(
                startOffsets.x - newOffsets.x, startOffsets.y - newOffsets.y, SERVICE_ID_1);

        MagnificationSpec expectedSpec = getMagnificationSpec(scale, newOffsets);
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(expectedSpec)));
        assertEquals(newCenter.x, mMagnificationController.getCenterX(), 0.0);
        assertEquals(newCenter.y, mMagnificationController.getCenterY(), 0.0);
        verify(mMockValueAnimator, times(0)).start();
    }

    @Test
    public void testOffsetMagnifiedRegion_whileNotMagnifying_hasNoEffect() {
        mMagnificationController.register();
        Mockito.reset(mMockWindowManager);
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        mMagnificationController.offsetMagnifiedRegion(10, 10, SERVICE_ID_1);
        assertThat(getCurrentMagnificationSpec(), closeTo(startSpec));
        mMagnificationController.offsetMagnifiedRegion(-10, -10, SERVICE_ID_1);
        assertThat(getCurrentMagnificationSpec(), closeTo(startSpec));
        verifyNoMoreInteractions(mMockWindowManager);
    }

    @Test
    public void testOffsetMagnifiedRegion_whileMagnifyingButAtEdge_hasNoEffect() {
        mMagnificationController.register();
        float scale = 2.0f;

        // Upper left edges
        PointF ulCenter = INITIAL_BOUNDS_UPPER_LEFT_2X_CENTER;
        assertTrue(mMagnificationController
                .setScaleAndCenter(scale, ulCenter.x, ulCenter.y, false, SERVICE_ID_1));
        Mockito.reset(mMockWindowManager);
        MagnificationSpec ulSpec = getCurrentMagnificationSpec();
        mMagnificationController.offsetMagnifiedRegion(-10, -10, SERVICE_ID_1);
        assertThat(getCurrentMagnificationSpec(), closeTo(ulSpec));
        verifyNoMoreInteractions(mMockWindowManager);

        // Lower right edges
        PointF lrCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        assertTrue(mMagnificationController
                .setScaleAndCenter(scale, lrCenter.x, lrCenter.y, false, SERVICE_ID_1));
        Mockito.reset(mMockWindowManager);
        MagnificationSpec lrSpec = getCurrentMagnificationSpec();
        mMagnificationController.offsetMagnifiedRegion(10, 10, SERVICE_ID_1);
        assertThat(getCurrentMagnificationSpec(), closeTo(lrSpec));
        verifyNoMoreInteractions(mMockWindowManager);
    }

    @Test
    public void testGetIdOfLastServiceToChange_returnsCorrectValue() {
        mMagnificationController.register();
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        assertTrue(mMagnificationController
                .setScale(2.0f, startCenter.x, startCenter.y, false, SERVICE_ID_1));
        assertEquals(SERVICE_ID_1, mMagnificationController.getIdOfLastServiceToMagnify());
        assertTrue(mMagnificationController
                .setScale(1.5f, startCenter.x, startCenter.y, false, SERVICE_ID_2));
        assertEquals(SERVICE_ID_2, mMagnificationController.getIdOfLastServiceToMagnify());
    }

    @Test
    public void testSetUserId_resetsOnlyIfIdChanges() {
        final int userId1 = 1;
        final int userId2 = 2;

        mMagnificationController.register();
        mMagnificationController.setUserId(userId1);
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        float scale = 2.0f;
        mMagnificationController.setScale(scale, startCenter.x, startCenter.y, false, SERVICE_ID_1);

        mMagnificationController.setUserId(userId1);
        assertTrue(mMagnificationController.isMagnifying());
        mMagnificationController.setUserId(userId2);
        assertFalse(mMagnificationController.isMagnifying());
    }

    @Test
    public void testResetIfNeeded_doesWhatItSays() {
        mMagnificationController.register();
        zoomIn2xToMiddle();
        reset(mMockAms);
        assertTrue(mMagnificationController.resetIfNeeded(false));
        verify(mMockAms).notifyMagnificationChanged(
                eq(INITIAL_MAGNIFICATION_REGION), eq(1.0f), anyFloat(), anyFloat());
        assertFalse(mMagnificationController.isMagnifying());
        assertFalse(mMagnificationController.resetIfNeeded(false));
    }

    @Test
    public void testTurnScreenOff_resetsMagnification() {
        mMagnificationController.register();
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockContext).registerReceiver(
                broadcastReceiverCaptor.capture(), (IntentFilter) anyObject());
        BroadcastReceiver br = broadcastReceiverCaptor.getValue();
        zoomIn2xToMiddle();
        br.onReceive(mMockContext, null);
        mMessageCapturingHandler.sendAllMessages();
        assertFalse(mMagnificationController.isMagnifying());
    }

    @Test
    public void testUserContextChange_resetsMagnification() {
        mMagnificationController.register();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        zoomIn2xToMiddle();
        callbacks.onUserContextChanged();
        mMessageCapturingHandler.sendAllMessages();
        assertFalse(mMagnificationController.isMagnifying());
    }

    @Test
    public void testRotation_resetsMagnification() {
        mMagnificationController.register();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        zoomIn2xToMiddle();
        mMessageCapturingHandler.sendAllMessages();
        assertTrue(mMagnificationController.isMagnifying());
        callbacks.onRotationChanged(0);
        mMessageCapturingHandler.sendAllMessages();
        assertFalse(mMagnificationController.isMagnifying());
    }

    @Test
    public void testBoundsChange_whileMagnifyingWithCompatibleSpec_noSpecChange() {
        // Going from a small region to a large one leads to no issues
        mMagnificationController.register();
        zoomIn2xToMiddle();
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        Mockito.reset(mMockWindowManager);
        callbacks.onMagnificationRegionChanged(OTHER_REGION);
        mMessageCapturingHandler.sendAllMessages();
        assertThat(getCurrentMagnificationSpec(), closeTo(startSpec));
        verifyNoMoreInteractions(mMockWindowManager);
    }

    @Test
    public void testBoundsChange_whileZoomingWithCompatibleSpec_noSpecChange() {
        mMagnificationController.register();
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        float scale = 2.0f;
        mMagnificationController.setScale(scale, startCenter.x, startCenter.y, true, SERVICE_ID_1);
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        Mockito.reset(mMockWindowManager);
        callbacks.onMagnificationRegionChanged(OTHER_REGION);
        mMessageCapturingHandler.sendAllMessages();
        assertThat(getCurrentMagnificationSpec(), closeTo(startSpec));
        verifyNoMoreInteractions(mMockWindowManager);
    }

    @Test
    public void testBoundsChange_whileMagnifyingWithIncompatibleSpec_offsetsConstrained() {
        // In a large region, pan to the farthest point possible
        mMagnificationController.register();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        callbacks.onMagnificationRegionChanged(OTHER_REGION);
        mMessageCapturingHandler.sendAllMessages();
        PointF startCenter = OTHER_BOUNDS_LOWER_RIGHT_2X_CENTER;
        float scale = 2.0f;
        mMagnificationController.setScale(scale, startCenter.x, startCenter.y, false, SERVICE_ID_1);
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(startSpec)));
        Mockito.reset(mMockWindowManager);

        callbacks.onMagnificationRegionChanged(INITIAL_MAGNIFICATION_REGION);
        mMessageCapturingHandler.sendAllMessages();

        MagnificationSpec endSpec = getCurrentMagnificationSpec();
        assertThat(endSpec, CoreMatchers.not(closeTo(startSpec)));
        PointF expectedOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS,
                INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER, scale);
        assertThat(endSpec, closeTo(getMagnificationSpec(scale, expectedOffsets)));
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(endSpec)));
    }

    @Test
    public void testBoundsChange_whileZoomingWithIncompatibleSpec_jumpsToCompatibleSpec() {
        mMagnificationController.register();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        callbacks.onMagnificationRegionChanged(OTHER_REGION);
        mMessageCapturingHandler.sendAllMessages();
        PointF startCenter = OTHER_BOUNDS_LOWER_RIGHT_2X_CENTER;
        float scale = 2.0f;
        mMagnificationController.setScale(scale, startCenter.x, startCenter.y, true, SERVICE_ID_1);
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        when (mMockValueAnimator.isRunning()).thenReturn(true);

        callbacks.onMagnificationRegionChanged(INITIAL_MAGNIFICATION_REGION);
        mMessageCapturingHandler.sendAllMessages();
        verify(mMockValueAnimator).cancel();

        MagnificationSpec endSpec = getCurrentMagnificationSpec();
        assertThat(endSpec, CoreMatchers.not(closeTo(startSpec)));
        PointF expectedOffsets = computeOffsets(INITIAL_MAGNIFICATION_BOUNDS,
                INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER, scale);
        assertThat(endSpec, closeTo(getMagnificationSpec(scale, expectedOffsets)));
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(endSpec)));
    }

    @Test
    public void testRequestRectOnScreen_rectAlreadyOnScreen_doesNothing() {
        mMagnificationController.register();
        zoomIn2xToMiddle();
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        Mockito.reset(mMockWindowManager);
        int centerX = (int) INITIAL_MAGNIFICATION_BOUNDS_CENTER.x;
        int centerY = (int) INITIAL_MAGNIFICATION_BOUNDS_CENTER.y;
        callbacks.onRectangleOnScreenRequested(centerX - 1, centerY - 1, centerX + 1, centerY - 1);
        mMessageCapturingHandler.sendAllMessages();
        assertThat(getCurrentMagnificationSpec(), closeTo(startSpec));
        verifyNoMoreInteractions(mMockWindowManager);
    }

    @Test
    public void testRequestRectOnScreen_rectCanFitOnScreen_pansToGetRectOnScreen() {
        mMagnificationController.register();
        zoomIn2xToMiddle();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        Mockito.reset(mMockWindowManager);
        callbacks.onRectangleOnScreenRequested(0, 0, 1, 1);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationSpec expectedEndSpec = getMagnificationSpec(2.0f, 0, 0);
        assertThat(getCurrentMagnificationSpec(), closeTo(expectedEndSpec));
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(expectedEndSpec)));
    }

    @Test
    public void testRequestRectOnScreen_garbageInput_doesNothing() {
        mMagnificationController.register();
        zoomIn2xToMiddle();
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        Mockito.reset(mMockWindowManager);
        callbacks.onRectangleOnScreenRequested(0, 0, -50, -50);
        mMessageCapturingHandler.sendAllMessages();
        assertThat(getCurrentMagnificationSpec(), closeTo(startSpec));
        verifyNoMoreInteractions(mMockWindowManager);
    }


    @Test
    public void testRequestRectOnScreen_rectTooWide_pansToGetStartOnScreenBasedOnLocale() {
        Locale.setDefault(new Locale("en", "us"));
        mMagnificationController.register();
        zoomIn2xToMiddle();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        Mockito.reset(mMockWindowManager);
        Rect wideRect = new Rect(0, 50, 100, 51);
        callbacks.onRectangleOnScreenRequested(
                wideRect.left, wideRect.top, wideRect.right, wideRect.bottom);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationSpec expectedEndSpec = getMagnificationSpec(2.0f, 0, startSpec.offsetY);
        assertThat(getCurrentMagnificationSpec(), closeTo(expectedEndSpec));
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(expectedEndSpec)));
        Mockito.reset(mMockWindowManager);

        // Repeat with RTL
        Locale.setDefault(new Locale("he", "il"));
        callbacks.onRectangleOnScreenRequested(
                wideRect.left, wideRect.top, wideRect.right, wideRect.bottom);
        mMessageCapturingHandler.sendAllMessages();
        expectedEndSpec = getMagnificationSpec(2.0f, -100, startSpec.offsetY);
        assertThat(getCurrentMagnificationSpec(), closeTo(expectedEndSpec));
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(expectedEndSpec)));
    }

    @Test
    public void testRequestRectOnScreen_rectTooTall_pansMinimumToGetTopOnScreen() {
        mMagnificationController.register();
        zoomIn2xToMiddle();
        MagnificationCallbacks callbacks = getMagnificationCallbacks();
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        Mockito.reset(mMockWindowManager);
        Rect tallRect = new Rect(50, 0, 51, 100);
        callbacks.onRectangleOnScreenRequested(
                tallRect.left, tallRect.top, tallRect.right, tallRect.bottom);
        mMessageCapturingHandler.sendAllMessages();
        MagnificationSpec expectedEndSpec = getMagnificationSpec(2.0f, startSpec.offsetX, 0);
        assertThat(getCurrentMagnificationSpec(), closeTo(expectedEndSpec));
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(expectedEndSpec)));
    }

    @Test
    public void testChangeMagnification_duringAnimation_animatesToNewValue() {
        mMagnificationController.register();
        MagnificationSpec startSpec = getCurrentMagnificationSpec();
        float scale = 2.5f;
        PointF firstCenter = INITIAL_BOUNDS_LOWER_RIGHT_2X_CENTER;
        MagnificationSpec firstEndSpec = getMagnificationSpec(
                scale, computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, firstCenter, scale));

        assertTrue(mMagnificationController.setScaleAndCenter(scale, firstCenter.x, firstCenter.y,
                true, SERVICE_ID_1));

        assertEquals(firstCenter.x, mMagnificationController.getCenterX(), 0.5);
        assertEquals(firstCenter.y, mMagnificationController.getCenterY(), 0.5);
        assertThat(getCurrentMagnificationSpec(), closeTo(firstEndSpec));
        verify(mMockValueAnimator, times(1)).start();

        // Initial value
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(0.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(startSpec);
        verify(mMockAms).notifyMagnificationChanged(
                INITIAL_MAGNIFICATION_REGION, scale, firstCenter.x, firstCenter.y);
        Mockito.reset(mMockWindowManager);

        // Intermediate point
        float fraction = 0.33f;
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(fraction);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        MagnificationSpec intermediateSpec1 =
                getInterpolatedMagSpec(startSpec, firstEndSpec, fraction);
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(intermediateSpec1)));
        Mockito.reset(mMockWindowManager);

        PointF newCenter = INITIAL_BOUNDS_UPPER_LEFT_2X_CENTER;
        MagnificationSpec newEndSpec = getMagnificationSpec(
                scale, computeOffsets(INITIAL_MAGNIFICATION_BOUNDS, newCenter, scale));
        assertTrue(mMagnificationController.setCenter(
                newCenter.x, newCenter.y, true, SERVICE_ID_1));

        // Animation should have been restarted
        verify(mMockValueAnimator, times(2)).start();
        verify(mMockAms).notifyMagnificationChanged(
                INITIAL_MAGNIFICATION_REGION, scale, newCenter.x, newCenter.y);

        // New starting point should be where we left off
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(0.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(intermediateSpec1)));
        Mockito.reset(mMockWindowManager);

        // Second intermediate point
        fraction = 0.5f;
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(fraction);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(
                argThat(closeTo(getInterpolatedMagSpec(intermediateSpec1, newEndSpec, fraction))));
        Mockito.reset(mMockWindowManager);

        // Final value should be the new center
        Mockito.reset(mMockWindowManager);
        when(mMockValueAnimator.getAnimatedFraction()).thenReturn(1.0f);
        mTargetAnimationListener.onAnimationUpdate(mMockValueAnimator);
        verify(mMockWindowManager).setMagnificationSpec(argThat(closeTo(newEndSpec)));
    }

    private void zoomIn2xToMiddle() {
        PointF startCenter = INITIAL_MAGNIFICATION_BOUNDS_CENTER;
        float scale = 2.0f;
        mMagnificationController.setScale(scale, startCenter.x, startCenter.y, false, SERVICE_ID_1);
        assertTrue(mMagnificationController.isMagnifying());
    }

    private MagnificationCallbacks getMagnificationCallbacks() {
        ArgumentCaptor<MagnificationCallbacks> magnificationCallbacksCaptor =
                ArgumentCaptor.forClass(MagnificationCallbacks.class);
        verify(mMockWindowManager)
                .setMagnificationCallbacks(magnificationCallbacksCaptor.capture());
        return magnificationCallbacksCaptor.getValue();
    }

    private PointF computeOffsets(Rect magnifiedBounds, PointF center, float scale) {
        return new PointF(
                magnifiedBounds.centerX() - scale * center.x,
                magnifiedBounds.centerY() - scale * center.y);
    }

    private MagnificationSpec getInterpolatedMagSpec(MagnificationSpec start, MagnificationSpec end,
            float fraction) {
        MagnificationSpec interpolatedSpec = MagnificationSpec.obtain();
        interpolatedSpec.scale = start.scale + fraction * (end.scale - start.scale);
        interpolatedSpec.offsetX = start.offsetX + fraction * (end.offsetX - start.offsetX);
        interpolatedSpec.offsetY = start.offsetY + fraction * (end.offsetY - start.offsetY);
        return interpolatedSpec;
    }

    private MagnificationSpec getMagnificationSpec(float scale, PointF offsets) {
        return getMagnificationSpec(scale, offsets.x, offsets.y);
    }

    private MagnificationSpec getMagnificationSpec(float scale, float offsetX, float offsetY) {
        MagnificationSpec spec = MagnificationSpec.obtain();
        spec.scale = scale;
        spec.offsetX = offsetX;
        spec.offsetY = offsetY;
        return spec;
    }

    private MagnificationSpec getCurrentMagnificationSpec() {
        return getMagnificationSpec(mMagnificationController.getScale(),
                mMagnificationController.getOffsetX(), mMagnificationController.getOffsetY());
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
