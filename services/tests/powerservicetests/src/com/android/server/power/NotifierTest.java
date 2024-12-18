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

import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.DisplayManagerInternal;
import android.os.BatteryStats;
import android.os.BatteryStatsInternal;
import android.os.Handler;
import android.os.IWakeLockCallback;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.testing.TestableContext;
import android.util.IntArray;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.IBatteryStats;
import com.android.server.LocalServices;
import com.android.server.input.InputManagerInternal;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.FrameworkStatsLogger.WakelockEventType;
import com.android.server.power.batterysaver.BatterySaverStateMachine;
import com.android.server.power.feature.PowerManagerFlags;
import com.android.server.statusbar.StatusBarManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

/**
 * Tests for {@link com.android.server.power.Notifier}
 */
public class NotifierTest {
    private static final String SYSTEM_PROPERTY_QUIESCENT = "ro.boot.quiescent";
    private static final int USER_ID = 0;
    private static final int DISPLAY_PORT = 0xFF;
    private static final long DISPLAY_MODEL = 0xEEEEEEEEL;

    private static final int UID = 1234;
    private static final int OWNER_UID = 1235;
    private static final int WORK_SOURCE_UID_1 = 2345;
    private static final int WORK_SOURCE_UID_2 = 2346;
    private static final int OWNER_WORK_SOURCE_UID_1 = 3456;
    private static final int OWNER_WORK_SOURCE_UID_2 = 3457;
    private static final int PID = 5678;

    @Mock private BatterySaverStateMachine mBatterySaverStateMachineMock;
    @Mock private PowerManagerService.NativeWrapper mNativeWrapperMock;
    @Mock private Notifier mNotifierMock;
    @Mock private WirelessChargerDetector mWirelessChargerDetectorMock;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfigurationMock;
    @Mock private SystemPropertiesWrapper mSystemPropertiesMock;
    @Mock private InattentiveSleepWarningController mInattentiveSleepWarningControllerMock;
    @Mock private Vibrator mVibrator;
    @Mock private StatusBarManagerInternal mStatusBarManagerInternal;
    @Mock private InputManagerInternal mInputManagerInternal;
    @Mock private InputMethodManagerInternal mInputMethodManagerInternal;
    @Mock private DisplayManagerInternal mDisplayManagerInternal;
    @Mock private ActivityManagerInternal mActivityManagerInternal;
    @Mock private WakeLockLog mWakeLockLog;

    @Mock private IBatteryStats mBatteryStats;

    @Mock private WindowManagerPolicy mPolicy;

    @Mock private PowerManagerFlags mPowerManagerFlags;

    @Mock private AppOpsManager mAppOpsManager;

    @Mock private BatteryStatsInternal mBatteryStatsInternal;
    @Mock private FrameworkStatsLogger mLogger;

    private PowerManagerService mService;
    private Context mContextSpy;
    private Resources mResourcesSpy;
    private TestLooper mTestLooper = new TestLooper();
    private FakeExecutor mTestExecutor = new FakeExecutor();
    private Notifier mNotifier;
    private DisplayInfo mDefaultDisplayInfo = new DisplayInfo();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mStatusBarManagerInternal);

        LocalServices.removeServiceForTest(InputManagerInternal.class);
        LocalServices.addService(InputManagerInternal.class, mInputManagerInternal);
        LocalServices.removeServiceForTest(InputMethodManagerInternal.class);
        LocalServices.addService(InputMethodManagerInternal.class, mInputMethodManagerInternal);

        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mActivityManagerInternal);

        mDefaultDisplayInfo.address = DisplayAddress.fromPortAndModel(DISPLAY_PORT, DISPLAY_MODEL);
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternal);

        mContextSpy = spy(new TestableContext(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), anyString())).thenReturn("");
        when(mContextSpy.getSystemService(Vibrator.class)).thenReturn(mVibrator);
        when(mDisplayManagerInternal.getDisplayInfo(Display.DEFAULT_DISPLAY)).thenReturn(
                mDefaultDisplayInfo);

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
    public void testOnGlobalWakefulnessChangeStarted() {
        createNotifier();
        // GIVEN system is currently non-interactive
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(false);
        final int displayId1 = 101;
        final int displayId2 = 102;
        final int[] displayIds = new int[]{displayId1, displayId2};
        when(mDisplayManagerInternal.getDisplayIds()).thenReturn(IntArray.wrap(displayIds));
        mNotifier.onGlobalWakefulnessChangeStarted(WAKEFULNESS_ASLEEP,
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, /* eventTime= */ 1000);
        mTestLooper.dispatchAll();

        // WHEN a global wakefulness change to interactive starts
        mNotifier.onGlobalWakefulnessChangeStarted(WAKEFULNESS_AWAKE,
                PowerManager.WAKE_REASON_TAP, /* eventTime= */ 2000);
        mTestLooper.dispatchAll();

        // THEN input is notified of all displays being interactive
        final SparseBooleanArray expectedDisplayInteractivities = new SparseBooleanArray();
        expectedDisplayInteractivities.put(displayId1, true);
        expectedDisplayInteractivities.put(displayId2, true);
        verify(mInputManagerInternal).setDisplayInteractivities(expectedDisplayInteractivities);
        verify(mInputMethodManagerInternal).setInteractive(/* interactive= */ true);
    }

    @Test
    public void testOnGroupWakefulnessChangeStarted_newPowerGroup_perDisplayWakeDisabled() {
        createNotifier();
        // GIVEN power group is not yet known to Notifier and per-display wake by touch is disabled
        final int groupId = 123;
        final int changeReason = PowerManager.WAKE_REASON_TAP;
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(false);

        // WHEN a power group wakefulness change starts
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId, WAKEFULNESS_AWAKE, changeReason, /* eventTime= */ 999);
        mTestLooper.dispatchAll();

        // THEN window manager policy is informed that device has started waking up
        verify(mPolicy).startedWakingUp(groupId, changeReason);
        verify(mDisplayManagerInternal, never()).getDisplayIds();
        verify(mInputManagerInternal, never()).setDisplayInteractivities(any());
    }

    @Test
    public void testOnGroupWakefulnessChangeStarted_interactivityNoChange_perDisplayWakeDisabled() {
        createNotifier();
        // GIVEN power group is not interactive and per-display wake by touch is disabled
        final int groupId = 234;
        final int changeReason = PowerManager.GO_TO_SLEEP_REASON_TIMEOUT;
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(false);
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId, WAKEFULNESS_ASLEEP, changeReason, /* eventTime= */ 999);
        mTestLooper.dispatchAll();
        verify(mPolicy, times(1)).startedGoingToSleep(groupId, changeReason);

        // WHEN a power wakefulness change to not interactive starts
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId, WAKEFULNESS_ASLEEP, changeReason, /* eventTime= */ 999);
        mTestLooper.dispatchAll();

        // THEN policy is only informed once of non-interactive wakefulness change
        verify(mPolicy, times(1)).startedGoingToSleep(groupId, changeReason);
        verify(mDisplayManagerInternal, never()).getDisplayIds();
        verify(mInputManagerInternal, never()).setDisplayInteractivities(any());
    }

    @Test
    public void testOnGroupWakefulnessChangeStarted_interactivityChange_perDisplayWakeDisabled() {
        createNotifier();
        // GIVEN power group is not interactive and per-display wake by touch is disabled
        final int groupId = 345;
        final int firstChangeReason = PowerManager.GO_TO_SLEEP_REASON_TIMEOUT;
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(false);
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId, WAKEFULNESS_ASLEEP, firstChangeReason, /* eventTime= */ 999);
        mTestLooper.dispatchAll();

        // WHEN a power wakefulness change to interactive starts
        final int secondChangeReason = PowerManager.WAKE_REASON_TAP;
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId, WAKEFULNESS_AWAKE, secondChangeReason, /* eventTime= */ 999);
        mTestLooper.dispatchAll();

        // THEN policy is informed of the change
        verify(mPolicy).startedWakingUp(groupId, secondChangeReason);
        verify(mDisplayManagerInternal, never()).getDisplayIds();
        verify(mInputManagerInternal, never()).setDisplayInteractivities(any());
    }

    @Test
    public void testOnGroupWakefulnessChangeStarted_perDisplayWakeByTouchEnabled() {
        createNotifier();
        // GIVEN per-display wake by touch flag is enabled
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(true);
        final int groupId = 456;
        final int displayId1 = 1001;
        final int displayId2 = 1002;
        final int[] displays = new int[]{displayId1, displayId2};
        when(mDisplayManagerInternal.getDisplayIds()).thenReturn(IntArray.wrap(displays));
        when(mDisplayManagerInternal.getDisplayIdsForGroup(groupId)).thenReturn(displays);
        final int changeReason = PowerManager.WAKE_REASON_TAP;

        // WHEN power group wakefulness change started
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId, WAKEFULNESS_AWAKE, changeReason, /* eventTime= */ 999);
        mTestLooper.dispatchAll();

        // THEN native input manager is updated that the displays are interactive
        final SparseBooleanArray expectedDisplayInteractivities = new SparseBooleanArray();
        expectedDisplayInteractivities.put(displayId1, true);
        expectedDisplayInteractivities.put(displayId2, true);
        verify(mInputManagerInternal).setDisplayInteractivities(expectedDisplayInteractivities);
    }

    @Test
    public void testOnGroupRemoved_perDisplayWakeByTouchEnabled() {
        createNotifier();
        // GIVEN per-display wake by touch is enabled and one display group has been defined
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(true);
        final int groupId = 313;
        final int displayId1 = 3113;
        final int displayId2 = 4114;
        final int[] displays = new int[]{displayId1, displayId2};
        when(mDisplayManagerInternal.getDisplayIds()).thenReturn(IntArray.wrap(displays));
        when(mDisplayManagerInternal.getDisplayIdsForGroup(groupId)).thenReturn(displays);
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId, WAKEFULNESS_AWAKE, PowerManager.WAKE_REASON_TAP, /* eventTime= */ 1000);
        final SparseBooleanArray expectedDisplayInteractivities = new SparseBooleanArray();
        expectedDisplayInteractivities.put(displayId1, true);
        expectedDisplayInteractivities.put(displayId2, true);
        verify(mInputManagerInternal).setDisplayInteractivities(expectedDisplayInteractivities);

        // WHEN display group is removed
        when(mDisplayManagerInternal.getDisplayIdsByGroupsIds()).thenReturn(new SparseArray<>());
        mNotifier.onGroupRemoved(groupId);

        // THEN native input manager is informed that displays in that group no longer exist
        verify(mInputManagerInternal).setDisplayInteractivities(new SparseBooleanArray());
    }

    @Test
    public void testOnWakeLockReleased_FrameworkStatsLogged_NoChains() {
        when(mPowerManagerFlags.isMoveWscLoggingToNotifierEnabled()).thenReturn(true);
        createNotifier();

        clearInvocations(mLogger, mWakeLockLog, mBatteryStats, mAppOpsManager);

        when(mBatteryStatsInternal.getOwnerUid(UID)).thenReturn(OWNER_UID);
        when(mBatteryStatsInternal.getOwnerUid(WORK_SOURCE_UID_1))
                .thenReturn(OWNER_WORK_SOURCE_UID_1);

        mNotifier.onWakeLockAcquired(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                /* workSource= */ null,
                /* historyTag= */ null,
                /* callback= */ null);

        WorkSource ws = new WorkSource(WORK_SOURCE_UID_1);

        mNotifier.onWakeLockChanging(
                /* existing WakeLock params */
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                /* workSource= */ null,
                /* historyTag= */ null,
                /* callback= */ null,
                /* updated WakeLock params */
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                ws,
                /* historyTag= */ null,
                /* callback= */ null);

        mNotifier.onWakeLockReleased(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                ws,
                /* historyTag= */ null,
                /* callback= */ null);

        verify(mBatteryStatsInternal, atLeast(1)).getOwnerUid(eq(UID));
        verify(mBatteryStatsInternal, atLeast(1)).getOwnerUid(eq(WORK_SOURCE_UID_1));

        // ACQUIRE before RELEASE
        InOrder inOrder1 = inOrder(mLogger);
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_UID),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_UID),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));

        InOrder inOrder2 = inOrder(mLogger);
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_WORK_SOURCE_UID_1),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_WORK_SOURCE_UID_1),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
    }

    @Test
    public void testOnWakeLockReleased_FrameworkStatsLogged_MultipleWorkSourceUids() {
        // UIDs stored directly in WorkSource
        WorkSource ws = new WorkSource(WORK_SOURCE_UID_1);
        ws.add(WORK_SOURCE_UID_2);
        testWorkSource(ws);

        InOrder inOrder = inOrder(mLogger);
        ArgumentCaptor<Integer> captorInt = ArgumentCaptor.forClass(int.class);

        // ACQUIRE
        inOrder.verify(mLogger, times(2))
                .wakelockStateChanged(
                        /* uid= */ captorInt.capture(),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        assertThat(captorInt.getAllValues())
                .containsExactly(OWNER_WORK_SOURCE_UID_1, OWNER_WORK_SOURCE_UID_2);

        // RELEASE
        captorInt = ArgumentCaptor.forClass(int.class);
        inOrder.verify(mLogger, times(2))
                .wakelockStateChanged(
                        /* uid= */ captorInt.capture(),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
        assertThat(captorInt.getAllValues())
                .containsExactly(OWNER_WORK_SOURCE_UID_1, OWNER_WORK_SOURCE_UID_2);
    }

    @Test
    public void testOnWakeLockReleased_FrameworkStatsLogged_OneChain() {
        // UIDs stored in a WorkChain of the WorkSource
        WorkSource ws = new WorkSource();
        WorkChain wc = ws.createWorkChain();
        wc.addNode(WORK_SOURCE_UID_1, "tag1");
        wc.addNode(WORK_SOURCE_UID_2, "tag2");
        testWorkSource(ws);

        WorkChain expectedWorkChain = new WorkChain();
        expectedWorkChain.addNode(OWNER_WORK_SOURCE_UID_1, "tag1");
        expectedWorkChain.addNode(OWNER_WORK_SOURCE_UID_2, "tag2");

        InOrder inOrder = inOrder(mLogger);

        // ACQUIRE
        inOrder.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        // RELEASE
        inOrder.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
    }

    @Test
    public void testOnWakeLockReleased_FrameworkStatsLogged_OneUid_OneChain() {
        WorkSource ws = new WorkSource(WORK_SOURCE_UID_1);
        WorkChain wc = ws.createWorkChain();
        wc.addNode(WORK_SOURCE_UID_2, "someTag");
        testWorkSource(ws);

        WorkChain expectedWorkChain = new WorkChain();
        expectedWorkChain.addNode(OWNER_WORK_SOURCE_UID_2, "someTag");

        InOrder inOrder1 = inOrder(mLogger);
        InOrder inOrder2 = inOrder(mLogger);

        // ACQUIRE
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_WORK_SOURCE_UID_1),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        // RELEASE
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_WORK_SOURCE_UID_1),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
    }

    @Test
    public void testOnWakeLockReleased_FrameworkStatsLogged_TwoChains() {
        // UIDs stored in a WorkChain of the WorkSource
        WorkSource ws = new WorkSource();
        WorkChain wc1 = ws.createWorkChain();
        wc1.addNode(WORK_SOURCE_UID_1, "tag1");

        WorkChain wc2 = ws.createWorkChain();
        wc2.addNode(WORK_SOURCE_UID_2, "tag2");

        testWorkSource(ws);

        WorkChain expectedWorkChain1 = new WorkChain();
        expectedWorkChain1.addNode(OWNER_WORK_SOURCE_UID_1, "tag1");

        WorkChain expectedWorkChain2 = new WorkChain();
        expectedWorkChain2.addNode(OWNER_WORK_SOURCE_UID_2, "tag2");

        InOrder inOrder1 = inOrder(mLogger);
        InOrder inOrder2 = inOrder(mLogger);

        // ACQUIRE
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain1),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain2),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));

        // RELEASE
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain1),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain2),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
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
        Notifier.Injector injector =
                new Notifier.Injector() {
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

                    @Override
                    public FrameworkStatsLogger getFrameworkStatsLogger() {
                        return mLogger;
                    }

                    @Override
                    public BatteryStatsInternal getBatteryStatsInternal() {
                        return mBatteryStatsInternal;
                    }
                };

        mNotifier = new Notifier(
                mTestLooper.getLooper(),
                mContextSpy,
                mBatteryStats,
                mInjector.createSuspendBlocker(mService, "testBlocker"),
                mPolicy,
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

    private void testWorkSource(WorkSource ws) {
        when(mPowerManagerFlags.isMoveWscLoggingToNotifierEnabled()).thenReturn(true);
        createNotifier();
        clearInvocations(
                mBatteryStatsInternal, mLogger, mWakeLockLog, mBatteryStats, mAppOpsManager);

        when(mBatteryStatsInternal.getOwnerUid(WORK_SOURCE_UID_1))
                .thenReturn(OWNER_WORK_SOURCE_UID_1);
        when(mBatteryStatsInternal.getOwnerUid(WORK_SOURCE_UID_2))
                .thenReturn(OWNER_WORK_SOURCE_UID_2);

        mNotifier.onWakeLockAcquired(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                ws,
                /* historyTag= */ null,
                /* callback= */ null);

        mNotifier.onWakeLockReleased(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                ws,
                /* historyTag= */ null,
                /* callback= */ null);

        verify(mBatteryStatsInternal, atLeast(1)).getOwnerUid(eq(WORK_SOURCE_UID_1));
        verify(mBatteryStatsInternal, atLeast(1)).getOwnerUid(eq(WORK_SOURCE_UID_2));
    }
}
