/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.DisplayCutout.NO_CUTOUT;
import static android.view.IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT;
import static android.view.IWindowManager.FIXED_TO_USER_ROTATION_DISABLED;
import static android.view.IWindowManager.FIXED_TO_USER_ROTATION_ENABLED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.atLeast;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.atMost;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.same;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;

import android.app.WindowConfiguration;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManagerInternal;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.view.DisplayAddress;
import android.view.Surface;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.statusbar.StatusBarManagerInternal;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test class for {@link DisplayRotation}.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayRotationTests
 */
@SmallTest
@Presubmit
public class DisplayRotationTests {
    private static final long UI_HANDLER_WAIT_TIMEOUT_MS = 50;

    private StatusBarManagerInternal mPreviousStatusBarManagerInternal;

    private static WindowManagerService sMockWm;
    private DisplayContent mMockDisplayContent;
    private DisplayPolicy mMockDisplayPolicy;
    private DisplayAddress mMockDisplayAddress;
    private Context mMockContext;
    private Resources mMockRes;
    private SensorManager mMockSensorManager;
    private Sensor mFakeOrientationSensor;
    private DisplayWindowSettings mMockDisplayWindowSettings;
    private ContentResolver mMockResolver;
    private FakeSettingsProvider mFakeSettingsProvider;
    private StatusBarManagerInternal mMockStatusBarManagerInternal;

    // Fields below are callbacks captured from test target.
    private ContentObserver mShowRotationSuggestionsObserver;
    private ContentObserver mAccelerometerRotationObserver;
    private ContentObserver mUserRotationObserver;
    private SensorEventListener mOrientationSensorListener;

    private DisplayRotationBuilder mBuilder;

    private DisplayRotation mTarget;

    @BeforeClass
    public static void setUpOnce() {
        sMockWm = mock(WindowManagerService.class);
        sMockWm.mPowerManagerInternal = mock(PowerManagerInternal.class);
        sMockWm.mPolicy = mock(WindowManagerPolicy.class);
    }

    @AfterClass
    public static void tearDownOnce() {
        // Make sure the fake settings are cleared after the last test method.
        FakeSettingsProvider.clearSettingsProvider();
    }

    @Before
    public void setUp() {
        FakeSettingsProvider.clearSettingsProvider();

        mPreviousStatusBarManagerInternal = LocalServices.getService(
                StatusBarManagerInternal.class);
        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        mMockStatusBarManagerInternal = mock(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mMockStatusBarManagerInternal);

        mBuilder = new DisplayRotationBuilder();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        if (mPreviousStatusBarManagerInternal != null) {
            LocalServices.addService(StatusBarManagerInternal.class,
                    mPreviousStatusBarManagerInternal);
            mPreviousStatusBarManagerInternal = null;
        }
    }

    // ================================
    // Display Settings Related Tests
    // ================================
    @Test
    public void testLocksUserRotation_LockRotation_DefaultDisplay() throws Exception {
        mBuilder.build();

        freezeRotation(Surface.ROTATION_180);

        assertEquals(WindowManagerPolicy.USER_ROTATION_LOCKED, mTarget.getUserRotationMode());
        assertEquals(Surface.ROTATION_180, mTarget.getUserRotation());

        assertEquals(0, Settings.System.getInt(mMockResolver,
                Settings.System.ACCELEROMETER_ROTATION));
        assertEquals(Surface.ROTATION_180, Settings.System.getInt(mMockResolver,
                Settings.System.USER_ROTATION));
    }

    @Test
    public void testPersistsUserRotation_LockRotation_NonDefaultDisplay() throws Exception {
        mBuilder.setIsDefaultDisplay(false).build();

        freezeRotation(Surface.ROTATION_180);

        assertEquals(WindowManagerPolicy.USER_ROTATION_LOCKED, mTarget.getUserRotationMode());
        assertEquals(Surface.ROTATION_180, mTarget.getUserRotation());

        verify(mMockDisplayWindowSettings).setUserRotation(mMockDisplayContent,
                WindowManagerPolicy.USER_ROTATION_LOCKED, Surface.ROTATION_180);
    }

    @Test
    public void testPersistUserRotation_UnlockRotation_DefaultDisplay() throws Exception {
        mBuilder.build();

        thawRotation();

        assertEquals(WindowManagerPolicy.USER_ROTATION_FREE, mTarget.getUserRotationMode());

        assertEquals(1, Settings.System.getInt(mMockResolver,
                Settings.System.ACCELEROMETER_ROTATION));
    }

    @Test
    public void testPersistsUserRotation_UnlockRotation_NonDefaultDisplay() throws Exception {
        mBuilder.setIsDefaultDisplay(false).build();

        thawRotation();

        assertEquals(WindowManagerPolicy.USER_ROTATION_FREE, mTarget.getUserRotationMode());

        verify(mMockDisplayWindowSettings).setUserRotation(same(mMockDisplayContent),
                eq(WindowManagerPolicy.USER_ROTATION_FREE), anyInt());
    }

    @Test
    public void testPersistsFixedToUserRotation() throws Exception {
        mBuilder.build();

        mTarget.setFixedToUserRotation(FIXED_TO_USER_ROTATION_ENABLED);

        verify(mMockDisplayWindowSettings).setFixedToUserRotation(mMockDisplayContent,
                FIXED_TO_USER_ROTATION_ENABLED);

        reset(mMockDisplayWindowSettings);
        mTarget.setFixedToUserRotation(FIXED_TO_USER_ROTATION_DISABLED);

        verify(mMockDisplayWindowSettings).setFixedToUserRotation(mMockDisplayContent,
                FIXED_TO_USER_ROTATION_DISABLED);

        reset(mMockDisplayWindowSettings);
        mTarget.setFixedToUserRotation(FIXED_TO_USER_ROTATION_DEFAULT);

        verify(mMockDisplayWindowSettings).setFixedToUserRotation(mMockDisplayContent,
                FIXED_TO_USER_ROTATION_DEFAULT);
    }

    // ========================================
    // Tests for User Rotation based Rotation
    // ========================================
    @Test
    public void testReturnsUserRotation_UserRotationLocked_NoAppRequest()
            throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);

        freezeRotation(Surface.ROTATION_180);

        assertEquals(Surface.ROTATION_180, mTarget.rotationForOrientation(
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, Surface.ROTATION_90));
    }

    @Test
    public void testReturnsLandscape_UserRotationLockedSeascape_AppRequestsLandscape()
            throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false /* isCar */,
                false /* isTv */);

        freezeRotation(Surface.ROTATION_180);

        assertEquals(Surface.ROTATION_0, mTarget.rotationForOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Surface.ROTATION_90));
    }

    @Test
    public void testReturnsSeascape_UserRotationLockedSeascape_AppRequestsSeascape()
            throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false /* isCar */,
                false /* isTv */);

        freezeRotation(Surface.ROTATION_180);

        assertEquals(Surface.ROTATION_180, mTarget.rotationForOrientation(
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, Surface.ROTATION_90));
    }

    @Test
    public void testReturnsPortrait_UserRotationLockedPortrait_AppRequestsPortrait()
            throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false /* isCar */,
                false /* isTv */);

        freezeRotation(Surface.ROTATION_270);

        assertEquals(Surface.ROTATION_270, mTarget.rotationForOrientation(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Surface.ROTATION_0));
    }

    @Test
    public void testReturnsUpsideDown_UserRotationLockedUpsideDown_AppRequestsUpsideDown()
            throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false /* isCar */,
                false /* isTv */);

        freezeRotation(Surface.ROTATION_90);

        assertEquals(Surface.ROTATION_90, mTarget.rotationForOrientation(
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, Surface.ROTATION_0));
    }

    @Test
    public void testReturnsSideways_UserRotationLocked_IncompatibleAppRequest()
            throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);

        freezeRotation(Surface.ROTATION_180);

        final int rotation = mTarget.rotationForOrientation(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Surface.ROTATION_90);
        assertTrue("Rotation should be sideways, but it's "
                        + Surface.rotationToString(rotation),
                rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270);
    }

    // =================================
    // Tests for Sensor based Rotation
    // =================================
    private void verifyOrientationListenerRegistration(int numOfInvocation) {
        final ArgumentCaptor<SensorEventListener> listenerCaptor = ArgumentCaptor.forClass(
                SensorEventListener.class);
        waitForUiHandler();
        verify(mMockSensorManager, times(numOfInvocation)).registerListener(
                listenerCaptor.capture(),
                same(mFakeOrientationSensor),
                anyInt(),
                any());
        if (numOfInvocation > 0) {
            mOrientationSensorListener = listenerCaptor.getValue();
        }
    }

    @Test
    public void testNotEnablesSensor_AutoRotationNotSupported() throws Exception {
        mBuilder.setSupportAutoRotation(false).build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        thawRotation();

        when(mMockDisplayPolicy.isScreenOnEarly()).thenReturn(true);
        when(mMockDisplayPolicy.isAwake()).thenReturn(true);
        when(mMockDisplayPolicy.isKeyguardDrawComplete()).thenReturn(true);
        when(mMockDisplayPolicy.isWindowManagerDrawComplete()).thenReturn(true);
        mTarget.updateOrientationListener();
        verifyOrientationListenerRegistration(0);
    }

    @Test
    public void testNotEnablesSensor_ScreenNotOn() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        thawRotation();

        when(mMockDisplayPolicy.isScreenOnEarly()).thenReturn(false);
        when(mMockDisplayPolicy.isAwake()).thenReturn(true);
        when(mMockDisplayPolicy.isKeyguardDrawComplete()).thenReturn(true);
        when(mMockDisplayPolicy.isWindowManagerDrawComplete()).thenReturn(true);
        mTarget.updateOrientationListener();
        verifyOrientationListenerRegistration(0);
    }

    @Test
    public void testNotEnablesSensor_NotAwake() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        thawRotation();

        when(mMockDisplayPolicy.isScreenOnEarly()).thenReturn(true);
        when(mMockDisplayPolicy.isAwake()).thenReturn(false);
        when(mMockDisplayPolicy.isKeyguardDrawComplete()).thenReturn(true);
        when(mMockDisplayPolicy.isWindowManagerDrawComplete()).thenReturn(true);
        mTarget.updateOrientationListener();
        verifyOrientationListenerRegistration(0);
    }

    @Test
    public void testNotEnablesSensor_KeyguardNotDrawnCompletely() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        thawRotation();

        when(mMockDisplayPolicy.isScreenOnEarly()).thenReturn(true);
        when(mMockDisplayPolicy.isAwake()).thenReturn(true);
        when(mMockDisplayPolicy.isKeyguardDrawComplete()).thenReturn(false);
        when(mMockDisplayPolicy.isWindowManagerDrawComplete()).thenReturn(true);
        mTarget.updateOrientationListener();
        verifyOrientationListenerRegistration(0);
    }

    @Test
    public void testNotEnablesSensor_WindowManagerNotDrawnCompletely() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        thawRotation();

        when(mMockDisplayPolicy.isScreenOnEarly()).thenReturn(true);
        when(mMockDisplayPolicy.isAwake()).thenReturn(true);
        when(mMockDisplayPolicy.isKeyguardDrawComplete()).thenReturn(true);
        when(mMockDisplayPolicy.isWindowManagerDrawComplete()).thenReturn(false);
        mTarget.updateOrientationListener();
        verifyOrientationListenerRegistration(0);
    }

    @Test
    public void testNotEnablesSensor_FixedUserRotation() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        when(mMockDisplayPolicy.isScreenOnEarly()).thenReturn(true);
        when(mMockDisplayPolicy.isAwake()).thenReturn(true);
        when(mMockDisplayPolicy.isKeyguardDrawComplete()).thenReturn(true);
        when(mMockDisplayPolicy.isWindowManagerDrawComplete()).thenReturn(true);
        mTarget.setFixedToUserRotation(FIXED_TO_USER_ROTATION_ENABLED);
        mTarget.updateOrientationListener();
        verifyOrientationListenerRegistration(0);
    }

    @Test
    public void testNotEnablesSensor_ForceDefaultRotation_Car() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, true, false);

        when(mMockDisplayPolicy.isScreenOnEarly()).thenReturn(true);
        when(mMockDisplayPolicy.isAwake()).thenReturn(true);
        when(mMockDisplayPolicy.isKeyguardDrawComplete()).thenReturn(true);
        when(mMockDisplayPolicy.isWindowManagerDrawComplete()).thenReturn(true);
        mTarget.updateOrientationListener();
        verifyOrientationListenerRegistration(0);
    }

    @Test
    public void testNotEnablesSensor_ForceDefaultRotation_Tv() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, true);

        when(mMockDisplayPolicy.isScreenOnEarly()).thenReturn(true);
        when(mMockDisplayPolicy.isAwake()).thenReturn(true);
        when(mMockDisplayPolicy.isKeyguardDrawComplete()).thenReturn(true);
        when(mMockDisplayPolicy.isWindowManagerDrawComplete()).thenReturn(true);
        mTarget.updateOrientationListener();
        verifyOrientationListenerRegistration(0);
    }

    private void enableOrientationSensor() {
        when(mMockDisplayPolicy.isScreenOnEarly()).thenReturn(true);
        when(mMockDisplayPolicy.isAwake()).thenReturn(true);
        when(mMockDisplayPolicy.isKeyguardDrawComplete()).thenReturn(true);
        when(mMockDisplayPolicy.isWindowManagerDrawComplete()).thenReturn(true);
        mTarget.updateOrientationListener();
        verifyOrientationListenerRegistration(1);
    }

    private SensorEvent createSensorEvent(int rotation) throws Exception {
        final Constructor<SensorEvent> constructor =
                SensorEvent.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        final SensorEvent event = constructor.newInstance(1);
        event.sensor = mFakeOrientationSensor;
        event.values[0] = rotation;
        event.timestamp = SystemClock.elapsedRealtimeNanos();
        return event;
    }

    @Test
    public void testReturnsSensorRotation_RotationThawed() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        thawRotation();

        enableOrientationSensor();

        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_90));

        assertEquals(Surface.ROTATION_90, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_UNSPECIFIED, Surface.ROTATION_0));
    }

    private boolean waitForUiHandler() {
        final CountDownLatch latch = new CountDownLatch(1);
        UiThread.getHandler().post(latch::countDown);
        try {
            return latch.await(UI_HANDLER_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        throw new AssertionError("Failed to wait for ui handler");
    }

    @Test
    public void testUpdatesRotationWhenSensorUpdates_RotationThawed() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        thawRotation();

        enableOrientationSensor();

        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_90));
        assertTrue(waitForUiHandler());

        verify(sMockWm).updateRotation(false, false);
    }

    @Test
    public void testNotifiesChoiceWhenSensorUpdates_RotationLocked() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        freezeRotation(Surface.ROTATION_270);

        enableOrientationSensor();

        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_90));
        assertTrue(waitForUiHandler());

        verify(mMockStatusBarManagerInternal).onProposedRotationChanged(Surface.ROTATION_90, true);
    }

    @Test
    public void testAllowAllRotations_allowsUpsideDownSuggestion()
            throws Exception {
        mBuilder.build();
        mTarget.updateOrientation(SCREEN_ORIENTATION_UNSPECIFIED, true);
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(true);
        freezeRotation(Surface.ROTATION_0);
        enableOrientationSensor();

        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_180));
        assertTrue(waitForUiHandler());

        verify(mMockStatusBarManagerInternal)
                .onProposedRotationChanged(Surface.ROTATION_180, true);
    }

    @Test
    public void testDoNotAllowAllRotations_doesNotAllowUpsideDownSuggestion()
            throws Exception {
        mBuilder.build();
        mTarget.updateOrientation(SCREEN_ORIENTATION_UNSPECIFIED, true);
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(false);
        freezeRotation(Surface.ROTATION_0);
        enableOrientationSensor();

        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_180));
        assertTrue(waitForUiHandler());

        verify(mMockStatusBarManagerInternal)
                .onProposedRotationChanged(Surface.ROTATION_180, false);
    }

    @Test
    public void testAllowAllRotations_allowAllRotationsBecomesDisabled_forbidsUpsideDownSuggestion()
            throws Exception {
        mBuilder.build();
        mTarget.updateOrientation(SCREEN_ORIENTATION_UNSPECIFIED, true);
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(true);
        freezeRotation(Surface.ROTATION_0);
        enableOrientationSensor();
        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_0));
        assertTrue(waitForUiHandler());

        // Change resource to disallow all rotations.
        // Reset "allowAllRotations".
        mTarget.applyCurrentRotation(Surface.ROTATION_0);
        clearInvocations(mMockStatusBarManagerInternal);
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(false);
        mTarget.resetAllowAllRotations();
        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_180));
        assertTrue(waitForUiHandler());

        verify(mMockStatusBarManagerInternal)
                .onProposedRotationChanged(Surface.ROTATION_180, false);
    }

    @Test
    public void testReturnsCompatibleRotation_SensorEnabled_RotationThawed() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        thawRotation();

        enableOrientationSensor();

        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_180));

        final int rotation = mTarget.rotationForOrientation(SCREEN_ORIENTATION_LANDSCAPE,
                Surface.ROTATION_0);
        assertTrue("Rotation should be sideways but it's "
                + Surface.rotationToString(rotation),
                rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270);
    }

    @Test
    public void testReturnsUserRotation_SensorEnabled_RotationLocked() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        freezeRotation(Surface.ROTATION_270);

        enableOrientationSensor();

        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_180));

        assertEquals(Surface.ROTATION_270, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_UNSPECIFIED, Surface.ROTATION_0));
    }

    @Test
    public void testReturnsSensorRotation_180degrees_allRotationsAllowed()
            throws Exception {
        mBuilder.build();
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(true);
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);
        enableOrientationSensor();
        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_180));

        assertEquals(Surface.ROTATION_180, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_SENSOR, Surface.ROTATION_0));
    }

    @Test
    public void testReturnLastRotation_sensor180_allRotationsNotAllowed()
            throws Exception {
        mBuilder.build();
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(false);
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);
        enableOrientationSensor();
        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_180));

        assertEquals(Surface.ROTATION_0, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_SENSOR, Surface.ROTATION_0));
    }

    @Test
    public void testAllowRotationsIsCached()
            throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);
        enableOrientationSensor();

        // Rotate once to read the resource
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(true);
        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_180));
        mTarget.rotationForOrientation(SCREEN_ORIENTATION_SENSOR, Surface.ROTATION_0);

        // Change resource to disallow all rotations.
        // Rotate again and 180 degrees rotation should still be returned even if "disallowed".
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(false);
        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_180));
        assertEquals(Surface.ROTATION_180, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_SENSOR, Surface.ROTATION_0));
    }

    @Test
    public void testResetAllowRotations()
            throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);
        enableOrientationSensor();

        // Rotate once to read the resource
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(true);
        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_180));
        mTarget.rotationForOrientation(SCREEN_ORIENTATION_SENSOR, Surface.ROTATION_0);

        // Change resource to disallow all rotations.
        // Reset "allowAllRotations".
        // Rotate again and 180 degrees rotation should not be allowed anymore.
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(false);
        mTarget.resetAllowAllRotations();
        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_180));
        assertEquals(Surface.ROTATION_0, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_SENSOR, Surface.ROTATION_0));
    }

    // ====================================================
    // Tests for half-fold auto-rotate override of rotation
    // ====================================================
    @Test
    public void testUpdatesRotationWhenSensorUpdates_RotationLocked_HalfFolded() throws Exception {
        mBuilder.setSupportHalfFoldAutoRotateOverride(true);
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);

        enableOrientationSensor();

        mTarget.foldStateChanged(DeviceStateController.FoldState.OPEN);
        freezeRotation(Surface.ROTATION_270);

        mOrientationSensorListener.onSensorChanged(createSensorEvent(Surface.ROTATION_0));
        assertTrue(waitForUiHandler());
        // No rotation...
        assertEquals(Surface.ROTATION_270, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_UNSPECIFIED, Surface.ROTATION_0));

        // ... until half-fold
        mTarget.foldStateChanged(DeviceStateController.FoldState.HALF_FOLDED);
        assertTrue(waitForUiHandler());
        verify(sMockWm).updateRotation(false, false);
        assertTrue(waitForUiHandler());
        assertEquals(Surface.ROTATION_0, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_UNSPECIFIED, Surface.ROTATION_0));

        // ... then transition back to flat
        mTarget.foldStateChanged(DeviceStateController.FoldState.OPEN);
        assertTrue(waitForUiHandler());
        verify(sMockWm, atLeast(1)).updateRotation(false, false);
        assertTrue(waitForUiHandler());
        assertEquals(Surface.ROTATION_270, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_UNSPECIFIED, Surface.ROTATION_0));
    }

    // =================================
    // Tests for Policy based Rotation
    // =================================
    @Test
    public void testReturnsUserRotation_ForceDefaultRotation_Car() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, true, false);

        assertEquals(Surface.ROTATION_0, mTarget.rotationForOrientation(SCREEN_ORIENTATION_PORTRAIT,
                Surface.ROTATION_180));
    }

    @Test
    public void testReturnsUserRotation_ForceDefaultRotation_Tv() throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, true);

        assertEquals(Surface.ROTATION_0, mTarget.rotationForOrientation(SCREEN_ORIENTATION_PORTRAIT,
                Surface.ROTATION_180));
    }

    @Test
    public void testReturnsLidOpenRotation_LidOpen() throws Exception {
        mBuilder.setLidOpenRotation(Surface.ROTATION_90).build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);

        when(mMockDisplayPolicy.getLidState()).thenReturn(
                WindowManagerPolicy.WindowManagerFuncs.LID_OPEN);

        freezeRotation(Surface.ROTATION_270);

        assertEquals(Surface.ROTATION_90, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_UNSPECIFIED, Surface.ROTATION_0));
    }

    @Test
    public void testReturnsCarDockRotation_CarDockedMode() throws Exception {
        mBuilder.setCarDockRotation(Surface.ROTATION_270).build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);

        when(mMockDisplayPolicy.getDockMode()).thenReturn(Intent.EXTRA_DOCK_STATE_CAR);

        freezeRotation(Surface.ROTATION_90);

        assertEquals(Surface.ROTATION_270, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_UNSPECIFIED, Surface.ROTATION_90));
    }

    @Test
    public void testReturnsDeskDockRotation_DeskDockedMode() throws Exception {
        mBuilder.setDeskDockRotation(Surface.ROTATION_270).build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);

        when(mMockDisplayPolicy.getDockMode()).thenReturn(Intent.EXTRA_DOCK_STATE_DESK);

        freezeRotation(Surface.ROTATION_90);

        assertEquals(Surface.ROTATION_270, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_UNSPECIFIED, Surface.ROTATION_90));
    }

    @Test
    public void testReturnsUserRotation_FixedToUserRotation_IgnoreIncompatibleAppRequest()
            throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_PORTRAIT, false, false);

        mTarget.setFixedToUserRotation(FIXED_TO_USER_ROTATION_ENABLED);

        freezeRotation(Surface.ROTATION_180);

        final int rotation = mTarget.rotationForOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Surface.ROTATION_90);
        assertEquals(Surface.ROTATION_180, rotation);
    }

    @Test
    public void testReturnsUserRotation_NonDefaultDisplay() throws Exception {
        mBuilder.setIsDefaultDisplay(false).build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);

        freezeRotation(Surface.ROTATION_90);

        assertEquals(Surface.ROTATION_90, mTarget.rotationForOrientation(
                SCREEN_ORIENTATION_UNSPECIFIED, Surface.ROTATION_0));
    }

    @Test
    public void testShouldRotateSeamlessly() throws Exception {
        mBuilder.build();

        final WindowState win = mock(WindowState.class);
        win.mToken = win.mActivityRecord = mock(ActivityRecord.class);
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();
        attrs.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;

        doReturn(attrs).when(win).getAttrs();
        doReturn(true).when(mMockDisplayPolicy).navigationBarCanMove();
        doReturn(win).when(mMockDisplayPolicy).getTopFullscreenOpaqueWindow();
        mMockDisplayContent.mCurrentFocus = win;
        // This should not affect the condition of shouldRotateSeamlessly.
        mTarget.mUpsideDownRotation = Surface.ROTATION_90;

        doReturn(true).when(win.mActivityRecord).matchParentBounds();
        // The focused fullscreen opaque window without override bounds should be able to be
        // rotated seamlessly.
        assertTrue(mTarget.shouldRotateSeamlessly(
                Surface.ROTATION_0, Surface.ROTATION_90, false /* forceUpdate */));
        // Reject any 180 degree because non-movable navbar will be placed in a different position.
        doReturn(false).when(mMockDisplayPolicy).navigationBarCanMove();
        assertFalse(mTarget.shouldRotateSeamlessly(
                Surface.ROTATION_90, Surface.ROTATION_180, false /* forceUpdate */));

        doReturn(true).when(mMockDisplayPolicy).navigationBarCanMove();
        doReturn(false).when(win.mActivityRecord).matchParentBounds();
        // No seamless rotation if the window may be positioned with offset after rotation.
        assertFalse(mTarget.shouldRotateSeamlessly(
                Surface.ROTATION_0, Surface.ROTATION_90, false /* forceUpdate */));
    }

    // ========================
    // Non-rotation API Tests
    // ========================
    @Test
    public void testIsNotFixedToUserRotationByDefault() throws Exception {
        mBuilder.build();

        assertFalse("Display rotation should respect app requested orientation by"
                + " default.", mTarget.isFixedToUserRotation());
    }

    @Test
    public void testIsFixedToUserRotation() throws Exception {
        mBuilder.build();
        mTarget.setFixedToUserRotation(FIXED_TO_USER_ROTATION_ENABLED);

        assertTrue("Display rotation shouldn't respect app requested orientation if"
                + " fixed to user rotation.", mTarget.isFixedToUserRotation());
    }

    /**
     * Call {@link DisplayRotation#configure(int, int)} to configure {@link #mTarget}
     * according to given parameters.
     */
    private void configureDisplayRotation(int displayOrientation, boolean isCar, boolean isTv) {
        final int width;
        final int height;
        switch (displayOrientation) {
            case SCREEN_ORIENTATION_LANDSCAPE:
                width = 1920;
                height = 1080;
                break;
            case SCREEN_ORIENTATION_PORTRAIT:
                width = 1080;
                height = 1920;
                break;
            default:
                throw new IllegalArgumentException("displayOrientation needs to be either landscape"
                        + " or portrait, but we got "
                        + ActivityInfo.screenOrientationToString(displayOrientation));
        }

        final PackageManager mockPackageManager = mock(PackageManager.class);
        when(mMockContext.getPackageManager()).thenReturn(mockPackageManager);
        when(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
                .thenReturn(isCar);
        when(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
                .thenReturn(isTv);

        mTarget.configure(width, height);
    }

    private void freezeRotation(int rotation) {
        mTarget.freezeRotation(rotation);

        if (mTarget.isDefaultDisplay) {
            mAccelerometerRotationObserver.onChange(false);
            mUserRotationObserver.onChange(false);
        }
    }

    private void thawRotation() {
        mTarget.thawRotation();

        if (mTarget.isDefaultDisplay) {
            mAccelerometerRotationObserver.onChange(false);
            mUserRotationObserver.onChange(false);
        }
    }

    private class DisplayRotationBuilder {
        private boolean mIsDefaultDisplay = true;
        private boolean mSupportAutoRotation = true;
        private boolean mSupportHalfFoldAutoRotateOverride = false;

        private int mLidOpenRotation = WindowManagerPolicy.WindowManagerFuncs.LID_ABSENT;
        private int mCarDockRotation;
        private int mDeskDockRotation;
        private int mUndockedHdmiRotation;

        private DisplayRotationBuilder setIsDefaultDisplay(boolean isDefaultDisplay) {
            mIsDefaultDisplay = isDefaultDisplay;
            return this;
        }

        private DisplayRotationBuilder setSupportAutoRotation(boolean supportAutoRotation) {
            mSupportAutoRotation = supportAutoRotation;
            return this;
        }

        private DisplayRotationBuilder setLidOpenRotation(int rotation) {
            mLidOpenRotation = rotation;
            return this;
        }

        private DisplayRotationBuilder setCarDockRotation(int rotation) {
            mCarDockRotation = rotation;
            return this;
        }

        private DisplayRotationBuilder setDeskDockRotation(int rotation) {
            mDeskDockRotation = rotation;
            return this;
        }

        private DisplayRotationBuilder setUndockedHdmiRotation(int rotation) {
            mUndockedHdmiRotation = rotation;
            return this;
        }

        private DisplayRotationBuilder setSupportHalfFoldAutoRotateOverride(
                boolean supportHalfFoldAutoRotateOverride) {
            mSupportHalfFoldAutoRotateOverride = supportHalfFoldAutoRotateOverride;
            return this;
        }

        private void captureObservers() {
            ArgumentCaptor<ContentObserver> captor = ArgumentCaptor.forClass(
                    ContentObserver.class);
            verify(mMockResolver, atMost(1)).registerContentObserver(
                    eq(Settings.Secure.getUriFor(Settings.Secure.SHOW_ROTATION_SUGGESTIONS)),
                    anyBoolean(),
                    captor.capture(),
                    anyInt());
            if (!captor.getAllValues().isEmpty()) {
                mShowRotationSuggestionsObserver = captor.getValue();
            }

            captor = ArgumentCaptor.forClass(ContentObserver.class);
            verify(mMockResolver, atMost(1)).registerContentObserver(
                    eq(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION)),
                    anyBoolean(),
                    captor.capture(),
                    anyInt());
            if (!captor.getAllValues().isEmpty()) {
                mAccelerometerRotationObserver = captor.getValue();
            }

            captor = ArgumentCaptor.forClass(ContentObserver.class);
            verify(mMockResolver, atMost(1)).registerContentObserver(
                    eq(Settings.System.getUriFor(Settings.System.USER_ROTATION)),
                    anyBoolean(),
                    captor.capture(),
                    anyInt());
            if (!captor.getAllValues().isEmpty()) {
                mUserRotationObserver = captor.getValue();
            }
        }

        private Sensor createSensor(int type) throws Exception {
            Constructor<Sensor> constr = Sensor.class.getDeclaredConstructor();
            constr.setAccessible(true);
            Sensor sensor = constr.newInstance();

            setSensorType(sensor, type);
            setSensorField(sensor, "mName", "Mock " + sensor.getStringType() + "/" + type);
            setSensorField(sensor, "mVendor", "Mock Vendor");
            setSensorField(sensor, "mVersion", 1);
            setSensorField(sensor, "mHandle", -1);
            setSensorField(sensor, "mMaxRange", 10);
            setSensorField(sensor, "mResolution", 1);
            setSensorField(sensor, "mPower", 1);
            setSensorField(sensor, "mMinDelay", 1000);
            setSensorField(sensor, "mMaxDelay", 1000000000);
            setSensorField(sensor, "mFlags", 0);
            setSensorField(sensor, "mId", -1);

            return sensor;
        }

        private void setSensorType(Sensor sensor, int type) throws Exception {
            Method setter = Sensor.class.getDeclaredMethod("setType", Integer.TYPE);
            setter.setAccessible(true);
            setter.invoke(sensor, type);
        }

        private void setSensorField(Sensor sensor, String fieldName, Object value)
                throws Exception {
            Field field = Sensor.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(sensor, value);
        }

        private int convertRotationToDegrees(@Surface.Rotation int rotation) {
            switch (rotation) {
                case Surface.ROTATION_0:
                    return 0;
                case Surface.ROTATION_90:
                    return 90;
                case Surface.ROTATION_180:
                    return 180;
                case Surface.ROTATION_270:
                    return 270;
                default:
                    return -1;
            }
        }

        private void build() throws Exception {
            mMockContext = mock(Context.class);

            mMockDisplayContent = mock(DisplayContent.class);
            mMockDisplayContent.isDefaultDisplay = mIsDefaultDisplay;
            when(mMockDisplayContent.calculateDisplayCutoutForRotation(anyInt()))
                    .thenReturn(NO_CUTOUT);
            when(mMockDisplayContent.getDefaultTaskDisplayArea())
                    .thenReturn(mock(TaskDisplayArea.class));
            when(mMockDisplayContent.getWindowConfiguration())
                    .thenReturn(new WindowConfiguration());

            mMockDisplayPolicy = mock(DisplayPolicy.class);

            mMockRes = mock(Resources.class);
            when(mMockContext.getResources()).thenReturn((mMockRes));
            when(mMockRes.getBoolean(com.android.internal.R.bool.config_supportAutoRotation))
                    .thenReturn(mSupportAutoRotation);
            when(mMockRes.getInteger(com.android.internal.R.integer.config_lidOpenRotation))
                    .thenReturn(convertRotationToDegrees(mLidOpenRotation));
            when(mMockRes.getInteger(com.android.internal.R.integer.config_carDockRotation))
                    .thenReturn(convertRotationToDegrees(mCarDockRotation));
            when(mMockRes.getInteger(com.android.internal.R.integer.config_deskDockRotation))
                    .thenReturn(convertRotationToDegrees(mDeskDockRotation));
            when(mMockRes.getInteger(com.android.internal.R.integer.config_undockedHdmiRotation))
                    .thenReturn(convertRotationToDegrees(mUndockedHdmiRotation));

            mMockSensorManager = mock(SensorManager.class);
            when(mMockContext.getSystemService(Context.SENSOR_SERVICE))
                    .thenReturn(mMockSensorManager);
            mFakeOrientationSensor = createSensor(Sensor.TYPE_DEVICE_ORIENTATION);
            when(mMockSensorManager.getSensorList(Sensor.TYPE_DEVICE_ORIENTATION)).thenReturn(
                    Collections.singletonList(mFakeOrientationSensor));

            when(mMockContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_windowManagerHalfFoldAutoRotateOverride))
                    .thenReturn(mSupportHalfFoldAutoRotateOverride);

            mMockResolver = mock(ContentResolver.class);
            when(mMockContext.getContentResolver()).thenReturn(mMockResolver);
            mFakeSettingsProvider = new FakeSettingsProvider();
            when(mMockResolver.acquireProvider(Settings.AUTHORITY))
                    .thenReturn(mFakeSettingsProvider.getIContentProvider());

            mMockDisplayAddress = mock(DisplayAddress.class);

            mMockDisplayWindowSettings = mock(DisplayWindowSettings.class);

            mTarget = new DisplayRotation(sMockWm, mMockDisplayContent, mMockDisplayAddress,
                    mMockDisplayPolicy, mMockDisplayWindowSettings, mMockContext, new Object()) {
                @Override
                DisplayRotationImmersiveAppCompatPolicy initImmersiveAppCompatPolicy(
                        WindowManagerService service, DisplayContent displayContent) {
                    return null;
                }
            };

            reset(sMockWm);

            captureObservers();
        }
    }
}
