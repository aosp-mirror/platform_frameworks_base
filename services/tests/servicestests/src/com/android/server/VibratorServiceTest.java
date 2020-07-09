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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.PackageManagerInternal;
import android.hardware.vibrator.IVibrator;
import android.os.Handler;
import android.os.IBinder;
import android.os.IVibratorStateListener;
import android.os.Looper;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link VibratorService}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibratorServiceTest
 */
@Presubmit
public class VibratorServiceTest {

    private static final int UID = Process.ROOT_UID;
    private static final String PACKAGE_NAME = "package";
    private static final VibrationAttributes ALARM_ATTRS =
            new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build();

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Mock private PackageManagerInternal mPackageManagerInternalMock;
    @Mock private PowerManagerInternal mPowerManagerInternalMock;
    @Mock private VibratorService.NativeWrapper mNativeWrapperMock;
    @Mock private IVibratorStateListener mVibratorStateListenerMock;
    @Mock private IBinder mVibratorStateListenerBinderMock;

    private TestLooper mTestLooper;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();

        when(mVibratorStateListenerMock.asBinder()).thenReturn(mVibratorStateListenerBinderMock);
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName("", ""));

        addLocalServiceMock(PackageManagerInternal.class, mPackageManagerInternalMock);
        addLocalServiceMock(PowerManagerInternal.class, mPowerManagerInternalMock);
    }

    private VibratorService createService() {
        return new VibratorService(InstrumentationRegistry.getContext(),
                new VibratorService.Injector() {
                    @Override
                    VibratorService.NativeWrapper getNativeWrapper() {
                        return mNativeWrapperMock;
                    }

                    @Override
                    Handler createHandler(Looper looper) {
                        return new Handler(mTestLooper.getLooper());
                    }

                    @Override
                    void addService(String name, IBinder service) {
                        // ignore
                    }
                });
    }

    @Test
    public void createService_initializesNativeService() {
        createService();
        verify(mNativeWrapperMock).vibratorInit();
        verify(mNativeWrapperMock).vibratorOff();
    }

    @Test
    public void hasVibrator_withVibratorHalPresent_returnsTrue() {
        when(mNativeWrapperMock.vibratorExists()).thenReturn(true);
        assertTrue(createService().hasVibrator());
    }

    @Test
    public void hasVibrator_withNoVibratorHalPresent_returnsFalse() {
        when(mNativeWrapperMock.vibratorExists()).thenReturn(false);
        assertFalse(createService().hasVibrator());
    }

    @Test
    public void hasAmplitudeControl_withAmplitudeControlSupport_returnsTrue() {
        when(mNativeWrapperMock.vibratorSupportsAmplitudeControl()).thenReturn(true);
        assertTrue(createService().hasAmplitudeControl());
    }

    @Test
    public void hasAmplitudeControl_withNoAmplitudeControlSupport_returnsFalse() {
        when(mNativeWrapperMock.vibratorSupportsAmplitudeControl()).thenReturn(false);
        assertFalse(createService().hasAmplitudeControl());
    }

    @Test
    public void areEffectsSupported_withNullResultFromNative_returnsSupportUnknown() {
        when(mNativeWrapperMock.vibratorGetSupportedEffects()).thenReturn(null);
        assertArrayEquals(new int[]{Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN},
                createService().areEffectsSupported(new int[]{VibrationEffect.EFFECT_CLICK}));
    }

    @Test
    public void areEffectsSupported_withSomeEffectsSupported_returnsSupportYesAndNoForEffects() {
        int[] effects = new int[]{VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_TICK};

        when(mNativeWrapperMock.vibratorGetSupportedEffects())
                .thenReturn(new int[]{VibrationEffect.EFFECT_CLICK});
        assertArrayEquals(
                new int[]{Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                        Vibrator.VIBRATION_EFFECT_SUPPORT_NO},
                createService().areEffectsSupported(effects));
    }

    @Test
    public void arePrimitivesSupported_withoutComposeCapability_returnsAlwaysFalse() {
        assertArrayEquals(new boolean[]{false, false},
                createService().arePrimitivesSupported(new int[]{
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_TICK
                }));
    }

    @Test
    public void arePrimitivesSupported_withComposeCapability_returnsAlwaysTrue() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        assertArrayEquals(new boolean[]{true, true},
                createService().arePrimitivesSupported(new int[]{
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                }));
    }

    @Test
    public void setAlwaysOnEffect_withCapabilityAndValidEffect_enablesAlwaysOnEffect() {
        mockVibratorCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        assertTrue(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK), ALARM_ATTRS));
        verify(mNativeWrapperMock).vibratorAlwaysOnEnable(
                eq(1L), eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG));
    }

    @Test
    public void setAlwaysOnEffect_withNonPrebakedEffect_ignoresEffect() {
        mockVibratorCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        assertFalse(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1,
                VibrationEffect.createOneShot(100, 255), ALARM_ATTRS));
        verify(mNativeWrapperMock, never()).vibratorAlwaysOnDisable(anyLong());
        verify(mNativeWrapperMock, never()).vibratorAlwaysOnEnable(anyLong(), anyLong(), anyLong());
    }

    @Test
    public void setAlwaysOnEffect_withNullEffect_disablesAlwaysOnEffect() {
        mockVibratorCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        assertTrue(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, null, ALARM_ATTRS));
        verify(mNativeWrapperMock).vibratorAlwaysOnDisable(eq(1L));
    }

    @Test
    public void setAlwaysOnEffect_withoutCapability_ignoresEffect() {
        assertFalse(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1,
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK), ALARM_ATTRS));
        verify(mNativeWrapperMock, never()).vibratorAlwaysOnDisable(anyLong());
        verify(mNativeWrapperMock, never()).vibratorAlwaysOnEnable(anyLong(), anyLong(), anyLong());
    }

    @Test
    public void vibrate_withOneShotAndAmplitudeControl_turnsVibratorOnAndSetsAmplitude() {
        when(mNativeWrapperMock.vibratorSupportsAmplitudeControl()).thenReturn(true);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createOneShot(100, 128));
        assertTrue(service.isVibrating());

        verify(mNativeWrapperMock).vibratorOff();
        verify(mNativeWrapperMock).vibratorOn(eq(100L));
        verify(mNativeWrapperMock).vibratorSetAmplitude(eq(128));
    }

    @Test
    public void vibrate_withOneShotAndNoAmplitudeControl_turnsVibratorOnAndIgnoresAmplitude() {
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createOneShot(100, 128));
        assertTrue(service.isVibrating());

        verify(mNativeWrapperMock).vibratorOff();
        verify(mNativeWrapperMock).vibratorOn(eq(100L));
        verify(mNativeWrapperMock, never()).vibratorSetAmplitude(anyInt());
    }

    @Test
    public void vibrate_withPrebaked_performsEffect() {
        when(mNativeWrapperMock.vibratorGetSupportedEffects())
                .thenReturn(new int[]{VibrationEffect.EFFECT_CLICK});
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));

        verify(mNativeWrapperMock).vibratorOff();
        verify(mNativeWrapperMock).vibratorPerformEffect(
                eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG),
                any(VibratorService.Vibration.class), eq(false));
    }

    @Test
    public void vibrate_withComposed_performsEffect() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 10)
                .compose();
        vibrate(service, effect);

        ArgumentCaptor<VibrationEffect.Composition.PrimitiveEffect[]> primitivesCaptor =
                ArgumentCaptor.forClass(VibrationEffect.Composition.PrimitiveEffect[].class);

        verify(mNativeWrapperMock).vibratorOff();
        verify(mNativeWrapperMock).vibratorPerformComposedEffect(
                primitivesCaptor.capture(), any(VibratorService.Vibration.class));

        // Check all primitive effect fields are passed down to the HAL.
        assertEquals(1, primitivesCaptor.getValue().length);
        VibrationEffect.Composition.PrimitiveEffect primitive = primitivesCaptor.getValue()[0];
        assertEquals(VibrationEffect.Composition.PRIMITIVE_CLICK, primitive.id);
        assertEquals(0.5f, primitive.scale, /* delta= */ 1e-2);
        assertEquals(10, primitive.delay);
    }

    @Test
    public void vibrate_withCallback_finishesVibrationWhenCallbackTriggered() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        doAnswer(invocation -> {
            ((VibratorService.Vibration) invocation.getArgument(1)).onComplete();
            return null;
        }).when(mNativeWrapperMock).vibratorPerformComposedEffect(
                any(), any(VibratorService.Vibration.class));

        // Use vibration with delay so there is time for the callback to be triggered.
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 10)
                .compose();
        vibrate(service, effect);

        // Vibration canceled once before perform and once by native callback.
        verify(mNativeWrapperMock, times(2)).vibratorOff();
        verify(mNativeWrapperMock).vibratorPerformComposedEffect(
                any(VibrationEffect.Composition.PrimitiveEffect[].class),
                any(VibratorService.Vibration.class));
    }

    @Test
    public void vibrate_whenBinderDies_cancelsVibration() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        doAnswer(invocation -> {
            ((VibratorService.Vibration) invocation.getArgument(1)).binderDied();
            return null;
        }).when(mNativeWrapperMock).vibratorPerformComposedEffect(
                any(), any(VibratorService.Vibration.class));

        // Use vibration with delay so there is time for the callback to be triggered.
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 10)
                .compose();
        vibrate(service, effect);

        // Vibration canceled once before perform and once by native binder death.
        verify(mNativeWrapperMock, times(2)).vibratorOff();
        verify(mNativeWrapperMock).vibratorPerformComposedEffect(
                any(VibrationEffect.Composition.PrimitiveEffect[].class),
                any(VibratorService.Vibration.class));
    }

    @Test
    public void cancelVibrate_withDeviceVibrating_callsVibratorOff() {
        VibratorService service = createService();
        vibrate(service, VibrationEffect.createOneShot(100, 128));
        assertTrue(service.isVibrating());
        Mockito.clearInvocations(mNativeWrapperMock);

        service.cancelVibrate(service);
        assertFalse(service.isVibrating());
        verify(mNativeWrapperMock).vibratorOff();
    }

    @Test
    public void cancelVibrate_withDeviceNotVibrating_ignoresCall() {
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        service.cancelVibrate(service);
        assertFalse(service.isVibrating());
        verify(mNativeWrapperMock, never()).vibratorOff();
    }

    @Test
    public void registerVibratorStateListener_callbacksAreTriggered() throws Exception {
        VibratorService service = createService();

        service.registerVibratorStateListener(mVibratorStateListenerMock);
        verify(mVibratorStateListenerMock).onVibrating(false);

        vibrate(service, VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE));
        verify(mVibratorStateListenerMock).onVibrating(true);

        // Run the scheduled callback to finish one-shot vibration.
        mTestLooper.moveTimeForward(10);
        mTestLooper.dispatchAll();
        verify(mVibratorStateListenerMock, times(2)).onVibrating(false);
    }

    @Test
    public void unregisterVibratorStateListener_callbackNotTriggeredAfter() throws Exception {
        VibratorService service = createService();

        service.registerVibratorStateListener(mVibratorStateListenerMock);
        verify(mVibratorStateListenerMock).onVibrating(false);

        vibrate(service, VibrationEffect.createOneShot(5, VibrationEffect.DEFAULT_AMPLITUDE));
        verify(mVibratorStateListenerMock).onVibrating(true);

        service.unregisterVibratorStateListener(mVibratorStateListenerMock);
        Mockito.clearInvocations(mVibratorStateListenerMock);

        vibrate(service, VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE));
        verifyNoMoreInteractions(mVibratorStateListenerMock);
    }

    private void vibrate(VibratorService service, VibrationEffect effect) {
        service.vibrate(UID, PACKAGE_NAME, effect, ALARM_ATTRS, "some reason", service);
    }

    private void mockVibratorCapabilities(int capabilities) {
        when(mNativeWrapperMock.vibratorGetCapabilities()).thenReturn((long) capabilities);
    }

    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }
}
