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
import static org.junit.Assert.assertNotEquals;
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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
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
import android.os.vibrator.VibrationConfig;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.InputDevice;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.LocalServices;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;

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
    private static final int VIRTUAL_DISPLAY_ID = 1;
    private static final String PACKAGE_NAME = "package";
    private static final PowerSaveState NORMAL_POWER_STATE = new PowerSaveState.Builder().build();
    private static final PowerSaveState LOW_POWER_STATE = new PowerSaveState.Builder()
            .setBatterySaverEnabled(true).build();
    private static final AudioAttributes AUDIO_ATTRS =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
    private static final VibrationAttributes ALARM_ATTRS =
            new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build();
    private static final VibrationAttributes HAPTIC_FEEDBACK_ATTRS =
            new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build();
    private static final VibrationAttributes NOTIFICATION_ATTRS =
            new VibrationAttributes.Builder().setUsage(
                    VibrationAttributes.USAGE_NOTIFICATION).build();
    private static final VibrationAttributes RINGTONE_ATTRS =
            new VibrationAttributes.Builder().setUsage(
                    VibrationAttributes.USAGE_RINGTONE).build();

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();
    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Mock
    private VibratorManagerService.NativeWrapper mNativeWrapperMock;
    @Mock
    private PackageManagerInternal mPackageManagerInternalMock;
    @Mock
    private PowerManagerInternal mPowerManagerInternalMock;
    @Mock
    private PowerSaveState mPowerSaveStateMock;
    @Mock
    private AppOpsManager mAppOpsManagerMock;
    @Mock
    private IInputManager mIInputManagerMock;
    @Mock
    private IBatteryStats mBatteryStatsMock;
    @Mock
    private VibratorFrameworkStatsLogger mVibratorFrameworkStatsLoggerMock;
    @Mock
    private VirtualDeviceManagerInternal mVirtualDeviceManagerInternalMock;

    private final Map<Integer, FakeVibratorControllerProvider> mVibratorProviders = new HashMap<>();

    private Context mContextSpy;
    private TestLooper mTestLooper;
    private FakeVibrator mVibrator;
    private PowerManagerInternal.LowPowerModeListener mRegisteredPowerModeListener;
    private VibratorManagerService.ExternalVibratorService mExternalVibratorService;
    private VibrationConfig mVibrationConfig;
    private VirtualDeviceManagerInternal.VirtualDisplayListener mRegisteredVirtualDisplayListener;
    private VirtualDeviceManagerInternal.AppsOnVirtualDeviceListener
            mRegisteredAppsOnVirtualDeviceListener;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        InputManager inputManager = InputManager.resetInstance(mIInputManagerMock);
        mVibrationConfig = new VibrationConfig(mContextSpy.getResources());

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);

        mVibrator = new FakeVibrator(mContextSpy);
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
        doAnswer(invocation -> {
            mRegisteredVirtualDisplayListener = invocation.getArgument(0);
            return null;
        }).when(mVirtualDeviceManagerInternalMock).registerVirtualDisplayListener(any());
        doAnswer(invocation -> {
            mRegisteredAppsOnVirtualDeviceListener = invocation.getArgument(0);
            return null;
        }).when(mVirtualDeviceManagerInternalMock).registerAppsOnVirtualDeviceListener(any());

        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        setUserSetting(Settings.System.ALARM_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.MEDIA_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);

        addLocalServiceMock(PackageManagerInternal.class, mPackageManagerInternalMock);
        addLocalServiceMock(PowerManagerInternal.class, mPowerManagerInternalMock);
        addLocalServiceMock(VirtualDeviceManagerInternal.class, mVirtualDeviceManagerInternalMock);

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
                    IBatteryStats getBatteryStatsService() {
                        return mBatteryStatsMock;
                    }

                    @Override
                    VibratorFrameworkStatsLogger getFrameworkStatsLogger(Handler handler) {
                        return mVibratorFrameworkStatsLoggerMock;
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
    public void createService_resetsVibrators() {
        mockVibrators(1, 2);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);

        createService();
        assertEquals(1, mVibratorProviders.get(1).getOffCount());
        assertEquals(1, mVibratorProviders.get(2).getOffCount());
        assertEquals(Arrays.asList(false), mVibratorProviders.get(1).getExternalControlStates());
        assertEquals(Arrays.asList(false), mVibratorProviders.get(2).getExternalControlStates());
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
    public void getVibratorInfo_vibratorFailedLoadBeforeSystemReady_returnsNull() {
        mockVibrators(1);
        mVibratorProviders.get(1).setVibratorInfoLoadSuccessful(false);
        assertNull(createService().getVibratorInfo(1));
    }

    @Test
    public void getVibratorInfo_vibratorFailedLoadAfterSystemReady_returnsInfoForVibrator() {
        mockVibrators(1);
        mVibratorProviders.get(1).setVibratorInfoLoadSuccessful(false);
        mVibratorProviders.get(1).setResonantFrequency(123.f);
        VibratorInfo info = createSystemReadyService().getVibratorInfo(1);

        assertNotNull(info);
        assertEquals(1, info.getId());
        assertEquals(123.f, info.getResonantFrequencyHz(), 0.01 /*tolerance*/);
    }

    @Test
    public void getVibratorInfo_vibratorSuccessfulLoadBeforeSystemReady_returnsInfoForVibrator() {
        mockVibrators(1);
        FakeVibratorControllerProvider vibrator = mVibratorProviders.get(1);
        vibrator.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS, IVibrator.CAP_AMPLITUDE_CONTROL);
        vibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        vibrator.setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK);
        vibrator.setResonantFrequency(123.f);
        vibrator.setQFactor(Float.NaN);
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
        assertEquals(123.f, info.getResonantFrequencyHz(), 0.01 /*tolerance*/);
        assertTrue(Float.isNaN(info.getQFactor()));
    }

    @Test
    public void getVibratorInfo_vibratorFailedThenSuccessfulLoad_returnsNullThenInfo() {
        mockVibrators(1);
        mVibratorProviders.get(1).setVibratorInfoLoadSuccessful(false);

        VibratorManagerService service = createService();
        assertNull(createService().getVibratorInfo(1));

        mVibratorProviders.get(1).setVibratorInfoLoadSuccessful(true);
        mVibratorProviders.get(1).setResonantFrequency(123.f);
        service.systemReady();

        VibratorInfo info = createService().getVibratorInfo(1);
        assertNotNull(info);
        assertEquals(1, info.getId());
        assertEquals(123.f, info.getResonantFrequencyHz(), 0.01 /*tolerance*/);
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

        InOrder batteryVerifier = inOrder(mBatteryStatsMock);
        batteryVerifier.verify(mBatteryStatsMock)
                .noteVibratorOn(UID, 40 + mVibrationConfig.getRampDownDurationMs());
        batteryVerifier.verify(mBatteryStatsMock).noteVibratorOff(UID);
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
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
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
                VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_MEDIUM);

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
                VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_MEDIUM);

        PrebakedSegment expectedTick = new PrebakedSegment(
                VibrationEffect.EFFECT_TICK, false, VibrationEffect.EFFECT_STRENGTH_MEDIUM);

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
    public void vibrate_withRingtone_usesRingerModeSettings() throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_DOUBLE_CLICK);

        setRingerMode(AudioManager.RINGER_MODE_SILENT);
        VibratorManagerService service = createSystemReadyService();
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), RINGTONE_ATTRS);
        // Wait before checking it never played.
        assertFalse(waitUntil(s -> !fakeVibrator.getAllEffectSegments().isEmpty(),
                service, /* timeout= */ 50));

        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        service = createSystemReadyService();
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK), RINGTONE_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getAllEffectSegments().size() == 1,
                service, TEST_TIMEOUT_MILLIS));

        setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        service = createSystemReadyService();
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK), RINGTONE_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getAllEffectSegments().size() == 2,
                service, TEST_TIMEOUT_MILLIS));

        assertEquals(
                Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_HEAVY_CLICK),
                        expectedPrebaked(VibrationEffect.EFFECT_DOUBLE_CLICK)),
                mVibratorProviders.get(1).getAllEffectSegments());
    }

    @Test
    public void vibrate_withPowerMode_usesPowerModeState() throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_DOUBLE_CLICK);
        VibratorManagerService service = createSystemReadyService();
        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        // The haptic feedback should be ignored in low power, but not the ringtone. The end
        // of the test asserts which actual effects ended up playing.
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_TICK), HAPTIC_FEEDBACK_ATTRS);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), RINGTONE_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getAllEffectSegments().size() == 1,
                service, TEST_TIMEOUT_MILLIS));
        // Allow the ringtone to complete, as the other vibrations won't cancel it.
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK),
                /* attrs= */ null);
        assertTrue(waitUntil(s -> fakeVibrator.getAllEffectSegments().size() == 2,
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK),
                NOTIFICATION_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getAllEffectSegments().size() == 3,
                service, TEST_TIMEOUT_MILLIS));

        assertEquals(
                Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_CLICK),
                        expectedPrebaked(VibrationEffect.EFFECT_HEAVY_CLICK),
                        expectedPrebaked(VibrationEffect.EFFECT_DOUBLE_CLICK)),
                mVibratorProviders.get(1).getAllEffectSegments());
    }

    @Test
    public void vibrate_withAudioAttributes_usesOriginalAudioUsageInAppOpsManager() {
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build();
        VibrationAttributes vibrationAttributes =
                new VibrationAttributes.Builder(audioAttributes).build();

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
        vibrate(service, VibrationEffect.createOneShot(2000, 200),
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
                eq(AudioAttributes.USAGE_VOICE_COMMUNICATION),
                anyInt(), anyString());
        inOrderVerifier.verify(mAppOpsManagerMock).checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                eq(AudioAttributes.USAGE_UNKNOWN), anyInt(), anyString());
    }

    @Test
    public void vibrate_withVibrationAttributesEnforceFreshSettings_refreshesVibrationSettings()
            throws Exception {
        mockVibrators(0);
        mVibratorProviders.get(0).setSupportedEffects(VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_TICK);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        VibratorManagerService service = createSystemReadyService();

        VibrationAttributes enforceFreshAttrs = new VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
                .setFlags(VibrationAttributes.FLAG_INVALIDATE_SETTINGS_CACHE)
                .build();

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), NOTIFICATION_ATTRS);
        // VibrationThread will start this vibration async, so wait before vibrating a second time.
        assertTrue(waitUntil(s -> mVibratorProviders.get(0).getAllEffectSegments().size() > 0,
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_TICK), enforceFreshAttrs);
        // VibrationThread will start this vibration async, so wait before checking.
        assertTrue(waitUntil(s -> mVibratorProviders.get(0).getAllEffectSegments().size() > 1,
                service, TEST_TIMEOUT_MILLIS));

        assertEquals(
                Arrays.asList(
                        expectedPrebaked(VibrationEffect.EFFECT_CLICK,
                                VibrationEffect.EFFECT_STRENGTH_STRONG),
                        expectedPrebaked(VibrationEffect.EFFECT_TICK,
                                VibrationEffect.EFFECT_STRENGTH_LIGHT)),
                mVibratorProviders.get(0).getAllEffectSegments());
    }

    @Test
    public void vibrate_withAttributesUnknownUsage_usesEffectToIdentifyTouchUsage() {
        VibratorManagerService service = createSystemReadyService();

        VibrationAttributes unknownAttributes = VibrationAttributes.createForUsage(
                VibrationAttributes.USAGE_UNKNOWN);
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), unknownAttributes);
        vibrate(service, VibrationEffect.createOneShot(200, 200), unknownAttributes);
        vibrate(service, VibrationEffect.createWaveform(
                new long[] { 100, 200, 300 }, new int[] {1, 2, 3}, -1), unknownAttributes);
        vibrate(service,
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)
                        .compose(),
                unknownAttributes);

        verify(mAppOpsManagerMock, times(4))
                .checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                        eq(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION), anyInt(), anyString());
        verify(mAppOpsManagerMock, never())
                .checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                        eq(AudioAttributes.USAGE_UNKNOWN), anyInt(), anyString());
    }

    @Test
    public void vibrate_withAttributesUnknownUsage_ignoresEffectIfNotHapticFeedbackCandidate() {
        VibratorManagerService service = createSystemReadyService();

        VibrationAttributes unknownAttributes = VibrationAttributes.createForUsage(
                VibrationAttributes.USAGE_UNKNOWN);
        vibrate(service, VibrationEffect.get(VibrationEffect.RINGTONES[0]), unknownAttributes);
        vibrate(service, VibrationEffect.createOneShot(2000, 200), unknownAttributes);
        vibrate(service, VibrationEffect.createWaveform(
                new long[] { 100, 200, 300 }, new int[] {1, 2, 3}, 0), unknownAttributes);
        vibrate(service,
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                        .compose(),
                unknownAttributes);

        verify(mAppOpsManagerMock, never())
                .checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                        eq(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION), anyInt(), anyString());
        verify(mAppOpsManagerMock, times(4))
                .checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
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
        assertTrue(waitUntil(s -> !mVibratorProviders.get(1).getAllEffectSegments().isEmpty(),
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), HAPTIC_FEEDBACK_ATTRS);

        // Wait before checking it never played a second effect.
        assertFalse(waitUntil(s -> mVibratorProviders.get(1).getAllEffectSegments().size() > 1,
                service, /* timeout= */ 50));

        // The time estimate is recorded when the vibration starts, repeating vibrations
        // are capped at BATTERY_STATS_REPEATING_VIBRATION_DURATION (=5000).
        verify(mBatteryStatsMock).noteVibratorOn(UID, 5000);
        // The second vibration shouldn't have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(1)).noteVibratorOn(anyInt(), anyLong());
    }

    @Test
    public void vibrate_withNewRepeatingVibration_cancelsOngoingEffect() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect alarmEffect = VibrationEffect.createWaveform(
                new long[]{10_000, 10_000}, new int[]{128, 255}, -1);
        vibrate(service, alarmEffect, ALARM_ATTRS);

        // VibrationThread will start this vibration async, so wait before checking it started.
        assertTrue(waitUntil(s -> !mVibratorProviders.get(1).getAllEffectSegments().isEmpty(),
                service, TEST_TIMEOUT_MILLIS));

        VibrationEffect repeatingEffect = VibrationEffect.createWaveform(
                new long[]{10, 10}, new int[]{128, 255}, 1);
        vibrate(service, repeatingEffect, NOTIFICATION_ATTRS);

        // VibrationThread will start this vibration async, so wait before checking it started.
        assertTrue(waitUntil(s -> mVibratorProviders.get(1).getAllEffectSegments().size() > 2,
                service, TEST_TIMEOUT_MILLIS));

        // The second vibration should have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(2)).noteVibratorOn(anyInt(), anyLong());
    }

    @Test
    public void vibrate_withOngoingHigherImportanceVibration_ignoresEffect() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{10_000, 10_000}, new int[]{128, 255}, -1);
        vibrate(service, effect, ALARM_ATTRS);

        // VibrationThread will start this vibration async, so wait before checking it started.
        assertTrue(waitUntil(s -> !mVibratorProviders.get(1).getAllEffectSegments().isEmpty(),
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, effect, HAPTIC_FEEDBACK_ATTRS);

        // Wait before checking it never played a second effect.
        assertFalse(waitUntil(s -> mVibratorProviders.get(1).getAllEffectSegments().size() > 1,
                service, /* timeout= */ 50));

        // The second vibration shouldn't have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(1)).noteVibratorOn(anyInt(), anyLong());
    }

    @Test
    public void vibrate_withOngoingLowerImportanceVibration_cancelsOngoingEffect()
            throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{10_000, 10_000}, new int[]{128, 255}, -1);
        vibrate(service, effect, HAPTIC_FEEDBACK_ATTRS);

        // VibrationThread will start this vibration async, so wait before checking it started.
        assertTrue(waitUntil(s -> !mVibratorProviders.get(1).getAllEffectSegments().isEmpty(),
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, effect, RINGTONE_ATTRS);

        // VibrationThread will start this vibration async, so wait before checking it started.
        assertTrue(waitUntil(s -> mVibratorProviders.get(1).getAllEffectSegments().size() > 1,
                service, TEST_TIMEOUT_MILLIS));

        // The second vibration should have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(2)).noteVibratorOn(anyInt(), anyLong());
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
        // Mock alarm intensity equals to default value to avoid scaling in this test.
        setUserSetting(Settings.System.ALARM_VIBRATION_INTENSITY,
                mVibrator.getDefaultVibrationIntensity(VibrationAttributes.USAGE_ALARM));
        VibratorManagerService service = createSystemReadyService();

        CombinedVibration effect = CombinedVibration.createParallel(
                VibrationEffect.createOneShot(10, 10));
        vibrate(service, effect, ALARM_ATTRS);
        verify(mIInputManagerMock).vibrateCombined(eq(1), eq(effect), any());

        // VibrationThread will start this vibration async, so wait before checking it never played.
        assertFalse(waitUntil(s -> !mVibratorProviders.get(1).getAllEffectSegments().isEmpty(),
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
        mVibratorProviders.get(1).setSupportedPrimitives(
                VibrationEffect.Composition.PRIMITIVE_CLICK);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        mVibratorProviders.get(2).setSupportedPrimitives(
                VibrationEffect.Composition.PRIMITIVE_CLICK);
        // Mock alarm intensity equals to default value to avoid scaling in this test.
        setUserSetting(Settings.System.ALARM_VIBRATION_INTENSITY,
                mVibrator.getDefaultVibrationIntensity(VibrationAttributes.USAGE_ALARM));
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
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(1).getAllEffectSegments());
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(2).getAllEffectSegments());

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
        assertTrue(waitUntil(s -> !fakeVibrator1.getAllEffectSegments().isEmpty(), service,
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
        assertTrue(waitUntil(s -> !fakeVibrator1.getAllEffectSegments().isEmpty(), service,
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
        assertTrue(waitUntil(s -> !mVibratorProviders.get(1).getAllEffectSegments().isEmpty(),
                service, TEST_TIMEOUT_MILLIS));

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
        assertTrue(waitUntil(s -> !mVibratorProviders.get(1).getAllEffectSegments().isEmpty(),
                service, TEST_TIMEOUT_MILLIS));

        verify(mNativeWrapperMock).prepareSynced(eq(new int[]{1, 2}));
        verify(mNativeWrapperMock).triggerSynced(anyLong());
        verify(mNativeWrapperMock, times(2)).cancelSynced(); // Trigger on service creation too.
    }

    @Test
    public void vibrate_withIntensitySettings_appliesSettingsToScaleVibrations() throws Exception {
        int defaultNotificationIntensity =
                mVibrator.getDefaultVibrationIntensity(VibrationAttributes.USAGE_NOTIFICATION);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                defaultNotificationIntensity < Vibrator.VIBRATION_INTENSITY_HIGH
                        ? defaultNotificationIntensity + 1
                        : defaultNotificationIntensity);

        int defaultTouchIntensity =
                mVibrator.getDefaultVibrationIntensity(VibrationAttributes.USAGE_TOUCH);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                defaultTouchIntensity > Vibrator.VIBRATION_INTENSITY_LOW
                        ? defaultTouchIntensity - 1
                        : defaultTouchIntensity);

        setUserSetting(Settings.System.ALARM_VIBRATION_INTENSITY,
                mVibrator.getDefaultVibrationIntensity(VibrationAttributes.USAGE_ALARM));
        setUserSetting(Settings.System.MEDIA_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, Vibrator.VIBRATION_INTENSITY_OFF);

        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL,
                IVibrator.CAP_COMPOSE_EFFECTS);
        fakeVibrator.setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK);
        VibratorManagerService service = createSystemReadyService();

        vibrate(service, VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .compose(), HAPTIC_FEEDBACK_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getAllEffectSegments().size() == 1,
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, CombinedVibration.startSequential()
                .addNext(1, VibrationEffect.createOneShot(100, 125))
                .combine(), NOTIFICATION_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getAllEffectSegments().size() == 2,
                service, TEST_TIMEOUT_MILLIS));

        vibrate(service, VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .compose(), ALARM_ATTRS);
        assertTrue(waitUntil(s -> fakeVibrator.getAllEffectSegments().size() == 3,
                service, TEST_TIMEOUT_MILLIS));

        // Ring vibrations have intensity OFF and are not played.
        vibrate(service, VibrationEffect.createOneShot(100, 125), RINGTONE_ATTRS);
        assertFalse(waitUntil(s -> fakeVibrator.getAllEffectSegments().size() > 3,
                service, /* timeout= */ 50));

        // Only 3 effects played successfully.
        assertEquals(3, fakeVibrator.getAllEffectSegments().size());

        // Haptic feedback vibrations will be scaled with SCALE_LOW or none if default is low.
        assertEquals(defaultTouchIntensity > Vibrator.VIBRATION_INTENSITY_LOW,
                0.5 > ((PrimitiveSegment) fakeVibrator.getAllEffectSegments().get(0)).getScale());

        // Notification vibrations will be scaled with SCALE_HIGH or none if default is high.
        assertEquals(defaultNotificationIntensity < Vibrator.VIBRATION_INTENSITY_HIGH,
                0.6 < fakeVibrator.getAmplitudes().get(0));

        // Alarm vibration will be scaled with SCALE_NONE.
        assertEquals(1f,
                ((PrimitiveSegment) fakeVibrator.getAllEffectSegments().get(2)).getScale(), 1e-5);
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
    public void vibrate_withVitualDisplayChange_ignoreVibrationFromVirtualDisplay()
            throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();
        mRegisteredVirtualDisplayListener.onVirtualDisplayCreated(VIRTUAL_DISPLAY_ID);

        vibrateWithDisplay(service,
                VIRTUAL_DISPLAY_ID,
                CombinedVibration.startParallel()
                        .addVibrator(1, VibrationEffect.createOneShot(1000, 100))
                        .combine(),
                HAPTIC_FEEDBACK_ATTRS);

        // Haptic feedback ignored when it's from a virtual display.
        assertFalse(waitUntil(s -> s.isVibrating(1), service, /* timeout= */ 50));

        mRegisteredVirtualDisplayListener.onVirtualDisplayRemoved(VIRTUAL_DISPLAY_ID);
        vibrateWithDisplay(service,
                VIRTUAL_DISPLAY_ID,
                CombinedVibration.startParallel()
                        .addVibrator(1, VibrationEffect.createOneShot(1000, 100))
                        .combine(),
                HAPTIC_FEEDBACK_ATTRS);
        // Haptic feedback played normally when the virtual display is removed.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

    }

    @Test
    public void vibrate_withAppsOnVitualDisplayChange_ignoreVibrationFromVirtualDisplay()
            throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();
        mRegisteredAppsOnVirtualDeviceListener.onAppsOnAnyVirtualDeviceChanged(
                new ArraySet<>(Arrays.asList(UID)));
        vibrateWithDisplay(service,
                Display.INVALID_DISPLAY,
                CombinedVibration.startParallel()
                        .addVibrator(1, VibrationEffect.createOneShot(1000, 100))
                        .combine(),
                HAPTIC_FEEDBACK_ATTRS);

        // Haptic feedback ignored when it's from an app running virtual display.
        assertFalse(waitUntil(s -> s.isVibrating(1), service, /* timeout= */ 50));

        mRegisteredAppsOnVirtualDeviceListener.onAppsOnAnyVirtualDeviceChanged(new ArraySet<>());
        vibrateWithDisplay(service,
                Display.INVALID_DISPLAY,
                CombinedVibration.startParallel()
                        .addVibrator(1, VibrationEffect.createOneShot(1000, 100))
                        .combine(),
                HAPTIC_FEEDBACK_ATTRS);
        // Haptic feedback played normally when the same app no long runs on a virtual display.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

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
    public void onExternalVibration_ignoreVibrationFromVirtualDevices() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        createSystemReadyService();

        IBinder binderToken = mock(IBinder.class);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME, AUDIO_ATTRS,
                mock(IExternalVibrationController.class), binderToken);
        int scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertNotEquals(IExternalVibratorService.SCALE_MUTE, scale);

        mRegisteredAppsOnVirtualDeviceListener.onAppsOnAnyVirtualDeviceChanged(
                new ArraySet<>(Arrays.asList(UID)));
        scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertEquals(IExternalVibratorService.SCALE_MUTE, scale);
    }

    @Test
    public void onExternalVibration_setsExternalControl() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        createSystemReadyService();

        IBinder binderToken = mock(IBinder.class);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME, AUDIO_ATTRS,
                mock(IExternalVibrationController.class), binderToken);
        int scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        mExternalVibratorService.onExternalVibrationStop(externalVibration);

        assertNotEquals(IExternalVibratorService.SCALE_MUTE, scale);
        assertEquals(Arrays.asList(false, true, false),
                mVibratorProviders.get(1).getExternalControlStates());

        verify(binderToken).linkToDeath(any(), eq(0));
        verify(binderToken).unlinkToDeath(any(), eq(0));
    }

    @Test
    public void onExternalVibration_withOngoingExternalVibration_mutesPreviousVibration()
            throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        createSystemReadyService();

        IBinder firstToken = mock(IBinder.class);
        IBinder secondToken = mock(IBinder.class);
        IExternalVibrationController firstController = mock(IExternalVibrationController.class);
        IExternalVibrationController secondController = mock(IExternalVibrationController.class);
        ExternalVibration firstVibration = new ExternalVibration(UID, PACKAGE_NAME, AUDIO_ATTRS,
                firstController, firstToken);
        int firstScale = mExternalVibratorService.onExternalVibrationStart(firstVibration);

        AudioAttributes ringtoneAudioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build();
        ExternalVibration secondVibration = new ExternalVibration(UID, PACKAGE_NAME,
                ringtoneAudioAttrs, secondController, secondToken);
        int secondScale = mExternalVibratorService.onExternalVibrationStart(secondVibration);

        assertNotEquals(IExternalVibratorService.SCALE_MUTE, firstScale);
        assertNotEquals(IExternalVibratorService.SCALE_MUTE, secondScale);
        verify(firstController).mute();
        verify(secondController, never()).mute();
        // Set external control called only once.
        assertEquals(Arrays.asList(false, true),
                mVibratorProviders.get(1).getExternalControlStates());

        mExternalVibratorService.onExternalVibrationStop(secondVibration);
        mExternalVibratorService.onExternalVibrationStop(firstVibration);
        assertEquals(Arrays.asList(false, true, false),
                mVibratorProviders.get(1).getExternalControlStates());

        verify(firstToken).linkToDeath(any(), eq(0));
        verify(firstToken).unlinkToDeath(any(), eq(0));

        verify(secondToken).linkToDeath(any(), eq(0));
        verify(secondToken).unlinkToDeath(any(), eq(0));
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
        assertNotEquals(IExternalVibratorService.SCALE_MUTE, scale);

        // Vibration is cancelled.
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
        assertEquals(Arrays.asList(false, true),
                mVibratorProviders.get(1).getExternalControlStates());
    }

    @Test
    public void onExternalVibration_withRingtone_usesRingerModeSettings() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        AudioAttributes audioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build();
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME, audioAttrs,
                mock(IExternalVibrationController.class));

        setRingerMode(AudioManager.RINGER_MODE_SILENT);
        createSystemReadyService();
        int scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertEquals(IExternalVibratorService.SCALE_MUTE, scale);

        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        createSystemReadyService();
        scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertNotEquals(IExternalVibratorService.SCALE_MUTE, scale);

        setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        createSystemReadyService();
        scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertNotEquals(IExternalVibratorService.SCALE_MUTE, scale);
    }

    @Test
    public void onExternalVibration_withBypassMuteAudioFlag_ignoresUserSettings() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        setUserSetting(Settings.System.ALARM_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        AudioAttributes audioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build();
        AudioAttributes flaggedAudioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setFlags(AudioAttributes.FLAG_BYPASS_MUTE)
                .build();
        createSystemReadyService();

        int scale = mExternalVibratorService.onExternalVibrationStart(
                new ExternalVibration(UID, PACKAGE_NAME, audioAttrs,
                        mock(IExternalVibrationController.class)));
        assertEquals(IExternalVibratorService.SCALE_MUTE, scale);

        createSystemReadyService();
        scale = mExternalVibratorService.onExternalVibrationStart(
                new ExternalVibration(UID, PACKAGE_NAME, flaggedAudioAttrs,
                        mock(IExternalVibrationController.class)));
        assertNotEquals(IExternalVibratorService.SCALE_MUTE, scale);
    }

    @Test
    public void onExternalVibration_withUnknownUsage_appliesMediaSettings() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        setUserSetting(Settings.System.MEDIA_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        AudioAttributes flaggedAudioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_UNKNOWN)
                .setFlags(AudioAttributes.FLAG_BYPASS_MUTE)
                .build();
        createSystemReadyService();

        int scale = mExternalVibratorService.onExternalVibrationStart(
                new ExternalVibration(/* uid= */ 123, PACKAGE_NAME, flaggedAudioAttrs,
                        mock(IExternalVibrationController.class)));
        assertEquals(IExternalVibratorService.SCALE_MUTE, scale);
    }

    @Test
    public void frameworkStats_externalVibration_reportsAllMetrics() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        createSystemReadyService();

        AudioAttributes audioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build();

        ExternalVibration vib = new ExternalVibration(UID, PACKAGE_NAME, audioAttrs,
                mock(IExternalVibrationController.class));
        mExternalVibratorService.onExternalVibrationStart(vib);

        Thread.sleep(10);
        mExternalVibratorService.onExternalVibrationStop(vib);

        ArgumentCaptor<VibrationStats.StatsInfo> argumentCaptor =
                ArgumentCaptor.forClass(VibrationStats.StatsInfo.class);
        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibrationReportedAsync(argumentCaptor.capture());

        VibrationStats.StatsInfo statsInfo = argumentCaptor.getValue();
        assertEquals(UID, statsInfo.uid);
        assertEquals(FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__EXTERNAL,
                statsInfo.vibrationType);
        assertEquals(VibrationAttributes.USAGE_ALARM, statsInfo.usage);
        assertEquals(Vibration.Status.FINISHED.getProtoEnumValue(), statsInfo.status);
        assertTrue(statsInfo.totalDurationMillis > 0);
        assertTrue(
                "Expected vibrator ON for at least 10ms, got " + statsInfo.vibratorOnMillis + "ms",
                statsInfo.vibratorOnMillis >= 10);
        assertEquals(2, statsInfo.halSetExternalControlCount);
    }

    @Test
    public void frameworkStats_waveformVibration_reportsAllMetrics() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        VibratorManagerService service = createSystemReadyService();
        vibrateAndWaitUntilFinished(service,
                VibrationEffect.createWaveform(new long[] {0, 10, 20, 10}, -1), RINGTONE_ATTRS);

        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibratorStateOnAsync(eq(UID), anyLong());
        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibratorStateOffAsync(eq(UID));

        ArgumentCaptor<VibrationStats.StatsInfo> argumentCaptor =
                ArgumentCaptor.forClass(VibrationStats.StatsInfo.class);
        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibrationReportedAsync(argumentCaptor.capture());

        VibrationStats.StatsInfo metrics = argumentCaptor.getValue();
        assertEquals(UID, metrics.uid);
        assertEquals(FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__SINGLE,
                metrics.vibrationType);
        assertEquals(VibrationAttributes.USAGE_RINGTONE, metrics.usage);
        assertEquals(Vibration.Status.FINISHED.getProtoEnumValue(), metrics.status);
        assertTrue("Total duration was too low, " + metrics.totalDurationMillis + "ms",
                metrics.totalDurationMillis >= 20);
        assertTrue("Vibrator ON duration was too low, " + metrics.vibratorOnMillis + "ms",
                metrics.vibratorOnMillis >= 20);

        // All unrelated metrics are empty.
        assertEquals(0, metrics.repeatCount);
        assertEquals(0, metrics.halComposeCount);
        assertEquals(0, metrics.halComposePwleCount);
        assertEquals(0, metrics.halPerformCount);
        assertEquals(0, metrics.halSetExternalControlCount);
        assertEquals(0, metrics.halCompositionSize);
        assertEquals(0, metrics.halPwleSize);
        assertNull(metrics.halSupportedCompositionPrimitivesUsed);
        assertNull(metrics.halSupportedEffectsUsed);
        assertNull(metrics.halUnsupportedCompositionPrimitivesUsed);
        assertNull(metrics.halUnsupportedEffectsUsed);

        // Accommodate for ramping off config that might add extra setAmplitudes.
        assertEquals(2, metrics.halOnCount);
        assertTrue(metrics.halOffCount > 0);
        assertTrue(metrics.halSetAmplitudeCount >= 2);
    }

    @Test
    public void frameworkStats_repeatingVibration_reportsAllMetrics() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        VibratorManagerService service = createSystemReadyService();
        vibrate(service, VibrationEffect.createWaveform(new long[] {10, 100}, 1), RINGTONE_ATTRS);

        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibratorStateOnAsync(eq(UID), anyLong());

        // Wait for at least one loop before cancelling it.
        Thread.sleep(100);
        service.cancelVibrate(VibrationAttributes.USAGE_RINGTONE, service);

        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibratorStateOffAsync(eq(UID));

        ArgumentCaptor<VibrationStats.StatsInfo> argumentCaptor =
                ArgumentCaptor.forClass(VibrationStats.StatsInfo.class);
        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibrationReportedAsync(argumentCaptor.capture());

        VibrationStats.StatsInfo metrics = argumentCaptor.getValue();
        assertEquals(UID, metrics.uid);
        assertEquals(FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__REPEATED,
                metrics.vibrationType);
        assertEquals(VibrationAttributes.USAGE_RINGTONE, metrics.usage);
        assertEquals(Vibration.Status.CANCELLED_BY_USER.getProtoEnumValue(), metrics.status);
        assertTrue("Total duration was too low, " + metrics.totalDurationMillis + "ms",
                metrics.totalDurationMillis >= 100);
        assertTrue("Vibrator ON duration was too low, " + metrics.vibratorOnMillis + "ms",
                metrics.vibratorOnMillis >= 100);

        // All unrelated metrics are empty.
        assertTrue(metrics.repeatCount > 0);
        assertEquals(0, metrics.halComposeCount);
        assertEquals(0, metrics.halComposePwleCount);
        assertEquals(0, metrics.halPerformCount);
        assertEquals(0, metrics.halSetExternalControlCount);
        assertEquals(0, metrics.halCompositionSize);
        assertEquals(0, metrics.halPwleSize);
        assertNull(metrics.halSupportedCompositionPrimitivesUsed);
        assertNull(metrics.halSupportedEffectsUsed);
        assertNull(metrics.halUnsupportedCompositionPrimitivesUsed);
        assertNull(metrics.halUnsupportedEffectsUsed);

        // Accommodate for ramping off config that might add extra setAmplitudes.
        assertTrue(metrics.halOnCount > 0);
        assertTrue(metrics.halOffCount > 0);
        assertTrue(metrics.halSetAmplitudeCount > 0);
    }

    @Test
    public void frameworkStats_prebakedAndComposedVibrations_reportsAllMetrics() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(1).setSupportedPrimitives(
                VibrationEffect.Composition.PRIMITIVE_TICK);

        VibratorManagerService service = createSystemReadyService();
        vibrateAndWaitUntilFinished(service,
                VibrationEffect.startComposition()
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .compose(),
                ALARM_ATTRS);

        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibratorStateOnAsync(eq(UID), anyLong());
        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibratorStateOffAsync(eq(UID));

        ArgumentCaptor<VibrationStats.StatsInfo> argumentCaptor =
                ArgumentCaptor.forClass(VibrationStats.StatsInfo.class);
        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibrationReportedAsync(argumentCaptor.capture());

        VibrationStats.StatsInfo metrics = argumentCaptor.getValue();
        assertEquals(UID, metrics.uid);
        assertEquals(FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__SINGLE,
                metrics.vibrationType);
        assertEquals(VibrationAttributes.USAGE_ALARM, metrics.usage);
        assertEquals(Vibration.Status.FINISHED.getProtoEnumValue(), metrics.status);

        // At least 4 effect/primitive played, 20ms each, plus configured fallback.
        assertTrue("Total duration was too low, " + metrics.totalDurationMillis + "ms",
                metrics.totalDurationMillis >= 80);
        assertTrue("Vibrator ON duration was too low, " + metrics.vibratorOnMillis + "ms",
                metrics.vibratorOnMillis >= 80);

        // Related metrics were collected.
        assertEquals(2, metrics.halComposeCount); // TICK+TICK, then CLICK+CLICK
        assertEquals(3, metrics.halPerformCount); // CLICK, TICK, then CLICK
        assertEquals(4, metrics.halCompositionSize); // 2*TICK + 2*CLICK
        // No repetitions in reported effect/primitive IDs.
        assertArrayEquals(new int[] {VibrationEffect.Composition.PRIMITIVE_TICK},
                metrics.halSupportedCompositionPrimitivesUsed);
        assertArrayEquals(new int[] {VibrationEffect.Composition.PRIMITIVE_CLICK},
                metrics.halUnsupportedCompositionPrimitivesUsed);
        assertArrayEquals(new int[] {VibrationEffect.EFFECT_CLICK},
                metrics.halSupportedEffectsUsed);
        assertArrayEquals(new int[] {VibrationEffect.EFFECT_TICK},
                metrics.halUnsupportedEffectsUsed);

        // All unrelated metrics are empty.
        assertEquals(0, metrics.repeatCount);
        assertEquals(0, metrics.halComposePwleCount);
        assertEquals(0, metrics.halSetExternalControlCount);
        assertEquals(0, metrics.halPwleSize);

        // Accommodate for ramping off config that might add extra setAmplitudes
        // for the effect that plays the fallback instead of "perform".
        assertTrue(metrics.halOnCount > 0);
        assertTrue(metrics.halOffCount > 0);
        assertTrue(metrics.halSetAmplitudeCount > 0);
    }

    @Test
    public void frameworkStats_interruptingVibrations_reportsAllMetrics() throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        vibrate(service, VibrationEffect.createOneShot(1_000, 128), HAPTIC_FEEDBACK_ATTRS);

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
        assertTrue(waitUntil(s -> !mVibratorProviders.get(1).getAllEffectSegments().isEmpty(),
                service, TEST_TIMEOUT_MILLIS));

        vibrateAndWaitUntilFinished(service, VibrationEffect.createOneShot(10, 255), ALARM_ATTRS);

        ArgumentCaptor<VibrationStats.StatsInfo> argumentCaptor =
                ArgumentCaptor.forClass(VibrationStats.StatsInfo.class);
        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS).times(2))
                .writeVibrationReportedAsync(argumentCaptor.capture());

        VibrationStats.StatsInfo touchMetrics = argumentCaptor.getAllValues().get(0);
        assertEquals(UID, touchMetrics.uid);
        assertEquals(VibrationAttributes.USAGE_TOUCH, touchMetrics.usage);
        assertEquals(Vibration.Status.CANCELLED_SUPERSEDED.getProtoEnumValue(),
                touchMetrics.status);
        assertTrue(touchMetrics.endedBySameUid);
        assertEquals(VibrationAttributes.USAGE_ALARM, touchMetrics.endedByUsage);
        assertEquals(-1, touchMetrics.interruptedUsage);

        VibrationStats.StatsInfo alarmMetrics = argumentCaptor.getAllValues().get(1);
        assertEquals(UID, alarmMetrics.uid);
        assertEquals(VibrationAttributes.USAGE_ALARM, alarmMetrics.usage);
        assertEquals(Vibration.Status.FINISHED.getProtoEnumValue(), alarmMetrics.status);
        assertFalse(alarmMetrics.endedBySameUid);
        assertEquals(-1, alarmMetrics.endedByUsage);
        assertEquals(VibrationAttributes.USAGE_TOUCH, alarmMetrics.interruptedUsage);
    }

    @Test
    public void frameworkStats_ignoredVibration_reportsStatus() throws Exception {
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);

        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();
        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        // Haptic feedback ignored in low power state
        vibrateAndWaitUntilFinished(service, VibrationEffect.createOneShot(100, 128),
                HAPTIC_FEEDBACK_ATTRS);
        // Ringtone vibration user settings are off
        vibrateAndWaitUntilFinished(service, VibrationEffect.createOneShot(200, 128),
                RINGTONE_ATTRS);

        ArgumentCaptor<VibrationStats.StatsInfo> argumentCaptor =
                ArgumentCaptor.forClass(VibrationStats.StatsInfo.class);
        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS).times(2))
                .writeVibrationReportedAsync(argumentCaptor.capture());

        VibrationStats.StatsInfo touchMetrics = argumentCaptor.getAllValues().get(0);
        assertEquals(UID, touchMetrics.uid);
        assertEquals(VibrationAttributes.USAGE_TOUCH, touchMetrics.usage);
        assertEquals(Vibration.Status.IGNORED_FOR_POWER.getProtoEnumValue(), touchMetrics.status);

        VibrationStats.StatsInfo ringtoneMetrics = argumentCaptor.getAllValues().get(1);
        assertEquals(UID, ringtoneMetrics.uid);
        assertEquals(VibrationAttributes.USAGE_RINGTONE, ringtoneMetrics.usage);
        assertEquals(Vibration.Status.IGNORED_FOR_SETTINGS.getProtoEnumValue(),
                ringtoneMetrics.status);

        for (VibrationStats.StatsInfo metrics : argumentCaptor.getAllValues()) {
            // Latencies are empty since vibrations never started
            assertEquals(0, metrics.startLatencyMillis);
            assertEquals(0, metrics.endLatencyMillis);
            assertEquals(0, metrics.vibratorOnMillis);

            // All unrelated metrics are empty.
            assertEquals(0, metrics.repeatCount);
            assertEquals(0, metrics.halComposeCount);
            assertEquals(0, metrics.halComposePwleCount);
            assertEquals(0, metrics.halOffCount);
            assertEquals(0, metrics.halOnCount);
            assertEquals(0, metrics.halPerformCount);
            assertEquals(0, metrics.halSetExternalControlCount);
            assertEquals(0, metrics.halCompositionSize);
            assertEquals(0, metrics.halPwleSize);
            assertNull(metrics.halSupportedCompositionPrimitivesUsed);
            assertNull(metrics.halSupportedEffectsUsed);
            assertNull(metrics.halUnsupportedCompositionPrimitivesUsed);
            assertNull(metrics.halUnsupportedEffectsUsed);
        }
    }

    @Test
    public void frameworkStats_multiVibrators_reportsAllMetrics() throws Exception {
        mockVibrators(1, 2);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        mVibratorProviders.get(1).setSupportedPrimitives(
                VibrationEffect.Composition.PRIMITIVE_TICK);
        mVibratorProviders.get(2).setSupportedEffects(VibrationEffect.EFFECT_TICK);

        VibratorManagerService service = createSystemReadyService();
        vibrateAndWaitUntilFinished(service,
                CombinedVibration.startParallel()
                        .addVibrator(1,
                                VibrationEffect.startComposition()
                                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                                        .compose())
                        .addVibrator(2,
                                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        .combine(),
                NOTIFICATION_ATTRS);

        SparseBooleanArray expectedEffectsUsed = new SparseBooleanArray();
        expectedEffectsUsed.put(VibrationEffect.EFFECT_TICK, true);

        SparseBooleanArray expectedPrimitivesUsed = new SparseBooleanArray();
        expectedPrimitivesUsed.put(VibrationEffect.Composition.PRIMITIVE_TICK, true);

        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibratorStateOnAsync(eq(UID), anyLong());
        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibratorStateOffAsync(eq(UID));

        ArgumentCaptor<VibrationStats.StatsInfo> argumentCaptor =
                ArgumentCaptor.forClass(VibrationStats.StatsInfo.class);
        verify(mVibratorFrameworkStatsLoggerMock, timeout(TEST_TIMEOUT_MILLIS))
                .writeVibrationReportedAsync(argumentCaptor.capture());

        VibrationStats.StatsInfo metrics = argumentCaptor.getValue();
        assertEquals(UID, metrics.uid);
        assertEquals(FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__SINGLE,
                metrics.vibrationType);
        assertEquals(VibrationAttributes.USAGE_NOTIFICATION, metrics.usage);
        assertEquals(Vibration.Status.FINISHED.getProtoEnumValue(), metrics.status);
        assertTrue(metrics.totalDurationMillis >= 20);

        // vibratorOnMillis accumulates both vibrators, it's 20 for each constant.
        assertEquals(40, metrics.vibratorOnMillis);

        // Related metrics were collected.
        assertEquals(1, metrics.halComposeCount);
        assertEquals(1, metrics.halPerformCount);
        assertEquals(1, metrics.halCompositionSize);
        assertEquals(2, metrics.halOffCount);
        assertArrayEquals(new int[] {VibrationEffect.Composition.PRIMITIVE_TICK},
                metrics.halSupportedCompositionPrimitivesUsed);
        assertArrayEquals(new int[] {VibrationEffect.EFFECT_TICK},
                metrics.halSupportedEffectsUsed);

        // All unrelated metrics are empty.
        assertEquals(0, metrics.repeatCount);
        assertEquals(0, metrics.halComposePwleCount);
        assertEquals(0, metrics.halOnCount);
        assertEquals(0, metrics.halSetAmplitudeCount);
        assertEquals(0, metrics.halSetExternalControlCount);
        assertEquals(0, metrics.halPwleSize);
        assertNull(metrics.halUnsupportedCompositionPrimitivesUsed);
        assertNull(metrics.halUnsupportedEffectsUsed);
    }

    private VibrationEffectSegment expectedPrebaked(int effectId) {
        return expectedPrebaked(effectId, VibrationEffect.EFFECT_STRENGTH_MEDIUM);
    }

    private VibrationEffectSegment expectedPrebaked(int effectId, int effectStrength) {
        return new PrebakedSegment(effectId, false, effectStrength);
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

    private void vibrateAndWaitUntilFinished(VibratorManagerService service, VibrationEffect effect,
            VibrationAttributes attrs) throws InterruptedException {
        vibrateAndWaitUntilFinished(service, CombinedVibration.createParallel(effect), attrs);
    }

    private void vibrateAndWaitUntilFinished(VibratorManagerService service,
            CombinedVibration effect, VibrationAttributes attrs) throws InterruptedException {
        Vibration vib =
                service.vibrateInternal(UID, Display.DEFAULT_DISPLAY, PACKAGE_NAME, effect, attrs,
                        "some reason", service);
        if (vib != null) {
            vib.waitForEnd();
        }
    }

    private void vibrate(VibratorManagerService service, VibrationEffect effect,
            VibrationAttributes attrs) {
        vibrate(service, CombinedVibration.createParallel(effect), attrs);
    }

    private void vibrate(VibratorManagerService service, CombinedVibration effect,
            VibrationAttributes attrs) {
        vibrateWithDisplay(service, Display.DEFAULT_DISPLAY, effect, attrs);
    }

    private void vibrateWithDisplay(VibratorManagerService service, int displayId,
            CombinedVibration effect, VibrationAttributes attrs) {
        service.vibrate(UID, displayId, PACKAGE_NAME, effect, attrs, "some reason", service);
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
