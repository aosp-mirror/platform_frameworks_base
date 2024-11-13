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

package com.android.server.power;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.IWakeLockCallback;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.os.WorkSource;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.IBatteryStats;
import com.android.server.LocalServices;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.batterysaver.BatterySaverStateMachine;
import com.android.server.power.feature.PowerManagerFlags;
import com.android.server.statusbar.StatusBarManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

/**
 * Tests for {@link com.android.server.power.Notifier}
 */
public class NotifierTest {
    private static final String SYSTEM_PROPERTY_QUIESCENT = "ro.boot.quiescent";
    private static final int USER_ID = 0;

    @Mock private BatterySaverStateMachine mBatterySaverStateMachineMock;
    @Mock private PowerManagerService.NativeWrapper mNativeWrapperMock;
    @Mock private Notifier mNotifierMock;
    @Mock private WirelessChargerDetector mWirelessChargerDetectorMock;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfigurationMock;
    @Mock private SystemPropertiesWrapper mSystemPropertiesMock;
    @Mock private InattentiveSleepWarningController mInattentiveSleepWarningControllerMock;
    @Mock private Vibrator mVibrator;
    @Mock private StatusBarManagerInternal mStatusBarManagerInternal;
    @Mock private WakeLockLog mWakeLockLog;

    @Mock private IBatteryStats mBatteryStats;

    @Mock private PowerManagerFlags mPowerManagerFlags;

    @Mock private AppOpsManager mAppOpsManager;

    private PowerManagerService mService;
    private Context mContextSpy;
    private Resources mResourcesSpy;
    private TestLooper mTestLooper = new TestLooper();
    private FakeExecutor mTestExecutor = new FakeExecutor();
    private Notifier mNotifier;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mStatusBarManagerInternal);

        mContextSpy = spy(new TestableContext(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), anyString())).thenReturn("");
        when(mContextSpy.getSystemService(Vibrator.class)).thenReturn(mVibrator);

        mService = new PowerManagerService(mContextSpy, mInjector);
    }

    @Test
    public void testVibrateEnabled_wiredCharging() {
        createNotifier();

        // GIVEN the charging vibration is enabled
        enableChargingVibration(true);

        // WHEN wired charging starts
        mNotifier.onWiredChargingStarted(USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device vibrates once
        verify(mVibrator, times(1)).vibrate(anyInt(), any(), any(), any(),
                any(VibrationAttributes.class));
    }

    @Test
    public void testVibrateDisabled_wiredCharging() {
        createNotifier();

        // GIVEN the charging vibration is disabled
        enableChargingVibration(false);

        // WHEN wired charging starts
        mNotifier.onWiredChargingStarted(USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device doesn't vibrate
        verifyZeroInteractions(mVibrator);
    }

    @Test
    public void testVibrateEnabled_wirelessCharging() {
        createNotifier();

        // GIVEN the charging vibration is enabled
        enableChargingVibration(true);

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device vibrates once
        verify(mVibrator, times(1)).vibrate(anyInt(), any(), any(), any(),
                any(VibrationAttributes.class));
    }

    @Test
    public void testVibrateDisabled_wirelessCharging() {
        createNotifier();

        // GIVEN the charging vibration is disabled
        enableChargingVibration(false);

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device doesn't vibrate
        verifyZeroInteractions(mVibrator);
    }

    @Test
    public void testVibrateEnabled_dndOn() {
        createNotifier();

        // GIVEN the charging vibration is enabled but dnd is on
        enableChargingVibration(true);
        enableChargingFeedback(
                /* chargingFeedbackEnabled */ true,
                /* dndOn */ true);

        // WHEN wired charging starts
        mNotifier.onWiredChargingStarted(USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device doesn't vibrate
        verify(mVibrator, never()).vibrate(any(), any(VibrationAttributes.class));
    }

    @Test
    public void testWirelessAnimationEnabled() {
        // GIVEN the wireless charging animation is enabled
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_showBuiltinWirelessChargingAnim))
                .thenReturn(true);
        createNotifier();

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the charging animation is triggered
        verify(mStatusBarManagerInternal, times(1)).showChargingAnimation(5);
    }

    @Test
    public void testWirelessAnimationDisabled() {
        // GIVEN the wireless charging animation is disabled
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_showBuiltinWirelessChargingAnim))
                .thenReturn(false);
        createNotifier();

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the charging animation never gets called
        verify(mStatusBarManagerInternal, never()).showChargingAnimation(anyInt());
    }

    @Test
    public void testOnWakeLockListener_RemoteException_NoRethrow() throws RemoteException {
        when(mPowerManagerFlags.improveWakelockLatency()).thenReturn(true);
        createNotifier();
        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager);
        IWakeLockCallback exceptingCallback = new IWakeLockCallback.Stub() {
            @Override public void onStateChanged(boolean enabled) throws RemoteException {
                throw new RemoteException("Just testing");
            }
        };

        final int uid = 1234;
        final int pid = 5678;
        mNotifier.onWakeLockReleased(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);
        verifyZeroInteractions(mWakeLockLog);
        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockReleased("wakelockTag", uid, 1);
        clearInvocations(mBatteryStats);
        mNotifier.onWakeLockAcquired(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);

        verifyNoMoreInteractions(mWakeLockLog, mBatteryStats);
        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockAcquired("wakelockTag", uid,
                PowerManager.PARTIAL_WAKE_LOCK, 1);
        verify(mBatteryStats).noteStartWakelock(uid, pid, "wakelockTag", /* historyTag= */ null,
                BatteryStats.WAKE_TYPE_PARTIAL, false);

        verifyNoMoreInteractions(mWakeLockLog, mBatteryStats);
        WorkSource worksourceOld = new WorkSource(/*uid=*/ 1);
        WorkSource worksourceNew = new WorkSource(/*uid=*/ 2);

        mNotifier.onWakeLockChanging(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, worksourceOld, /* historyTag= */ null,
                exceptingCallback,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, worksourceNew, /* newHistoryTag= */ null,
                exceptingCallback);
        mTestLooper.dispatchAll();
        verify(mBatteryStats).noteChangeWakelockFromSource(worksourceOld, pid, "wakelockTag",
                null, BatteryStats.WAKE_TYPE_PARTIAL, worksourceNew, pid, "wakelockTag",
                null, BatteryStats.WAKE_TYPE_FULL, false);
        // If we didn't throw, we're good!

        // Test with improveWakelockLatency flag false, hence the wakelock log will run on the same
        // thread
        clearInvocations(mWakeLockLog, mBatteryStats);
        when(mPowerManagerFlags.improveWakelockLatency()).thenReturn(false);

        // Acquire the wakelock
        mNotifier.onWakeLockAcquired(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);
        verify(mWakeLockLog).onWakeLockAcquired("wakelockTag", uid,
                PowerManager.PARTIAL_WAKE_LOCK, -1);

        // Update the wakelock
        mNotifier.onWakeLockChanging(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, worksourceOld, /* historyTag= */ null,
                exceptingCallback,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, worksourceNew, /* newHistoryTag= */ null,
                exceptingCallback);
        verify(mBatteryStats).noteChangeWakelockFromSource(worksourceOld, pid, "wakelockTag",
                null, BatteryStats.WAKE_TYPE_PARTIAL, worksourceNew, pid, "wakelockTag",
                null, BatteryStats.WAKE_TYPE_FULL, false);

        // Release the wakelock
        mNotifier.onWakeLockReleased(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);
        verify(mWakeLockLog).onWakeLockReleased("wakelockTag", uid, -1);
    }

    @Test
    public void
            test_notifierProcessesWorkSourceDeepCopy_OnWakelockChanging() throws RemoteException {
        when(mPowerManagerFlags.improveWakelockLatency()).thenReturn(true);
        createNotifier();
        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager);
        IWakeLockCallback exceptingCallback = new IWakeLockCallback.Stub() {
            @Override public void onStateChanged(boolean enabled) throws RemoteException {
                throw new RemoteException("Just testing");
            }
        };

        final int uid = 1234;
        final int pid = 5678;
        mTestLooper.dispatchAll();
        WorkSource worksourceOld = new WorkSource(/*uid=*/ 1);
        WorkSource worksourceNew =  new WorkSource(/*uid=*/ 2);

        mNotifier.onWakeLockChanging(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, worksourceOld, /* historyTag= */ null,
                exceptingCallback,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, worksourceNew, /* newHistoryTag= */ null,
                exceptingCallback);
        // The newWorksource is modified before notifier could process it.
        worksourceNew.set(/*uid=*/ 3);

        mTestLooper.dispatchAll();
        verify(mBatteryStats).noteChangeWakelockFromSource(worksourceOld, pid,
                "wakelockTag", null, BatteryStats.WAKE_TYPE_PARTIAL,
                new WorkSource(/*uid=*/ 2), pid, "wakelockTag", null,
                BatteryStats.WAKE_TYPE_FULL, false);
    }


    @Test
    public void testOnWakeLockListener_FullWakeLock_ProcessesOnHandler() throws RemoteException {
        when(mPowerManagerFlags.improveWakelockLatency()).thenReturn(true);
        createNotifier();

        IWakeLockCallback exceptingCallback = new IWakeLockCallback.Stub() {
            @Override public void onStateChanged(boolean enabled) throws RemoteException {
                throw new RemoteException("Just testing");
            }
        };
        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager);

        final int uid = 1234;
        final int pid = 5678;

        // Release the wakelock
        mNotifier.onWakeLockReleased(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);

        // No interaction because we expect that to happen in async
        verifyZeroInteractions(mWakeLockLog, mBatteryStats, mAppOpsManager);

        // Progressing the looper, and validating all the interactions
        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockReleased("wakelockTag", uid, 1);
        verify(mBatteryStats).noteStopWakelock(uid, pid, "wakelockTag", /* historyTag= */ null,
                BatteryStats.WAKE_TYPE_FULL);
        verify(mAppOpsManager).finishOp(AppOpsManager.OP_WAKE_LOCK, uid,
                "my.package.name", null);

        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager);

        // Acquire the wakelock
        mNotifier.onWakeLockAcquired(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);

        // No interaction because we expect that to happen in async
        verifyNoMoreInteractions(mWakeLockLog, mBatteryStats, mAppOpsManager);

        // Progressing the looper, and validating all the interactions
        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockAcquired("wakelockTag", uid,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, 1);
        verify(mBatteryStats).noteStartWakelock(uid, pid, "wakelockTag", /* historyTag= */ null,
                BatteryStats.WAKE_TYPE_FULL, false);
        verify(mAppOpsManager).startOpNoThrow(AppOpsManager.OP_WAKE_LOCK, uid,
                "my.package.name", false, null, null);

        // Test with improveWakelockLatency flag false, hence the wakelock log will run on the same
        // thread
        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager);
        when(mPowerManagerFlags.improveWakelockLatency()).thenReturn(false);

        mNotifier.onWakeLockAcquired(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);
        verify(mWakeLockLog).onWakeLockAcquired("wakelockTag", uid,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, -1);

        mNotifier.onWakeLockReleased(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);
        verify(mWakeLockLog).onWakeLockReleased("wakelockTag", uid, -1);
    }

    @Test
    public void testOnWakeLockListener_FullWakeLock_ProcessesInSync() throws RemoteException {
        createNotifier();

        IWakeLockCallback exceptingCallback = new IWakeLockCallback.Stub() {
            @Override public void onStateChanged(boolean enabled) throws RemoteException {
                throw new RemoteException("Just testing");
            }
        };
        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager);

        final int uid = 1234;
        final int pid = 5678;

        // Release the wakelock
        mNotifier.onWakeLockReleased(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);

        verify(mWakeLockLog).onWakeLockReleased("wakelockTag", uid, -1);
        verify(mBatteryStats).noteStopWakelock(uid, pid, "wakelockTag", /* historyTag= */ null,
                BatteryStats.WAKE_TYPE_FULL);
        verify(mAppOpsManager).finishOp(AppOpsManager.OP_WAKE_LOCK, uid,
                "my.package.name", null);

        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager);

        // Acquire the wakelock
        mNotifier.onWakeLockAcquired(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);

        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockAcquired("wakelockTag", uid,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, -1);
        verify(mBatteryStats).noteStartWakelock(uid, pid, "wakelockTag", /* historyTag= */ null,
                BatteryStats.WAKE_TYPE_FULL, false);
        verify(mAppOpsManager).startOpNoThrow(AppOpsManager.OP_WAKE_LOCK, uid,
                "my.package.name", false, null, null);
    }

    private final PowerManagerService.Injector mInjector = new PowerManagerService.Injector() {
        @Override
        Notifier createNotifier(Looper looper, Context context, IBatteryStats batteryStats,
                SuspendBlocker suspendBlocker, WindowManagerPolicy policy,
                FaceDownDetector faceDownDetector, ScreenUndimDetector screenUndimDetector,
                Executor backgroundExecutor, PowerManagerFlags powerManagerFlags) {
            return mNotifierMock;
        }

        @Override
        SuspendBlocker createSuspendBlocker(PowerManagerService service, String name) {
            return super.createSuspendBlocker(service, name);
        }

        @Override
        BatterySaverStateMachine createBatterySaverStateMachine(Object lock, Context context) {
            return mBatterySaverStateMachineMock;
        }

        @Override
        PowerManagerService.NativeWrapper createNativeWrapper() {
            return mNativeWrapperMock;
        }

        @Override
        WirelessChargerDetector createWirelessChargerDetector(
                SensorManager sensorManager, SuspendBlocker suspendBlocker, Handler handler) {
            return mWirelessChargerDetectorMock;
        }

        @Override
        AmbientDisplayConfiguration createAmbientDisplayConfiguration(Context context) {
            return mAmbientDisplayConfigurationMock;
        }

        @Override
        InattentiveSleepWarningController createInattentiveSleepWarningController() {
            return mInattentiveSleepWarningControllerMock;
        }

        @Override
        public SystemPropertiesWrapper createSystemPropertiesWrapper() {
            return mSystemPropertiesMock;
        }

        @Override
        void invalidateIsInteractiveCaches() {
            // Avoids an SELinux denial.
        }
    };

    private void enableChargingFeedback(boolean chargingFeedbackEnabled, boolean dndOn) {
        // enable/disable charging feedback
        Settings.Secure.putIntForUser(
                mContextSpy.getContentResolver(),
                Settings.Secure.CHARGING_SOUNDS_ENABLED,
                chargingFeedbackEnabled ? 1 : 0,
                USER_ID);

        // toggle on/off dnd
        Settings.Global.putInt(
                mContextSpy.getContentResolver(),
                Settings.Global.ZEN_MODE,
                dndOn ? Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                        : Settings.Global.ZEN_MODE_OFF);
    }

    private void enableChargingVibration(boolean enable) {
        enableChargingFeedback(true, false);

        Settings.Secure.putIntForUser(
                mContextSpy.getContentResolver(),
                Settings.Secure.CHARGING_VIBRATION_ENABLED,
                enable ? 1 : 0,
                USER_ID);
    }

    private void createNotifier() {
        Notifier.Injector injector = new Notifier.Injector() {
            @Override
            public long currentTimeMillis() {
                return 1;
            }

            @Override
            public WakeLockLog getWakeLockLog(Context context) {
                return mWakeLockLog;
            }

            @Override
            public AppOpsManager getAppOpsManager(Context context) {
                return mAppOpsManager;
            }
        };

        mNotifier = new Notifier(
                mTestLooper.getLooper(),
                mContextSpy,
                mBatteryStats,
                mInjector.createSuspendBlocker(mService, "testBlocker"),
                null,
                null,
                null,
                mTestExecutor, mPowerManagerFlags, injector);
    }

    private static class FakeExecutor implements Executor {
        private Runnable mLastCommand;

        @Override
        public void execute(Runnable command) {
            assertNull(mLastCommand);
            assertNotNull(command);
            mLastCommand = command;
        }

        public Runnable getAndResetLastCommand() {
            Runnable toReturn = mLastCommand;
            mLastCommand = null;
            return toReturn;
        }

        public void simulateAsyncExecutionOfLastCommand() {
            Runnable toRun = getAndResetLastCommand();
            if (toRun != null) {
                toRun.run();
            }
        }
    }

}
