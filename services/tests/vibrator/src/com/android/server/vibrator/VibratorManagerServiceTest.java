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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.frameworks.vibrator.ScaleParam;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerGlobal;
import android.hardware.vibrator.IVibrator;
import android.hardware.vibrator.IVibratorManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.CombinedVibration;
import android.os.ExternalVibration;
import android.os.ExternalVibrationScale;
import android.os.Handler;
import android.os.IBinder;
import android.os.IExternalVibrationController;
import android.os.IVibratorStateListener;
import android.os.Looper;
import android.os.PersistableBundle;
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
import android.os.test.FakeVibrator;
import android.os.test.TestLooper;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationConfig;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.LocalServices;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.pm.BackgroundUserSoundNotifier;
import com.android.server.vibrator.VibrationSession.Status;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class VibratorManagerServiceTest {

    private static final int TEST_TIMEOUT_MILLIS = 1_000;
    // Time to allow for a cancellation to complete and the vibrators to become idle.
    private static final int CLEANUP_TIMEOUT_MILLIS = 100;
    private static final int UID = Process.ROOT_UID;
    private static final int VIRTUAL_DEVICE_ID = 1;
    private static final String PACKAGE_NAME = "package";
    private static final PowerSaveState NORMAL_POWER_STATE = new PowerSaveState.Builder().build();
    private static final PowerSaveState LOW_POWER_STATE = new PowerSaveState.Builder()
            .setBatterySaverEnabled(true).build();
    private static final AudioAttributes AUDIO_ALARM_ATTRS =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
    private static final AudioAttributes AUDIO_NOTIFICATION_ATTRS =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build();
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
    private static final VibrationAttributes IME_FEEDBACK_ATTRS =
            new VibrationAttributes.Builder().setUsage(
                    VibrationAttributes.USAGE_IME_FEEDBACK).build();
    private static final VibrationAttributes UNKNOWN_ATTRS =
            new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_UNKNOWN).build();

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();
    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

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
    @Mock
    private AudioManager mAudioManagerMock;

    private final Map<Integer, FakeVibratorControllerProvider> mVibratorProviders = new HashMap<>();
    private final SparseArray<VibrationEffect>  mHapticFeedbackVibrationMap = new SparseArray<>();
    private final SparseArray<VibrationEffect>  mHapticFeedbackVibrationMapSourceRotary =
            new SparseArray<>();
    private final SparseArray<VibrationEffect>  mHapticFeedbackVibrationMapSourceTouchScreen =
            new SparseArray<>();

    private final List<HalVibration> mPendingVibrations = new ArrayList<>();

    private VibratorManagerService mService;
    private Context mContextSpy;
    private TestLooper mTestLooper;
    private FakeVibrator mVibrator;
    private FakeVibratorController mFakeVibratorController;
    private PowerManagerInternal.LowPowerModeListener mRegisteredPowerModeListener;
    private VibratorManagerService.ExternalVibratorService mExternalVibratorService;
    private VibratorControlService mVibratorControlService;
    private VibrationConfig mVibrationConfig;
    private InputManagerGlobal.TestSession mInputManagerGlobalSession;
    private InputManager mInputManager;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        mInputManagerGlobalSession = InputManagerGlobal.createTestSession(mIInputManagerMock);
        mVibrationConfig = new VibrationConfig(mContextSpy.getResources());
        mFakeVibratorController = new FakeVibratorController(mTestLooper.getLooper());

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);

        mVibrator = new FakeVibrator(mContextSpy);
        when(mContextSpy.getSystemService(eq(Context.VIBRATOR_SERVICE))).thenReturn(mVibrator);

        mInputManager = new InputManager(mContextSpy);
        when(mContextSpy.getSystemService(eq(Context.INPUT_SERVICE)))
                .thenReturn(mInputManager);
        when(mContextSpy.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManagerMock);
        when(mContextSpy.getSystemService(eq(Context.AUDIO_SERVICE))).thenReturn(mAudioManagerMock);
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
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        addLocalServiceMock(PackageManagerInternal.class, mPackageManagerInternalMock);
        addLocalServiceMock(PowerManagerInternal.class, mPowerManagerInternalMock);
        addLocalServiceMock(VirtualDeviceManagerInternal.class, mVirtualDeviceManagerInternalMock);

        mTestLooper.startAutoDispatch();
    }

    @After
    public void tearDown() throws Exception {
        if (mService != null) {
            // Make sure we have permission to cancel test vibrations, even if the test denied them.
            grantPermission(android.Manifest.permission.VIBRATE);
            // Cancel any pending vibration from tests, including external vibrations.
            cancelVibrate(mService);
            // Wait until pending vibrations end asynchronously.
            for (HalVibration vibration : mPendingVibrations) {
                vibration.waitForEnd();
            }
            // Wait until all vibrators have stopped vibrating, waiting for ramp-down.
            // Note: if a test is flaky here something is wrong with the vibration finalization.
            assertTrue(waitUntil(s -> {
                for (int vibratorId : mService.getVibratorIds()) {
                    if (s.isVibrating(vibratorId)) {
                        return false;
                    }
                }
                return true;
            }, mService, mVibrationConfig.getRampDownDurationMs() + CLEANUP_TIMEOUT_MILLIS));
        }

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal.class);
        // Ignore potential exceptions about the looper having never dispatched any messages.
        mTestLooper.stopAutoDispatchAndIgnoreExceptions();
        if (mInputManagerGlobalSession != null) {
            mInputManagerGlobalSession.close();
        }
    }

    private VibratorManagerService createSystemReadyService() {
        VibratorManagerService service = createService();
        service.systemReady();
        return service;
    }

    private VibratorManagerService createService() {
        mService = new VibratorManagerService(
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
                        if (service instanceof VibratorManagerService.ExternalVibratorService) {
                            mExternalVibratorService =
                                    (VibratorManagerService.ExternalVibratorService) service;
                        } else if (service instanceof VibratorControlService) {
                            mVibratorControlService = (VibratorControlService) service;
                            mFakeVibratorController.setVibratorControlService(
                                    mVibratorControlService);
                        }
                    }

                    @Override
                    HapticFeedbackVibrationProvider createHapticFeedbackVibrationProvider(
                            Resources resources, VibratorInfo vibratorInfo) {
                        return new HapticFeedbackVibrationProvider(resources, vibratorInfo,
                                new HapticFeedbackCustomization(mHapticFeedbackVibrationMap,
                                        mHapticFeedbackVibrationMapSourceRotary,
                                        mHapticFeedbackVibrationMapSourceTouchScreen));
                    }

                    @Override
                    VibratorControllerHolder createVibratorControllerHolder() {
                        VibratorControllerHolder holder = new VibratorControllerHolder();
                        holder.setVibratorController(mFakeVibratorController);
                        return holder;
                    }

                    @Override
                    boolean isServiceDeclared(String name) {
                        return true;
                    }
                });
        return mService;
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

        long oneShotDuration = 20;
        vibrateAndWaitUntilFinished(service,
                VibrationEffect.createOneShot(oneShotDuration, VibrationEffect.DEFAULT_AMPLITUDE),
                ALARM_ATTRS);

        InOrder inOrderVerifier = inOrder(listenerMock);
        // First notification done when listener is registered.
        inOrderVerifier.verify(listenerMock).onVibrating(eq(false));
        // Vibrator on notification done before vibration ended, no need to wait.
        inOrderVerifier.verify(listenerMock).onVibrating(eq(true));
        // Vibrator off notification done after vibration completed, wait for notification.
        inOrderVerifier.verify(listenerMock, timeout(TEST_TIMEOUT_MILLIS)).onVibrating(eq(false));
        inOrderVerifier.verifyNoMoreInteractions();

        InOrder batteryVerifier = inOrder(mBatteryStatsMock);
        batteryVerifier.verify(mBatteryStatsMock)
                .noteVibratorOn(UID, oneShotDuration + mVibrationConfig.getRampDownDurationMs());
        batteryVerifier
                .verify(mBatteryStatsMock, timeout(TEST_TIMEOUT_MILLIS)).noteVibratorOff(UID);
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
        // Vibrator on notification done before vibration ended, no need to wait.
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

        vibrateAndWaitUntilFinished(service, CombinedVibration.startParallel()
                .addVibrator(0, VibrationEffect.createOneShot(40, 100))
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .combine(), ALARM_ATTRS);

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
    public void vibrate_withoutVibratePermission_throwsSecurityException() {
        denyPermission(android.Manifest.permission.VIBRATE);
        VibratorManagerService service = createSystemReadyService();

        assertThrows("Expected vibrating without permission to fail!",
                SecurityException.class,
                () -> vibrate(service,
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)));
    }

    @Test
    public void vibrate_withoutBypassFlagsPermissions_bypassFlagsNotApplied() throws Exception {
        denyPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        denyPermission(android.Manifest.permission.MODIFY_PHONE_STATE);
        denyPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING);

        assertCanVibrateWithBypassFlags(false);
    }

    @Test
    public void vibrate_withSecureSettingsPermission_bypassFlagsApplied() throws Exception {
        grantPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        denyPermission(android.Manifest.permission.MODIFY_PHONE_STATE);
        denyPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING);

        assertCanVibrateWithBypassFlags(true);
    }

    @Test
    public void vibrate_withModifyPhoneStatePermission_bypassFlagsApplied() throws Exception {
        denyPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        grantPermission(android.Manifest.permission.MODIFY_PHONE_STATE);
        denyPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING);

        assertCanVibrateWithBypassFlags(true);
    }

    @Test
    public void vibrate_withModifyAudioRoutingPermission_bypassFlagsApplied() throws Exception {
        denyPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        denyPermission(android.Manifest.permission.MODIFY_PHONE_STATE);
        grantPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING);

        assertCanVibrateWithBypassFlags(true);
    }

    @Test
    public void vibrate_withRingtone_usesRingerModeSettings() throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_DOUBLE_CLICK);

        setRingerMode(AudioManager.RINGER_MODE_SILENT);
        VibratorManagerService service = createSystemReadyService();
        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                RINGTONE_ATTRS);

        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        service = createSystemReadyService();
        vibrateAndWaitUntilFinished(
                service, VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK), RINGTONE_ATTRS);

        setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        service = createSystemReadyService();
        vibrateAndWaitUntilFinished(
                service, VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK), RINGTONE_ATTRS);

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
        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_TICK),
                HAPTIC_FEEDBACK_ATTRS);
        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                RINGTONE_ATTRS);

        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);
        vibrateAndWaitUntilFinished(service,
                VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK), /* attrs= */ null);
        vibrateAndWaitUntilFinished(service,
                VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK), NOTIFICATION_ATTRS);

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
    @EnableFlags(android.os.vibrator.Flags.FLAG_CANCEL_BY_APPOPS)
    public void vibrate_thenDeniedAppOps_getsCancelled() throws Throwable {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        HalVibration vib = vibrate(service, VibrationEffect.createWaveform(
                new long[]{0, TEST_TIMEOUT_MILLIS, TEST_TIMEOUT_MILLIS}, 0), RINGTONE_ATTRS);

        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        when(mAppOpsManagerMock.checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                eq(AudioAttributes.USAGE_NOTIFICATION_RINGTONE), anyInt(), anyString()))
                .thenReturn(AppOpsManager.MODE_IGNORED);

        service.mAppOpsChangeListener.onOpChanged(AppOpsManager.OP_VIBRATE, null);

        assertTrue(waitUntil(s -> vib.hasEnded(), service, TEST_TIMEOUT_MILLIS));
        assertThat(vib.getStatus()).isEqualTo(Status.CANCELLED_BY_APP_OPS);
    }

    @Test
    public void vibrate_thenPowerModeChanges_getsCancelled() throws Exception {
        mockVibrators(1, 2);
        VibratorManagerService service = createSystemReadyService();

        HalVibration vib = vibrate(service,
                CombinedVibration.startParallel()
                        .addVibrator(1, VibrationEffect.createOneShot(2 * TEST_TIMEOUT_MILLIS, 100))
                        .combine(),
                HAPTIC_FEEDBACK_ATTRS);

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        assertTrue(waitUntil(s -> vib.hasEnded(), service, TEST_TIMEOUT_MILLIS));
        assertThat(vib.getStatus()).isEqualTo(Status.CANCELLED_BY_SETTINGS_UPDATE);
    }

    @Test
    public void vibrate_thenSettingsRefreshedWithoutChange_doNotCancelVibration() throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        vibrate(service, VibrationEffect.createOneShot(2 * TEST_TIMEOUT_MILLIS, 100),
                HAPTIC_FEEDBACK_ATTRS);

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        service.updateServiceState();

        // Vibration is not stopped nearly after updating service.
        assertFalse(waitUntil(s -> !s.isVibrating(1), service, 50));
    }

    @Test
    public void vibrate_thenSettingsChange_getsCancelled() throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        HalVibration vib = vibrate(service,
                VibrationEffect.createOneShot(2 * TEST_TIMEOUT_MILLIS, 100),
                HAPTIC_FEEDBACK_ATTRS);

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, Vibrator.VIBRATION_INTENSITY_OFF);
        service.mVibrationSettings.mSettingObserver.onChange(false);
        service.updateServiceState();

        assertTrue(waitUntil(s -> vib.hasEnded(), service, TEST_TIMEOUT_MILLIS));
        assertThat(vib.getStatus()).isEqualTo(Status.CANCELLED_BY_SETTINGS_UPDATE);
    }

    @Test
    public void vibrate_thenScreenTurnsOff_getsCancelled() throws Throwable {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        HalVibration vib = vibrate(service, VibrationEffect.createWaveform(
                new long[]{0, TEST_TIMEOUT_MILLIS, TEST_TIMEOUT_MILLIS}, 0), ALARM_ATTRS);

        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        service.mIntentReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_SCREEN_OFF));

        assertTrue(waitUntil(s -> vib.hasEnded(), service, TEST_TIMEOUT_MILLIS));
        assertThat(vib.getStatus()).isEqualTo(Status.CANCELLED_BY_SCREEN_OFF);
    }

    @Test
    @EnableFlags(android.multiuser.Flags.FLAG_ADD_UI_FOR_SOUNDS_FROM_BACKGROUND_USERS)
    public void vibrate_thenFgUserRequestsMute_getsCancelled() throws Throwable {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        HalVibration vib = vibrate(service, VibrationEffect.createWaveform(
                new long[]{0, TEST_TIMEOUT_MILLIS, TEST_TIMEOUT_MILLIS}, 0), ALARM_ATTRS);

        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));


        service.mIntentReceiver.onReceive(mContextSpy, new Intent(
                BackgroundUserSoundNotifier.ACTION_MUTE_SOUND));

        assertTrue(waitUntil(s -> vib.hasEnded(), service, TEST_TIMEOUT_MILLIS));
        assertThat(vib.getStatus()).isEqualTo(Status.CANCELLED_BY_FOREGROUND_USER);
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
        vibrate(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), IME_FEEDBACK_ATTRS);

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
        inOrderVerifier.verify(mAppOpsManagerMock).checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                eq(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION), anyInt(), anyString());
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

        VibrationAttributes notificationWithFreshAttrs = new VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
                .setFlags(VibrationAttributes.FLAG_INVALIDATE_SETTINGS_CACHE)
                .build();

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                NOTIFICATION_ATTRS);
        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_TICK),
                notificationWithFreshAttrs);

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
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect repeatingEffect = VibrationEffect.createWaveform(
                new long[]{10, 10_000}, new int[]{128, 255}, 1);
        vibrate(service, repeatingEffect,
                new VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_UNKNOWN)
                        .build());

        // VibrationThread will start this vibration async.
        // Wait until second step started to ensure the noteVibratorOn was triggered.
        assertTrue(waitUntil(s -> fakeVibrator.getAmplitudes().size() == 2, service,
                TEST_TIMEOUT_MILLIS));

        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                HAPTIC_FEEDBACK_ATTRS);

        // The time estimate is recorded when the vibration starts, repeating vibrations
        // are capped at BATTERY_STATS_REPEATING_VIBRATION_DURATION (=5000).
        verify(mBatteryStatsMock).noteVibratorOn(UID, 5000);
        // The second vibration shouldn't have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(1)).noteVibratorOn(anyInt(), anyLong());
        // No segment played is the prebaked CLICK from the second vibration.
        assertFalse(fakeVibrator.getAllEffectSegments().stream()
                .anyMatch(PrebakedSegment.class::isInstance));
    }

    @Test
    public void vibrate_withOngoingRepeatingVibrationBeingCancelled_playsAfterPreviousIsCancelled()
            throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setOffLatency(50); // Add latency so cancellation is slow.
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect repeatingEffect = VibrationEffect.createWaveform(
                new long[]{10, 10_000}, new int[]{128, 255}, 1);
        vibrate(service, repeatingEffect, ALARM_ATTRS);

        // VibrationThread will start this vibration async.
        // Wait until second step started to ensure the noteVibratorOn was triggered.
        assertTrue(waitUntil(s -> fakeVibrator.getAmplitudes().size() == 2, service,
                TEST_TIMEOUT_MILLIS));

        // Cancel vibration right before requesting a new one.
        // This should trigger slow IVibrator.off before setting the vibration status to cancelled.
        cancelVibrate(service);
        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                ALARM_ATTRS);

        // The second vibration should have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(2)).noteVibratorOn(anyInt(), anyLong());
        // Check that second vibration was played.
        assertTrue(fakeVibrator.getAllEffectSegments().stream()
                .anyMatch(PrebakedSegment.class::isInstance));
    }

    @Test
    public void vibrate_withNewSameImportanceVibrationAndBothRepeating_cancelsOngoingEffect()
            throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect repeatingEffect = VibrationEffect.createWaveform(
                new long[]{10, 10_000}, new int[]{128, 255}, 1);
        vibrate(service, repeatingEffect, ALARM_ATTRS);

        // VibrationThread will start this vibration async.
        // Wait until second step started to ensure the noteVibratorOn was triggered.
        assertTrue(waitUntil(s -> fakeVibrator.getAmplitudes().size() == 2, service,
                TEST_TIMEOUT_MILLIS));

        VibrationEffect repeatingEffect2 = VibrationEffect.createWaveform(
                new long[]{10, 10_000}, new int[]{255, 128}, 1);
        vibrate(service, repeatingEffect2, ALARM_ATTRS);

        // VibrationThread will start this vibration async.
        // Wait until second step started to ensure the noteVibratorOn was triggered.
        assertTrue(waitUntil(s -> fakeVibrator.getAmplitudes().size() == 4, service,
                TEST_TIMEOUT_MILLIS));

        // The second vibration should have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(2)).noteVibratorOn(anyInt(), anyLong());
    }

    @Test
    public void vibrate_withNewSameImportanceVibrationButOngoingIsRepeating_ignoreNewVibration()
            throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect repeatingEffect = VibrationEffect.createWaveform(
                new long[]{10, 10_000}, new int[]{128, 255}, 1);
        vibrate(service, repeatingEffect, ALARM_ATTRS);

        // VibrationThread will start this vibration async.
        // Wait until second step started to ensure the noteVibratorOn was triggered.
        assertTrue(waitUntil(s -> fakeVibrator.getAmplitudes().size() == 2, service,
                TEST_TIMEOUT_MILLIS));

        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                ALARM_ATTRS);

        // The second vibration shouldn't have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(1)).noteVibratorOn(anyInt(), anyLong());
        // The second vibration shouldn't have played any prebaked segment.
        assertFalse(fakeVibrator.getAllEffectSegments().stream()
                .anyMatch(PrebakedSegment.class::isInstance));
    }

    @Test
    public void vibrate_withNewUnknownUsageVibrationAndRepeating_cancelsOngoingEffect()
            throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect repeatingEffect = VibrationEffect.createWaveform(
                new long[]{10, 10_000}, new int[]{128, 255}, 1);
        vibrate(service, repeatingEffect, ALARM_ATTRS);

        // VibrationThread will start this vibration async.
        // Wait until second step started to ensure the noteVibratorOn was triggered.
        assertTrue(waitUntil(s -> fakeVibrator.getAmplitudes().size() == 2, service,
                TEST_TIMEOUT_MILLIS));

        VibrationEffect repeatingEffect2 = VibrationEffect.createWaveform(
                new long[]{10, 10_000}, new int[]{255, 128}, 1);
        vibrate(service, repeatingEffect2, UNKNOWN_ATTRS);

        // VibrationThread will start this vibration async.
        // Wait until second step started to ensure the noteVibratorOn was triggered.
        assertTrue(waitUntil(s -> fakeVibrator.getAmplitudes().size() == 4, service,
                TEST_TIMEOUT_MILLIS));

        // The second vibration should have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(2)).noteVibratorOn(anyInt(), anyLong());
    }

    @Test
    public void vibrate_withNewUnknownUsageVibrationAndNotRepeating_ignoreNewVibration()
            throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect alarmEffect = VibrationEffect.createWaveform(
                new long[]{10, 10_000}, new int[]{128, 255}, -1);
        vibrate(service, alarmEffect, ALARM_ATTRS);

        // VibrationThread will start this vibration async.
        // Wait until second step started to ensure the noteVibratorOn was triggered.
        assertTrue(waitUntil(s -> fakeVibrator.getAmplitudes().size() == 2, service,
                TEST_TIMEOUT_MILLIS));

        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                UNKNOWN_ATTRS);

        // The second vibration shouldn't have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(1)).noteVibratorOn(anyInt(), anyLong());
        // The second vibration shouldn't have played any prebaked segment.
        assertFalse(fakeVibrator.getAllEffectSegments().stream()
                .anyMatch(PrebakedSegment.class::isInstance));
    }

    @Test
    public void vibrate_withOngoingHigherImportanceVibration_ignoresEffect() throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{10, 10_000}, new int[]{128, 255}, -1);
        vibrate(service, effect, ALARM_ATTRS);

        // VibrationThread will start this vibration async.
        // Wait until second step started to ensure the noteVibratorOn was triggered.
        assertTrue(waitUntil(s -> fakeVibrator.getAmplitudes().size() == 2,
                service, TEST_TIMEOUT_MILLIS));

        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                HAPTIC_FEEDBACK_ATTRS);

        // The second vibration shouldn't have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(1)).noteVibratorOn(anyInt(), anyLong());
        // The second vibration shouldn't have played any prebaked segment.
        assertFalse(fakeVibrator.getAllEffectSegments().stream()
                .anyMatch(PrebakedSegment.class::isInstance));
    }

    @Test
    public void vibrate_withOngoingLowerImportanceVibration_cancelsOngoingEffect()
            throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{10, 10_000}, new int[]{128, 255}, -1);
        vibrate(service, effect, HAPTIC_FEEDBACK_ATTRS);

        // VibrationThread will start this vibration async.
        // Wait until second step started to ensure the noteVibratorOn was triggered.
        assertTrue(waitUntil(s -> fakeVibrator.getAmplitudes().size() == 2, service,
                TEST_TIMEOUT_MILLIS));

        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                RINGTONE_ATTRS);

        // The second vibration should have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(2)).noteVibratorOn(anyInt(), anyLong());
        // One segment played is the prebaked CLICK from the second vibration.
        assertEquals(1, fakeVibrator.getAllEffectSegments().stream()
                .filter(PrebakedSegment.class::isInstance).count());
    }

    @Test
    public void vibrate_withOngoingLowerImportanceExternalVibration_cancelsOngoingVibration()
            throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        VibratorManagerService service = createSystemReadyService();

        IBinder firstToken = mock(IBinder.class);
        IExternalVibrationController controller = mock(IExternalVibrationController.class);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS,
                controller, firstToken);
        ExternalVibrationScale scale =
                mExternalVibratorService.onExternalVibrationStart(externalVibration);

        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                RINGTONE_ATTRS);

        assertNotEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);
        // The external vibration should have been cancelled
        verify(controller).mute();
        assertEquals(Arrays.asList(false, true, false),
                mVibratorProviders.get(1).getExternalControlStates());
        // The new vibration should have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(1)).noteVibratorOn(anyInt(), anyLong());
        // One segment played is the prebaked CLICK from the new vibration.
        assertEquals(1, mVibratorProviders.get(1).getAllEffectSegments().stream()
                .filter(PrebakedSegment.class::isInstance).count());
    }

    @Test
    public void vibrate_withOngoingSameImportancePipelinedVibration_continuesOngoingEffect()
            throws Exception {
        VibrationAttributes pipelineAttrs = new VibrationAttributes.Builder(HAPTIC_FEEDBACK_ATTRS)
                .setFlags(VibrationAttributes.FLAG_PIPELINED_EFFECT)
                .build();

        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{100, 100}, new int[]{128, 255}, -1);
        vibrate(service, effect, pipelineAttrs);
        // This vibration will be enqueued, but evicted by the EFFECT_CLICK.
        vibrate(service, VibrationEffect.startComposition()
                .addOffDuration(Duration.ofSeconds(10))
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE)
                .compose(), pipelineAttrs);  // This will queue and be evicted for the click.

        vibrateAndWaitUntilFinished(service, VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                pipelineAttrs);

        // The second vibration should have recorded that the vibrators were turned on.
        verify(mBatteryStatsMock, times(2)).noteVibratorOn(anyInt(), anyLong());
        // One step segment (with several amplitudes) and one click should have played. Notably
        // there is no primitive segment.
        List<VibrationEffectSegment> played = fakeVibrator.getAllEffectSegments();
        assertEquals(2, played.size());
        assertEquals(1, played.stream().filter(StepSegment.class::isInstance).count());
        assertEquals(1, played.stream().filter(PrebakedSegment.class::isInstance).count());
    }

    @Test
    @EnableFlags(android.os.vibrator.Flags.FLAG_VIBRATION_PIPELINE_ENABLED)
    public void vibrate_withPipelineFlagEnabledAndShortEffect_continuesOngoingEffect()
            throws Exception {
        assumeTrue(mVibrationConfig.getVibrationPipelineMaxDurationMs() > 0);

        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        fakeVibrator.setSupportedPrimitives(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_THUD);
        fakeVibrator.setPrimitiveDuration(
                mVibrationConfig.getVibrationPipelineMaxDurationMs() - 1);
        VibratorManagerService service = createSystemReadyService();

        HalVibration firstVibration = vibrateWithUid(service, /* uid= */ 123,
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .compose(), HAPTIC_FEEDBACK_ATTRS);
        HalVibration secondVibration = vibrateWithUid(service, /* uid= */ 456,
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
                        .compose(), HAPTIC_FEEDBACK_ATTRS);
        secondVibration.waitForEnd();

        assertThat(fakeVibrator.getAllEffectSegments()).hasSize(2);
        assertThat(firstVibration.getStatus()).isEqualTo(Status.FINISHED);
        assertThat(secondVibration.getStatus()).isEqualTo(Status.FINISHED);
    }

    @Test
    public void vibrate_withInputDevices_vibratesInputDevices() throws Exception {
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
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
        vibrateAndWaitUntilFinished(service, effect, ALARM_ATTRS);

        verify(mIInputManagerMock).vibrateCombined(eq(1), eq(effect), any());
        assertTrue(fakeVibrator.getAllEffectSegments().isEmpty());
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
        vibrateAndWaitUntilFinished(service, effect, ALARM_ATTRS);

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
        vibrateAndWaitUntilFinished(service, effect, ALARM_ATTRS);

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
        vibrateAndWaitUntilFinished(service, effect, ALARM_ATTRS);

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
        vibrateAndWaitUntilFinished(service, effect, ALARM_ATTRS);

        verify(mNativeWrapperMock).prepareSynced(eq(new int[]{1, 2}));
        verify(mNativeWrapperMock).triggerSynced(anyLong());
        verify(mNativeWrapperMock, times(2)).cancelSynced(); // Trigger on service creation too.
    }

    @Test
    @EnableFlags(android.view.flags.Flags.FLAG_SCROLL_FEEDBACK_API)
    public void performHapticFeedback_doesNotRequireVibrateOrBypassPermissions() throws Exception {
        // Deny permissions that would have been required for regular vibrations, and check that
        // the vibration proceed as expected to verify that haptic feedback does not need these
        // permissions.
        denyPermission(android.Manifest.permission.VIBRATE);
        denyPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        denyPermission(android.Manifest.permission.MODIFY_PHONE_STATE);
        denyPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING);
        mHapticFeedbackVibrationMap.put(
                HapticFeedbackConstants.SCROLL_TICK,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        VibratorManagerService service = createSystemReadyService();

        HalVibration vibration =
                performHapticFeedbackAndWaitUntilFinished(
                        service, HapticFeedbackConstants.SCROLL_TICK, /* always= */ true);

        List<VibrationEffectSegment> playedSegments = fakeVibrator.getAllEffectSegments();
        assertEquals(1, playedSegments.size());
        PrebakedSegment segment = (PrebakedSegment) playedSegments.get(0);
        assertEquals(VibrationEffect.EFFECT_CLICK, segment.getEffectId());
        VibrationAttributes attrs = vibration.callerInfo.attrs;
        assertTrue(attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF));
        assertTrue(attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY));
    }

    @Test
    @EnableFlags({
            android.view.flags.Flags.FLAG_SCROLL_FEEDBACK_API,
            android.os.vibrator.Flags.FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED,
    })
    public void performHapticFeedbackForInputDevice_doesNotRequireVibrateOrBypassPermissions()
            throws Exception {
        // Deny permissions that would have been required for regular vibrations, and check that
        // the vibration proceed as expected to verify that haptic feedback does not need these
        // permissions.
        denyPermission(android.Manifest.permission.VIBRATE);
        denyPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        denyPermission(android.Manifest.permission.MODIFY_PHONE_STATE);
        denyPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING);
        mHapticFeedbackVibrationMapSourceRotary.put(
                HapticFeedbackConstants.SCROLL_TICK,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        mHapticFeedbackVibrationMapSourceTouchScreen.put(
                HapticFeedbackConstants.SCROLL_ITEM_FOCUS,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_THUD));
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_THUD);
        VibratorManagerService service = createSystemReadyService();

        HalVibration vibrationByRotary =
                performHapticFeedbackForInputDeviceAndWaitUntilFinished(
                        service, HapticFeedbackConstants.SCROLL_TICK, /* inputDeviceId= */ 0,
                        InputDevice.SOURCE_ROTARY_ENCODER, /* always= */ true);
        HalVibration vibrationByTouchScreen =
                performHapticFeedbackForInputDeviceAndWaitUntilFinished(
                        service, HapticFeedbackConstants.SCROLL_ITEM_FOCUS, /* inputDeviceId= */ 0,
                        InputDevice.SOURCE_TOUCHSCREEN, /* always= */ true);

        List<VibrationEffectSegment> playedSegments = fakeVibrator.getAllEffectSegments();
        // 2 haptics: 1 by rotary + 1 by touch screen
        assertEquals(2, playedSegments.size());
        // Verify feedback by rotary input
        PrebakedSegment segmentByRotary = (PrebakedSegment) playedSegments.get(0);
        assertEquals(VibrationEffect.EFFECT_CLICK, segmentByRotary.getEffectId());
        VibrationAttributes attrsByRotary = vibrationByRotary.callerInfo.attrs;
        assertTrue(attrsByRotary.isFlagSet(
                VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF));
        assertTrue(attrsByRotary.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY));
        // Verify feedback by touch screen input
        PrebakedSegment segmentByTouchScreen = (PrebakedSegment) playedSegments.get(1);
        assertEquals(VibrationEffect.EFFECT_THUD, segmentByTouchScreen.getEffectId());
        VibrationAttributes attrsByTouchScreen = vibrationByTouchScreen.callerInfo.attrs;
        assertTrue(attrsByTouchScreen.isFlagSet(
                VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF));
        assertTrue(
                attrsByTouchScreen.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY));
    }

    @Test
    public void performHapticFeedback_restrictedConstantsWithoutPermission_doesNotVibrate()
            throws Exception {
        // Deny permission to vibrate with restricted constants
        denyPermission(android.Manifest.permission.VIBRATE_SYSTEM_CONSTANTS);
        // Public constant, no permission required
        mHapticFeedbackVibrationMap.put(
                HapticFeedbackConstants.CONFIRM,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        // Hidden system-only constant, permission required
        mHapticFeedbackVibrationMap.put(
                HapticFeedbackConstants.BIOMETRIC_CONFIRM,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setSupportedEffects(
                VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK);
        VibratorManagerService service = createSystemReadyService();

        performHapticFeedbackAndWaitUntilFinished(
                service, HapticFeedbackConstants.CONFIRM, /* always= */ false);

        performHapticFeedbackAndWaitUntilFinished(
                service, HapticFeedbackConstants.BIOMETRIC_CONFIRM, /* always= */ false);

        List<VibrationEffectSegment> playedSegments = fakeVibrator.getAllEffectSegments();
        assertEquals(1, playedSegments.size());
        PrebakedSegment segment = (PrebakedSegment) playedSegments.get(0);
        assertEquals(VibrationEffect.EFFECT_CLICK, segment.getEffectId());
    }

    @Test
    @EnableFlags({
            android.view.flags.Flags.FLAG_SCROLL_FEEDBACK_API,
            android.os.vibrator.Flags.FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED,
    })
    public void performHapticFeedbackForInputDevice_restrictedConstantsWithoutPermission_doesNotVibrate()
            throws Exception {
        // Deny permission to vibrate with restricted constants
        denyPermission(android.Manifest.permission.VIBRATE_SYSTEM_CONSTANTS);
        // Public constant, no permission required
        mHapticFeedbackVibrationMapSourceRotary.put(
                HapticFeedbackConstants.CONFIRM,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        // Hidden system-only constant, permission required
        mHapticFeedbackVibrationMapSourceTouchScreen.put(
                HapticFeedbackConstants.BIOMETRIC_CONFIRM,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setSupportedEffects(
                VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK);
        VibratorManagerService service = createSystemReadyService();

        // This vibrates.
        performHapticFeedbackForInputDeviceAndWaitUntilFinished(
                        service, HapticFeedbackConstants.CONFIRM, /* inputDeviceId= */ 0,
                InputDevice.SOURCE_ROTARY_ENCODER, /* always= */ false);
        // This doesn't.
        performHapticFeedbackForInputDeviceAndWaitUntilFinished(
                        service, HapticFeedbackConstants.BIOMETRIC_CONFIRM, /* inputDeviceId= */ 0,
                InputDevice.SOURCE_TOUCHSCREEN, /* always= */ false);

        List<VibrationEffectSegment> playedSegments = fakeVibrator.getAllEffectSegments();
        assertEquals(1, playedSegments.size());
        PrebakedSegment segment = (PrebakedSegment) playedSegments.get(0);
        assertEquals(VibrationEffect.EFFECT_CLICK, segment.getEffectId());
    }

    @Test
    public void performHapticFeedback_restrictedConstantsWithPermission_playsVibration()
            throws Exception {
        // Grant permission to vibrate with restricted constants
        grantPermission(android.Manifest.permission.VIBRATE_SYSTEM_CONSTANTS);
        // Public constant, no permission required
        mHapticFeedbackVibrationMap.put(
                HapticFeedbackConstants.CONFIRM,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        // Hidden system-only constant, permission required
        mHapticFeedbackVibrationMap.put(
                HapticFeedbackConstants.BIOMETRIC_CONFIRM,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setSupportedEffects(
                VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK);
        VibratorManagerService service = createSystemReadyService();

        performHapticFeedbackAndWaitUntilFinished(
                service, HapticFeedbackConstants.CONFIRM, /* always= */ false);

        performHapticFeedbackAndWaitUntilFinished(
                service, HapticFeedbackConstants.BIOMETRIC_CONFIRM, /* always= */ false);

        List<VibrationEffectSegment> playedSegments = fakeVibrator.getAllEffectSegments();
        assertEquals(2, playedSegments.size());
        assertEquals(VibrationEffect.EFFECT_CLICK,
                ((PrebakedSegment) playedSegments.get(0)).getEffectId());
        assertEquals(VibrationEffect.EFFECT_HEAVY_CLICK,
                ((PrebakedSegment) playedSegments.get(1)).getEffectId());
    }

    @Test
    @EnableFlags(android.os.vibrator.Flags.FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED)
    public void performHapticFeedbackForInputDevice_restrictedConstantsWithPermission_playsVibration()
            throws Exception {
        // Grant permission to vibrate with restricted constants
        grantPermission(android.Manifest.permission.VIBRATE_SYSTEM_CONSTANTS);
        // Public constant, no permission required
        mHapticFeedbackVibrationMapSourceRotary.put(
                HapticFeedbackConstants.CONFIRM,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        // Hidden system-only constant, permission required
        mHapticFeedbackVibrationMapSourceTouchScreen.put(
                HapticFeedbackConstants.BIOMETRIC_CONFIRM,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setSupportedEffects(
                VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK);
        VibratorManagerService service = createSystemReadyService();

        performHapticFeedbackForInputDeviceAndWaitUntilFinished(
                service, HapticFeedbackConstants.CONFIRM, /* inputDeviceId= */ 0,
                InputDevice.SOURCE_ROTARY_ENCODER, /* always= */ false);
        performHapticFeedbackForInputDeviceAndWaitUntilFinished(
                service, HapticFeedbackConstants.BIOMETRIC_CONFIRM, /* inputDeviceId= */ 0,
                InputDevice.SOURCE_TOUCHSCREEN, /* always= */ false);

        List<VibrationEffectSegment> playedSegments = fakeVibrator.getAllEffectSegments();
        assertEquals(2, playedSegments.size());
        assertEquals(VibrationEffect.EFFECT_CLICK,
                ((PrebakedSegment) playedSegments.get(0)).getEffectId());
        assertEquals(VibrationEffect.EFFECT_HEAVY_CLICK,
                ((PrebakedSegment) playedSegments.get(1)).getEffectId());
    }

    @Test
    @EnableFlags({
            android.view.flags.Flags.FLAG_SCROLL_FEEDBACK_API,
            android.os.vibrator.Flags.FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED,
    })
    public void performHapticFeedback_doesNotVibrateWhenVibratorInfoNotReady() throws Exception {
        denyPermission(android.Manifest.permission.VIBRATE);
        mHapticFeedbackVibrationMap.put(
                HapticFeedbackConstants.KEYBOARD_TAP,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        mHapticFeedbackVibrationMapSourceRotary.put(
                HapticFeedbackConstants.KEYBOARD_TAP,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_THUD));
        mHapticFeedbackVibrationMapSourceTouchScreen.put(
                HapticFeedbackConstants.KEYBOARD_TAP,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setVibratorInfoLoadSuccessful(false);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_TICK,
                VibrationEffect.EFFECT_THUD);
        VibratorManagerService service = createService();

        // performHapticFeedback.
        performHapticFeedbackAndWaitUntilFinished(
                service, HapticFeedbackConstants.KEYBOARD_TAP, /* always= */ true);
        // performHapticFeedbackForInputDevice.
        performHapticFeedbackForInputDeviceAndWaitUntilFinished(
                service, HapticFeedbackConstants.KEYBOARD_TAP, /* inputDeviceId= */ 0,
                InputDevice.SOURCE_ROTARY_ENCODER, /* always= */ true);
        performHapticFeedbackForInputDeviceAndWaitUntilFinished(
                service, HapticFeedbackConstants.KEYBOARD_TAP, /* inputDeviceId= */ 0,
                InputDevice.SOURCE_TOUCHSCREEN, /* always= */ true);

        assertTrue(fakeVibrator.getAllEffectSegments().isEmpty());
    }

    @Test
    @EnableFlags({
            android.view.flags.Flags.FLAG_SCROLL_FEEDBACK_API,
            android.os.vibrator.Flags.FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED,
    })
    public void performHapticFeedback_doesNotVibrateForInvalidConstant() throws Exception {
        denyPermission(android.Manifest.permission.VIBRATE);
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        // These are bad haptic feedback IDs, so expect no vibration played.
        // Test performHapticFeedback
        performHapticFeedbackAndWaitUntilFinished(service, /* constant= */ -1, /* always= */ false);
        performHapticFeedbackAndWaitUntilFinished(
                service, HapticFeedbackConstants.NO_HAPTICS, /* always= */ true);
        // Test performHapticFeedbackForInputDevice
        performHapticFeedbackForInputDeviceAndWaitUntilFinished(
                service, /* constant= */ -1, /* inputDeviceId= */ 0,
                InputDevice.SOURCE_ROTARY_ENCODER, /* always= */ true);
        performHapticFeedbackForInputDeviceAndWaitUntilFinished(
                service, /* constant= */ -1, /* inputDeviceId= */ 0,
                InputDevice.SOURCE_TOUCHSCREEN, /* always= */ true);

        assertTrue(mVibratorProviders.get(1).getAllEffectSegments().isEmpty());
    }

    @Test
    @EnableFlags(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void vibrate_vendorEffectsWithoutPermission_doesNotVibrate() throws Exception {
        // Deny permission to vibrate with vendor effects
        denyPermission(android.Manifest.permission.VIBRATE_VENDOR_EFFECTS);
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_PERFORM_VENDOR_EFFECTS);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_TICK);
        VibratorManagerService service = createSystemReadyService();

        PersistableBundle vendorData = new PersistableBundle();
        vendorData.putString("key", "value");
        VibrationEffect vendorEffect = VibrationEffect.createVendorEffect(vendorData);
        VibrationEffect tickEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);

        vibrateAndWaitUntilFinished(service, vendorEffect, RINGTONE_ATTRS);
        vibrateAndWaitUntilFinished(service, tickEffect, RINGTONE_ATTRS);

        // No vendor effect played, but predefined TICK plays successfully.
        assertThat(fakeVibrator.getAllVendorEffects()).isEmpty();
        assertThat(fakeVibrator.getAllEffectSegments()).hasSize(1);
        assertThat(fakeVibrator.getAllEffectSegments().get(0)).isInstanceOf(PrebakedSegment.class);
    }

    @Test
    @EnableFlags(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void vibrate_vendorEffectsWithPermission_successful() throws Exception {
        // Grant permission to vibrate with vendor effects
        grantPermission(android.Manifest.permission.VIBRATE_VENDOR_EFFECTS);
        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_PERFORM_VENDOR_EFFECTS);
        VibratorManagerService service = createSystemReadyService();

        PersistableBundle vendorData = new PersistableBundle();
        vendorData.putString("key", "value");
        VibrationEffect vendorEffect = VibrationEffect.createVendorEffect(vendorData);

        vibrateAndWaitUntilFinished(service, vendorEffect, RINGTONE_ATTRS);

        // Compare vendor data only, ignore scale applied by device settings in this test.
        assertThat(fakeVibrator.getAllVendorEffects()).hasSize(1);
        assertThat(fakeVibrator.getAllVendorEffects().get(0).getVendorData().keySet())
                .containsExactly("key");
        assertThat(fakeVibrator.getAllVendorEffects().get(0).getVendorData().getString("key"))
                .isEqualTo("value");
    }

    @Test
    public void vibrate_withIntensitySettings_appliesSettingsToScaleVibrations() throws Exception {
        int defaultNotificationIntensity =
                mVibrator.getDefaultVibrationIntensity(VibrationAttributes.USAGE_NOTIFICATION);
        // This will scale up notification vibrations.
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                defaultNotificationIntensity < Vibrator.VIBRATION_INTENSITY_HIGH
                        ? defaultNotificationIntensity + 1
                        : defaultNotificationIntensity);

        int defaultTouchIntensity =
                mVibrator.getDefaultVibrationIntensity(VibrationAttributes.USAGE_TOUCH);
        // This will scale down touch vibrations.
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

        vibrateAndWaitUntilFinished(service, VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .compose(), HAPTIC_FEEDBACK_ATTRS);

        vibrateAndWaitUntilFinished(service, CombinedVibration.startSequential()
                .addNext(1, VibrationEffect.createOneShot(100, 125))
                .combine(), NOTIFICATION_ATTRS);

        vibrateAndWaitUntilFinished(service, VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .compose(), ALARM_ATTRS);

        // Ring vibrations have intensity OFF and are not played.
        vibrateAndWaitUntilFinished(service, VibrationEffect.createOneShot(100, 125),
                RINGTONE_ATTRS);

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
    @EnableFlags(android.os.vibrator.Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED)
    public void vibrate_withAdaptiveHaptics_appliesCorrectAdaptiveScales() throws Exception {
        // Keep user settings the same as device default so only adaptive scale is applied.
        setUserSetting(Settings.System.ALARM_VIBRATION_INTENSITY,
                mVibrator.getDefaultVibrationIntensity(VibrationAttributes.USAGE_ALARM));
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                mVibrator.getDefaultVibrationIntensity(VibrationAttributes.USAGE_NOTIFICATION));
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                mVibrator.getDefaultVibrationIntensity(VibrationAttributes.USAGE_TOUCH));

        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        fakeVibrator.setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK);
        VibratorManagerService service = createSystemReadyService();

        SparseArray<Float> vibrationScales = new SparseArray<>();
        vibrationScales.put(ScaleParam.TYPE_ALARM, 0.7f);
        vibrationScales.put(ScaleParam.TYPE_NOTIFICATION, 0.4f);

        mVibratorControlService.setVibrationParams(
                VibrationParamGenerator.generateVibrationParams(vibrationScales),
                mFakeVibratorController);

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .compose();
        vibrateAndWaitUntilFinished(service, effect, ALARM_ATTRS);
        vibrateAndWaitUntilFinished(service, effect, NOTIFICATION_ATTRS);
        vibrateAndWaitUntilFinished(service, effect, HAPTIC_FEEDBACK_ATTRS);

        List<VibrationEffectSegment> segments = fakeVibrator.getAllEffectSegments();
        assertEquals(3, segments.size());
        assertEquals(0.7f, ((PrimitiveSegment) segments.get(0)).getScale(), 1e-5);
        assertEquals(0.4f, ((PrimitiveSegment) segments.get(1)).getScale(), 1e-5);
        assertEquals(1f, ((PrimitiveSegment) segments.get(2)).getScale(), 1e-5);
        verify(mVibratorFrameworkStatsLoggerMock).logVibrationAdaptiveHapticScale(UID, 0.7f);
        verify(mVibratorFrameworkStatsLoggerMock).logVibrationAdaptiveHapticScale(UID, 0.4f);
        verify(mVibratorFrameworkStatsLoggerMock,
                timeout(TEST_TIMEOUT_MILLIS)).logVibrationAdaptiveHapticScale(UID, 1f);
    }

    @Test
    @EnableFlags({
            android.os.vibrator.Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED,
            android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS,
    })
    public void vibrate_withIntensitySettingsAndAdaptiveHaptics_appliesSettingsToVendorEffects()
            throws Exception {
        // Grant permission to vibrate with vendor effects
        grantPermission(android.Manifest.permission.VIBRATE_VENDOR_EFFECTS);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);

        mockVibrators(1);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(1);
        fakeVibrator.setCapabilities(IVibrator.CAP_PERFORM_VENDOR_EFFECTS);
        VibratorManagerService service = createSystemReadyService();

        SparseArray<Float> vibrationScales = new SparseArray<>();
        vibrationScales.put(ScaleParam.TYPE_NOTIFICATION, 0.4f);

        mVibratorControlService.setVibrationParams(
                VibrationParamGenerator.generateVibrationParams(vibrationScales),
                mFakeVibratorController);

        PersistableBundle vendorData = new PersistableBundle();
        vendorData.putString("key", "value");
        VibrationEffect vendorEffect = VibrationEffect.createVendorEffect(vendorData);
        vibrateAndWaitUntilFinished(service, vendorEffect, NOTIFICATION_ATTRS);

        assertThat(fakeVibrator.getAllVendorEffects()).hasSize(1);
        VibrationEffect.VendorEffect scaled = fakeVibrator.getAllVendorEffects().get(0);
        assertThat(scaled.getEffectStrength()).isEqualTo(VibrationEffect.EFFECT_STRENGTH_LIGHT);
        assertThat(scaled.getScale()).isAtMost(1); // Scale down or none if default is LOW
        assertThat(scaled.getAdaptiveScale()).isEqualTo(0.4f);
    }

    @Test
    public void vibrate_ignoreVibrationFromVirtualDevice() throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        vibrateWithDevice(service,
                VIRTUAL_DEVICE_ID,
                CombinedVibration.startParallel()
                        .addVibrator(1, VibrationEffect.createOneShot(1000, 100))
                        .combine(),
                HAPTIC_FEEDBACK_ATTRS);

        // Haptic feedback ignored when it's from a virtual device.
        assertFalse(waitUntil(s -> s.isVibrating(1), service, /* timeout= */ 50));

        vibrateWithDevice(service,
                Context.DEVICE_ID_DEFAULT,
                CombinedVibration.startParallel()
                        .addVibrator(1, VibrationEffect.createOneShot(1000, 100))
                        .combine(),
                HAPTIC_FEEDBACK_ATTRS);
        // Haptic feedback played normally when it's from the default device.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
    }

    @Test
    public void vibrate_prebakedAndComposedVibrationsWithFallbacks_playsFallbackOnlyForPredefined()
            throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(1).setSupportedPrimitives(
                VibrationEffect.Composition.PRIMITIVE_CLICK);

        VibratorManagerService service = createSystemReadyService();
        vibrateAndWaitUntilFinished(service,
                VibrationEffect.startComposition()
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .compose(),
                ALARM_ATTRS);

        List<VibrationEffectSegment> segments = mVibratorProviders.get(1).getAllEffectSegments();
        // At least one step segment played as fallback for unusupported vibration effect
        assertTrue(segments.size() > 2);
        // 0: Supported effect played
        assertTrue(segments.get(0) instanceof PrebakedSegment);
        // 1: No segment for unsupported primitive
        // 2: One or more intermediate step segments as fallback for unsupported effect
        for (int i = 1; i < segments.size() - 1; i++) {
            assertTrue(segments.get(i) instanceof StepSegment);
        }
        // 3: Supported primitive played
        assertTrue(segments.get(segments.size() - 1) instanceof PrimitiveSegment);
    }

    @Test
    public void cancelVibrate_withoutUsageFilter_stopsVibrating() throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        service.cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, service);
        assertFalse(service.isVibrating(1));

        vibrate(service, VibrationEffect.createOneShot(10 * TEST_TIMEOUT_MILLIS, 100), ALARM_ATTRS);

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        service.cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, service);

        // Alarm cancelled on filter match all.
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
    }

    @Test
    public void cancelVibrate_withFilter_onlyCancelsVibrationWithFilteredUsage() throws Exception {
        mockVibrators(1);
        VibratorManagerService service = createSystemReadyService();

        vibrate(service, VibrationEffect.createOneShot(10 * TEST_TIMEOUT_MILLIS, 100), ALARM_ATTRS);

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
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

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
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

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        // Cancel UNKNOWN vibration when all vibrations are being cancelled.
        service.cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, service);
        assertTrue(waitUntil(s -> !s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));
    }

    @Test
    public void cancelVibrate_externalVibration_cancelWithDifferentToken() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        createSystemReadyService();

        IBinder vibrationBinderToken = mock(IBinder.class);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS,
                mock(IExternalVibrationController.class), vibrationBinderToken);
        ExternalVibrationScale scale = mExternalVibratorService.onExternalVibrationStart(
                externalVibration);

        IBinder cancelBinderToken = mock(IBinder.class);
        mService.cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, cancelBinderToken);

        assertNotEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);
        assertEquals(Arrays.asList(false, true, false),
                mVibratorProviders.get(1).getExternalControlStates());
    }

    @Test
    public void onExternalVibration_ignoreVibrationFromVirtualDevices() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        createSystemReadyService();

        IBinder binderToken = mock(IBinder.class);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS,
                mock(IExternalVibrationController.class), binderToken);
        ExternalVibrationScale scale = mExternalVibratorService.onExternalVibrationStart(
                externalVibration);
        assertNotEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);

        when(mVirtualDeviceManagerInternalMock.isAppRunningOnAnyVirtualDevice(UID))
                .thenReturn(true);
        scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);
    }

    @Test
    public void onExternalVibration_setsExternalControl() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        createSystemReadyService();

        IBinder binderToken = mock(IBinder.class);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS,
                mock(IExternalVibrationController.class), binderToken);
        ExternalVibrationScale scale = mExternalVibratorService.onExternalVibrationStart(
                externalVibration);
        mExternalVibratorService.onExternalVibrationStop(externalVibration);

        assertNotEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);
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
        ExternalVibration firstVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS,
                firstController, firstToken);
        ExternalVibrationScale firstScale =
                mExternalVibratorService.onExternalVibrationStart(firstVibration);

        AudioAttributes ringtoneAudioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build();
        ExternalVibration secondVibration = new ExternalVibration(UID, PACKAGE_NAME,
                ringtoneAudioAttrs, secondController, secondToken);
        ExternalVibrationScale secondScale =
                mExternalVibratorService.onExternalVibrationStart(secondVibration);

        assertNotEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, firstScale.scaleLevel);
        assertNotEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, secondScale.scaleLevel);
        verify(firstController).mute();
        verify(secondController, never()).mute();
        // Set external control called for each vibration independently.
        assertEquals(Arrays.asList(false, true, false, true),
                mVibratorProviders.get(1).getExternalControlStates());

        mExternalVibratorService.onExternalVibrationStop(secondVibration);
        mExternalVibratorService.onExternalVibrationStop(firstVibration);
        assertEquals(Arrays.asList(false, true, false, true, false),
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
        HalVibration vibration = vibrate(service, effect, HAPTIC_FEEDBACK_ATTRS);

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS,
                mock(IExternalVibrationController.class));
        ExternalVibrationScale scale =
                mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertNotEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);

        // Vibration is cancelled.
        vibration.waitForEnd();
        assertThat(vibration.getStatus()).isEqualTo(Status.CANCELLED_SUPERSEDED);
        assertEquals(Arrays.asList(false, true),
                mVibratorProviders.get(1).getExternalControlStates());
    }

    @Test
    public void onExternalVibration_withOngoingHigherImportanceVibration_ignoreNewVibration()
            throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL,
                IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect effect = VibrationEffect.createOneShot(10 * TEST_TIMEOUT_MILLIS, 100);
        vibrate(service, effect, RINGTONE_ATTRS);

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS,
                mock(IExternalVibrationController.class));
        ExternalVibrationScale scale =
                mExternalVibratorService.onExternalVibrationStart(externalVibration);
        // External vibration is ignored.
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);

        // Vibration is not cancelled.
        assertEquals(Arrays.asList(false), mVibratorProviders.get(1).getExternalControlStates());
    }

    @Test
    public void onExternalVibration_withNewSameImportanceButRepeating_cancelsOngoingVibration()
            throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL,
                IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect repeatingEffect = VibrationEffect.createWaveform(
                new long[]{100, 200, 300}, new int[]{128, 255, 255}, 1);
        HalVibration repeatingVibration = vibrate(service, repeatingEffect, ALARM_ATTRS);

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS, mock(IExternalVibrationController.class));
        ExternalVibrationScale scale =
                mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertNotEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);

        // Vibration is cancelled.
        repeatingVibration.waitForEnd();
        assertThat(repeatingVibration.getStatus()).isEqualTo(Status.CANCELLED_SUPERSEDED);
        assertEquals(Arrays.asList(false, true),
                mVibratorProviders.get(1).getExternalControlStates());
    }

    @Test
    public void onExternalVibration_withNewSameImportanceButOngoingIsRepeating_ignoreNewVibration()
            throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL,
                IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        VibrationEffect repeatingEffect = VibrationEffect.createWaveform(
                new long[]{10_000, 10_000}, new int[]{128, 255}, 1);
        vibrate(service, repeatingEffect, NOTIFICATION_ATTRS);

        // VibrationThread will start this vibration async, so wait until vibration is triggered.
        assertTrue(waitUntil(s -> s.isVibrating(1), service, TEST_TIMEOUT_MILLIS));

        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_NOTIFICATION_ATTRS,
                mock(IExternalVibrationController.class));
        ExternalVibrationScale scale =
                mExternalVibratorService.onExternalVibrationStart(externalVibration);
        // New vibration is ignored.
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);

        // Vibration is not cancelled.
        assertEquals(Arrays.asList(false), mVibratorProviders.get(1).getExternalControlStates());
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
        ExternalVibrationScale scale =
                mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);

        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        createSystemReadyService();
        scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertNotEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);

        setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        createSystemReadyService();
        scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        assertNotEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);
    }

    @Test
    public void onExternalVibration_withBypassMuteAudioFlag_ignoresUserSettings() {
        // Permission needed for bypassing user settings
        grantPermission(android.Manifest.permission.MODIFY_PHONE_STATE);

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

        ExternalVibration vib = new ExternalVibration(UID, PACKAGE_NAME, audioAttrs,
                mock(IExternalVibrationController.class));
        ExternalVibrationScale scale = mExternalVibratorService.onExternalVibrationStart(vib);
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);

        mExternalVibratorService.onExternalVibrationStop(vib);
        scale = mExternalVibratorService.onExternalVibrationStart(
                new ExternalVibration(UID, PACKAGE_NAME, flaggedAudioAttrs,
                        mock(IExternalVibrationController.class)));
        assertNotEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);
    }

    @Test
    public void onExternalVibration_withUnknownUsage_appliesMediaSettings() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        setUserSetting(Settings.System.MEDIA_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        AudioAttributes flaggedAudioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        createSystemReadyService();

        ExternalVibrationScale scale =
                mExternalVibratorService.onExternalVibrationStart(
                        new ExternalVibration(/* uid= */ 123, PACKAGE_NAME, flaggedAudioAttrs,
                                mock(IExternalVibrationController.class)));
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_MUTE, scale.scaleLevel);
    }

    @Test
    @EnableFlags(android.os.vibrator.Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED)
    public void onExternalVibration_withAdaptiveHaptics_returnsCorrectAdaptiveScales() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL,
                IVibrator.CAP_AMPLITUDE_CONTROL);
        createSystemReadyService();

        SparseArray<Float> vibrationScales = new SparseArray<>();
        vibrationScales.put(ScaleParam.TYPE_ALARM, 0.7f);
        vibrationScales.put(ScaleParam.TYPE_NOTIFICATION, 0.4f);

        mVibratorControlService.setVibrationParams(
                VibrationParamGenerator.generateVibrationParams(vibrationScales),
                mFakeVibratorController);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS,
                mock(IExternalVibrationController.class));
        ExternalVibrationScale scale =
                mExternalVibratorService.onExternalVibrationStart(externalVibration);
        mExternalVibratorService.onExternalVibrationStop(externalVibration);

        assertEquals(scale.adaptiveHapticsScale, 0.7f, 0);
        verify(mVibratorFrameworkStatsLoggerMock).logVibrationAdaptiveHapticScale(UID, 0.7f);

        externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_NOTIFICATION_ATTRS,
                mock(IExternalVibrationController.class));
        scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        mExternalVibratorService.onExternalVibrationStop(externalVibration);

        assertEquals(scale.adaptiveHapticsScale, 0.4f, 0);
        verify(mVibratorFrameworkStatsLoggerMock).logVibrationAdaptiveHapticScale(UID, 0.4f);

        AudioAttributes ringtoneAudioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build();
        externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                ringtoneAudioAttrs,
                mock(IExternalVibrationController.class));
        scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        mExternalVibratorService.onExternalVibrationStop(externalVibration);

        assertEquals(scale.adaptiveHapticsScale, 1f, 0);
        verify(mVibratorFrameworkStatsLoggerMock).logVibrationAdaptiveHapticScale(UID, 1f);
    }

    @Test
    @DisableFlags(android.os.vibrator.Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED)
    public void onExternalVibration_withAdaptiveHapticsFlagDisabled_alwaysReturnScaleNone() {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL,
                IVibrator.CAP_AMPLITUDE_CONTROL);
        createSystemReadyService();

        SparseArray<Float> vibrationScales = new SparseArray<>();
        vibrationScales.put(ScaleParam.TYPE_ALARM, 0.7f);
        vibrationScales.put(ScaleParam.TYPE_NOTIFICATION, 0.4f);

        mVibratorControlService.setVibrationParams(
                VibrationParamGenerator.generateVibrationParams(vibrationScales),
                mFakeVibratorController);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS,
                mock(IExternalVibrationController.class));
        ExternalVibrationScale scale =
                mExternalVibratorService.onExternalVibrationStart(externalVibration);
        mExternalVibratorService.onExternalVibrationStop(externalVibration);

        assertEquals(scale.adaptiveHapticsScale, 1f, 0);

        externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_NOTIFICATION_ATTRS,
                mock(IExternalVibrationController.class));
        scale = mExternalVibratorService.onExternalVibrationStart(externalVibration);
        mExternalVibratorService.onExternalVibrationStop(externalVibration);

        assertEquals(scale.adaptiveHapticsScale, 1f, 0);
        verify(mVibratorFrameworkStatsLoggerMock, times(2))
                .logVibrationAdaptiveHapticScale(UID, 1f);
    }

    @Test
    public void onExternalVibration_thenDeniedAppOps_doNotCancelVibration() throws Throwable {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        IExternalVibrationController externalVibrationControllerMock =
                mock(IExternalVibrationController.class);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS, externalVibrationControllerMock, mock(IBinder.class));
        ExternalVibrationScale scale = mExternalVibratorService.onExternalVibrationStart(
                externalVibration);

        assertThat(scale.scaleLevel).isNotEqualTo(ExternalVibrationScale.ScaleLevel.SCALE_MUTE);

        when(mAppOpsManagerMock.checkAudioOpNoThrow(eq(AppOpsManager.OP_VIBRATE),
                eq(AudioAttributes.USAGE_ALARM), anyInt(), anyString()))
                .thenReturn(AppOpsManager.MODE_IGNORED);
        service.mAppOpsChangeListener.onOpChanged(AppOpsManager.OP_VIBRATE, null);

        verify(externalVibrationControllerMock, never()).mute();
    }

    @Test
    public void onExternalVibration_thenPowerModeChanges_doNotCancelVibration() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        createSystemReadyService();

        IExternalVibrationController externalVibrationControllerMock =
                mock(IExternalVibrationController.class);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS, externalVibrationControllerMock, mock(IBinder.class));
        ExternalVibrationScale scale = mExternalVibratorService.onExternalVibrationStart(
                externalVibration);

        assertThat(scale.scaleLevel).isNotEqualTo(ExternalVibrationScale.ScaleLevel.SCALE_MUTE);

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        verify(externalVibrationControllerMock, never()).mute();
    }

    @Test
    public void onExternalVibration_thenSettingsChange_doNotCancelVibration() throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        IExternalVibrationController externalVibrationControllerMock =
                mock(IExternalVibrationController.class);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS, externalVibrationControllerMock, mock(IBinder.class));
        ExternalVibrationScale scale = mExternalVibratorService.onExternalVibrationStart(
                externalVibration);

        assertThat(scale.scaleLevel).isNotEqualTo(ExternalVibrationScale.ScaleLevel.SCALE_MUTE);

        setUserSetting(Settings.System.ALARM_VIBRATION_INTENSITY, Vibrator.VIBRATION_INTENSITY_OFF);
        service.mVibrationSettings.mSettingObserver.onChange(false);
        service.updateServiceState();

        verify(externalVibrationControllerMock, never()).mute();
    }

    @Test
    public void onExternalVibration_thenScreenTurnsOff_doNotCancelVibration() throws Throwable {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        IExternalVibrationController externalVibrationControllerMock =
                mock(IExternalVibrationController.class);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS, externalVibrationControllerMock, mock(IBinder.class));
        ExternalVibrationScale scale = mExternalVibratorService.onExternalVibrationStart(
                externalVibration);

        assertThat(scale.scaleLevel).isNotEqualTo(ExternalVibrationScale.ScaleLevel.SCALE_MUTE);

        service.mIntentReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_SCREEN_OFF));

        verify(externalVibrationControllerMock, never()).mute();
    }

    @Test
    @EnableFlags(android.multiuser.Flags.FLAG_ADD_UI_FOR_SOUNDS_FROM_BACKGROUND_USERS)
    public void onExternalVibration_thenFgUserRequestsMute_doNotCancelVibration() throws Throwable {
        mockVibrators(1);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        VibratorManagerService service = createSystemReadyService();

        IExternalVibrationController externalVibrationControllerMock =
                mock(IExternalVibrationController.class);
        ExternalVibration externalVibration = new ExternalVibration(UID, PACKAGE_NAME,
                AUDIO_ALARM_ATTRS, externalVibrationControllerMock, mock(IBinder.class));
        ExternalVibrationScale scale = mExternalVibratorService.onExternalVibrationStart(
                externalVibration);

        assertThat(scale.scaleLevel).isNotEqualTo(ExternalVibrationScale.ScaleLevel.SCALE_MUTE);

        service.mIntentReceiver.onReceive(mContextSpy, new Intent(
                BackgroundUserSoundNotifier.ACTION_MUTE_SOUND));

        verify(externalVibrationControllerMock, never()).mute();
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
        assertEquals(Status.FINISHED.getProtoEnumValue(), statsInfo.status);
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
        assertEquals(Status.FINISHED.getProtoEnumValue(), metrics.status);
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
        assertEquals(Status.CANCELLED_BY_USER.getProtoEnumValue(), metrics.status);
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
        assertEquals(Status.FINISHED.getProtoEnumValue(), metrics.status);

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
        assertEquals(Status.CANCELLED_SUPERSEDED.getProtoEnumValue(),
                touchMetrics.status);
        assertTrue(touchMetrics.endedBySameUid);
        assertEquals(VibrationAttributes.USAGE_ALARM, touchMetrics.endedByUsage);
        assertEquals(-1, touchMetrics.interruptedUsage);

        VibrationStats.StatsInfo alarmMetrics = argumentCaptor.getAllValues().get(1);
        assertEquals(UID, alarmMetrics.uid);
        assertEquals(VibrationAttributes.USAGE_ALARM, alarmMetrics.usage);
        assertEquals(Status.FINISHED.getProtoEnumValue(), alarmMetrics.status);
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
        assertEquals(Status.IGNORED_FOR_POWER.getProtoEnumValue(), touchMetrics.status);

        VibrationStats.StatsInfo ringtoneMetrics = argumentCaptor.getAllValues().get(1);
        assertEquals(UID, ringtoneMetrics.uid);
        assertEquals(VibrationAttributes.USAGE_RINGTONE, ringtoneMetrics.usage);
        assertEquals(Status.IGNORED_FOR_SETTINGS.getProtoEnumValue(),
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
        assertEquals(Status.FINISHED.getProtoEnumValue(), metrics.status);
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

    private void assertCanVibrateWithBypassFlags(boolean expectedCanApplyBypassFlags)
            throws Exception {
        mockVibrators(1);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        VibratorManagerService service = createSystemReadyService();

        HalVibration vibration = vibrateAndWaitUntilFinished(
                service,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                new VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_TOUCH)
                        .setFlags(
                                VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF
                                        | VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY)
                        .build());

        VibrationAttributes attrs = vibration.callerInfo.attrs;
        assertEquals(
                expectedCanApplyBypassFlags,
                attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF));
        assertEquals(
                expectedCanApplyBypassFlags,
                attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY));
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

    private void cancelVibrate(VibratorManagerService service) {
        service.cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, service);
    }

    private IVibratorStateListener mockVibratorStateListener() {
        IVibratorStateListener listenerMock = mock(IVibratorStateListener.class);
        IBinder binderMock = mock(IBinder.class);
        when(listenerMock.asBinder()).thenReturn(binderMock);
        return listenerMock;
    }

    private InputDevice createInputDeviceWithVibrator(int id) {
        return new InputDevice.Builder()
                .setId(id)
                .setName("Test Device " + id)
                .setHasVibrator(true)
                .build();
    }

    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private void setRingerMode(int ringerMode) {
        when(mAudioManagerMock.getRingerModeInternal()).thenReturn(ringerMode);
    }

    private void setUserSetting(String settingName, int value) {
        Settings.System.putIntForUser(
                mContextSpy.getContentResolver(), settingName, value, UserHandle.USER_CURRENT);
    }

    private HalVibration performHapticFeedbackAndWaitUntilFinished(VibratorManagerService service,
            int constant, boolean always) throws InterruptedException {
        HalVibration vib = service.performHapticFeedbackInternal(UID, Context.DEVICE_ID_DEFAULT,
                PACKAGE_NAME, constant, "some reason", service,
                always ? HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING : 0 /* flags */,
                0 /* privFlags */);
        if (vib != null) {
            vib.waitForEnd();
        }

        return vib;
    }

    private HalVibration performHapticFeedbackForInputDeviceAndWaitUntilFinished(
            VibratorManagerService service, int constant, int inputDeviceId, int inputSource,
            boolean always) throws InterruptedException {
        HalVibration vib = service.performHapticFeedbackForInputDeviceInternal(UID,
                Context.DEVICE_ID_DEFAULT, PACKAGE_NAME, constant, inputDeviceId, inputSource,
                "some reason", service,
                always ? HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING : 0 /* flags */,
                0 /* privFlags */);
        if (vib != null) {
            vib.waitForEnd();
        }

        return vib;
    }

    private HalVibration vibrateAndWaitUntilFinished(VibratorManagerService service,
            VibrationEffect effect, VibrationAttributes attrs) throws InterruptedException {
        return vibrateAndWaitUntilFinished(
                service, CombinedVibration.createParallel(effect), attrs);
    }

    private HalVibration vibrateAndWaitUntilFinished(VibratorManagerService service,
            CombinedVibration effect, VibrationAttributes attrs) throws InterruptedException {
        HalVibration vib = vibrate(service, effect, attrs);
        if (vib != null) {
            vib.waitForEnd();
        }
        return vib;
    }

    private HalVibration vibrate(VibratorManagerService service, VibrationEffect effect,
            VibrationAttributes attrs) {
        return vibrate(service, CombinedVibration.createParallel(effect), attrs);
    }

    private HalVibration vibrate(VibratorManagerService service, CombinedVibration effect,
            VibrationAttributes attrs) {
        return vibrateWithDevice(service, Context.DEVICE_ID_DEFAULT, effect, attrs);
    }

    private HalVibration vibrateWithUid(VibratorManagerService service, int uid,
            VibrationEffect effect, VibrationAttributes attrs) {
        return vibrateWithUidAndDevice(service, uid, Context.DEVICE_ID_DEFAULT,
                CombinedVibration.createParallel(effect), attrs);
    }

    private HalVibration vibrateWithDevice(VibratorManagerService service, int deviceId,
            CombinedVibration effect, VibrationAttributes attrs) {
        return vibrateWithUidAndDevice(service, UID, deviceId, effect, attrs);
    }

    private HalVibration vibrateWithUidAndDevice(VibratorManagerService service, int uid,
            int deviceId, CombinedVibration effect, VibrationAttributes attrs) {
        HalVibration vib = service.vibrateWithPermissionCheck(uid, deviceId, PACKAGE_NAME, effect,
                attrs, "some reason", service);
        if (vib != null) {
            mPendingVibrations.add(vib);
        }
        return vib;
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

    private void grantPermission(String permission) {
        when(mContextSpy.checkCallingOrSelfPermission(permission))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        doNothing().when(mContextSpy).enforceCallingOrSelfPermission(eq(permission), anyString());
    }

    private void denyPermission(String permission) {
        when(mContextSpy.checkCallingOrSelfPermission(permission))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        doThrow(new SecurityException()).when(mContextSpy)
                .enforceCallingOrSelfPermission(eq(permission), anyString());
    }
}
