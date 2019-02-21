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
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.atMost;
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
import android.view.Surface;

import androidx.test.filters.SmallTest;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.After;
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
    private Context mMockContext;
    private Resources mMockRes;
    private SensorManager mMockSensorManager;
    private Sensor mFakeSensor;
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
        mBuilder.mIsDefaultDisplay = false;

        mBuilder.build();

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
        mBuilder.mIsDefaultDisplay = false;

        mBuilder.build();

        thawRotation();

        assertEquals(WindowManagerPolicy.USER_ROTATION_FREE, mTarget.getUserRotationMode());

        verify(mMockDisplayWindowSettings).setUserRotation(same(mMockDisplayContent),
                eq(WindowManagerPolicy.USER_ROTATION_FREE), anyInt());
    }

    @Test
    public void testPersistsFixedToUserRotation() throws Exception {
        mBuilder.build();

        mTarget.setFixedToUserRotation(true);

        verify(mMockDisplayWindowSettings).setFixedToUserRotation(mMockDisplayContent, true);

        reset(mMockDisplayWindowSettings);
        mTarget.setFixedToUserRotation(false);

        verify(mMockDisplayWindowSettings).setFixedToUserRotation(mMockDisplayContent, false);
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
    public void testReturnsUserRotation_UserRotationLocked_CompatibleAppRequest()
            throws Exception {
        mBuilder.build();
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);

        freezeRotation(Surface.ROTATION_180);

        assertEquals(Surface.ROTATION_180, mTarget.rotationForOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Surface.ROTATION_90));
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
        verify(mMockSensorManager, times(numOfInvocation)).registerListener(
                listenerCaptor.capture(),
                same(mFakeSensor),
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
        mTarget.setFixedToUserRotation(true);
        mTarget.updateOrientationListener();
        verifyOrientationListenerRegistration(0);
    }

    @Test
    public void testNotEnablesSensor_ForceDefaultRotation() throws Exception {
        mBuilder.build();
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_forceDefaultOrientation))
                .thenReturn(true);
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);

        when(mMockDisplayPolicy.isScreenOnEarly()).thenReturn(true);
        when(mMockDisplayPolicy.isAwake()).thenReturn(true);
        when(mMockDisplayPolicy.isKeyguardDrawComplete()).thenReturn(true);
        when(mMockDisplayPolicy.isWindowManagerDrawComplete()).thenReturn(true);
        mTarget.updateOrientationListener();
        verifyOrientationListenerRegistration(0);
    }

    @Test
    public void testNotEnablesSensor_ForceDefaultRotation_Car() throws Exception {
        mBuilder.build();
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_forceDefaultOrientation))
                .thenReturn(true);
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
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_forceDefaultOrientation))
                .thenReturn(true);
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
        event.sensor = mFakeSensor;
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

    private boolean waitForUiHandler() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        UiThread.getHandler().post(latch::countDown);
        return latch.await(UI_HANDLER_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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

    // =================================
    // Tests for Policy based Rotation
    // =================================
    @Test
    public void testReturnsUserRotation_ForceDefaultRotation() throws Exception {
        mBuilder.build();
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_forceDefaultOrientation))
                .thenReturn(true);
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, false, false);

        assertEquals(Surface.ROTATION_0, mTarget.rotationForOrientation(SCREEN_ORIENTATION_PORTRAIT,
                Surface.ROTATION_180));
    }

    @Test
    public void testReturnsUserRotation_ForceDefaultRotation_Car() throws Exception {
        mBuilder.build();
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_forceDefaultOrientation))
                .thenReturn(true);
        configureDisplayRotation(SCREEN_ORIENTATION_LANDSCAPE, true, false);

        assertEquals(Surface.ROTATION_0, mTarget.rotationForOrientation(SCREEN_ORIENTATION_PORTRAIT,
                Surface.ROTATION_180));
    }

    @Test
    public void testReturnsUserRotation_ForceDefaultRotation_Tv() throws Exception {
        mBuilder.build();
        when(mMockRes.getBoolean(com.android.internal.R.bool.config_forceDefaultOrientation))
                .thenReturn(true);
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

        mTarget.setFixedToUserRotation(true);

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

    // ========================
    // Non-rotation API Tests
    // ========================
    @Test
    public void testRespectsAppRequestedOrientationByDefault() throws Exception {
        mBuilder.build();

        assertTrue("Display rotation should respect app requested orientation by"
                + " default.", mTarget.respectAppRequestedOrientation());
    }

    @Test
    public void testNotRespectAppRequestedOrientation_FixedToUserRotation() throws Exception {
        mBuilder.build();
        mTarget.setFixedToUserRotation(true);

        assertFalse("Display rotation shouldn't respect app requested orientation if"
                + " fixed to user rotation.", mTarget.respectAppRequestedOrientation());
    }

    /**
     * Call {@link DisplayRotation#configure(int, int, int, int)} to configure {@link #mTarget}
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

        final int shortSizeDp = (isCar || isTv) ? 540 : 720;
        final int longSizeDp = 960;
        mTarget.configure(width, height, shortSizeDp, longSizeDp);
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
                    .thenReturn(WmDisplayCutout.NO_CUTOUT);

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
            mFakeSensor = createSensor(Sensor.TYPE_DEVICE_ORIENTATION);
            when(mMockSensorManager.getSensorList(Sensor.TYPE_DEVICE_ORIENTATION)).thenReturn(
                    Collections.singletonList(mFakeSensor));

            mMockResolver = mock(ContentResolver.class);
            when(mMockContext.getContentResolver()).thenReturn(mMockResolver);
            mFakeSettingsProvider = new FakeSettingsProvider();
            when(mMockResolver.acquireProvider(Settings.AUTHORITY))
                    .thenReturn(mFakeSettingsProvider.getIContentProvider());

            mMockDisplayWindowSettings = mock(DisplayWindowSettings.class);
            mTarget = new DisplayRotation(sMockWm, mMockDisplayContent, mMockDisplayPolicy,
                    mMockDisplayWindowSettings, mMockContext, new Object());
            reset(sMockWm);

            captureObservers();
        }
    }
}
