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

package com.android.server;

import static com.android.server.testutils.TestUtils.assertExpectException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.vibrator.IVibrator;
import android.os.CombinedVibrationEffect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;

import com.android.server.vibrator.VibratorController;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link VibratorManagerService}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibratorManagerServiceTest
 */
@Presubmit
public class VibratorManagerServiceTest {

    private static final int UID = Process.ROOT_UID;
    private static final String PACKAGE_NAME = "package";
    private static final VibrationAttributes ALARM_ATTRS =
            new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build();

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Mock private VibratorManagerService.NativeWrapper mNativeWrapperMock;
    @Mock private PowerManagerInternal mPowerManagerInternalMock;
    @Mock private PowerSaveState mPowerSaveStateMock;

    private final Map<Integer, VibratorController.NativeWrapper> mNativeWrappers = new HashMap<>();

    private TestLooper mTestLooper;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();

        when(mPowerManagerInternalMock.getLowPowerState(PowerManager.ServiceType.VIBRATION))
                .thenReturn(mPowerSaveStateMock);

        addLocalServiceMock(PowerManagerInternal.class, mPowerManagerInternalMock);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
    }

    private VibratorManagerService createService() {
        VibratorManagerService service = new VibratorManagerService(
                InstrumentationRegistry.getContext(),
                new VibratorManagerService.Injector() {
                    @Override
                    VibratorManagerService.NativeWrapper getNativeWrapper() {
                        return mNativeWrapperMock;
                    }

                    @Override
                    Handler createHandler(Looper looper) {
                        return new Handler(mTestLooper.getLooper());
                    }

                    @Override
                    VibratorController createVibratorController(int vibratorId,
                            VibratorController.OnVibrationCompleteListener listener) {
                        return new VibratorController(
                                vibratorId, listener, mNativeWrappers.get(vibratorId));
                    }
                });
        service.systemReady();
        return service;
    }

    @Test
    public void createService_initializesNativeService() {
        createService();
        verify(mNativeWrapperMock).init();
    }

    @Test
    public void getVibratorIds_withNullResultFromNative_returnsEmptyArray() {
        when(mNativeWrapperMock.getVibratorIds()).thenReturn(null);
        assertArrayEquals(new int[0], createService().getVibratorIds());
    }

    @Test
    public void getVibratorIds_withNonEmptyResultFromNative_returnsSameArray() {
        mNativeWrappers.put(1, mockVibrator(0));
        mNativeWrappers.put(2, mockVibrator(0));
        when(mNativeWrapperMock.getVibratorIds()).thenReturn(new int[]{2, 1});
        assertArrayEquals(new int[]{2, 1}, createService().getVibratorIds());
    }

    @Test
    public void setAlwaysOnEffect_withMono_enablesAlwaysOnEffectToAllVibratorsWithCapability() {
        VibratorController.NativeWrapper[] vibratorMocks = new VibratorController.NativeWrapper[] {
                mockVibrator(IVibrator.CAP_ALWAYS_ON_CONTROL),
                mockVibrator(0),
                mockVibrator(IVibrator.CAP_ALWAYS_ON_CONTROL),
        };
        mockVibrators(vibratorMocks);

        CombinedVibrationEffect effect = CombinedVibrationEffect.createSynced(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        assertTrue(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        // Only vibrators 0 and 2 have always-on capabilities.
        verify(vibratorMocks[0]).alwaysOnEnable(
                eq(1L), eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG));
        verify(vibratorMocks[1], never()).alwaysOnEnable(anyLong(), anyLong(), anyLong());
        verify(vibratorMocks[2]).alwaysOnEnable(
                eq(1L), eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG));
    }

    @Test
    public void setAlwaysOnEffect_withStereo_enablesAlwaysOnEffectToAllVibratorsWithCapability() {
        VibratorController.NativeWrapper[] vibratorMocks = new VibratorController.NativeWrapper[] {
                mockVibrator(IVibrator.CAP_ALWAYS_ON_CONTROL),
                mockVibrator(IVibrator.CAP_ALWAYS_ON_CONTROL),
                mockVibrator(0),
                mockVibrator(IVibrator.CAP_ALWAYS_ON_CONTROL),
        };
        mockVibrators(vibratorMocks);

        CombinedVibrationEffect effect = CombinedVibrationEffect.startSynced()
                .addVibrator(0, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                .addVibrator(1, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                .addVibrator(2, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                .combine();
        assertTrue(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        // Enables click on vibrator 0 and tick on vibrator 1 only.
        verify(vibratorMocks[0]).alwaysOnEnable(
                eq(1L), eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG));
        verify(vibratorMocks[1]).alwaysOnEnable(
                eq(1L), eq((long) VibrationEffect.EFFECT_TICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG));
        verify(vibratorMocks[2], never()).alwaysOnEnable(anyLong(), anyLong(), anyLong());
        verify(vibratorMocks[3], never()).alwaysOnEnable(anyLong(), anyLong(), anyLong());
    }

    @Test
    public void setAlwaysOnEffect_withNullEffect_disablesAlwaysOnEffects() {
        VibratorController.NativeWrapper[] vibratorMocks = new VibratorController.NativeWrapper[] {
                mockVibrator(IVibrator.CAP_ALWAYS_ON_CONTROL),
                mockVibrator(0),
                mockVibrator(IVibrator.CAP_ALWAYS_ON_CONTROL),
        };
        mockVibrators(vibratorMocks);

        assertTrue(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, null, ALARM_ATTRS));

        // Disables only 0 and 2 that have capability.
        verify(vibratorMocks[0]).alwaysOnDisable(eq(1L));
        verify(vibratorMocks[1], never()).alwaysOnDisable(anyLong());
        verify(vibratorMocks[2]).alwaysOnDisable(eq(1L));
    }

    @Test
    public void setAlwaysOnEffect_withNonPrebakedEffect_ignoresEffect() {
        VibratorController.NativeWrapper vibratorMock =
                mockVibrator(IVibrator.CAP_ALWAYS_ON_CONTROL);
        mockVibrators(vibratorMock);

        CombinedVibrationEffect effect = CombinedVibrationEffect.createSynced(
                VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
        assertFalse(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        verify(vibratorMock, never()).alwaysOnEnable(anyLong(), anyLong(), anyLong());
        verify(vibratorMock, never()).alwaysOnDisable(anyLong());
    }

    @Test
    public void setAlwaysOnEffect_withNonSyncedEffect_ignoresEffect() {
        VibratorController.NativeWrapper vibratorMock =
                mockVibrator(IVibrator.CAP_ALWAYS_ON_CONTROL);
        mockVibrators(vibratorMock);

        CombinedVibrationEffect effect = CombinedVibrationEffect.startSequential()
                .addNext(0, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .combine();
        assertFalse(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        verify(vibratorMock, never()).alwaysOnEnable(anyLong(), anyLong(), anyLong());
        verify(vibratorMock, never()).alwaysOnDisable(anyLong());
    }

    @Test
    public void setAlwaysOnEffect_withNoVibratorWithCapability_ignoresEffect() {
        VibratorController.NativeWrapper vibratorMock = mockVibrator(0);
        mockVibrators(vibratorMock);
        VibratorManagerService service = createService();

        CombinedVibrationEffect mono = CombinedVibrationEffect.createSynced(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        CombinedVibrationEffect stereo = CombinedVibrationEffect.startSynced()
                .addVibrator(0, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                .combine();
        assertFalse(service.setAlwaysOnEffect(UID, PACKAGE_NAME, 1, mono, ALARM_ATTRS));
        assertFalse(service.setAlwaysOnEffect(UID, PACKAGE_NAME, 2, stereo, ALARM_ATTRS));

        verify(vibratorMock, never()).alwaysOnEnable(anyLong(), anyLong(), anyLong());
        verify(vibratorMock, never()).alwaysOnDisable(anyLong());
    }

    @Test
    public void vibrate_isUnsupported() {
        VibratorManagerService service = createService();
        CombinedVibrationEffect effect = CombinedVibrationEffect.createSynced(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK));
        assertExpectException(UnsupportedOperationException.class,
                "Not implemented",
                () -> service.vibrate(UID, PACKAGE_NAME, effect, ALARM_ATTRS, "reason", service));
    }

    @Test
    public void cancelVibrate_isUnsupported() {
        VibratorManagerService service = createService();
        CombinedVibrationEffect effect = CombinedVibrationEffect.createSynced(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK));
        assertExpectException(UnsupportedOperationException.class,
                "Not implemented", () -> service.cancelVibrate(service));
    }

    private VibratorController.NativeWrapper mockVibrator(int capabilities) {
        VibratorController.NativeWrapper wrapper = mock(VibratorController.NativeWrapper.class);
        when(wrapper.getCapabilities()).thenReturn((long) capabilities);
        return wrapper;
    }

    private void mockVibrators(VibratorController.NativeWrapper... wrappers) {
        int[] ids = new int[wrappers.length];
        for (int i = 0; i < wrappers.length; i++) {
            ids[i] = i;
            mNativeWrappers.put(i, wrappers[i]);
        }
        when(mNativeWrapperMock.getVibratorIds()).thenReturn(ids);
    }

    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }
}
