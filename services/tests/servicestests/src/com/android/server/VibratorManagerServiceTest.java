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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;

import com.android.server.vibrator.FakeVibratorControllerProvider;
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

    private final Map<Integer, FakeVibratorControllerProvider> mVibratorProviders = new HashMap<>();

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
                        return mVibratorProviders.get(vibratorId)
                                .newVibratorController(vibratorId, listener);
                    }
                });
        service.systemReady();
        return service;
    }

    @Test
    public void createService_initializesNativeManagerServiceAndVibrators() {
        mockVibrators(1, 2);
        createService();
        verify(mNativeWrapperMock).init();
        assertTrue(mVibratorProviders.get(1).isInitialized());
        assertTrue(mVibratorProviders.get(2).isInitialized());
    }

    @Test
    public void getVibratorIds_withNullResultFromNative_returnsEmptyArray() {
        when(mNativeWrapperMock.getVibratorIds()).thenReturn(null);
        assertArrayEquals(new int[0], createService().getVibratorIds());
    }

    @Test
    public void getVibratorIds_withNonEmptyResultFromNative_returnsSameArray() {
        mockVibrators(2, 1);
        assertArrayEquals(new int[]{2, 1}, createService().getVibratorIds());
    }

    @Test
    public void getVibratorInfo_withMissingVibratorId_returnsNull() {
        mockVibrators(1);
        assertNull(createService().getVibratorInfo(2));
    }

    @Test
    public void getVibratorInfo_withExistingVibratorId_returnsHalInfoForVibrator() {
        mockVibrators(1);
        FakeVibratorControllerProvider vibrator = mVibratorProviders.get(1);
        vibrator.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS, IVibrator.CAP_AMPLITUDE_CONTROL);
        vibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        vibrator.setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK);
        VibratorInfo info = createService().getVibratorInfo(1);

        assertNotNull(info);
        assertEquals(1, info.getId());
        assertTrue(info.hasAmplitudeControl());
        assertTrue(info.hasCapability(IVibrator.CAP_COMPOSE_EFFECTS));
        assertFalse(info.hasCapability(IVibrator.CAP_ON_CALLBACK));
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                info.isEffectSupported(VibrationEffect.EFFECT_TICK));
        assertTrue(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_TICK));
    }

    @Test
    public void setAlwaysOnEffect_withMono_enablesAlwaysOnEffectToAllVibratorsWithCapability() {
        mockVibrators(1, 2, 3);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);
        mVibratorProviders.get(3).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        CombinedVibrationEffect effect = CombinedVibrationEffect.createSynced(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        assertTrue(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        VibrationEffect.Prebaked expectedEffect = new VibrationEffect.Prebaked(
                VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_STRONG);

        // Only vibrators 1 and 3 have always-on capabilities.
        assertEquals(mVibratorProviders.get(1).getAlwaysOnEffect(1), expectedEffect);
        assertNull(mVibratorProviders.get(2).getAlwaysOnEffect(1));
        assertEquals(mVibratorProviders.get(3).getAlwaysOnEffect(1), expectedEffect);
    }

    @Test
    public void setAlwaysOnEffect_withStereo_enablesAlwaysOnEffectToAllVibratorsWithCapability() {
        mockVibrators(1, 2, 3, 4);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);
        mVibratorProviders.get(4).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        CombinedVibrationEffect effect = CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                .addVibrator(3, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                .combine();
        assertTrue(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        VibrationEffect.Prebaked expectedClick = new VibrationEffect.Prebaked(
                VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_STRONG);

        VibrationEffect.Prebaked expectedTick = new VibrationEffect.Prebaked(
                VibrationEffect.EFFECT_TICK, false, VibrationEffect.EFFECT_STRENGTH_STRONG);

        // Enables click on vibrator 1 and tick on vibrator 2 only.
        assertEquals(mVibratorProviders.get(1).getAlwaysOnEffect(1), expectedClick);
        assertEquals(mVibratorProviders.get(2).getAlwaysOnEffect(1), expectedTick);
        assertNull(mVibratorProviders.get(3).getAlwaysOnEffect(1));
        assertNull(mVibratorProviders.get(4).getAlwaysOnEffect(1));
    }

    @Test
    public void setAlwaysOnEffect_withNullEffect_disablesAlwaysOnEffects() {
        mockVibrators(1, 2, 3);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);
        mVibratorProviders.get(3).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        CombinedVibrationEffect effect = CombinedVibrationEffect.createSynced(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        assertTrue(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        assertTrue(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, null, ALARM_ATTRS));

        assertNull(mVibratorProviders.get(1).getAlwaysOnEffect(1));
        assertNull(mVibratorProviders.get(2).getAlwaysOnEffect(1));
        assertNull(mVibratorProviders.get(3).getAlwaysOnEffect(1));
    }

    @Test
    public void setAlwaysOnEffect_withNonPrebakedEffect_ignoresEffect() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        CombinedVibrationEffect effect = CombinedVibrationEffect.createSynced(
                VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
        assertFalse(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        assertNull(mVibratorProviders.get(1).getAlwaysOnEffect(1));
    }

    @Test
    public void setAlwaysOnEffect_withNonSyncedEffect_ignoresEffect() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        CombinedVibrationEffect effect = CombinedVibrationEffect.startSequential()
                .addNext(0, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .combine();
        assertFalse(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        assertNull(mVibratorProviders.get(1).getAlwaysOnEffect(1));
    }

    @Test
    public void setAlwaysOnEffect_withNoVibratorWithCapability_ignoresEffect() {
        mockVibrators(1);
        VibratorManagerService service = createService();

        CombinedVibrationEffect mono = CombinedVibrationEffect.createSynced(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        CombinedVibrationEffect stereo = CombinedVibrationEffect.startSynced()
                .addVibrator(0, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                .combine();
        assertFalse(service.setAlwaysOnEffect(UID, PACKAGE_NAME, 1, mono, ALARM_ATTRS));
        assertFalse(service.setAlwaysOnEffect(UID, PACKAGE_NAME, 2, stereo, ALARM_ATTRS));

        assertNull(mVibratorProviders.get(1).getAlwaysOnEffect(1));
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

    private void mockVibrators(int... vibratorIds) {
        for (int vibratorId : vibratorIds) {
            mVibratorProviders.put(vibratorId,
                    new FakeVibratorControllerProvider(mTestLooper.getLooper()));
        }
        when(mNativeWrapperMock.getVibratorIds()).thenReturn(vibratorIds);
    }

    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }
}
