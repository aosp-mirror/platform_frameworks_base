/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.vibrator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
import android.hardware.vibrator.IVibratorManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.CombinedVibration;
import android.os.ExternalVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.IExternalVibrationController;
import android.os.IExternalVibratorService;
import android.os.IVibratorStateListener;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.os.test.TestLooper;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.view.InputDevice;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Tests for {@link VibratorManagerService}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibratorManagerServiceTest
 */
@Presubmit
public class VibratorManagerServiceTest {

    private static final int TEST_TIMEOUT_MILLIS = 1_000;
    private static final int UID = Process.ROOT_UID;
    private static final String PACKAGE_NAME = "package";
    private static final PowerSaveState NORMAL_POWER_STATE = new PowerSaveState.Builder().build();
    private static final PowerSaveState LOW_POWER_STATE = new PowerSaveState.Builder()
            .setBatterySaverEnabled(true).build();
    private static final AudioAttributes AUDIO_ATTRS =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
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

    @Rule public MockitoRule rule = MockitoJUnit.rule();
    @Rule public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Mock private VibratorManagerService.NativeWrapper mNativeWrapperMock;
    @Mock private PackageManagerInternal mPackageManagerInternalMock;
    @Mock private PowerManagerInternal mPowerManagerInternalMock;
    @Mock private PowerSaveState mPowerSaveStateMock;
    @Mock private AppOpsManager mAppOpsManagerMock;
    @Mock private IInputManager mIInputManagerMock;

    private final Map<Integer, FakeVibratorControllerProvider> mVibratorProviders = new HashMap<>();

    private Context mContextSpy;
    private TestLooper mTestLooper;
    private FakeVibrator mVibrator;
    private PowerManagerInternal.LowPowerModeListener mRegisteredPowerModeListener;
    private VibratorManagerService.ExternalVibratorService mExternalVibratorService;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mVibrator = new FakeVibrator();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        InputManager inputManager = InputManager.resetInstance(mIInputManagerMock);

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        when(mContextSpy.getSystemService(eq(Context.VIBRATOR_SERVICE))).thenReturn(mVibrator);
        when(mContextSpy.getSystemService(eq(Context.INPUT_SERVICE))).thenReturn(inputManager);
        when(mContextSpy.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManagerMock);
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[0]);
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName("", ""));
        when(mPowerManagerInternalMock.getLowPowerState(PowerManager.ServiceType.VIBRATION))
                .thenReturn(mPowerSaveStateMock);
        doAnswer(invocation -> {
            mRegisteredPowerModeListener = invocation.getArgument(0);
            return null;
        }).when(mPowerManagerInternalMock).registerLowPowerModeObserver(any());

        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);

        addLocalServiceMock(PackageManagerInternal.class, mPackageManagerInternalMock);
        addLocalServiceMock(PowerManagerInternal.class, mPowerManagerInternalMock);

        mTestLooper.startAutoDispatch();
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
    }

    private VibratorManagerService createSystemReadyService() {
        VibratorManagerService service = createService();
        service.systemReady();
        return service;
    }

    private VibratorManagerService createService() {
        return new VibratorManagerService(
                mContextSpy,
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

                    @Override
                    void addService(String name, IBinder service) {
                        Object serviceInstance = service;
                        mExternalVibratorService =
                                (VibratorManagerService.ExternalVibratorService) serviceInstance;
                    }
                });
    }

    @Test
    public void createService_initializesNativeManagerServiceAndVibrators() {
        mockVibrators(1, 2);
        createService();
        verify(mNativeWrapperMock).init(any());
        assertTrue(mVibratorProviders.get(1).isInitialized());
        assertTrue(mVibratorProviders.get(2).isInitialized());
    }

    @Test
    public void createService_doNotCrashIfUsedBeforeSystemReady() {
        mockVibrators(1, 2);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);
        VibratorManagerService service = createService();

        assertNotNull(service.getVibratorIds());
        assertNotNull(service.getVibratorInfo(1));
        assertFalse(service.isVibrating(1));

        CombinedVibration effect = CombinedVibration.createParallel(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        vibrate(service, effect, HAPTIC_FEEDBACK_ATTRS);
        service.cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, service);

        assertTrue(service.setAlwaysOnEffect(UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        IVibratorStateListener listener = mockVibratorStateListener();
        assertTrue(service.registerVibratorStateListener(1, listener));
        assertTrue(service.unregisterVibratorStateListener(1, listener));
    }

    @Test
    public void getVibratorIds_withNullResultFromNative_returnsEmptyArray() {
        when(mNativeWrapperMock.getVibratorIds()).thenReturn(null);
        assertArrayEquals(new int[0], createSystemReadyService().getVibratorIds());
    }

    @Test
    public void getVibratorIds_withNonEmptyResultFromNative_returnsSameArray() {
        mockVibrators(2, 1);
        assertArrayEquals(new int[]{2, 1}, createSystemReadyService().getVibratorIds());
    }

    @Test
    public void getVibratorInfo_withMissingVibratorId_returnsNull() {
        mockVibrators(1);
        assertNull(createSystemReadyService().getVibratorInfo(2));
    }

    @Test
    public void getVibratorInfo_withExistingVibratorId_returnsHalInfoForVibrator() {
        mockVibrators(1);
        FakeVibratorControllerProvider vibrator = mVibratorProviders.get(1);
        vibrator.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS, IVibrator.CAP_AMPLITUDE_CONTROL);
        vibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        vibrator.setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK);
        vibrator.setResonantFrequency(123.f);
        vibrator.setQFactor(Float.NaN);
        VibratorInfo info = createSystemReadyService().getVibratorInfo(1);

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
        assertEquals(123.f, info.getResonantFrequency(), 0.01 /*tolerance*/);
        assertTrue(Float.isNaN(info.getQFactor()));
    }

    @Test
    public void registerVibratorStateListener_callbacksAreTriggered() throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();
        IVibratorStateListener listenerMock = mockVibratorStateListener();
        service.registerVibratorStateListener(1, listenerMock);

        vibrate(service, VibrationEffect.createOneShot(40, 100), ALARM_ATTRS);
        // Wait until service knows vibrator is on.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
        // Wait until effect ends.
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        InOrder inOrderVerifier = inOrder(listenerMock);
        // First notification done when listener is registered.
        inOrderVerifier.verify(listenerMock).onVibrating(eq(false));
        inOrderVerifier.verify(listenerMock).onVibrating(eq(true));
        inOrderVerifier.verify(listenerMock).onVibrating(eq(false));
        inOrderVerifier.verifyNoMoreInteractions();
    }

    @Test
    public void unregisterVibratorStateListener_callbackNotTriggeredAfter() throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();
        IVibratorStateListener listenerMock = mockVibratorStateListener();
        service.registerVibratorStateListener(1, listenerMock);

        vibrate(service, VibrationEffect.createOneShot(40, 100), ALARM_ATTRS);

        // Wait until service knows vibrator is on.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        service.unregisterVibratorStateListener(1, listenerMock);

        // Wait until vibrator is off.
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        InOrder inOrderVerifier = inOrder(listenerMock);
        // First notification done when listener is registered.
        inOrderVerifier.verify(listenerMock).onVibrating(eq(false));
        inOrderVerifier.verify(listenerMock).onVibrating(eq(true));
        inOrderVerifier.verify(listenerMock, atLeastOnce()).asBinder(); // unregister
        inOrderVerifier.verifyNoMoreInteractions();
    }

    @Test
    public void registerVibratorStateListener_multipleVibratorsAreTriggered() throws Exception {
        mockVibrators(0, 1, 2);
        VibratorManagerService service = createSystemReadyService();
        IVibratorStateListener[] listeners = new IVibratorStateListener[3];
        for (int i = 0; i < 3; i++) {
            listeners[i] = mockVibratorStateListener();
            service.registerVibratorStateListener(i, listeners[i]);
        }

        vibrate(service, CombinedVibration.startParallel()
                .addVibrator(0, VibrationEffect.createOneShot(40, 100))
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .combine(), ALARM_ATTRS);
        // Wait until service knows vibrator is on.
        assertTrue(waitUntil(s -> s.isVibrating(0), service, TEST_TIMEOUT_MILLIS));

        verify(listeners[0]).onVibrating(eq(true));
        verify(listeners[1]).onVibrating(eq(true));
        verify(listeners[2], never()).onVibrating(eq(true));
    }

    @Test
    public void setAlwaysOnEffect_withMono_enablesAlwaysOnEffectToAllVibratorsWithCapability() {
        mockVibrators(1, 2, 3);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);
        mVibratorProviders.get(3).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        CombinedVibration effect = CombinedVibration.createParallel(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        assertTrue(createSystemReadyService().setAlwaysOnEffect(
                UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        PrebakedSegment expected = new PrebakedSegment(
                VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_STRONG);

        // Only vibrators 1 and 3 have always-on capabilities.
        assertEquals(mVibratorProviders.get(1).getAlwaysOnEffect(1), expected);
        assertNull(mVibratorProviders.get(2).getAlwaysOnEffect(1));
        assertEquals(mVibratorProviders.get(3).getAlwaysOnEffect(1), expected);
    }

    @Test
    public void setAlwaysOnEffect_withStereo_enablesAlwaysOnEffectToAllVibratorsWithCapability() {
        mockVibrators(1, 2, 3, 4);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);
        mVibratorProviders.get(4).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                .addVibrator(3, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                .combine();
        assertTrue(createSystemReadyService().setAlwaysOnEffect(
                UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        PrebakedSegment expectedClick = new PrebakedSegment(
                VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_STRONG);

        PrebakedSegment expectedTick = new PrebakedSegment(
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

        CombinedVibration effect = CombinedVibration.createParallel(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        assertTrue(createSystemReadyService().setAlwaysOnEffect(
                UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        assertTrue(createSystemReadyService().setAlwaysOnEffect(
                UID, PACKAGE_NAME, 1, null, ALARM_ATTRS));

        assertNull(mVibratorProviders.get(1).getAlwaysOnEffect(1));
        assertNull(mVibratorProviders.get(2).getAlwaysOnEffect(1));
        assertNull(mVibratorProviders.get(3).getAlwaysOnEffect(1));
    }

    @Test
    public void setAlwaysOnEffect_withNonPrebakedEffect_ignoresEffect() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        CombinedVibration effect = CombinedVibration.createParallel(
                VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
        assertFalse(createSystemReadyService().setAlwaysOnEffect(
                UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        assertNull(mVibratorProviders.get(1).getAlwaysOnEffect(1));
    }

    @Test
    public void setAlwaysOnEffect_withNonSyncedEffect_ignoresEffect() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        CombinedVibration effect = CombinedVibration.startSequential()
                .addNext(0, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .combine();
        assertFalse(createSystemReadyService().setAlwaysOnEffect(
                UID, PACKAGE_NAME, 1, effect, ALARM_ATTRS));

        assertNull(mVibratorProviders.get(1).getAlwaysOnEffect(1));
    }

    @Test
    public void setAlwaysOnEffect_withNoVibratorWithCapability_ignoresEffect() {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        CombinedVibration mono = CombinedVibration.createParallel(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        CombinedVibration stereo = CombinedVibration.startParallel()
                .addVibrator(0, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                .combine();
        assertFalse(service.setAlwaysOnEffect(UID, PACKAGE_NAME, 1, mono, ALARM_ATTRS));
        assertFalse(service.setAlwaysOnEffect(UID, PACKAGE_NAME, 2, stereo, ALARM_ATTRS));

        assertNull(mVibratorProviders.get(1).getAlwaysOnEffect(1));
    }

    @Test
    public void vibrate_withRingtone_usesRingtoneSettings() throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        mVibrator.setDefaultRingVibrationIntensity(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_DOUBLE_CLICK);

        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0);
        VibratorManagerService service = createSystemReadyService();
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), RINGTONE_ATTRS);
        // Wait before checking it never played.
        assertFalse(waitUntil(s -> !fakeVibrator.getEffectSegments().isEmpty(),
                service, /* timeout= */ 50));

        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 1);
        service = createSystemReadyService();
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK), RINGTONE_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getEffectSegments().size() == 1,
                service, TEST_TIMEOUT_MILLIS));

        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setGlobalSetting(Settings.Global.APPLY_RAMPING_RINGER, 0);
        service = createSystemReadyService();
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK), RINGTONE_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getEffectSegments().size() == 2,
                service, TEST_TIMEOUT_MILLIS));

        assertEquals(
                Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_HEAVY_CLICK),
                        expectedPrebaked(VibrationEffect.EFFECT_DOUBLE_CLICK)),
                mVibratorProviders.get(1).getEffectSegments());
    }

    @Test
    public void vibrate_withPowerMode_usesPowerModeState() throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_DOUBLE_CLICK);
        VibratorManagerService service = createSystemReadyService();
        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_TICK), HAPTIC_FEEDBACK_ATTRS);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), RINGTONE_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getEffectSegments().size() == 1,
                service, TEST_TIMEOUT_MILLIS));

        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK),
                /* attrs= */ null);
        assertTrue(waitUntil(s -> fakeVibrator.getEffectSegments().size() == 2,
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK),
                NOTIFICATION_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getEffectSegments().size() == 3,
                service, TEST_TIMEOUT_MILLIS));

        assertEquals(
                Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_CLICK),
                        expectedPrebaked(VibrationEffect.EFFECT_HEAVY_CLICK),
                        expectedPrebaked(VibrationEffect.EFFECT_DOUBLE_CLICK)),
                mVibratorProviders.get(1).getEffectSegments());
    }

    @Test
    public void vibrate_withAudioAttributes_usesOriginalAudioUsageInAppOpsManager() {
        VibratorManagerService service = createSystemReadyService();

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
        VibratorManagerService service = createSystemReadyService();

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
    public void vibrate_withOngoingRepeatingVibration_ignoresEffect() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect repeatingEffect = VibrationEffect.createWaveform(
                new long[]{10_000, 10_000}, new int[]{128, 255}, 1);
        vibrate(service, repeatingEffect, new VibrationAttributes.Builder().setUsage(
                VibrationAttributes.USAGE_UNKNOWN).build());

        // VibrationThread will start this vibration async, so wait before checking it started.
        assertTrue(waitUntil(s -> !mVibratorProviders.get(1).getEffectSegments().isEmpty(),
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                new VibrationAttributes.Builder().setUsage(
                        VibrationAttributes.USAGE_TOUCH).build());

        // Wait before checking it never played a second effect.
        assertFalse(waitUntil(s -> mVibratorProviders.get(1).getEffectSegments().size() > 1,
                service, /* timeout= */ 50));
    }

    @Test
    public void vibrate_withOngoingAlarmVibration_ignoresEffect() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect alarmEffect = VibrationEffect.createWaveform(
                new long[]{10_000, 10_000}, new int[]{128, 255}, -1);
        vibrate(service, alarmEffect, new VibrationAttributes.Builder().setUsage(
                VibrationAttributes.USAGE_ALARM).build());

        // VibrationThread will start this vibration async, so wait before checking it started.
        assertTrue(waitUntil(s -> !mVibratorProviders.get(1).getEffectSegments().isEmpty(),
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                new VibrationAttributes.Builder().setUsage(
                        VibrationAttributes.USAGE_TOUCH).build());

        // Wait before checking it never played a second effect.
        assertFalse(waitUntil(s -> mVibratorProviders.get(1).getEffectSegments().size() > 1,
                service, /* timeout= */ 50));
    }

    @Test
    public void vibrate_withInputDevices_vibratesInputDevices() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{1});
        when(mIInputManagerMock.getVibratorIds(eq(1))).thenReturn(new int[]{1});
        when(mIInputManagerMock.getInputDevice(eq(1))).thenReturn(createInputDeviceWithVibrator(1));
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        VibratorManagerService service = createSystemReadyService();

        CombinedVibration effect = CombinedVibration.createParallel(
                VibrationEffect.createOneShot(10, 10));
        vibrate(service, effect, ALARM_ATTRS);
        verify(mIInputManagerMock).vibrateCombined(eq(1), eq(effect), any());

        // VibrationThread will start this vibration async, so wait before checking it never played.
        assertFalse(waitUntil(s -> !mVibratorProviders.get(1).getEffectSegments().isEmpty(),
                service, /* timeout= */ 50));
    }

    @Test
    public void vibrate_withNativeCallbackTriggered_finishesVibration() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        VibratorManagerService service = createSystemReadyService();
        // The native callback will be dispatched manually in this test.
        mTestLooper.stopAutoDispatchAndIgnoreExceptions();

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), ALARM_ATTRS);

        // VibrationThread will start this vibration async, so wait before triggering callbacks.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        // Trigger callbacks from controller.
        mTestLooper.moveTimeForward(50);
        mTestLooper.dispatchAll();

        // VibrationThread needs some time to react to native callbacks and stop the vibrator.
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
    }

    @Test
    public void vibrate_withTriggerCallback_finishesVibration() throws Exception {
        mockCapabilities(IVibratorManager.CAP_SYNC, IVibratorManager.CAP_PREPARE_COMPOSE);
        mockVibrators(1, 2);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        VibratorManagerService service = createSystemReadyService();
        // The native callback will be dispatched manually in this test.
        mTestLooper.stopAutoDispatchAndIgnoreExceptions();

        ArgumentCaptor<VibratorManagerService.OnSyncedVibrationCompleteListener> listenerCaptor =
                ArgumentCaptor.forClass(
                        VibratorManagerService.OnSyncedVibrationCompleteListener.class);
        verify(mNativeWrapperMock).init(listenerCaptor.capture());

        CountDownLatch triggerCountDown = new CountDownLatch(1);
        // Mock trigger callback on registered listener right after the synced vibration starts.
        when(mNativeWrapperMock.prepareSynced(eq(new int[]{1, 2}))).thenReturn(true);
        when(mNativeWrapperMock.triggerSynced(anyLong())).then(answer -> {
            listenerCaptor.getValue().onComplete(answer.getArgument(0));
            triggerCountDown.countDown();
            return true;
        });

        VibrationEffect composed = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 100)
                .compose();
        CombinedVibration effect = CombinedVibration.createParallel(composed);

        vibrate(service, effect, ALARM_ATTRS);
        // VibrationThread will start this vibration async, so wait until vibration is triggered.
        triggerCountDown.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        verify(mNativeWrapperMock).prepareSynced(eq(new int[]{1, 2}));
        verify(mNativeWrapperMock).triggerSynced(anyLong());
        PrimitiveSegment expected = new PrimitiveSegment(
                VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 100);
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(1).getEffectSegments());
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(2).getEffectSegments());

        // VibrationThread needs some time to react to native callbacks and stop the vibrator.
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
    }

    @Test
    public void vibrate_withMultipleVibratorsAndCapabilities_prepareAndTriggerCalled()
            throws Exception {
        mockCapabilities(IVibratorManager.CAP_SYNC, IVibratorManager.CAP_PREPARE_PERFORM,
                IVibratorManager.CAP_PREPARE_COMPOSE, IVibratorManager.CAP_MIXED_TRIGGER_PERFORM,
                IVibratorManager.CAP_MIXED_TRIGGER_COMPOSE);
        mockVibrators(1, 2);
        when(mNativeWrapperMock.prepareSynced(eq(new int[]{1, 2}))).thenReturn(true);
        when(mNativeWrapperMock.triggerSynced(anyLong())).thenReturn(true);
        FakeVibratorControllerProvider fakeVibrator1 = mVibratorProviders.get(1);
        fakeVibrator1.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        VibratorManagerService service = createSystemReadyService();

        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2, VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .compose())
                .combine();
        vibrate(service, effect, ALARM_ATTRS);
        assertTrue(waitUntil(s -> !fakeVibrator1.getEffectSegments().isEmpty(), service,
                TEST_TIMEOUT_MILLIS));

        verify(mNativeWrapperMock).prepareSynced(eq(new int[]{1, 2}));
        verify(mNativeWrapperMock).triggerSynced(anyLong());
        verify(mNativeWrapperMock).cancelSynced(); // Trigger on service creation only.
    }

    @Test
    public void vibrate_withMultipleVibratorsWithoutCapabilities_skipPrepareAndTrigger()
            throws Exception {
        // Missing CAP_MIXED_TRIGGER_ON and CAP_MIXED_TRIGGER_PERFORM.
        mockCapabilities(IVibratorManager.CAP_SYNC, IVibratorManager.CAP_PREPARE_ON,
                IVibratorManager.CAP_PREPARE_PERFORM);
        mockVibrators(1, 2);
        FakeVibratorControllerProvider fakeVibrator1 = mVibratorProviders.get(1);
        fakeVibrator1.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        VibratorManagerService service = createSystemReadyService();

        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2, VibrationEffect.createOneShot(10, 100))
                .combine();
        vibrate(service, effect, ALARM_ATTRS);
        assertTrue(waitUntil(s -> !fakeVibrator1.getEffectSegments().isEmpty(), service,
                TEST_TIMEOUT_MILLIS));

        verify(mNativeWrapperMock, never()).prepareSynced(any());
        verify(mNativeWrapperMock, never()).triggerSynced(anyLong());
        verify(mNativeWrapperMock).cancelSynced(); // Trigger on service creation only.
    }

    @Test
    public void vibrate_withMultipleVibratorsPrepareFailed_skipTrigger() throws Exception {
        mockCapabilities(IVibratorManager.CAP_SYNC, IVibratorManager.CAP_PREPARE_ON);
        mockVibrators(1, 2);
        when(mNativeWrapperMock.prepareSynced(any())).thenReturn(false);
        VibratorManagerService service = createSystemReadyService();

        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.createOneShot(10, 50))
                .addVibrator(2, VibrationEffect.createOneShot(10, 100))
                .combine();
        vibrate(service, effect, ALARM_ATTRS);
        assertTrue(waitUntil(s -> !mVibratorProviders.get(1).getEffectSegments().isEmpty(), service,
                TEST_TIMEOUT_MILLIS));

        verify(mNativeWrapperMock).prepareSynced(eq(new int[]{1, 2}));
        verify(mNativeWrapperMock, never()).triggerSynced(anyLong());
        verify(mNativeWrapperMock).cancelSynced(); // Trigger on service creation only.
    }

    @Test
    public void vibrate_withMultipleVibratorsTriggerFailed_cancelPreparedSynced() throws Exception {
        mockCapabilities(IVibratorManager.CAP_SYNC, IVibratorManager.CAP_PREPARE_ON);
        mockVibrators(1, 2);
        when(mNativeWrapperMock.prepareSynced(eq(new int[]{1, 2}))).thenReturn(true);
        when(mNativeWrapperMock.triggerSynced(anyLong())).thenReturn(false);
        VibratorManagerService service = createSystemReadyService();

        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.createOneShot(10, 50))
                .addVibrator(2, VibrationEffect.createOneShot(10, 100))
                .combine();
        vibrate(service, effect, ALARM_ATTRS);
        assertTrue(waitUntil(s -> !mVibratorProviders.get(1).getEffectSegments().isEmpty(), service,
                TEST_TIMEOUT_MILLIS));

        verify(mNativeWrapperMock).prepareSynced(eq(new int[]{1, 2}));
        verify(mNativeWrapperMock).triggerSynced(anyLong());
        verify(mNativeWrapperMock, times(2)).cancelSynced(); // Trigger on service creation too.
    }

    @Test
    public void vibrate_withIntensitySettings_appliesSettingsToScaleVibrations() throws Exception {
        mVibrator.setDefaultNotificationVibrationIntensity(Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);

        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL,
                IVibrator.CAP_COMPOSE_EFFECTS);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        VibratorManagerService service = createSystemReadyService();

        vibrate(service, CombinedVibration.startSequential()
                .addNext(1, VibrationEffect.createOneShot(20, 100))
                .combine(), NOTIFICATION_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getEffectSegments().size() == 1,
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .compose(), HAPTIC_FEEDBACK_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getEffectSegments().size() == 3,
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .combine(), ALARM_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getEffectSegments().size() == 4,
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), RINGTONE_ATTRS);

        assertEquals(4, fakeVibrator.getEffectSegments().size());

        // Notification vibrations will be scaled with SCALE_VERY_HIGH.
        assertTrue(0.6 < fakeVibrator.getAmplitudes().get(0));

        // Haptic feedback vibrations will be scaled with SCALE_LOW.
        assertTrue(0.5 < ((PrimitiveSegment) fakeVibrator.getEffectSegments().get(1)).getScale());
        assertTrue(0.5 > ((PrimitiveSegment) fakeVibrator.getEffectSegments().get(2)).getScale());

        // Alarm vibration is always VIBRATION_INTENSITY_HIGH.
        PrebakedSegment expected = new PrebakedSegment(
                VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_STRONG);
        assertEquals(expected, fakeVibrator.getEffectSegments().get(3));

        // Ring vibrations have intensity OFF and are not played.
    }

    @Test
    public void vibrate_withPowerModeChange_cancelVibrationIfNotAllowed() throws Exception {
        mockVibrators(1, 2);
        VibratorManagerService service = createSystemReadyService();
        vibrate(service,
                CombinedVibration.startParallel()
                        .addVibrator(1, VibrationEffect.createOneShot(1000, 100))
                        .combine(),
                HAPTIC_FEEDBACK_ATTRS);

        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        // Haptic feedback cancelled on low power mode.
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
    }

    @Test
    public void vibrate_withSettingsChange_doNotCancelVibration() throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        vibrate(service, VibrationEffect.createOneShot(1000, 100), HAPTIC_FEEDBACK_ATTRS);
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        service.updateServiceState();
        // Vibration is not stopped nearly after updating service.
        assertFalse(waitUntil(s -> !s.isVibrating(1), service, 50));
    }

    @Test
    public void cancelVibrate_withoutUsageFilter_stopsVibrating() throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        service.cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, service);
        assertFalse(service.isVibrating(1));

        vibrate(service, VibrationEffect.createOneShot(10 * TEST_TIMEOUT_MILLIS, 100), ALARM_ATTRS);
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        service.cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, service);
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
    }

    @Test
    public void cancelVibrate_withFilter_onlyCancelsVibrationWithFilteredUsage() throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        vibrate(service, VibrationEffect.createOneShot(10 * TEST_TIMEOUT_MILLIS, 100), ALARM_ATTRS);
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        // Vibration is not cancelled with a different usage.
        service.cancelVibrate(VibrationAttributes.USAGE_RINGTONE, service);
        assertFalse(waitUntil(s -> !s.isVibrating(1), service, /* timeout= */ 50));

        // Vibration is not cancelled with a different usage class used as filter.
        service.cancelVibrate(
                VibrationAttributes.USAGE_CLASS_FEEDBACK | ~VibrationAttributes.USAGE_CLASS_MASK,
                service);
        assertFalse(waitUntil(s -> !s.isVibrating(1), service, /* timeout= */ 50));

        // Vibration is cancelled with usage class as filter.
        service.cancelVibrate(
                VibrationAttributes.USAGE_CLASS_ALARM | ~VibrationAttributes.USAGE_CLASS_MASK,
                service);
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
    }

    @Test
    public void cancelVibrate_withoutUnknownUsage_onlyStopsIfFilteringUnknownOrAllUsages()
            throws Exception {
        mockVibrators(1);
        VibrationAttributes attrs = new VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_UNKNOWN)
                .build();
        VibratorManagerService service = createSystemReadyService();

        vibrate(service, VibrationEffect.createOneShot(10 * TEST_TIMEOUT_MILLIS, 100), attrs);
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        // Do not cancel UNKNOWN vibration when filter is being applied for other usages.
        service.cancelVibrate(VibrationAttributes.USAGE_RINGTONE, service);
        assertFalse(waitUntil(s -> !s.isVibrating(1), service, /* timeout= */ 50));

        service.cancelVibrate(
                VibrationAttributes.USAGE_CLASS_ALARM | ~VibrationAttributes.USAGE_CLASS_MASK,
                service);
        assertFalse(waitUntil(s -> !s.isVibrating(1), service, /* timeout= */ 50));

        // Cancel UNKNOWN vibration when filtered for that vibration specifically.
        service.cancelVibrate(VibrationAttributes.USAGE_UNKNOWN, service);
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        vibrate(service, VibrationEffect.createOneShot(10 * TEST_TIMEOUT_MILLIS, 100), attrs);
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        // Cancel UNKNOWN vibration when all vibrations are being cancelled.
        service.cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, service);
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
    }

    @Test
    public void onExternalVibration_setsExternalControl() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        createSystemReadyService();

        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME, AUDIO_ATTRS,
                mock(IExternalVibrationController.class));
        int scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        mExternalVibratorService.onExternalVibrationStop(externalVibration);

        assertEquals(IExternalVibratorService.SCALE_NONE, scale);
        assertEquals(Arrays.asList(true, false),
                mVibratorProviders.get(1).getExternalControlStates());
    }

    @Test
    public void onExternalVibration_withOngoingExternalVibration_mutesPreviousVibration()
            throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        createSystemReadyService();

        IExternalVibrationController firstController = mock(IExternalVibrationController.class);
        IExternalVibrationController secondController = mock(IExternalVibrationController.class);
        ExternalVibration firstVibration = new ExternalVibration(UID, PACKAGE_NAME, AUDIO_ATTRS,
                firstController);
        int firstScale = mExternalVibratorService.onExternalVibrationStart(firstVibration);

        ExternalVibration secondVibration = new ExternalVibration(UID, PACKAGE_NAME, AUDIO_ATTRS,
                secondController);
        int secondScale = mExternalVibratorService.onExternalVibrationStart(secondVibration);

        assertEquals(IExternalVibratorService.SCALE_NONE, firstScale);
        assertEquals(IExternalVibratorService.SCALE_NONE, secondScale);
        verify(firstController).mute();
        verifyNoMoreInteractions(secondController);
        // Set external control called only once.
        assertEquals(Arrays.asList(true), mVibratorProviders.get(1).getExternalControlStates());
    }

    @Test
    public void onExternalVibration_withOngoingVibration_cancelsOngoingVibrationImmediately()
            throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL,
                IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect effect = VibrationEffect.createOneShot(10 * TEST_TIMEOUT_MILLIS, 100);
        vibrate(service, effect, HAPTIC_FEEDBACK_ATTRS);
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME, AUDIO_ATTRS,
                mock(IExternalVibrationController.class));
        int scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertEquals(IExternalVibratorService.SCALE_NONE, scale);

        // Vibration is cancelled.
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
        assertEquals(Arrays.asList(true), mVibratorProviders.get(1).getExternalControlStates());
    }

    private VibrationEffectSegment expectedPrebaked(int effectId) {
        return new PrebakedSegment(effectId, false, VibrationEffect.EFFECT_STRENGTH_MEDIUM);
    }

    private void mockCapabilities(long... capabilities) {
        when(mNativeWrapperMock.getCapabilities()).thenReturn(
                Arrays.stream(capabilities).reduce(0, (a, b) -> a | b));
    }

    private void mockVibrators(int... vibratorIds) {
        for (int vibratorId : vibratorIds) {
            mVibratorProviders.put(vibratorId,
                    new FakeVibratorControllerProvider(mTestLooper.getLooper()));
        }
        when(mNativeWrapperMock.getVibratorIds()).thenReturn(vibratorIds);
    }

    private IVibratorStateListener mockVibratorStateListener() {
        IVibratorStateListener listenerMock = mock(IVibratorStateListener.class);
        IBinder binderMock = mock(IBinder.class);
        when(listenerMock.asBinder()).thenReturn(binderMock);
        return listenerMock;
    }

    private InputDevice createInputDeviceWithVibrator(int id) {
        return new InputDevice(id, 0, 0, "name", 0, 0, "description", false, 0, 0,
                null, /* hasVibrator= */ true, false, false, false, false);
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

    private void vibrate(VibratorManagerService service, VibrationEffect effect,
            VibrationAttributes attrs) {
        vibrate(service, CombinedVibration.createParallel(effect), attrs);
    }

    private void vibrate(VibratorManagerService service, CombinedVibration effect,
            VibrationAttributes attrs) {
        service.vibrate(UID, PACKAGE_NAME, effect, attrs, "some reason", service);
    }

    private boolean waitUntil(Predicate<VibratorManagerService> predicate,
            VibratorManagerService service, long timeout) throws InterruptedException {
        long timeoutTimestamp = SystemClock.uptimeMillis() + timeout;
        boolean predicateResult = false;
        while (!predicateResult && SystemClock.uptimeMillis() < timeoutTimestamp) {
            Thread.sleep(10);
            predicateResult = predicate.test(service);
        }
        return predicateResult;
    }
}
