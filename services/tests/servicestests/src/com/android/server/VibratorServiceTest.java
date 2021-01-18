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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
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
import com.android.server.vibrator.FakeVibrator;
import com.android.server.vibrator.FakeVibratorControllerProvider;
import com.android.server.vibrator.VibratorController;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Tests for {@link VibratorService}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibratorServiceTest
 */
@Presubmit
public class VibratorServiceTest {

    private static final int UID = Process.ROOT_UID;
    private static final int VIBRATOR_ID = 1;
    private static final String PACKAGE_NAME = "package";
    private static final PowerSaveState NORMAL_POWER_STATE = new PowerSaveState.Builder().build();
    private static final PowerSaveState LOW_POWER_STATE = new PowerSaveState.Builder()
            .setBatterySaverEnabled(true).build();
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
    @Mock private AppOpsManager mAppOpsManagerMock;
    @Mock private IVibratorStateListener mVibratorStateListenerMock;
    @Mock private IInputManager mIInputManagerMock;
    @Mock private IBinder mVibratorStateListenerBinderMock;

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;
    private PowerManagerInternal.LowPowerModeListener mRegisteredPowerModeListener;
    private FakeVibrator mFakeVibrator;
    private FakeVibratorControllerProvider mVibratorProvider;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mFakeVibrator = new FakeVibrator();
        mVibratorProvider = new FakeVibratorControllerProvider(mTestLooper.getLooper());
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        InputManager inputManager = InputManager.resetInstance(mIInputManagerMock);

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        when(mContextSpy.getSystemService(eq(Context.VIBRATOR_SERVICE))).thenReturn(mFakeVibrator);
        when(mContextSpy.getSystemService(eq(Context.INPUT_SERVICE))).thenReturn(inputManager);
        when(mContextSpy.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManagerMock);
        when(mVibratorStateListenerMock.asBinder()).thenReturn(mVibratorStateListenerBinderMock);
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName("", ""));
        doAnswer(invocation -> {
            mRegisteredPowerModeListener = invocation.getArgument(0);
            return null;
        }).when(mPowerManagerInternalMock).registerLowPowerModeObserver(any());
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
        InputManager.clearInstance();
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
    }

    private VibratorService createService() {
        VibratorService service = new VibratorService(mContextSpy,
                new VibratorService.Injector() {
                    @Override
                    VibratorController createVibratorController(
                            VibratorController.OnVibrationCompleteListener listener) {
                        return mVibratorProvider.newVibratorController(VIBRATOR_ID, listener);
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
        assertTrue(mVibratorProvider.isInitialized());
    }

    @Test
    public void hasVibrator_withVibratorHalPresent_returnsTrue() {
        assertTrue(createService().hasVibrator());
    }

    @Test
    public void hasVibrator_withNoVibratorHalPresent_returnsFalse() {
        mVibratorProvider.disableVibrators();
        assertFalse(createService().hasVibrator());
    }

    @Test
    public void hasAmplitudeControl_withAmplitudeControlSupport_returnsTrue() {
        mVibratorProvider.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
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
        mVibratorProvider.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        assertTrue(createService().hasAmplitudeControl());
    }

    @Test
    public void getVibratorInfo_returnsSameInfoFromNative() {
        mVibratorProvider.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS,
                IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProvider.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProvider.setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK);

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
    public void vibrate_withRingtone_usesRingtoneSettings() throws Exception {
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0);
        vibrate(createService(), VibrationEffect.createOneShot(1, 1), RINGTONE_ATTRS);

        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 1);
        vibrateAndWait(createService(), VibrationEffect.createOneShot(10, 10), RINGTONE_ATTRS);

        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0);
        vibrateAndWait(createService(), VibrationEffect.createOneShot(100, 100), RINGTONE_ATTRS);

        List<VibrationEffect> effects = mVibratorProvider.getEffects();
        assertEquals(2, effects.size());
        assertEquals(10, effects.get(0).getDuration());
        assertEquals(100, effects.get(1).getDuration());
    }

    @Test
    public void vibrate_withPowerModeChange_usesLowPowerModeState() throws Exception {
        VibratorService service = createService();
        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);
        vibrate(service, VibrationEffect.createOneShot(1, 1), HAPTIC_FEEDBACK_ATTRS);
        vibrateAndWait(service, VibrationEffect.createOneShot(2, 2), RINGTONE_ATTRS);

        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);
        vibrateAndWait(service, VibrationEffect.createOneShot(3, 3), /* attributes= */ null);
        vibrateAndWait(service, VibrationEffect.createOneShot(4, 4), NOTIFICATION_ATTRS);

        List<VibrationEffect> effects = mVibratorProvider.getEffects();
        assertEquals(3, effects.size());
        assertEquals(2, effects.get(0).getDuration());
        assertEquals(3, effects.get(1).getDuration());
        assertEquals(4, effects.get(2).getDuration());
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

        VibrationEffect effect = VibrationEffect.createOneShot(100, 128);
        vibrate(service, effect, ALARM_ATTRS);
        verify(mIInputManagerMock).vibrate(eq(1), eq(effect), any());

        // VibrationThread will start this vibration async, so wait before checking it never played.
        Thread.sleep(10);
        assertTrue(mVibratorProvider.getEffects().isEmpty());
    }

    @Test
    public void vibrate_withOneShotAndAmplitudeControl_turnsVibratorOnAndSetsAmplitude()
            throws Exception {
        mVibratorProvider.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorService service = createService();

        vibrateAndWait(service, VibrationEffect.createOneShot(100, 128), ALARM_ATTRS);

        List<VibrationEffect> effects = mVibratorProvider.getEffects();
        assertEquals(1, effects.size());
        assertEquals(100, effects.get(0).getDuration());
        assertEquals(Arrays.asList(128), mVibratorProvider.getAmplitudes());
    }

    @Test
    public void vibrate_withOneShotAndNoAmplitudeControl_turnsVibratorOnAndIgnoresAmplitude()
            throws Exception {
        VibratorService service = createService();
        clearInvocations();

        vibrateAndWait(service, VibrationEffect.createOneShot(100, 128), ALARM_ATTRS);

        List<VibrationEffect> effects = mVibratorProvider.getEffects();
        assertEquals(1, effects.size());
        assertEquals(100, effects.get(0).getDuration());
        assertTrue(mVibratorProvider.getAmplitudes().isEmpty());
    }

    @Test
    public void vibrate_withPrebaked_performsEffect() throws Exception {
        mVibratorProvider.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        VibratorService service = createService();

        VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
        vibrateAndWait(service, effect, ALARM_ATTRS);

        VibrationEffect.Prebaked expectedEffect = new VibrationEffect.Prebaked(
                VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_STRONG);
        assertEquals(Arrays.asList(expectedEffect), mVibratorProvider.getEffects());
    }

    @Test
    public void vibrate_withPrebakedAndInputDevices_vibratesFallbackWaveformOnInputDevices()
            throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(1)).thenReturn(createInputDeviceWithVibrator(1));
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        VibratorService service = createService();

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), ALARM_ATTRS);
        verify(mIInputManagerMock).vibrate(eq(1), any(), any());

        // VibrationThread will start this vibration async, so wait before checking it never played.
        Thread.sleep(10);
        assertTrue(mVibratorProvider.getEffects().isEmpty());
    }

    @Test
    public void vibrate_enteringLowPowerMode_cancelVibration() throws Exception {
        VibratorService service = createService();

        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);
        vibrate(service, VibrationEffect.createOneShot(1000, 100), HAPTIC_FEEDBACK_ATTRS);

        // VibrationThread will start this vibration async, so wait before triggering callbacks.
        Thread.sleep(10);
        assertTrue(service.isVibrating());

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        // Wait for callback to cancel vibration.
        Thread.sleep(10);
        assertFalse(service.isVibrating());
    }

    @Test
    public void vibrate_enteringLowPowerModeAndRingtone_doNotCancelVibration() throws Exception {
        VibratorService service = createService();

        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);
        vibrate(service, VibrationEffect.createOneShot(1000, 100), RINGTONE_ATTRS);

        // VibrationThread will start this vibration async, so wait before triggering callbacks.
        Thread.sleep(10);
        assertTrue(service.isVibrating());

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        // Wait for callback to cancel vibration.
        Thread.sleep(10);
        assertTrue(service.isVibrating());
    }

    @Test
    public void vibrate_withSettingsChanged_doNotCancelVibration() throws Exception {
        VibratorService service = createService();
        vibrate(service, VibrationEffect.createOneShot(1000, 100), HAPTIC_FEEDBACK_ATTRS);

        // VibrationThread will start this vibration async, so wait before triggering callbacks.
        Thread.sleep(10);
        assertTrue(service.isVibrating());

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);

        // FakeSettingsProvider don't support testing triggering ContentObserver yet.
        service.updateVibrators();

        // Wait for callback to cancel vibration.
        Thread.sleep(10);
        assertTrue(service.isVibrating());
    }

    @Test
    public void vibrate_withComposed_performsEffect() throws Exception {
        mVibratorProvider.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        VibratorService service = createService();

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 10)
                .compose();
        vibrateAndWait(service, effect, ALARM_ATTRS);
        assertEquals(Arrays.asList(effect), mVibratorProvider.getEffects());
    }

    @Test
    public void vibrate_withComposedAndInputDevices_vibratesInputDevices() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1, 2});
        when(mIInputManagerMock.getInputDevice(1)).thenReturn(createInputDeviceWithVibrator(1));
        when(mIInputManagerMock.getInputDevice(2)).thenReturn(createInputDeviceWithVibrator(2));
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        VibratorService service = createService();

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 10)
                .compose();
        vibrate(service, effect, ALARM_ATTRS);
        InOrder inOrderVerifier = inOrder(mIInputManagerMock);
        inOrderVerifier.verify(mIInputManagerMock).vibrate(eq(1), eq(effect), any());
        inOrderVerifier.verify(mIInputManagerMock).vibrate(eq(2), eq(effect), any());

        // VibrationThread will start this vibration async, so wait before checking it never played.
        Thread.sleep(10);
        assertTrue(mVibratorProvider.getEffects().isEmpty());
    }

    @Test
    public void vibrate_withWaveform_controlsVibratorAmplitudeDuringTotalVibrationTime()
            throws Exception {
        mVibratorProvider.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorService service = createService();

        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{10, 10, 10}, new int[]{100, 200, 50}, -1);
        vibrateAndWait(service, effect, ALARM_ATTRS);

        assertEquals(Arrays.asList(100, 200, 50), mVibratorProvider.getAmplitudes());
        assertEquals(
                Arrays.asList(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)),
                mVibratorProvider.getEffects());
    }

    @Test
    public void vibrate_withWaveformAndInputDevices_vibratesInputDevices() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(1)).thenReturn(createInputDeviceWithVibrator(1));
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        VibratorService service = createService();

        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{10, 10, 10}, new int[]{100, 200, 50}, -1);
        vibrate(service, effect, ALARM_ATTRS);
        verify(mIInputManagerMock).vibrate(eq(1), eq(effect), any());

        // VibrationThread will start this vibration async, so wait before checking it never played.
        Thread.sleep(10);
        assertTrue(mVibratorProvider.getEffects().isEmpty());
    }

    @Test
    public void vibrate_withNativeCallbackTriggered_finishesVibration() throws Exception {
        mVibratorProvider.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        VibratorService service = createService();

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), ALARM_ATTRS);

        // VibrationThread will start this vibration async, so wait before triggering callbacks.
        Thread.sleep(10);
        assertTrue(service.isVibrating());

        // Trigger callbacks from controller.
        mTestLooper.moveTimeForward(50);
        mTestLooper.dispatchAll();

        // VibrationThread needs some time to react to native callbacks and stop the vibrator.
        Thread.sleep(10);
        assertFalse(service.isVibrating());
    }

    @Test
    public void cancelVibrate_withDeviceVibrating_callsOff() throws Exception {
        VibratorService service = createService();

        vibrate(service, VibrationEffect.createOneShot(100, 100), ALARM_ATTRS);

        // VibrationThread will start this vibration async, so wait before checking.
        Thread.sleep(10);
        assertTrue(service.isVibrating());

        service.cancelVibrate(service);

        // VibrationThread will stop this vibration async, so wait before checking.
        Thread.sleep(10);
        assertFalse(service.isVibrating());
    }

    @Test
    public void registerVibratorStateListener_callbacksAreTriggered() throws Exception {
        VibratorService service = createService();
        service.registerVibratorStateListener(mVibratorStateListenerMock);

        vibrateAndWait(service, VibrationEffect.createOneShot(100, 100), ALARM_ATTRS);

        InOrder inOrderVerifier = inOrder(mVibratorStateListenerMock);
        // First notification done when listener is registered.
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(eq(false));
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(eq(true));
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(eq(false));
        inOrderVerifier.verifyNoMoreInteractions();
    }

    @Test
    public void unregisterVibratorStateListener_callbackNotTriggeredAfter() throws Exception {
        VibratorService service = createService();

        service.registerVibratorStateListener(mVibratorStateListenerMock);
        verify(mVibratorStateListenerMock).onVibrating(false);

        vibrate(service, VibrationEffect.createOneShot(100, 100), ALARM_ATTRS);

        // VibrationThread will start this vibration async, so wait before triggering callbacks.
        Thread.sleep(10);
        service.unregisterVibratorStateListener(mVibratorStateListenerMock);
        // Trigger callbacks from controller.
        mTestLooper.moveTimeForward(150);
        mTestLooper.dispatchAll();

        InOrder inOrderVerifier = inOrder(mVibratorStateListenerMock);
        // First notification done when listener is registered.
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(eq(false));
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(eq(true));
        inOrderVerifier.verify(mVibratorStateListenerMock, atLeastOnce()).asBinder(); // unregister
        inOrderVerifier.verifyNoMoreInteractions();
    }

    @Test
    public void scale_withPrebaked_userIntensitySettingAsEffectStrength() throws Exception {
        // Alarm vibration is always VIBRATION_INTENSITY_HIGH.
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        mVibratorProvider.setSupportedEffects(
                VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_TICK,
                VibrationEffect.EFFECT_DOUBLE_CLICK,
                VibrationEffect.EFFECT_HEAVY_CLICK);
        VibratorService service = createService();

        vibrateAndWait(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), ALARM_ATTRS);
        vibrateAndWait(service, VibrationEffect.get(VibrationEffect.EFFECT_TICK),
                NOTIFICATION_ATTRS);
        vibrateAndWait(service, VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK),
                HAPTIC_FEEDBACK_ATTRS);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK), RINGTONE_ATTRS);

        List<Integer> playedStrengths = mVibratorProvider.getEffects().stream()
                .map(VibrationEffect.Prebaked.class::cast)
                .map(VibrationEffect.Prebaked::getEffectStrength)
                .collect(Collectors.toList());
        assertEquals(Arrays.asList(
                VibrationEffect.EFFECT_STRENGTH_STRONG,
                VibrationEffect.EFFECT_STRENGTH_MEDIUM,
                VibrationEffect.EFFECT_STRENGTH_LIGHT),
                playedStrengths);
    }

    @Test
    public void scale_withOneShotAndWaveform_usesScaleLevelOnAmplitude() throws Exception {
        mFakeVibrator.setDefaultNotificationVibrationIntensity(Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);

        mVibratorProvider.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorService service = createService();

        vibrateAndWait(service, VibrationEffect.createOneShot(20, 100), ALARM_ATTRS);
        vibrateAndWait(service, VibrationEffect.createOneShot(20, 100), NOTIFICATION_ATTRS);
        vibrateAndWait(service,
                VibrationEffect.createWaveform(new long[]{10}, new int[]{100}, -1),
                HAPTIC_FEEDBACK_ATTRS);
        vibrate(service, VibrationEffect.createOneShot(20, 255), RINGTONE_ATTRS);

        List<Integer> amplitudes = mVibratorProvider.getAmplitudes();
        assertEquals(3, amplitudes.size());
        // Alarm vibration is never scaled.
        assertEquals(100, amplitudes.get(0).intValue());
        // Notification vibrations will be scaled with SCALE_VERY_HIGH.
        assertTrue(amplitudes.get(1) > 150);
        // Haptic feedback vibrations will be scaled with SCALE_LOW.
        assertTrue(amplitudes.get(2) < 100 && amplitudes.get(2) > 50);
    }

    @Test
    public void scale_withComposed_usesScaleLevelOnPrimitiveScaleValues() throws Exception {
        mFakeVibrator.setDefaultNotificationVibrationIntensity(Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);

        mVibratorProvider.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        VibratorService service = createService();

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .compose();

        vibrateAndWait(service, effect, ALARM_ATTRS);
        vibrateAndWait(service, effect, NOTIFICATION_ATTRS);
        vibrateAndWait(service, effect, HAPTIC_FEEDBACK_ATTRS);
        vibrate(service, effect, RINGTONE_ATTRS);

        List<VibrationEffect.Composition.PrimitiveEffect> primitives =
                mVibratorProvider.getEffects().stream()
                        .map(VibrationEffect.Composed.class::cast)
                        .map(VibrationEffect.Composed::getPrimitiveEffects)
                        .flatMap(List::stream)
                        .collect(Collectors.toList());

        // Ringtone vibration is off, so only the other 3 are propagated to native.
        assertEquals(6, primitives.size());

        // Alarm vibration is never scaled.
        assertEquals(1f, primitives.get(0).scale, /* delta= */ 1e-2);
        assertEquals(0.5f, primitives.get(1).scale, /* delta= */ 1e-2);

        // Notification vibrations will be scaled with SCALE_VERY_HIGH.
        assertEquals(1f, primitives.get(2).scale, /* delta= */ 1e-2);
        assertTrue(0.7 < primitives.get(3).scale);

        // Haptic feedback vibrations will be scaled with SCALE_LOW.
        assertTrue(0.5 < primitives.get(4).scale);
        assertTrue(0.5 > primitives.get(5).scale);
    }

    private void vibrate(VibratorService service, VibrationEffect effect,
            VibrationAttributes attrs) {
        service.vibrate(UID, PACKAGE_NAME, effect, attrs, "some reason", service);
    }

    private void vibrateAndWait(VibratorService service, VibrationEffect effect,
            VibrationAttributes attrs) throws Exception {
        CountDownLatch startedCount = new CountDownLatch(1);
        CountDownLatch finishedCount = new CountDownLatch(1);
        service.registerVibratorStateListener(new IVibratorStateListener() {
            @Override
            public void onVibrating(boolean vibrating) {
                if (vibrating) {
                    startedCount.countDown();
                } else if (startedCount.getCount() == 0) {
                    finishedCount.countDown();
                }
            }

            @Override
            public IBinder asBinder() {
                return mock(IBinder.class);
            }
        });

        mTestLooper.startAutoDispatch();
        service.vibrate(UID, PACKAGE_NAME, effect, attrs, "some reason", service);
        assertTrue(startedCount.await(1, TimeUnit.SECONDS));
        assertTrue(finishedCount.await(1, TimeUnit.SECONDS));
        mTestLooper.stopAutoDispatchAndIgnoreExceptions();
    }

    private InputDevice createInputDeviceWithVibrator(int id) {
        return new InputDevice(id, 0, 0, "name", 0, 0, "description", false, 0, 0,
                null, /* hasVibrator= */ true, false, false, false /* hasSensor */);
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
