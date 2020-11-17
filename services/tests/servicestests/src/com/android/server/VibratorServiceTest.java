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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.gt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManagerInternal;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManager;
import android.hardware.vibrator.IVibrator;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.IVibratorStateListener;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.view.InputDevice;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.vibrator.VibratorController;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final VibrationAttributes HAPTIC_FEEDBACK_ATTRS =
            new VibrationAttributes.Builder().setUsage(
                    VibrationAttributes.USAGE_TOUCH).build();
    private static final VibrationAttributes NOTIFICATION_ATTRS =
            new VibrationAttributes.Builder().setUsage(
                    VibrationAttributes.USAGE_NOTIFICATION).build();
    private static final VibrationAttributes RINGTONE_ATTRS =
            new VibrationAttributes.Builder().setUsage(
                    VibrationAttributes.USAGE_RINGTONE).build();

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Mock private PackageManagerInternal mPackageManagerInternalMock;
    @Mock private PowerManagerInternal mPowerManagerInternalMock;
    @Mock private PowerSaveState mPowerSaveStateMock;
    // TODO(b/131311651): replace with a FakeVibrator instead.
    @Mock private Vibrator mVibratorMock;
    @Mock private AppOpsManager mAppOpsManagerMock;
    @Mock private VibratorController.NativeWrapper mNativeWrapperMock;
    @Mock private IVibratorStateListener mVibratorStateListenerMock;
    @Mock private IInputManager mIInputManagerMock;
    @Mock private IBinder mVibratorStateListenerBinderMock;

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        InputManager inputManager = InputManager.resetInstance(mIInputManagerMock);

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        when(mContextSpy.getSystemService(eq(Context.VIBRATOR_SERVICE))).thenReturn(mVibratorMock);
        when(mContextSpy.getSystemService(eq(Context.INPUT_SERVICE))).thenReturn(inputManager);
        when(mContextSpy.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManagerMock);
        when(mVibratorMock.getDefaultHapticFeedbackIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        when(mVibratorMock.getDefaultNotificationVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        when(mVibratorMock.getDefaultRingVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        when(mVibratorStateListenerMock.asBinder()).thenReturn(mVibratorStateListenerBinderMock);
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName("", ""));
        when(mPowerManagerInternalMock.getLowPowerState(PowerManager.ServiceType.VIBRATION))
                .thenReturn(mPowerSaveStateMock);
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[0]);

        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);

        addLocalServiceMock(PackageManagerInternal.class, mPackageManagerInternalMock);
        addLocalServiceMock(PowerManagerInternal.class, mPowerManagerInternalMock);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
    }

    private VibratorService createService() {
        VibratorService service = new VibratorService(mContextSpy,
                new VibratorService.Injector() {
                    @Override
                    VibratorController createVibratorController(
                            VibratorController.OnVibrationCompleteListener listener) {
                        return new VibratorController(0, listener, mNativeWrapperMock);
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
        service.systemReady();
        return service;
    }

    @Test
    public void createService_initializesNativeService() {
        createService();
        verify(mNativeWrapperMock).init(eq(0), notNull());
        verify(mNativeWrapperMock).off();
    }

    @Test
    public void hasVibrator_withVibratorHalPresent_returnsTrue() {
        when(mNativeWrapperMock.isAvailable()).thenReturn(true);
        assertTrue(createService().hasVibrator());
    }

    @Test
    public void hasVibrator_withNoVibratorHalPresent_returnsFalse() {
        when(mNativeWrapperMock.isAvailable()).thenReturn(false);
        assertFalse(createService().hasVibrator());
    }

    @Test
    public void hasAmplitudeControl_withAmplitudeControlSupport_returnsTrue() {
        mockVibratorCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        assertTrue(createService().hasAmplitudeControl());
    }

    @Test
    public void hasAmplitudeControl_withNoAmplitudeControlSupport_returnsFalse() {
        assertFalse(createService().hasAmplitudeControl());
    }

    @Test
    public void hasAmplitudeControl_withInputDevices_returnsTrue() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(1)).thenReturn(createInputDeviceWithVibrator(1));
        mockVibratorCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        assertTrue(createService().hasAmplitudeControl());
    }

    @Test
    public void getVibratorInfo_returnsSameInfoFromNative() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS | IVibrator.CAP_AMPLITUDE_CONTROL);
        when(mNativeWrapperMock.getSupportedEffects())
                .thenReturn(new int[]{VibrationEffect.EFFECT_CLICK});
        when(mNativeWrapperMock.getSupportedPrimitives())
                .thenReturn(new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK});

        VibratorInfo info = createService().getVibratorInfo();
        assertTrue(info.hasAmplitudeControl());
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                info.isEffectSupported(VibrationEffect.EFFECT_TICK));
        assertTrue(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_TICK));
    }

    @Test
    public void vibrate_withRingtone_usesRingtoneSettings() {
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0);
        vibrate(createService(), VibrationEffect.createOneShot(1, 1), RINGTONE_ATTRS);

        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 1);
        vibrate(createService(), VibrationEffect.createOneShot(10, 10), RINGTONE_ATTRS);

        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0);
        vibrate(createService(), VibrationEffect.createOneShot(100, 100), RINGTONE_ATTRS);

        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock, never()).on(eq(1L), anyLong());
        inOrderVerifier.verify(mNativeWrapperMock).on(eq(10L), anyLong());
        inOrderVerifier.verify(mNativeWrapperMock).on(eq(100L), anyLong());
    }

    @Test
    public void vibrate_withAudioAttributes_usesOriginalAudioUsageInAppOpsManager() {
        VibratorService service = createService();

        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build();
        VibrationAttributes vibrationAttributes = new VibrationAttributes.Builder(
                audioAttributes, effect).build();

        vibrate(service, effect, vibrationAttributes);

        verify(mAppOpsManagerMock).checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                eq(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY), anyInt(), anyString());
    }

    @Test
    public void vibrate_withVibrationAttributes_usesCorrespondingAudioUsageInAppOpsManager() {
        VibratorService service = createService();

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), ALARM_ATTRS);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_TICK), NOTIFICATION_ATTRS);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), RINGTONE_ATTRS);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_TICK), HAPTIC_FEEDBACK_ATTRS);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                new VibrationAttributes.Builder().setUsage(
                        VibrationAttributes.USAGE_COMMUNICATION_REQUEST).build());
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_TICK),
                new VibrationAttributes.Builder().setUsage(
                        VibrationAttributes.USAGE_UNKNOWN).build());

        InOrder inOrderVerifier = inOrder(mAppOpsManagerMock);
        inOrderVerifier.verify(mAppOpsManagerMock).checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                eq(AudioAttributes.USAGE_ALARM), anyInt(), anyString());
        inOrderVerifier.verify(mAppOpsManagerMock).checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                eq(AudioAttributes.USAGE_NOTIFICATION), anyInt(), anyString());
        inOrderVerifier.verify(mAppOpsManagerMock).checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                eq(AudioAttributes.USAGE_NOTIFICATION_RINGTONE), anyInt(), anyString());
        inOrderVerifier.verify(mAppOpsManagerMock).checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                eq(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION), anyInt(), anyString());
        inOrderVerifier.verify(mAppOpsManagerMock).checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                eq(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST),
                anyInt(), anyString());
        inOrderVerifier.verify(mAppOpsManagerMock).checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                eq(AudioAttributes.USAGE_UNKNOWN), anyInt(), anyString());
    }

    @Test
    public void vibrate_withOneShotAndInputDevices_vibratesInputDevices() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(1)).thenReturn(createInputDeviceWithVibrator(1));
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.createOneShot(100, 128);
        vibrate(service, effect);
        assertFalse(service.isVibrating());

        verify(mIInputManagerMock).vibrate(eq(1), eq(effect), any());
        verify(mNativeWrapperMock, never()).on(anyLong(), anyLong());
    }

    @Test
    public void vibrate_withOneShotAndAmplitudeControl_turnsVibratorOnAndSetsAmplitude() {
        mockVibratorCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createOneShot(100, 128));
        assertTrue(service.isVibrating());

        verify(mNativeWrapperMock).off();
        verify(mNativeWrapperMock).on(eq(100L), gt(0L));
        verify(mNativeWrapperMock).setAmplitude(eq(128));
    }

    @Test
    public void vibrate_withOneShotAndNoAmplitudeControl_turnsVibratorOnAndIgnoresAmplitude() {
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createOneShot(100, 128));
        assertTrue(service.isVibrating());

        verify(mNativeWrapperMock).off();
        verify(mNativeWrapperMock).on(eq(100L), gt(0L));
        verify(mNativeWrapperMock, never()).setAmplitude(anyInt());
    }

    @Test
    public void vibrate_withPrebaked_performsEffect() {
        when(mNativeWrapperMock.getSupportedEffects())
                .thenReturn(new int[]{VibrationEffect.EFFECT_CLICK});
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));

        verify(mNativeWrapperMock).off();
        verify(mNativeWrapperMock).perform(eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG), gt(0L));
    }

    @Test
    public void vibrate_withPrebakedAndInputDevices_vibratesFallbackWaveformOnInputDevices()
            throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(1)).thenReturn(createInputDeviceWithVibrator(1));
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK));
        assertFalse(service.isVibrating());

        // Wait for VibrateThread to turn input device vibrator ON.
        Thread.sleep(5);
        verify(mIInputManagerMock).vibrate(eq(1), any(), any());
        verify(mNativeWrapperMock, never()).on(anyLong(), anyLong());
        verify(mNativeWrapperMock, never()).perform(anyLong(), anyLong(), anyLong());
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

        verify(mNativeWrapperMock).off();
        verify(mNativeWrapperMock).compose(primitivesCaptor.capture(), gt(0L));

        // Check all primitive effect fields are passed down to the HAL.
        assertEquals(1, primitivesCaptor.getValue().length);
        VibrationEffect.Composition.PrimitiveEffect primitive = primitivesCaptor.getValue()[0];
        assertEquals(VibrationEffect.Composition.PRIMITIVE_CLICK, primitive.id);
        assertEquals(0.5f, primitive.scale, /* delta= */ 1e-2);
        assertEquals(10, primitive.delay);
    }

    @Test
    public void vibrate_withComposedAndInputDevices_vibratesInputDevices()
            throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1, 2});
        when(mIInputManagerMock.getInputDevice(1)).thenReturn(createInputDeviceWithVibrator(1));
        when(mIInputManagerMock.getInputDevice(2)).thenReturn(createInputDeviceWithVibrator(2));
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 10)
                .compose();
        vibrate(service, effect);
        assertFalse(service.isVibrating());

        verify(mIInputManagerMock).vibrate(eq(1), eq(effect), any());
        verify(mIInputManagerMock).vibrate(eq(2), eq(effect), any());
        verify(mNativeWrapperMock, never()).compose(any(), anyLong());
    }

    @Test
    public void vibrate_withWaveform_controlsVibratorAmplitudeDuringTotalVibrationTime()
            throws Exception {
        mockVibratorCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{10, 10, 10}, new int[]{100, 200, 50}, -1);
        vibrate(service, effect);

        // Wait for VibrateThread to finish: 10ms 100, 10ms 200, 10ms 50.
        Thread.sleep(40);
        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock).off();
        inOrderVerifier.verify(mNativeWrapperMock).on(eq(30L), anyLong());
        inOrderVerifier.verify(mNativeWrapperMock).setAmplitude(eq(100));
        inOrderVerifier.verify(mNativeWrapperMock).setAmplitude(eq(200));
        inOrderVerifier.verify(mNativeWrapperMock).setAmplitude(eq(50));
        inOrderVerifier.verify(mNativeWrapperMock).off();
    }

    @Test
    public void vibrate_withWaveform_totalVibrationTimeRespected() throws Exception {
        int totalDuration = 10_000; // 10s
        int stepDuration = 25; // 25ms

        // 25% of the first waveform step will be spent on the native on() call.
        mockVibratorCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        doAnswer(invocation -> {
            Thread.currentThread().sleep(stepDuration / 4);
            return null;
        }).when(mNativeWrapperMock).on(anyLong(), anyLong());
        // 25% of each waveform step will be spent on the native setAmplitude() call..
        doAnswer(invocation -> {
            Thread.currentThread().sleep(stepDuration / 4);
            return null;
        }).when(mNativeWrapperMock).setAmplitude(anyInt());

        VibratorService service = createService();

        int stepCount = totalDuration / stepDuration;
        long[] timings = new long[stepCount];
        int[] amplitudes = new int[stepCount];
        Arrays.fill(timings, stepDuration);
        Arrays.fill(amplitudes, VibrationEffect.DEFAULT_AMPLITUDE);
        VibrationEffect effect = VibrationEffect.createWaveform(timings, amplitudes, -1);

        int perceivedDuration = vibrateAndMeasure(service, effect, /* timeoutSecs= */ 15);
        int delay = Math.abs(perceivedDuration - totalDuration);

        // Allow some delay for thread scheduling and callback triggering.
        int maxDelay = (int) (0.05 * totalDuration); // < 5% of total duration
        assertTrue("Waveform with perceived delay of " + delay + "ms,"
                        + " expected less than " + maxDelay + "ms",
                delay < maxDelay);
    }

    @Test
    public void vibrate_withWaveformAndInputDevices_vibratesInputDevices() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(1)).thenReturn(createInputDeviceWithVibrator(1));
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{10, 10, 10}, new int[]{100, 200, 50}, -1);
        vibrate(service, effect);
        assertFalse(service.isVibrating());

        // Wait for VibrateThread to turn input device vibrator ON.
        Thread.sleep(5);
        verify(mIInputManagerMock).vibrate(eq(1), eq(effect), any());
        verify(mNativeWrapperMock, never()).on(anyLong(), anyLong());
    }

    @Test
    public void vibrate_withOneShotAndNativeCallbackTriggered_finishesVibration() {
        VibratorService service = createService();
        doAnswer(invocation -> {
            service.onVibrationComplete(invocation.getArgument(1));
            return null;
        }).when(mNativeWrapperMock).on(anyLong(), anyLong());
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));

        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock).off();
        inOrderVerifier.verify(mNativeWrapperMock).on(eq(100L), gt(0L));
        inOrderVerifier.verify(mNativeWrapperMock).off();
    }

    @Test
    public void vibrate_withPrebakedAndNativeCallbackTriggered_finishesVibration() {
        when(mNativeWrapperMock.getSupportedEffects())
                .thenReturn(new int[]{VibrationEffect.EFFECT_CLICK});
        VibratorService service = createService();
        doAnswer(invocation -> {
            service.onVibrationComplete(invocation.getArgument(2));
            return 10_000L; // 10s
        }).when(mNativeWrapperMock).perform(anyLong(), anyLong(), anyLong());
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));

        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock).off();
        inOrderVerifier.verify(mNativeWrapperMock).perform(
                eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG),
                gt(0L));
        inOrderVerifier.verify(mNativeWrapperMock).off();
    }

    @Test
    public void vibrate_withWaveformAndNativeCallback_callbackIgnoredAndWaveformPlaysCompletely()
            throws Exception {
        VibratorService service = createService();
        doAnswer(invocation -> {
            service.onVibrationComplete(invocation.getArgument(1));
            return null;
        }).when(mNativeWrapperMock).on(anyLong(), anyLong());
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{1, 3, 1, 2}, -1);
        vibrate(service, effect);

        // Wait for VibrateThread to finish: 1ms OFF, 3ms ON, 1ms OFF, 2ms ON.
        Thread.sleep(15);
        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock, times(2)).off();
        inOrderVerifier.verify(mNativeWrapperMock).on(eq(3L), anyLong());
        inOrderVerifier.verify(mNativeWrapperMock).off();
        inOrderVerifier.verify(mNativeWrapperMock).on(eq(2L), anyLong());
        inOrderVerifier.verify(mNativeWrapperMock).off();
    }

    @Test
    public void vibrate_withComposedAndNativeCallbackTriggered_finishesVibration() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        VibratorService service = createService();
        doAnswer(invocation -> {
            service.onVibrationComplete(invocation.getArgument(1));
            return null;
        }).when(mNativeWrapperMock).compose(any(), anyLong());
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 10)
                .compose();
        vibrate(service, effect);

        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock).off();
        inOrderVerifier.verify(mNativeWrapperMock).compose(
                any(VibrationEffect.Composition.PrimitiveEffect[].class), gt(0L));
        inOrderVerifier.verify(mNativeWrapperMock).off();
    }

    @Test
    public void cancelVibrate_withDeviceVibrating_callsoff() {
        VibratorService service = createService();
        vibrate(service, VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
        assertTrue(service.isVibrating());
        Mockito.clearInvocations(mNativeWrapperMock);

        service.cancelVibrate(service);
        assertFalse(service.isVibrating());
        verify(mNativeWrapperMock).off();
    }

    @Test
    public void cancelVibrate_withDeviceNotVibrating_ignoresCall() {
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        service.cancelVibrate(service);
        assertFalse(service.isVibrating());
        verify(mNativeWrapperMock, never()).off();
    }

    @Test
    public void registerVibratorStateListener_callbacksAreTriggered() throws Exception {
        VibratorService service = createService();
        service.registerVibratorStateListener(mVibratorStateListenerMock);

        vibrate(service, VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE));
        service.cancelVibrate(service);

        InOrder inOrderVerifier = inOrder(mVibratorStateListenerMock);
        // First notification done when listener is registered.
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(false);
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(eq(true));
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(eq(false));
        inOrderVerifier.verifyNoMoreInteractions();
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

    @Test
    public void scale_withPrebaked_userIntensitySettingAsEffectStrength() {
        // Alarm vibration is always VIBRATION_INTENSITY_HIGH.
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        VibratorService service = createService();

        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                ALARM_ATTRS);
        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                NOTIFICATION_ATTRS);
        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK),
                HAPTIC_FEEDBACK_ATTRS);
        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK),
                RINGTONE_ATTRS);

        verify(mNativeWrapperMock).perform(
                eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG), anyLong());
        verify(mNativeWrapperMock).perform(
                eq((long) VibrationEffect.EFFECT_TICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_MEDIUM), anyLong());
        verify(mNativeWrapperMock).perform(
                eq((long) VibrationEffect.EFFECT_DOUBLE_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_LIGHT), anyLong());
        verify(mNativeWrapperMock, never()).perform(
                eq((long) VibrationEffect.EFFECT_HEAVY_CLICK), anyLong(), anyLong());
    }

    @Test
    public void scale_withOneShotAndWaveform_usesScaleLevelOnAmplitude() throws Exception {
        when(mVibratorMock.getDefaultNotificationVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);

        mockVibratorCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorService service = createService();

        vibrate(service, VibrationEffect.createOneShot(20, 100), ALARM_ATTRS);
        vibrate(service, VibrationEffect.createOneShot(20, 100), NOTIFICATION_ATTRS);
        vibrate(service, VibrationEffect.createOneShot(20, 255), RINGTONE_ATTRS);
        vibrate(service, VibrationEffect.createWaveform(new long[] { 10 }, new int[] { 100 }, -1),
                HAPTIC_FEEDBACK_ATTRS);

        // Waveform effect runs on a separate thread.
        Thread.sleep(15);

        // Alarm vibration is never scaled.
        verify(mNativeWrapperMock).setAmplitude(eq(100));
        // Notification vibrations will be scaled with SCALE_VERY_HIGH.
        verify(mNativeWrapperMock).setAmplitude(intThat(amplitude -> amplitude > 150));
        // Haptic feedback vibrations will be scaled with SCALE_LOW.
        verify(mNativeWrapperMock).setAmplitude(
                intThat(amplitude -> amplitude < 100 && amplitude > 50));
        // Ringtone vibration is off.
        verify(mNativeWrapperMock, never()).setAmplitude(eq(255));
    }

    @Test
    public void scale_withComposed_usesScaleLevelOnPrimitiveScaleValues() {
        when(mVibratorMock.getDefaultNotificationVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);

        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        VibratorService service = createService();

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .compose();
        ArgumentCaptor<VibrationEffect.Composition.PrimitiveEffect[]> primitivesCaptor =
                ArgumentCaptor.forClass(VibrationEffect.Composition.PrimitiveEffect[].class);

        vibrate(service, effect, ALARM_ATTRS);
        vibrate(service, effect, NOTIFICATION_ATTRS);
        vibrate(service, effect, HAPTIC_FEEDBACK_ATTRS);
        vibrate(service, effect, RINGTONE_ATTRS);

        // Ringtone vibration is off, so only the other 3 are propagated to native.
        verify(mNativeWrapperMock, times(3)).compose(
                primitivesCaptor.capture(), anyLong());

        List<VibrationEffect.Composition.PrimitiveEffect[]> values =
                primitivesCaptor.getAllValues();

        // Alarm vibration is never scaled.
        assertEquals(1f, values.get(0)[0].scale, /* delta= */ 1e-2);
        assertEquals(0.5f, values.get(0)[1].scale, /* delta= */ 1e-2);

        // Notification vibrations will be scaled with SCALE_VERY_HIGH.
        assertEquals(1f, values.get(1)[0].scale, /* delta= */ 1e-2);
        assertTrue(0.7 < values.get(1)[1].scale);

        // Haptic feedback vibrations will be scaled with SCALE_LOW.
        assertTrue(0.5 < values.get(2)[0].scale);
        assertTrue(0.5 > values.get(2)[1].scale);
    }

    private void vibrate(VibratorService service, VibrationEffect effect) {
        vibrate(service, effect, ALARM_ATTRS);
    }

    private void vibrate(VibratorService service, VibrationEffect effect,
            VibrationAttributes attributes) {
        service.vibrate(UID, PACKAGE_NAME, effect, attributes, "some reason", service);
    }

    private int vibrateAndMeasure(
            VibratorService service, VibrationEffect effect, long timeoutSecs) throws Exception {
        AtomicLong startTime = new AtomicLong(0);
        AtomicLong endTime = new AtomicLong(0);
        CountDownLatch startedCount = new CountDownLatch(1);
        CountDownLatch finishedCount = new CountDownLatch(1);
        service.registerVibratorStateListener(new IVibratorStateListener() {
            @Override
            public void onVibrating(boolean vibrating) throws RemoteException {
                if (vibrating) {
                    startTime.set(SystemClock.uptimeMillis());
                    startedCount.countDown();
                } else if (startedCount.getCount() == 0) {
                    endTime.set(SystemClock.uptimeMillis());
                    finishedCount.countDown();
                }
            }

            @Override
            public IBinder asBinder() {
                return mVibratorStateListenerBinderMock;
            }
        });

        vibrate(service, effect);

        assertTrue(finishedCount.await(timeoutSecs, TimeUnit.SECONDS));
        return (int) (endTime.get() - startTime.get());
    }

    private void mockVibratorCapabilities(int capabilities) {
        when(mNativeWrapperMock.getCapabilities()).thenReturn((long) capabilities);
    }

    private InputDevice createInputDeviceWithVibrator(int id) {
        return new InputDevice(id, 0, 0, "name", 0, 0, "description", false, 0, 0,
                null, /* hasVibrator= */ true, false, false);
    }

    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private void setRingerMode(int ringerMode) {
        AudioManager audioManager = mContextSpy.getSystemService(AudioManager.class);
        audioManager.setRingerModeInternal(ringerMode);
        assertEquals(ringerMode, audioManager.getRingerModeInternal());
    }

    private void setUserSetting(String settingName, int value) {
        Settings.System.putIntForUser(
                mContextSpy.getContentResolver(), settingName, value, UserHandle.USER_CURRENT);
    }

    private void setGlobalSetting(String settingName, int value) {
        Settings.Global.putInt(mContextSpy.getContentResolver(), settingName, value);
    }
}
