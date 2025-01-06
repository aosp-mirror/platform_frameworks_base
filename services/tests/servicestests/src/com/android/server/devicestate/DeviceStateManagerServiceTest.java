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

package com.android.server.devicestate;

import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER;

import static com.android.compatibility.common.util.PollingCheck.waitFor;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.frameworks.devicestate.DeviceStateConfiguration;
import android.frameworks.devicestate.ErrorCode;
import android.frameworks.devicestate.IDeviceStateListener;
import android.frameworks.devicestate.IDeviceStateService;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateInfo;
import android.hardware.devicestate.DeviceStateRequest;
import android.hardware.devicestate.IDeviceStateManagerCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.annotation.NonNull;
import androidx.test.filters.FlakyTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.PollingCheck;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowProcessController;
import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

/**
 * Unit tests for {@link DeviceStateManagerService}.
 *
 * <p> Build/Install/Run:
 * atest FrameworksServicesTests:DeviceStateManagerServiceTest
 */
@Presubmit
@RunWith(ParameterizedAndroidJunit4.class)
public final class DeviceStateManagerServiceTest {
    private static final DeviceState DEFAULT_DEVICE_STATE = new DeviceState(
            new DeviceState.Configuration.Builder(0, "DEFAULT").build());
    private static final int DEFAULT_DEVICE_STATE_IDENTIFIER = DEFAULT_DEVICE_STATE.getIdentifier();
    private static final String DEFAULT_DEVICE_STATE_TRACE_STRING =
            DEFAULT_DEVICE_STATE_IDENTIFIER + ":" + DEFAULT_DEVICE_STATE.getName();

    private static final DeviceState OTHER_DEVICE_STATE = new DeviceState(
            new DeviceState.Configuration.Builder(1, "DEFAULT").build());
    private static final int OTHER_DEVICE_STATE_IDENTIFIER = OTHER_DEVICE_STATE.getIdentifier();
    private static final String OTHER_DEVICE_STATE_TRACE_STRING =
            OTHER_DEVICE_STATE_IDENTIFIER + ":" + OTHER_DEVICE_STATE.getName();

    private static final DeviceState DEVICE_STATE_CANCEL_WHEN_REQUESTER_NOT_ON_TOP =
            new DeviceState(new DeviceState.Configuration.Builder(2,
                    "DEVICE_STATE_CANCEL_WHEN_REQUESTER_NOT_ON_TOP")
                    .setSystemProperties(new HashSet<>(List.of(
                            DeviceState.PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP)))
                    .build());

    // A device state that is not reported as being supported for the default test provider.
    private static final DeviceState UNSUPPORTED_DEVICE_STATE = new DeviceState(
            new DeviceState.Configuration.Builder(255, "UNSUPPORTED")
                    .build());

    private static final List<DeviceState> SUPPORTED_DEVICE_STATES = Arrays.asList(
            DEFAULT_DEVICE_STATE, OTHER_DEVICE_STATE,
            DEVICE_STATE_CANCEL_WHEN_REQUESTER_NOT_ON_TOP);

    private static final int FAKE_PROCESS_ID = 100;

    private static final int TIMEOUT = 2000;

    @Rule
    public final SetFlagsRule mSetFlagsRule;

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return FlagsParameterization.allCombinationsOf(Flags.FLAG_WLINFO_ONCREATE);
    }

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    @NonNull
    private TestDeviceStatePolicy mPolicy;
    @NonNull
    private TestDeviceStateProvider mProvider;
    @NonNull
    private DeviceStateManagerService mService;
    @NonNull
    private TestSystemPropertySetter mSysPropSetter;
    @NonNull
    private WindowProcessController mWindowProcessController;

    public DeviceStateManagerServiceTest(FlagsParameterization flags) {
        mSetFlagsRule = new SetFlagsRule(flags);
    }

    @Before
    public void setup() {
        mProvider = new TestDeviceStateProvider();
        mPolicy = new TestDeviceStatePolicy(mContext, mProvider);
        mSysPropSetter = new TestSystemPropertySetter();
        setupDeviceStateManagerService();
        if (!Flags.wlinfoOncreate()) {
            flushHandler(); // Flush the handler to ensure the initial values are committed.
        }
    }

    private void setupDeviceStateManagerService() {
        mService = new DeviceStateManagerService(mContext, mPolicy, mSysPropSetter);

        // Necessary to allow us to check for top app process id in tests
        mService.mActivityTaskManagerInternal = mock(ActivityTaskManagerInternal.class);
        mWindowProcessController = mock(WindowProcessController.class);
        when(mService.mActivityTaskManagerInternal.getTopApp())
                .thenReturn(mWindowProcessController);
        when(mWindowProcessController.getPid()).thenReturn(FAKE_PROCESS_ID);
    }

    private void flushHandler() {
        flushHandler(1);
    }

    private void flushHandler(int count) {
        for (int i = 0; i < count; i++) {
            mService.getHandler().runWithScissors(() -> {}, 0);
        }
    }

    private void waitAndAssert(PollingCheck.PollingCheckCondition condition) {
        waitFor(TIMEOUT, condition);
    }

    @Test
    public void baseStateChanged() {
        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).isEmpty();
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(DEFAULT_DEVICE_STATE_IDENTIFIER);

        mProvider.setState(OTHER_DEVICE_STATE_IDENTIFIER);
        flushHandler();

        assertThat(mService.getCommittedState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mService.getPendingState()).isEmpty();
        assertThat(mSysPropSetter.getValue()).isEqualTo(OTHER_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(OTHER_DEVICE_STATE_IDENTIFIER);
    }

    @Test
    public void baseStateChanged_withStatePendingPolicyCallback() {
        mPolicy.blockConfigure();

        mProvider.setState(OTHER_DEVICE_STATE_IDENTIFIER);
        flushHandler();
        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(OTHER_DEVICE_STATE_IDENTIFIER);

        mProvider.setState(DEFAULT_DEVICE_STATE_IDENTIFIER);
        flushHandler();
        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(OTHER_DEVICE_STATE_IDENTIFIER);

        mPolicy.resumeConfigure();
        flushHandler();
        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).isEmpty();
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(DEFAULT_DEVICE_STATE_IDENTIFIER);
    }

    @Test
    public void baseStateChanged_unsupportedState() {
        assertThrows(IllegalArgumentException.class, () -> {
            mProvider.setState(UNSUPPORTED_DEVICE_STATE.getIdentifier());
        });

        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).isEmpty();
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(DEFAULT_DEVICE_STATE_IDENTIFIER);
    }

    @Test
    public void baseStateChanged_invalidState() {
        assertThrows(IllegalArgumentException.class,
                () -> mProvider.setState(INVALID_DEVICE_STATE_IDENTIFIER));

        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).isEmpty();
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(DEFAULT_DEVICE_STATE_IDENTIFIER);
    }

    @Test
    public void supportedStatesChanged() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).isEmpty();
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getSupportedStates()).containsExactly(DEFAULT_DEVICE_STATE,
                OTHER_DEVICE_STATE, DEVICE_STATE_CANCEL_WHEN_REQUESTER_NOT_ON_TOP);

        mProvider.notifySupportedDeviceStates(new DeviceState[]{DEFAULT_DEVICE_STATE});
        flushHandler();

        // The current committed and requests states do not change because the current state remains
        // supported.
        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).isEmpty();
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getSupportedStates()).containsExactly(DEFAULT_DEVICE_STATE);

        assertThat(callback.getLastNotifiedInfo().supportedStates)
                .containsExactly(DEFAULT_DEVICE_STATE);
    }

    @Test
    public void supportedStatesChanged_statesRemainSame() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        if (!Flags.wlinfoOncreate()) {
            // An initial callback will be triggered on registration, so we clear it here.
            flushHandler();
            callback.clearLastNotifiedInfo();
        }

        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).isEmpty();
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getSupportedStates()).containsExactly(DEFAULT_DEVICE_STATE,
                OTHER_DEVICE_STATE, DEVICE_STATE_CANCEL_WHEN_REQUESTER_NOT_ON_TOP);

        mProvider.notifySupportedDeviceStates(new DeviceState[]{DEFAULT_DEVICE_STATE,
                OTHER_DEVICE_STATE, DEVICE_STATE_CANCEL_WHEN_REQUESTER_NOT_ON_TOP});
        flushHandler();

        // The current committed and requests states do not change because the current state remains
        // supported.
        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).isEmpty();
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getSupportedStates()).containsExactly(DEFAULT_DEVICE_STATE,
                OTHER_DEVICE_STATE, DEVICE_STATE_CANCEL_WHEN_REQUESTER_NOT_ON_TOP);

        // The callback wasn't notified about a change in supported states as the states have not
        // changed.
        assertThat(callback.getLastNotifiedInfo()).isNull();
    }

    @Test
    public void getDeviceStateInfo() throws RemoteException {
        final DeviceStateInfo info = mService.getBinderService().getDeviceStateInfo();
        assertThat(info).isNotNull();
        assertThat(info.supportedStates)
                .containsExactlyElementsIn(SUPPORTED_DEVICE_STATES).inOrder();
        assertThat(info.baseState).isEqualTo(DEFAULT_DEVICE_STATE);
        assertThat(info.currentState).isEqualTo(DEFAULT_DEVICE_STATE);
    }

    @FlakyTest(bugId = 297949293)
    @Test
    public void getDeviceStateInfo_baseStateAndCommittedStateNotSet() throws RemoteException {
        // Create a provider and a service without an initial base state.
        mProvider = new TestDeviceStateProvider(null /* initialState */);
        mPolicy = new TestDeviceStatePolicy(mContext, mProvider);
        setupDeviceStateManagerService();
        if (!Flags.wlinfoOncreate()) {
            flushHandler(); // Flush the handler to ensure the initial values are committed.
        }

        final DeviceStateInfo info = mService.getBinderService().getDeviceStateInfo();

        assertThat(info.supportedStates)
                .containsExactlyElementsIn(SUPPORTED_DEVICE_STATES).inOrder();
        assertThat(info.baseState.getIdentifier()).isEqualTo(INVALID_DEVICE_STATE_IDENTIFIER);
        assertThat(info.currentState.getIdentifier()).isEqualTo(INVALID_DEVICE_STATE_IDENTIFIER);
    }

    @Test
    public void halRegisterUnregisterCallback() throws RemoteException {
        IDeviceStateService halService = mService.getHalBinderService();
        IDeviceStateListener halListener = new IDeviceStateListener.Stub() {
            @Override
            public void onDeviceStateChanged(DeviceStateConfiguration deviceState) { }

            @Override
            public int getInterfaceVersion() {
                return IDeviceStateListener.VERSION;
            }

            @Override
            public String getInterfaceHash() {
                return IDeviceStateListener.HASH;
            }
        };

        int errorCode = ErrorCode.OK;
        try {
            halService.unregisterListener(halListener);
        } catch(ServiceSpecificException e) {
            errorCode = e.errorCode;
        }
        assertEquals(errorCode, ErrorCode.BAD_INPUT);

        errorCode = ErrorCode.OK;
        try {
            halService.unregisterListener(null);
        } catch(ServiceSpecificException e) {
            errorCode = e.errorCode;
        }
        assertEquals(errorCode, ErrorCode.BAD_INPUT);

        halService.registerListener(halListener);

        errorCode = ErrorCode.OK;
        try {
            halService.registerListener(halListener);
        } catch (ServiceSpecificException e) {
            errorCode = e.errorCode;
        }
        assertEquals(errorCode, ErrorCode.ALREADY_EXISTS);

        halService.unregisterListener(halListener);
    }

    @Test
    public void registerCallback() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        mProvider.setState(OTHER_DEVICE_STATE_IDENTIFIER);
        if (Flags.wlinfoOncreate()) {
            waitAndAssert(() -> callback.getLastNotifiedInfo() != null);
        }
        waitAndAssert(() -> callback.getLastNotifiedInfo().baseState.getIdentifier()
                == OTHER_DEVICE_STATE_IDENTIFIER);
        waitAndAssert(() -> callback.getLastNotifiedInfo().currentState.getIdentifier()
                == OTHER_DEVICE_STATE_IDENTIFIER);

        mProvider.setState(DEFAULT_DEVICE_STATE_IDENTIFIER);
        waitAndAssert(() -> callback.getLastNotifiedInfo().baseState.getIdentifier()
                == DEFAULT_DEVICE_STATE_IDENTIFIER);
        waitAndAssert(() -> callback.getLastNotifiedInfo().currentState.getIdentifier()
                == DEFAULT_DEVICE_STATE_IDENTIFIER);

        mPolicy.blockConfigure();
        mProvider.setState(OTHER_DEVICE_STATE_IDENTIFIER);
        // The callback should not have been notified of the state change as the policy is still
        // pending callback.
        waitAndAssert(() -> callback.getLastNotifiedInfo().baseState.getIdentifier()
                == DEFAULT_DEVICE_STATE_IDENTIFIER);
        waitAndAssert(() -> callback.getLastNotifiedInfo().currentState.getIdentifier()
                == DEFAULT_DEVICE_STATE_IDENTIFIER);

        mPolicy.resumeConfigure();
        // Now that the policy is finished processing the callback should be notified of the state
        // change.
        waitAndAssert(() -> callback.getLastNotifiedInfo().baseState.getIdentifier()
                == OTHER_DEVICE_STATE_IDENTIFIER);
        waitAndAssert(() -> callback.getLastNotifiedInfo().currentState.getIdentifier()
                == OTHER_DEVICE_STATE_IDENTIFIER);
    }

    @Test
    public void registerCallback_initialValueAvailable_emitsDeviceState() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();

        final DeviceStateInfo stateInfo;
        if (Flags.wlinfoOncreate()) {
            stateInfo = mService.getBinderService().registerCallback(callback);
        } else {
            mService.getBinderService().registerCallback(callback);
            flushHandler();
            stateInfo = callback.getLastNotifiedInfo();
        }

        assertThat(stateInfo).isNotNull();
        assertThat(stateInfo.baseState).isEqualTo(DEFAULT_DEVICE_STATE);
        assertThat(stateInfo.currentState).isEqualTo(DEFAULT_DEVICE_STATE);
    }

    @Test
    public void registerCallback_initialValueUnavailable_nullDeviceState() throws RemoteException {
        // Create a provider and a service without an initial base state.
        mProvider = new TestDeviceStateProvider(null /* initialState */);
        mPolicy = new TestDeviceStatePolicy(mContext, mProvider);
        setupDeviceStateManagerService();
        if (!Flags.wlinfoOncreate()) {
            flushHandler(); // Flush the handler to ensure the initial values are committed.
        }

        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        final DeviceStateInfo stateInfo;
        if (Flags.wlinfoOncreate()) {
            // Return null when the base state is not set yet.
            stateInfo = mService.getBinderService().registerCallback(callback);
        } else {
            mService.getBinderService().registerCallback(callback);
            flushHandler();
            // The callback should never be called when the base state is not set yet.
            stateInfo = callback.getLastNotifiedInfo();
        }

        assertThat(stateInfo).isNull();
    }

    @Test
    public void requestState() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);
        if (!Flags.wlinfoOncreate()) {
            flushHandler();
        }

        final IBinder token = new Binder();
        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_UNKNOWN);

        mService.getBinderService()
                .requestState(token, OTHER_DEVICE_STATE_IDENTIFIER, 0 /* flags */);

        waitAndAssert(() -> callback.getLastNotifiedStatus(token)
                == TestDeviceStateManagerCallback.STATUS_ACTIVE);
        // Committed state changes as there is a requested override.
        assertThat(mService.getCommittedState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mSysPropSetter.getValue()).isEqualTo(OTHER_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getOverrideState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(OTHER_DEVICE_STATE_IDENTIFIER);

        assertThat(callback.getLastNotifiedInfo()).isNotNull();
        assertThat(callback.getLastNotifiedInfo().baseState).isEqualTo(DEFAULT_DEVICE_STATE);
        assertThat(callback.getLastNotifiedInfo().currentState).isEqualTo(OTHER_DEVICE_STATE);

        mService.getBinderService().cancelStateRequest();

        waitAndAssert(() -> callback.getLastNotifiedStatus(token)
                == TestDeviceStateManagerCallback.STATUS_CANCELED);
        // Committed state is set back to the requested state once the override is cleared.
        waitAndAssert(() -> mService.getCommittedState().equals(Optional.of(DEFAULT_DEVICE_STATE)));
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getOverrideState()).isEmpty();
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(DEFAULT_DEVICE_STATE_IDENTIFIER);

        assertThat(callback.getLastNotifiedInfo().baseState).isEqualTo(DEFAULT_DEVICE_STATE);
        assertThat(callback.getLastNotifiedInfo().currentState).isEqualTo(DEFAULT_DEVICE_STATE);
    }

    @FlakyTest(bugId = 200332057)
    @Test
    public void requestState_pendingStateAtRequest() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);
        if (!Flags.wlinfoOncreate()) {
            flushHandler();
        }

        mPolicy.blockConfigure();

        final IBinder firstRequestToken = new Binder();
        final IBinder secondRequestToken = new Binder();
        assertThat(callback.getLastNotifiedStatus(firstRequestToken))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_UNKNOWN);
        assertThat(callback.getLastNotifiedStatus(secondRequestToken))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_UNKNOWN);

        mService.getBinderService()
                .requestState(firstRequestToken, OTHER_DEVICE_STATE_IDENTIFIER, 0 /* flags */);
        // Flush the handler twice. The first flush ensures the request is added and the policy is
        // notified, while the second flush ensures the callback is notified once the change is
        // committed.
        flushHandler(2 /* count */);

        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(OTHER_DEVICE_STATE_IDENTIFIER);

        mService.getBinderService()
                .requestState(secondRequestToken, DEFAULT_DEVICE_STATE_IDENTIFIER, 0 /* flags */);
        mPolicy.resumeConfigureOnce();
        flushHandler();

        // First request status is now canceled as there is another pending request.
        assertThat(callback.getLastNotifiedStatus(firstRequestToken))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_CANCELED);
        // Second request status still unknown because the service is still awaiting policy
        // callback.
        assertThat(callback.getLastNotifiedStatus(secondRequestToken))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_UNKNOWN);
        assertThat(mService.getCommittedState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mService.getPendingState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mSysPropSetter.getValue()).isEqualTo(OTHER_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(DEFAULT_DEVICE_STATE_IDENTIFIER);

        mPolicy.resumeConfigure();
        flushHandler();

        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).isEmpty();
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(DEFAULT_DEVICE_STATE_IDENTIFIER);

        // Now cancel the second request to make the first request active.
        mService.getBinderService().cancelStateRequest();
        flushHandler();

        assertThat(callback.getLastNotifiedStatus(firstRequestToken))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_CANCELED);
        assertThat(callback.getLastNotifiedStatus(secondRequestToken))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_CANCELED);

        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getPendingState()).isEmpty();
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(DEFAULT_DEVICE_STATE_IDENTIFIER);
    }

    @Test
    public void requestState_sameAsBaseState() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);
        if (!Flags.wlinfoOncreate()) {
            flushHandler();
        }

        final IBinder token = new Binder();
        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_UNKNOWN);

        mService.getBinderService()
                .requestState(token, DEFAULT_DEVICE_STATE_IDENTIFIER, 0 /* flags */);
        flushHandler();

        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_ACTIVE);
    }

    @Test
    public void requestState_flagCancelWhenBaseChanges() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);
        if (!Flags.wlinfoOncreate()) {
            flushHandler();
        }

        final IBinder token = new Binder();
        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_UNKNOWN);

        mService.getBinderService().requestState(token, OTHER_DEVICE_STATE_IDENTIFIER,
                DeviceStateRequest.FLAG_CANCEL_WHEN_BASE_CHANGES);
        // Flush the handler twice. The first flush ensures the request is added and the policy is
        // notified, while the second flush ensures the callback is notified once the change is
        // committed.
        flushHandler(2 /* count */);

        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_ACTIVE);

        // Committed state changes as there is a requested override.
        assertThat(mService.getCommittedState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mSysPropSetter.getValue()).isEqualTo(OTHER_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getOverrideState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(OTHER_DEVICE_STATE_IDENTIFIER);

        mProvider.setState(OTHER_DEVICE_STATE_IDENTIFIER);
        flushHandler();

        // Request is canceled because the base state changed.
        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_CANCELED);
        // Committed state is set back to the requested state once the override is cleared.
        assertThat(mService.getCommittedState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mService.getBaseState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mSysPropSetter.getValue()).isEqualTo(OTHER_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getOverrideState()).isEmpty();
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(OTHER_DEVICE_STATE_IDENTIFIER);
    }

    @Test
    public void requestState_flagCancelWhenRequesterNotOnTop_onDeviceSleep()
            throws RemoteException {
        requestState_flagCancelWhenRequesterNotOnTop_common(
                // When the device is awake, the state should not change
                () -> mService.mOverrideRequestScreenObserver.onAwakeStateChanged(true),
                // When the device is in sleep mode, the state should be canceled
                () -> mService.mOverrideRequestScreenObserver.onAwakeStateChanged(false)
        );
    }

    @Test
    public void requestState_flagCancelWhenRequesterNotOnTop_onKeyguardShow()
            throws RemoteException {
        requestState_flagCancelWhenRequesterNotOnTop_common(
                // When the keyguard is not showing, the state should not change
                () -> mService.mOverrideRequestScreenObserver.onKeyguardStateChanged(false),
                // When the keyguard is showing, the state should be canceled
                () -> mService.mOverrideRequestScreenObserver.onKeyguardStateChanged(true)
        );
    }

    @Test
    public void requestState_flagCancelWhenRequesterNotOnTop_onTaskMovedToFront()
            throws RemoteException {
        requestState_flagCancelWhenRequesterNotOnTop_common(
                // When the app is foreground, the state should not change
                () -> {
                    final int pid = Binder.getCallingPid();
                    final int uid = Binder.getCallingUid();
                    try {
                        mService.mProcessObserver.onForegroundActivitiesChanged(pid, uid,
                                true /* foregroundActivities */);
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                },
                // When the app is not foreground, the state should change
                () -> {
                    when(mWindowProcessController.getPid()).thenReturn(FAKE_PROCESS_ID);
                    try {
                        final int pid = Binder.getCallingPid();
                        final int uid = Binder.getCallingUid();
                        mService.mProcessObserver.onForegroundActivitiesChanged(pid, uid,
                                false /* foregroundActivities */);

                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    @FlakyTest(bugId = 200332057)
    @Test
    public void requestState_becomesUnsupported() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);
        if (!Flags.wlinfoOncreate()) {
            flushHandler();
        }

        final IBinder token = new Binder();
        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_UNKNOWN);

        mService.getBinderService()
                .requestState(token, OTHER_DEVICE_STATE_IDENTIFIER, 0 /* flags */);
        flushHandler();

        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_ACTIVE);
        // Committed state changes as there is a requested override.
        assertThat(mService.getCommittedState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mSysPropSetter.getValue()).isEqualTo(OTHER_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getOverrideState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(OTHER_DEVICE_STATE_IDENTIFIER);

        mProvider.notifySupportedDeviceStates(
                new DeviceState[]{DEFAULT_DEVICE_STATE});
        flushHandler();

        // Request is canceled because the state is no longer supported.
        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_CANCELED);
        // Committed state is set back to the requested state as the override state is no longer
        // supported.
        assertThat(mService.getCommittedState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getOverrideState()).isEmpty();
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(DEFAULT_DEVICE_STATE_IDENTIFIER);
    }

    @Test
    public void requestState_unsupportedState() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        assertThrows(IllegalArgumentException.class, () -> {
            final IBinder token = new Binder();
            mService.getBinderService()
                    .requestState(token, UNSUPPORTED_DEVICE_STATE.getIdentifier(), 0 /* flags */);
        });
    }

    @Test
    public void requestState_invalidState() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        assertThrows(IllegalArgumentException.class, () -> {
            final IBinder token = new Binder();
            mService.getBinderService()
                    .requestState(token, INVALID_DEVICE_STATE_IDENTIFIER, 0 /* flags */);
        });
    }

    @Test
    public void requestState_beforeRegisteringCallback() {
        assertThrows(IllegalStateException.class, () -> {
            final IBinder token = new Binder();
            mService.getBinderService()
                    .requestState(token, DEFAULT_DEVICE_STATE_IDENTIFIER, 0 /* flags */);
        });
    }

    @Test
    public void requestBaseStateOverride() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);
        if (!Flags.wlinfoOncreate()) {
            flushHandler();
        }

        final IBinder token = new Binder();
        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_UNKNOWN);

        mService.getBinderService().requestBaseStateOverride(token,
                OTHER_DEVICE_STATE_IDENTIFIER,
                0 /* flags */);

        waitAndAssert(() -> callback.getLastNotifiedStatus(token)
                == TestDeviceStateManagerCallback.STATUS_ACTIVE);
        // Committed state changes as there is a requested override.
        assertThat(mService.getCommittedState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mSysPropSetter.getValue()).isEqualTo(OTHER_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mService.getOverrideBaseState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mService.getOverrideState()).isEmpty();
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(OTHER_DEVICE_STATE_IDENTIFIER);

        assertThat(callback.getLastNotifiedInfo()).isNotNull();
        assertThat(callback.getLastNotifiedInfo().baseState).isEqualTo(OTHER_DEVICE_STATE);
        assertThat(callback.getLastNotifiedInfo().currentState).isEqualTo(OTHER_DEVICE_STATE);

        mService.getBinderService().cancelBaseStateOverride();

        waitAndAssert(() -> callback.getLastNotifiedStatus(token)
                == TestDeviceStateManagerCallback.STATUS_CANCELED);
        // Committed state is set back to the requested state once the override is cleared.
        waitAndAssert(() -> mService.getCommittedState().equals(Optional.of(DEFAULT_DEVICE_STATE)));
        assertThat(mSysPropSetter.getValue()).isEqualTo(DEFAULT_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(DEFAULT_DEVICE_STATE);
        assertThat(mService.getOverrideBaseState()).isEmpty();
        assertThat(mService.getOverrideState()).isEmpty();
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(DEFAULT_DEVICE_STATE_IDENTIFIER);

        waitAndAssert(() -> callback.getLastNotifiedInfo().baseState.getIdentifier()
                == DEFAULT_DEVICE_STATE_IDENTIFIER);
        assertThat(callback.getLastNotifiedInfo().currentState).isEqualTo(DEFAULT_DEVICE_STATE);
    }

    @Test
    public void requestBaseStateOverride_cancelledByBaseStateUpdate() throws RemoteException {
        final DeviceState testDeviceState = new DeviceState(
                new DeviceState.Configuration.Builder(2, "TEST").build());
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);
        mProvider.notifySupportedDeviceStates(new DeviceState[]{DEFAULT_DEVICE_STATE,
                OTHER_DEVICE_STATE, testDeviceState});
        flushHandler();

        final IBinder token = new Binder();
        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_UNKNOWN);

        mService.getBinderService().requestBaseStateOverride(token,
                OTHER_DEVICE_STATE_IDENTIFIER,
                0 /* flags */);

        waitAndAssert(() -> callback.getLastNotifiedStatus(token)
                == TestDeviceStateManagerCallback.STATUS_ACTIVE);
        // Committed state changes as there is a requested override.
        assertThat(mService.getCommittedState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mSysPropSetter.getValue()).isEqualTo(OTHER_DEVICE_STATE_TRACE_STRING);
        assertThat(mService.getBaseState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mService.getOverrideBaseState()).hasValue(OTHER_DEVICE_STATE);
        assertThat(mService.getOverrideState()).isEmpty();
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(OTHER_DEVICE_STATE_IDENTIFIER);

        assertThat(callback.getLastNotifiedInfo()).isNotNull();
        assertThat(callback.getLastNotifiedInfo().baseState).isEqualTo(OTHER_DEVICE_STATE);
        assertThat(callback.getLastNotifiedInfo().currentState).isEqualTo(OTHER_DEVICE_STATE);

        mProvider.setState(testDeviceState.getIdentifier());

        waitAndAssert(() -> callback.getLastNotifiedStatus(token)
                == TestDeviceStateManagerCallback.STATUS_CANCELED);
        // Committed state is set to the new base state once the override is cleared.
        waitAndAssert(() -> mService.getCommittedState().equals(Optional.of(testDeviceState)));
        assertThat(mSysPropSetter.getValue())
                .isEqualTo(testDeviceState.getIdentifier() + ":" + testDeviceState.getName());
        assertThat(mService.getBaseState()).hasValue(testDeviceState);
        assertThat(mService.getOverrideBaseState()).isEmpty();
        assertThat(mService.getOverrideState()).isEmpty();
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(testDeviceState.getIdentifier());

        waitAndAssert(() -> callback.getLastNotifiedInfo().baseState.getIdentifier()
                == testDeviceState.getIdentifier());
        assertThat(callback.getLastNotifiedInfo().currentState).isEqualTo(testDeviceState);
    }

    @Test
    public void requestBaseState_unsupportedState() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        assertThrows(IllegalArgumentException.class, () -> {
            final IBinder token = new Binder();
            mService.getBinderService().requestBaseStateOverride(token,
                    UNSUPPORTED_DEVICE_STATE.getIdentifier(), 0 /* flags */);
        });
    }

    @Test
    public void requestBaseState_invalidState() throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        assertThrows(IllegalArgumentException.class, () -> {
            final IBinder token = new Binder();
            mService.getBinderService().requestBaseStateOverride(token,
                    INVALID_DEVICE_STATE_IDENTIFIER, 0 /* flags */);
        });
    }

    @Test
    public void requestBaseState_beforeRegisteringCallback() {
        assertThrows(IllegalStateException.class, () -> {
            final IBinder token = new Binder();
            mService.getBinderService().requestBaseStateOverride(token,
                    DEFAULT_DEVICE_STATE_IDENTIFIER,
                    0 /* flags */);
        });
    }

    @Test
    public void shouldShowRdmEduDialog1() {
        // RDM V1 Cases
        assertTrue(DeviceStateManagerService.shouldShowRdmEduDialog(
                false /* hasControlDeviceStatePermission */,
                false /* requestingRdmOuterDefault */,
                false /* isDeviceClosed (no-op) */));

        assertFalse(DeviceStateManagerService.shouldShowRdmEduDialog(
                true /* hasControlDeviceStatePermission */,
                false /* requestingRdmOuterDefault */,
                true /* isDeviceClosed (no-op) */));

        // RDM V2 Cases
        // hasControlDeviceStatePermission = false
        assertFalse(DeviceStateManagerService.shouldShowRdmEduDialog(
                false /* hasControlDeviceStatePermission */,
                true /* requestingRdmOuterDefault */,
                false /* isDeviceClosed */));
        assertTrue(DeviceStateManagerService.shouldShowRdmEduDialog(
                false /* hasControlDeviceStatePermission */,
                true /* requestingRdmOuterDefault */,
                true /* isDeviceClosed */));

        // hasControlDeviceStatePermission = true
        assertFalse(DeviceStateManagerService.shouldShowRdmEduDialog(
                true /* hasControlDeviceStatePermission */,
                true /* requestingRdmOuterDefault */,
                false /* isDeviceClosed */));
        assertFalse(DeviceStateManagerService.shouldShowRdmEduDialog(
                true /* hasControlDeviceStatePermission */,
                true /* requestingRdmOuterDefault */,
                true /* isDeviceClosed */));
    }

    /**
     * Common code to verify the handling of FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP flag.
     *
     * The device state with FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP should be automatically canceled
     * when certain events happen, e.g. when the top activity belongs to another app or when the
     * device goes into the sleep mode.
     *
     * @param noChangeEvent an event that should not trigger auto cancellation of the state.
     * @param autoCancelEvent an event that should trigger auto cancellation of the state.
     * @throws RemoteException when the service throws exceptions.
     */
    private void requestState_flagCancelWhenRequesterNotOnTop_common(
            Runnable noChangeEvent,
            Runnable autoCancelEvent
    ) throws RemoteException {
        final TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);
        if (!Flags.wlinfoOncreate()) {
            flushHandler();
        }

        final IBinder token = new Binder();
        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_UNKNOWN);

        mService.getBinderService().requestState(token,
                DEVICE_STATE_CANCEL_WHEN_REQUESTER_NOT_ON_TOP.getIdentifier(),
                0 /* flags */);
        flushHandler(2 /* count */);

        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_ACTIVE);

        // Committed state changes as there is a requested override.
        assertDeviceStateConditions(
                DEVICE_STATE_CANCEL_WHEN_REQUESTER_NOT_ON_TOP,
                DEFAULT_DEVICE_STATE, /* base state */
                true /* isOverrideState */);

        noChangeEvent.run();
        flushHandler();
        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_ACTIVE);
        assertDeviceStateConditions(
                DEVICE_STATE_CANCEL_WHEN_REQUESTER_NOT_ON_TOP,
                DEFAULT_DEVICE_STATE, /* base state */
                true /* isOverrideState */);

        autoCancelEvent.run();
        flushHandler();
        assertThat(callback.getLastNotifiedStatus(token))
                .isEqualTo(TestDeviceStateManagerCallback.STATUS_CANCELED);
        assertDeviceStateConditions(DEFAULT_DEVICE_STATE, DEFAULT_DEVICE_STATE,
                false /* isOverrideState */);
    }

    /**
     * Verify that the current device state and base state match the expected values.
     *
     * @param state the expected committed state.
     * @param baseState the expected base state.
     * @param isOverrideState whether a state override is active.
     */
    private void assertDeviceStateConditions(
            @NonNull DeviceState state, @NonNull DeviceState baseState,
            boolean isOverrideState) {
        assertThat(mService.getCommittedState()).hasValue(state);
        assertThat(mService.getBaseState()).hasValue(baseState);
        assertThat(mSysPropSetter.getValue())
                .isEqualTo(state.getIdentifier() + ":" + state.getName());
        assertThat(mPolicy.getMostRecentRequestedStateToConfigure())
                .isEqualTo(state.getIdentifier());
        if (isOverrideState) {
            // When a state override is active, the committed state should batch the override state.
            assertThat(mService.getOverrideState()).hasValue(state);
        } else {
            // When there is no state override, the override state should be empty.
            assertThat(mService.getOverrideState()).isEmpty();
        }
    }

    private static final class TestDeviceStatePolicy extends DeviceStatePolicy {
        private final DeviceStateProvider mProvider;
        private int mLastDeviceStateRequestedToConfigure = INVALID_DEVICE_STATE_IDENTIFIER;
        private boolean mConfigureBlocked = false;
        @Nullable
        private Runnable mPendingConfigureCompleteRunnable;

        TestDeviceStatePolicy(@NonNull Context context, @NonNull DeviceStateProvider provider) {
            super(context);
            mProvider = provider;
        }

        @Override
        public DeviceStateProvider getDeviceStateProvider() {
            return mProvider;
        }

        public void blockConfigure() {
            mConfigureBlocked = true;
        }

        public void resumeConfigure() {
            mConfigureBlocked = false;
            if (mPendingConfigureCompleteRunnable != null) {
                final Runnable onComplete = mPendingConfigureCompleteRunnable;
                mPendingConfigureCompleteRunnable = null;
                onComplete.run();
            }
        }

        public void resumeConfigureOnce() {
            if (mPendingConfigureCompleteRunnable != null) {
                final Runnable onComplete = mPendingConfigureCompleteRunnable;
                mPendingConfigureCompleteRunnable = null;
                onComplete.run();
            }
        }

        public int getMostRecentRequestedStateToConfigure() {
            return mLastDeviceStateRequestedToConfigure;
        }

        @Override
        public void configureDeviceForState(int state, @NonNull Runnable onComplete) {
            if (mPendingConfigureCompleteRunnable != null) {
                throw new IllegalStateException("configureDeviceForState() called while configure"
                        + " is pending");
            }

            mLastDeviceStateRequestedToConfigure = state;
            if (mConfigureBlocked) {
                mPendingConfigureCompleteRunnable = onComplete;
                return;
            }
            onComplete.run();
        }

        @Override
        public void dump(@NonNull PrintWriter writer, @Nullable String[] args) {
        }
    }

    private static final class TestDeviceStateProvider implements DeviceStateProvider {
        private DeviceState[] mSupportedDeviceStates =
                new DeviceState[]{
                        DEFAULT_DEVICE_STATE,
                        OTHER_DEVICE_STATE,
                        DEVICE_STATE_CANCEL_WHEN_REQUESTER_NOT_ON_TOP};

        @Nullable
        private final DeviceState mInitialState;
        @Nullable
        private Listener mListener;

        private TestDeviceStateProvider() {
            this(DEFAULT_DEVICE_STATE);
        }

        private TestDeviceStateProvider(@Nullable DeviceState initialState) {
            mInitialState = initialState;
        }

        @Override
        public void setListener(Listener listener) {
            if (mListener != null) {
                throw new IllegalArgumentException("Provider already has listener set.");
            }

            mListener = listener;
            mListener.onSupportedDeviceStatesChanged(mSupportedDeviceStates,
                    SUPPORTED_DEVICE_STATES_CHANGED_INITIALIZED);
            if (mInitialState != null) {
                mListener.onStateChanged(mInitialState.getIdentifier());
            }
        }

        public void notifySupportedDeviceStates(@NonNull DeviceState[] supportedDeviceStates) {
            mSupportedDeviceStates = supportedDeviceStates;
            mListener.onSupportedDeviceStatesChanged(supportedDeviceStates,
                    SUPPORTED_DEVICE_STATES_CHANGED_INITIALIZED);
        }

        public void setState(int identifier) {
            mListener.onStateChanged(identifier);
        }

        @Override
        public void dump(@NonNull PrintWriter writer, @Nullable String[] args) {
        }
    }

    private static final class TestDeviceStateManagerCallback extends
            IDeviceStateManagerCallback.Stub {
        public static final int STATUS_UNKNOWN = 0;
        public static final int STATUS_ACTIVE = 1;
        public static final int STATUS_SUSPENDED = 2;
        public static final int STATUS_CANCELED = 3;

        @Nullable
        private DeviceStateInfo mLastNotifiedInfo;
        private final HashMap<IBinder, Integer> mLastNotifiedStatus = new HashMap<>();

        @Override
        public void onDeviceStateInfoChanged(@NonNull DeviceStateInfo info) {
            mLastNotifiedInfo = info;
        }

        @Override
        public void onRequestActive(@NonNull IBinder token) {
            mLastNotifiedStatus.put(token, STATUS_ACTIVE);
        }

        @Override
        public void onRequestCanceled(@NonNull IBinder token) {
            mLastNotifiedStatus.put(token, STATUS_CANCELED);
        }

        @Nullable
        DeviceStateInfo getLastNotifiedInfo() {
            return mLastNotifiedInfo;
        }

        void clearLastNotifiedInfo() {
            mLastNotifiedInfo = null;
        }

        int getLastNotifiedStatus(@NonNull IBinder requestToken) {
            return mLastNotifiedStatus.getOrDefault(requestToken, STATUS_UNKNOWN);
        }
    }

    private static final class TestSystemPropertySetter implements
            DeviceStateManagerService.SystemPropertySetter {
        @NonNull
        private String mValue;

        @Override
        public void setDebugTracingDeviceStateProperty(@NonNull String value) {
            mValue = value;
        }

        @NonNull
        public String getValue() {
            return mValue;
        }
    }
}
