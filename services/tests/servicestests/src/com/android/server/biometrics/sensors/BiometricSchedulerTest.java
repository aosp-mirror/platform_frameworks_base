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

package com.android.server.biometrics.sensors;

import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_ERROR_CANCELED;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_SUCCESS;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.AuthenticateOptions;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.fingerprint.Fingerprint;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.util.Slog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.Flags;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.nano.BiometricSchedulerProto;
import com.android.server.biometrics.nano.BiometricsProto;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.aidl.AidlSession;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Presubmit
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BiometricSchedulerTest {

    private static final String TAG = "BiometricSchedulerTest";
    private static final int TEST_SENSOR_ID = 1;
    private static final int LOG_NUM_RECENT_OPERATIONS = 2;
    private static final Fingerprint TEST_FINGERPRINT = new Fingerprint("" /* name */,
            1 /* fingerId */, TEST_SENSOR_ID);

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getContext(), null);
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();
    private BiometricScheduler<IFingerprint, ISession> mScheduler;
    private IBinder mToken;
    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    private boolean mShouldFailStopUser = false;
    private final List<Integer> mStartedUsers = new ArrayList<>();
    private final StartUserClient.UserStartedCallback<ISession> mUserStartedCallback =
            (newUserId, newUser, halInterfaceVersion) -> {
                mStartedUsers.add(newUserId);
                mCurrentUserId = newUserId;
            };
    private int mUsersStoppedCount = 0;
    private final StopUserClient.UserStoppedCallback mUserStoppedCallback =
            () -> {
                mUsersStoppedCount++;
                mCurrentUserId = UserHandle.USER_NULL;
            };
    private boolean mStartOperationsFinish = true;
    private int mStartUserClientCount = 0;
    @Mock
    private IBiometricService mBiometricService;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private ISession mSession;
    @Mock
    private IFingerprint mFingerprint;
    @Mock
    private ClientMonitorCallbackConverter mListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mToken = new Binder();
        when(mAuthSessionCoordinator.getLockoutStateFor(anyInt(), anyInt())).thenReturn(
                BIOMETRIC_SUCCESS);
        when(mBiometricContext.getAuthSessionCoordinator()).thenReturn(mAuthSessionCoordinator);
        if (Flags.deHidl()) {
            mScheduler = new BiometricScheduler<>(
                    new Handler(TestableLooper.get(this).getLooper()),
                    BiometricScheduler.SENSOR_TYPE_UNKNOWN,
                    null /* gestureAvailabilityDispatcher */,
                    mBiometricService,
                    LOG_NUM_RECENT_OPERATIONS,
                    () -> mCurrentUserId,
                    new UserSwitchProvider<IFingerprint, ISession>() {
                        @NonNull
                        @Override
                        public StopUserClient<ISession> getStopUserClient(int userId) {
                            return new TestStopUserClient(mContext, () -> mSession, mToken, userId,
                                    TEST_SENSOR_ID, mBiometricLogger, mBiometricContext,
                                    mUserStoppedCallback, () -> mShouldFailStopUser);
                        }

                        @NonNull
                        @Override
                        public StartUserClient<IFingerprint, ISession> getStartUserClient(
                                int newUserId) {
                            mStartUserClientCount++;
                            return new TestStartUserClient(mContext, () -> mFingerprint, mToken,
                                    newUserId, TEST_SENSOR_ID, mBiometricLogger, mBiometricContext,
                                    mUserStartedCallback, mStartOperationsFinish);
                        }
                    });
        } else {
            mScheduler = new BiometricScheduler<>(
                    new Handler(TestableLooper.get(this).getLooper()),
                    BiometricScheduler.SENSOR_TYPE_UNKNOWN, null /* gestureAvailabilityTracker */,
                    mBiometricService, LOG_NUM_RECENT_OPERATIONS);
        }
    }

    @Test
    public void testClientDuplicateFinish_ignoredBySchedulerAndDoesNotCrash() {
        final Supplier<Object> nonNullDaemon = () -> mock(Object.class);

        final HalClientMonitor<Object> client1 = new TestHalClientMonitor(mContext, mToken,
                nonNullDaemon);
        final HalClientMonitor<Object> client2 = new TestHalClientMonitor(mContext, mToken,
                nonNullDaemon);
        mScheduler.scheduleClientMonitor(client1);
        mScheduler.scheduleClientMonitor(client2);

        client1.mCallback.onClientFinished(client1, true /* success */);
        client1.mCallback.onClientFinished(client1, true /* success */);
    }

    @Test
    public void testRemovesPendingOperations_whenNullHal_andNotBiometricPrompt() {
        // Even if second client has a non-null daemon, it needs to be canceled.
        final TestHalClientMonitor client1 = new TestHalClientMonitor(mContext, mToken, () -> null);
        final TestHalClientMonitor client2 = new TestHalClientMonitor(mContext, mToken,
                () -> mock(Object.class));

        final ClientMonitorCallback callback1 = mock(ClientMonitorCallback.class);
        final ClientMonitorCallback callback2 = mock(ClientMonitorCallback.class);

        // Pretend the scheduler is busy so the first operation doesn't start right away. We want
        // to pretend like there are two operations in the queue before kicking things off
        mScheduler.mCurrentOperation = new BiometricSchedulerOperation(
                createBaseClientMonitor(), mock(ClientMonitorCallback.class));

        mScheduler.scheduleClientMonitor(client1, callback1);
        assertEquals(1, mScheduler.mPendingOperations.size());
        // client1 is pending. Allow the scheduler to start once second client is added.
        mScheduler.mCurrentOperation = null;
        mScheduler.scheduleClientMonitor(client2, callback2);
        waitForIdle();

        assertTrue(client1.mUnableToStart);
        verify(callback1).onClientFinished(eq(client1), eq(false) /* success */);
        verify(callback1, never()).onClientStarted(any());

        assertTrue(client2.mUnableToStart);
        verify(callback2).onClientFinished(eq(client2), eq(false) /* success */);
        verify(callback2, never()).onClientStarted(any());

        assertTrue(mScheduler.mPendingOperations.isEmpty());
    }

    @Test
    public void testRemovesOnlyBiometricPromptOperation_whenNullHal() throws Exception {
        // Second non-BiometricPrompt client has a valid daemon
        final Object daemon2 = mock(Object.class);

        final ClientMonitorCallbackConverter listener1 = mock(ClientMonitorCallbackConverter.class);

        final TestAuthenticationClient client1 = new TestAuthenticationClient(mContext, () -> null,
                mToken, listener1, mBiometricContext);
        final TestHalClientMonitor client2 = new TestHalClientMonitor(mContext, mToken,
                () -> daemon2);

        final ClientMonitorCallback callback1 = mock(ClientMonitorCallback.class);
        final ClientMonitorCallback callback2 = mock(ClientMonitorCallback.class);

        // Pretend the scheduler is busy so the first operation doesn't start right away. We want
        // to pretend like there are two operations in the queue before kicking things off
        mScheduler.mCurrentOperation = new BiometricSchedulerOperation(
                createBaseClientMonitor(), mock(ClientMonitorCallback.class));

        mScheduler.scheduleClientMonitor(client1, callback1);
        assertEquals(1, mScheduler.mPendingOperations.size());
        // client1 is pending. Allow the scheduler to start once second client is added.
        mScheduler.mCurrentOperation = null;
        mScheduler.scheduleClientMonitor(client2, callback2);
        waitForIdle();

        // Simulate that the BiometricPrompt client's sensor is ready
        mScheduler.startPreparedClient(client1.getCookie());

        // Client 1 cleans up properly
        verify(listener1).onError(eq(TEST_SENSOR_ID), anyInt(),
                eq(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE), eq(0));
        verify(callback1).onClientFinished(eq(client1), eq(false) /* success */);
        verify(callback1, never()).onClientStarted(any());

        // Client 2 was able to start
        assertFalse(client2.mUnableToStart);
        assertTrue(client2.mStarted);
        verify(callback2).onClientStarted(eq(client2));
    }

    @Test
    public void testCancelNotInvoked_whenOperationWaitingForCookie() {
        final Supplier<Object> lazyDaemon1 = () -> mock(Object.class);
        final TestAuthenticationClient client1 = new TestAuthenticationClient(mContext, lazyDaemon1,
                mToken, mock(ClientMonitorCallbackConverter.class), mBiometricContext);
        final ClientMonitorCallback callback1 = mock(ClientMonitorCallback.class);

        // Schedule a BiometricPrompt authentication request
        mScheduler.scheduleClientMonitor(client1, callback1);

        assertNotEquals(0,
                mScheduler.mCurrentOperation.isReadyToStart(mock(ClientMonitorCallback.class)));
        assertEquals(client1, mScheduler.mCurrentOperation.getClientMonitor());
        assertEquals(0, mScheduler.mPendingOperations.size());

        // Request it to be canceled. The operation can be canceled immediately, and the scheduler
        // should go back to idle, since in this case the framework has not even requested the HAL
        // to authenticate yet.
        mScheduler.cancelAuthenticationOrDetection(mToken, 1 /* requestId */);
        waitForIdle();
        assertTrue(client1.isAlreadyDone());
        assertTrue(client1.mDestroyed);
        assertFalse(client1.mStartedHal);
        assertNull(mScheduler.mCurrentOperation);
    }

    @Test
    public void testProtoDump_singleCurrentOperation() throws Exception {
        // Nothing so far
        BiometricSchedulerProto bsp = getDump(true /* clearSchedulerBuffer */);
        assertEquals(BiometricsProto.CM_NONE, bsp.currentOperation);
        assertEquals(0, bsp.totalOperations);
        // TODO:(b/178828362) See bug and/or commit message :/
        // assertEquals(0, bsp.recentOperations.length);

        // Pretend the scheduler is busy enrolling, and check the proto dump again.
        final TestHalClientMonitor client = new TestHalClientMonitor(mContext, mToken,
                () -> mock(Object.class), 0, BiometricsProto.CM_ENROLL);
        mScheduler.scheduleClientMonitor(client);
        waitForIdle();
        bsp = getDump(true /* clearSchedulerBuffer */);
        assertEquals(BiometricsProto.CM_ENROLL, bsp.currentOperation);
        // No operations have completed yet
        assertEquals(0, bsp.totalOperations);

        // TODO:(b/178828362) See bug and/or commit message :/
        assertEquals(1, bsp.recentOperations.length);
        assertEquals(BiometricsProto.CM_NONE, bsp.recentOperations[0]);

        // Finish this operation, so the next scheduled one can start
        client.getCallback().onClientFinished(client, true);
    }

    @Test
    public void testProtoDump_fifo() throws Exception {
        // Add the first operation
        final TestHalClientMonitor client = new TestHalClientMonitor(mContext, mToken,
                () -> mock(Object.class), 0, BiometricsProto.CM_ENROLL);
        mScheduler.scheduleClientMonitor(client);
        waitForIdle();
        BiometricSchedulerProto bsp = getDump(false /* clearSchedulerBuffer */);
        assertEquals(BiometricsProto.CM_ENROLL, bsp.currentOperation);
        // No operations have completed yet
        assertEquals(0, bsp.totalOperations);
        // TODO:(b/178828362) See bug and/or commit message :/
        // assertEquals(0, bsp.recentOperations.length);
        // Finish this operation, so the next scheduled one can start
        client.getCallback().onClientFinished(client, true);

        // Add another operation
        final TestHalClientMonitor client2 = new TestHalClientMonitor(mContext, mToken,
                () -> mock(Object.class), 0, BiometricsProto.CM_REMOVE);
        mScheduler.scheduleClientMonitor(client2);
        waitForIdle();
        bsp = getDump(false /* clearSchedulerBuffer */);
        assertEquals(BiometricsProto.CM_REMOVE, bsp.currentOperation);
        assertEquals(1, bsp.totalOperations); // Enroll finished
        assertEquals(1, bsp.recentOperations.length);
        assertEquals(BiometricsProto.CM_ENROLL, bsp.recentOperations[0]);
        client2.getCallback().onClientFinished(client2, true);

        // And another operation
        final TestHalClientMonitor client3 = new TestHalClientMonitor(mContext, mToken,
                () -> mock(Object.class), 0, BiometricsProto.CM_AUTHENTICATE);
        mScheduler.scheduleClientMonitor(client3);
        waitForIdle();
        bsp = getDump(false /* clearSchedulerBuffer */);
        assertEquals(BiometricsProto.CM_AUTHENTICATE, bsp.currentOperation);
        assertEquals(2, bsp.totalOperations);
        assertEquals(2, bsp.recentOperations.length);
        assertEquals(BiometricsProto.CM_ENROLL, bsp.recentOperations[0]);
        assertEquals(BiometricsProto.CM_REMOVE, bsp.recentOperations[1]);

        // Finish the last operation, and check that the first operation is removed from the FIFO.
        // The test initializes the scheduler with "LOG_NUM_RECENT_OPERATIONS = 2" :)
        client3.getCallback().onClientFinished(client3, true);
        waitForIdle();
        bsp = getDump(true /* clearSchedulerBuffer */);
        assertEquals(3, bsp.totalOperations);
        assertEquals(2, bsp.recentOperations.length);
        assertEquals(BiometricsProto.CM_REMOVE, bsp.recentOperations[0]);
        assertEquals(BiometricsProto.CM_AUTHENTICATE, bsp.recentOperations[1]);
        // Nothing is currently running anymore
        assertEquals(BiometricsProto.CM_NONE, bsp.currentOperation);

        // RecentOperations queue is cleared (by the previous dump)
        bsp = getDump(true /* clearSchedulerBuffer */);

        // TODO:(b/178828362) See bug and/or commit message :/
        assertEquals(1, bsp.recentOperations.length);
        assertEquals(BiometricsProto.CM_NONE, bsp.recentOperations[0]);
    }

    @Test
    public void testCancelPendingAuth() throws RemoteException {
        final Supplier<Object> lazyDaemon = () -> mock(Object.class);
        final TestHalClientMonitor client1 = new TestHalClientMonitor(mContext, mToken, lazyDaemon);
        final ClientMonitorCallbackConverter callback = mock(ClientMonitorCallbackConverter.class);
        final TestAuthenticationClient client2 = new TestAuthenticationClient(mContext, lazyDaemon,
                mToken, callback, mBiometricContext);

        // Add a non-cancellable client, then add the auth client
        mScheduler.scheduleClientMonitor(client1);
        mScheduler.scheduleClientMonitor(client2);
        waitForIdle();

        assertEquals(mScheduler.getCurrentClient(), client1);
        assertFalse(mScheduler.mPendingOperations.getFirst().isStarted());

        // Request cancel before the authentication client has started
        mScheduler.cancelAuthenticationOrDetection(mToken, 1 /* requestId */);
        waitForIdle();
        assertTrue(mScheduler.mPendingOperations.getFirst().isMarkedCanceling());

        // Finish the blocking client. The authentication client should send ERROR_CANCELED
        client1.getCallback().onClientFinished(client1, true /* success */);
        waitForIdle();
        verify(callback).onError(anyInt(), anyInt(),
                eq(BIOMETRIC_ERROR_CANCELED),
                eq(0) /* vendorCode */);
        assertNull(mScheduler.getCurrentClient());
        assertTrue(client1.isAlreadyDone());
        assertTrue(client1.mDestroyed);
        assertTrue(client2.isAlreadyDone());
        assertTrue(client2.mDestroyed);
    }

    @Test
    public void testCancels_whenAuthRequestIdNotSet() {
        testCancelsAuthDetectWhenRequestId(null /* requestId */, 2, true /* started */);
    }

    @Test
    public void testCancels_whenAuthRequestIdNotSet_notStarted() {
        testCancelsAuthDetectWhenRequestId(null /* requestId */, 2, false /* started */);
    }

    @Test
    public void testCancels_whenAuthRequestIdMatches() {
        testCancelsAuthDetectWhenRequestId(200L, 200, true /* started */);
    }

    @Test
    public void testCancels_whenAuthRequestIdMatches_noStarted() {
        testCancelsAuthDetectWhenRequestId(200L, 200, false /* started */);
    }

    @Test
    public void testDoesNotCancel_whenAuthRequestIdMismatched() {
        testCancelsAuthDetectWhenRequestId(10L, 20, true /* started */);
    }

    @Test
    public void testDoesNotCancel_whenAuthRequestIdMismatched_notStarted() {
        testCancelsAuthDetectWhenRequestId(10L, 20, false /* started */);
    }

    private void testCancelsAuthDetectWhenRequestId(@Nullable Long requestId, long cancelRequestId,
            boolean started) {
        final Supplier<Object> lazyDaemon = () -> mock(Object.class);
        final ClientMonitorCallbackConverter callback = mock(ClientMonitorCallbackConverter.class);
        testCancelsWhenRequestId(requestId, cancelRequestId, started,
                new TestAuthenticationClient(mContext, lazyDaemon, mToken, callback,
                        mBiometricContext));
    }

    @Test
    public void testCancels_whenEnrollRequestIdNotSet() {
        testCancelsEnrollWhenRequestId(null /* requestId */, 2, false /* started */);
    }

    @Test
    public void testCancels_whenEnrollRequestIdMatches() {
        testCancelsEnrollWhenRequestId(200L, 200, false /* started */);
    }

    @Test
    public void testDoesNotCancel_whenEnrollRequestIdMismatched() {
        testCancelsEnrollWhenRequestId(10L, 20, false /* started */);
    }

    @Test
    public void testCancelAuthenticationClientWithoutStarting() {
        final Supplier<Object> lazyDaemon = () -> mock(Object.class);
        final TestHalClientMonitor client1 = new TestHalClientMonitor(mContext, mToken, lazyDaemon);
        final ClientMonitorCallbackConverter callback = mock(ClientMonitorCallbackConverter.class);
        final TestAuthenticationClient client2 = new TestAuthenticationClient(mContext, lazyDaemon,
                mToken, callback, mBiometricContext);

        //Schedule authentication client to the pending queue
        mScheduler.scheduleClientMonitor(client1);
        mScheduler.scheduleClientMonitor(client2);
        waitForIdle();

        assertThat(mScheduler.getCurrentClient()).isEqualTo(client1);

        client2.cancel();
        waitForIdle();

        assertThat(client2.isAlreadyCancelled()).isTrue();

        client1.getCallback().onClientFinished(client1, false);
        waitForIdle();

        assertThat(mScheduler.getCurrentClient()).isNull();
    }

    @Test
    public void testCancelAuthenticationClientWithoutStarting_whenAppCrashes() {
        final Supplier<Object> lazyDaemon = () -> mock(Object.class);
        final TestHalClientMonitor client1 = new TestHalClientMonitor(mContext, mToken, lazyDaemon);
        final ClientMonitorCallbackConverter callback = mock(ClientMonitorCallbackConverter.class);
        final TestAuthenticationClient client2 = new TestAuthenticationClient(mContext, lazyDaemon,
                mToken, callback, mBiometricContext);

        //Schedule authentication client to the pending queue
        mScheduler.scheduleClientMonitor(client1);
        mScheduler.scheduleClientMonitor(client2);
        waitForIdle();

        assertThat(mScheduler.getCurrentClient()).isEqualTo(client1);

        //App crashes
        client2.binderDied();
        waitForIdle();

        assertThat(client2.isAlreadyCancelled()).isTrue();

        client1.getCallback().onClientFinished(client1, false);
        waitForIdle();

        assertThat(mScheduler.getCurrentClient()).isNull();
    }

    private void testCancelsEnrollWhenRequestId(@Nullable Long requestId, long cancelRequestId,
            boolean started) {
        final Supplier<Object> lazyDaemon = () -> mock(Object.class);
        final ClientMonitorCallbackConverter callback = mock(ClientMonitorCallbackConverter.class);
        testCancelsWhenRequestId(requestId, cancelRequestId, started,
                new TestEnrollClient(mContext, lazyDaemon, mToken, callback));
    }

    private void testCancelsWhenRequestId(@Nullable Long requestId, long cancelRequestId,
            boolean started, HalClientMonitor<?> client) {
        final boolean matches = requestId == null || requestId == cancelRequestId;
        if (requestId != null) {
            client.setRequestId(requestId);
        }

        final boolean isAuth = client instanceof TestAuthenticationClient;
        final boolean isEnroll = client instanceof TestEnrollClient;

        mScheduler.scheduleClientMonitor(client);
        waitForIdle();
        if (started) {
            mScheduler.startPreparedClient(client.getCookie());
        }
        waitForIdle();
        if (isAuth) {
            mScheduler.cancelAuthenticationOrDetection(mToken, cancelRequestId);
        } else if (isEnroll) {
            mScheduler.cancelEnrollment(mToken, cancelRequestId);
        } else {
            fail("unexpected operation type");
        }
        waitForIdle();

        if (isAuth) {
            // auth clients that were waiting for cookie when canceled should never invoke the hal
            final TestAuthenticationClient authClient = (TestAuthenticationClient) client;
            assertEquals(matches && started ? 1 : 0, authClient.mNumCancels);
            assertEquals(started, authClient.mStartedHal);
        } else if (isEnroll) {
            final TestEnrollClient enrollClient = (TestEnrollClient) client;
            assertEquals(matches ? 1 : 0, enrollClient.mNumCancels);
            assertTrue(enrollClient.mStartedHal);
        }

        if (matches) {
            if (started || isEnroll) { // prep'd auth clients and enroll clients
                assertTrue(mScheduler.mCurrentOperation.isCanceling());
            }
        } else {
            if (started || isEnroll) { // prep'd auth clients and enroll clients
                assertTrue(mScheduler.mCurrentOperation.isStarted());
            } else {
                assertNotEquals(0, mScheduler.mCurrentOperation.isReadyToStart(
                        mock(ClientMonitorCallback.class)));
            }
        }
    }

    @Test
    public void testCancelsPending_whenAuthRequestIdsSet() {
        final long requestId1 = 10;
        final long requestId2 = 20;
        final Supplier<Object> lazyDaemon = () -> mock(Object.class);
        final ClientMonitorCallbackConverter callback = mock(ClientMonitorCallbackConverter.class);
        final TestAuthenticationClient client1 = new TestAuthenticationClient(mContext, lazyDaemon,
                mToken, callback, mBiometricContext);
        client1.setRequestId(requestId1);
        final TestAuthenticationClient client2 = new TestAuthenticationClient(mContext, lazyDaemon,
                mToken, callback, mBiometricContext);
        client2.setRequestId(requestId2);

        mScheduler.scheduleClientMonitor(client1);
        mScheduler.scheduleClientMonitor(client2);
        mScheduler.startPreparedClient(client1.getCookie());
        waitForIdle();
        mScheduler.cancelAuthenticationOrDetection(mToken, 9999);
        waitForIdle();

        assertTrue(mScheduler.mCurrentOperation.isStarted());
        assertFalse(mScheduler.mPendingOperations.getFirst().isStarted());

        mScheduler.cancelAuthenticationOrDetection(mToken, requestId2);
        waitForIdle();

        assertTrue(mScheduler.mCurrentOperation.isStarted());
        assertTrue(mScheduler.mPendingOperations.getFirst().isMarkedCanceling());
    }

    @Test
    public void testInterruptPrecedingClients_whenExpected() {
        final BaseClientMonitor interruptableMonitor = createBaseClientMonitor();
        when(interruptableMonitor.isInterruptable()).thenReturn(true);

        final BaseClientMonitor interrupter = createBaseClientMonitor();
        when(interrupter.interruptsPrecedingClients()).thenReturn(true);

        mScheduler.scheduleClientMonitor(interruptableMonitor);
        mScheduler.scheduleClientMonitor(interrupter);
        waitForIdle();

        verify(interruptableMonitor).cancel();
        mScheduler.getInternalCallback().onClientFinished(interruptableMonitor, true /* success */);
    }

    @Test
    public void testDoesNotInterruptPrecedingClients_whenNotExpected() {
        final BaseClientMonitor interruptableMonitor = createBaseClientMonitor();
        when(interruptableMonitor.isInterruptable()).thenReturn(true);

        final BaseClientMonitor interrupter = createBaseClientMonitor();
        when(interrupter.interruptsPrecedingClients()).thenReturn(false);

        mScheduler.scheduleClientMonitor(interruptableMonitor);
        mScheduler.scheduleClientMonitor(interrupter);
        waitForIdle();

        verify(interruptableMonitor, never()).cancel();
    }

    @Test
    public void testClientDestroyed_afterFinish() {
        final Supplier<Object> nonNullDaemon = () -> mock(Object.class);
        final TestHalClientMonitor client = new TestHalClientMonitor(mContext, mToken,
                nonNullDaemon);
        mScheduler.scheduleClientMonitor(client);
        client.mCallback.onClientFinished(client, true /* success */);
        waitForIdle();
        assertTrue(client.mDestroyed);
    }

    @Test
    public void testClearBiometricQueue_clearsHungAuthOperation() {
        // Creating a hung client
        final TestableLooper looper = TestableLooper.get(this);
        final Supplier<Object> lazyDaemon1 = () -> mock(Object.class);
        final TestAuthenticationClient client1 = new TestAuthenticationClient(mContext,
                lazyDaemon1, mToken, mock(ClientMonitorCallbackConverter.class), 0 /* cookie */,
                mBiometricContext);
        final ClientMonitorCallback callback1 = mock(ClientMonitorCallback.class);

        mScheduler.scheduleClientMonitor(client1, callback1);
        waitForIdle();

        mScheduler.startWatchdog();
        waitForIdle();

        //Checking client is hung
        verify(callback1).onClientStarted(client1);
        verify(callback1, never()).onClientFinished(any(), anyBoolean());
        assertNotNull(mScheduler.mCurrentOperation);
        assertEquals(0, mScheduler.getCurrentPendingCount());

        looper.moveTimeForward(10000);
        waitForIdle();
        looper.moveTimeForward(3000);
        waitForIdle();

        // The hung client did not honor this operation, verify onError and authenticated
        // were never called.
        assertFalse(client1.mOnErrorCalled);
        assertFalse(client1.mAuthenticateCalled);
        verify(callback1).onClientFinished(client1, false /* success */);
        assertNull(mScheduler.mCurrentOperation);
        assertEquals(0, mScheduler.getCurrentPendingCount());
    }

    @Test
    public void testAuthWorks_afterClearBiometricQueue() {
        // Creating a hung client
        final TestableLooper looper = TestableLooper.get(this);
        final Supplier<Object> lazyDaemon1 = () -> mock(Object.class);
        final TestAuthenticationClient client1 = new TestAuthenticationClient(mContext,
                lazyDaemon1, mToken, mock(ClientMonitorCallbackConverter.class), 0 /* cookie */,
                mBiometricContext);
        final ClientMonitorCallback callback1 = mock(ClientMonitorCallback.class);

        mScheduler.scheduleClientMonitor(client1, callback1);

        assertEquals(client1, mScheduler.mCurrentOperation.getClientMonitor());
        assertEquals(0, mScheduler.getCurrentPendingCount());

        //Checking client is hung
        waitForIdle();
        verify(callback1, never()).onClientFinished(any(), anyBoolean());

        //Start watchdog
        mScheduler.startWatchdog();
        waitForIdle();

        // The watchdog should kick off the cancellation
        looper.moveTimeForward(10000);
        waitForIdle();
        // After 10 seconds the HAL has 3 seconds to respond to a cancel
        looper.moveTimeForward(3000);
        waitForIdle();

        // The hung client did not honor this operation, verify onError and authenticated
        // were never called.
        assertFalse(client1.mOnErrorCalled);
        assertFalse(client1.mAuthenticateCalled);
        verify(callback1).onClientFinished(client1, false /* success */);
        assertEquals(0, mScheduler.getCurrentPendingCount());
        assertNull(mScheduler.mCurrentOperation);


        //Run additional auth client
        final TestAuthenticationClient client2 = new TestAuthenticationClient(mContext,
                lazyDaemon1, mToken, mock(ClientMonitorCallbackConverter.class), 0 /* cookie */,
                mBiometricContext);
        final ClientMonitorCallback callback2 = mock(ClientMonitorCallback.class);

        mScheduler.scheduleClientMonitor(client2, callback2);

        assertEquals(client2, mScheduler.mCurrentOperation.getClientMonitor());
        assertEquals(0, mScheduler.getCurrentPendingCount());

        //Start watchdog
        mScheduler.startWatchdog();
        waitForIdle();
        mScheduler.scheduleClientMonitor(createBaseClientMonitor(),
                mock(ClientMonitorCallback.class));
        waitForIdle();

        //Ensure auth client passes
        verify(callback2).onClientStarted(client2);
        client2.getCallback().onClientFinished(client2, true);
        waitForIdle();

        looper.moveTimeForward(10000);
        waitForIdle();
        // After 10 seconds the HAL has 3 seconds to respond to a cancel
        looper.moveTimeForward(3000);
        waitForIdle();

        //Asserting auth client passes
        assertTrue(client2.isAlreadyDone());
        assertNotNull(mScheduler.mCurrentOperation);
    }

    @Test
    public void testClearBiometricQueue_doesNotClearOperationsWhenQueueNotStuck() {
        //Creating clients
        final TestableLooper looper = TestableLooper.get(this);
        final Supplier<Object> lazyDaemon1 = () -> mock(Object.class);
        final TestAuthenticationClient client1 = new TestAuthenticationClient(mContext,
                lazyDaemon1, mToken, mock(ClientMonitorCallbackConverter.class), 0 /* cookie */,
                mBiometricContext);
        final ClientMonitorCallback callback1 = mock(ClientMonitorCallback.class);

        mScheduler.scheduleClientMonitor(client1, callback1);
        //Start watchdog
        mScheduler.startWatchdog();
        waitForIdle();
        mScheduler.scheduleClientMonitor(createBaseClientMonitor(),
                mock(ClientMonitorCallback.class));
        mScheduler.scheduleClientMonitor(createBaseClientMonitor(),
                mock(ClientMonitorCallback.class));
        waitForIdle();

        assertEquals(client1, mScheduler.mCurrentOperation.getClientMonitor());
        assertEquals(2, mScheduler.getCurrentPendingCount());
        verify(callback1, never()).onClientFinished(any(), anyBoolean());
        verify(callback1).onClientStarted(client1);

        //Client finishes successfully
        client1.getCallback().onClientFinished(client1, true);
        waitForIdle();

        // The watchdog should kick off the cancellation
        looper.moveTimeForward(10000);
        waitForIdle();
        // After 10 seconds the HAL has 3 seconds to respond to a cancel
        looper.moveTimeForward(3000);
        waitForIdle();

        //Watchdog does not clear pending operations
        assertEquals(1, mScheduler.getCurrentPendingCount());
        assertNotNull(mScheduler.mCurrentOperation);

    }

    @Test
    public void testTwoInternalCleanupOps_withFirstFavorHalEnrollment() throws Exception {
        final String owner = "test.owner";
        final int userId = 1;
        final Supplier<Object> daemon = () -> mock(AidlSession.class);
        final FingerprintUtils utils = mock(FingerprintUtils.class);
        final Map<Integer, Long> authenticatorIds = new HashMap<>();
        final ClientMonitorCallback callback0 = mock(ClientMonitorCallback.class);
        final ClientMonitorCallback callback1 = mock(ClientMonitorCallback.class);
        final ClientMonitorCallback callback2 = mock(ClientMonitorCallback.class);

        final TestInternalCleanupClient client1 = new
                TestInternalCleanupClient(mContext, daemon, userId,
                owner, TEST_SENSOR_ID, mock(BiometricLogger.class),
                mBiometricContext, utils, authenticatorIds);
        final TestInternalCleanupClient client2 = new
                TestInternalCleanupClient(mContext, daemon, userId,
                owner, TEST_SENSOR_ID, mock(BiometricLogger.class),
                mBiometricContext, utils, authenticatorIds);

        //add initial start client to scheduler, so later clients will be on pending operation queue
        final TestHalClientMonitor startClient = new TestHalClientMonitor(mContext, mToken,
                daemon);
        mScheduler.scheduleClientMonitor(startClient, callback0);

        //add first cleanup client which favors enrollments from HAL
        client1.setFavorHalEnrollments();
        mScheduler.scheduleClientMonitor(client1, callback1);
        assertEquals(1, mScheduler.mPendingOperations.size());

        when(utils.getBiometricsForUser(mContext, userId)).thenAnswer(i ->
                new ArrayList<>(client1.getFingerprints()));

        //add second cleanup client
        mScheduler.scheduleClientMonitor(client2, callback2);

        //finish the start client, so other pending clients are processed
        startClient.getCallback().onClientFinished(startClient, true);

        waitForIdle();

        assertTrue(client1.isAlreadyDone());
        assertTrue(client2.isAlreadyDone());
        assertNull(mScheduler.mCurrentOperation);
        verify(utils, never()).removeBiometricForUser(mContext, userId,
                TEST_FINGERPRINT.getBiometricId());
        assertEquals(1,  client1.getFingerprints().size());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void testScheduleOperation_whenNoUser() {
        mCurrentUserId = UserHandle.USER_NULL;

        final BaseClientMonitor nextClient = createBaseClientMonitor();
        when(nextClient.getTargetUserId()).thenReturn(0);

        mScheduler.scheduleClientMonitor(nextClient);
        waitForIdle();

        assertThat(mUsersStoppedCount).isEqualTo(0);
        assertThat(mStartedUsers).containsExactly(0);
        verify(nextClient).start(any());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void testScheduleOperation_whenNoUser_notStarted() {
        mCurrentUserId = UserHandle.USER_NULL;
        mStartOperationsFinish = false;

        final BaseClientMonitor[] nextClients = new BaseClientMonitor[]{
                createBaseClientMonitor(),
                createBaseClientMonitor(),
                createBaseClientMonitor()
        };
        for (BaseClientMonitor client : nextClients) {
            when(client.getTargetUserId()).thenReturn(5);
            mScheduler.scheduleClientMonitor(client);
            waitForIdle();
        }

        assertThat(mUsersStoppedCount).isEqualTo(0);
        assertThat(mStartedUsers).isEmpty();
        assertThat(mStartUserClientCount).isEqualTo(1);
        for (BaseClientMonitor client : nextClients) {
            verify(client, never()).start(any());
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void testScheduleOperation_whenNoUser_notStarted_andReset() {
        mCurrentUserId = UserHandle.USER_NULL;
        mStartOperationsFinish = false;

        final BaseClientMonitor client = createBaseClientMonitor();

        when(client.getTargetUserId()).thenReturn(5);

        mScheduler.scheduleClientMonitor(client);
        waitForIdle();

        final TestStartUserClient startUserClient =
                (TestStartUserClient) mScheduler.mCurrentOperation.getClientMonitor();
        mScheduler.reset();

        assertThat(mScheduler.mCurrentOperation).isNull();

        final BiometricSchedulerOperation fakeOperation = new BiometricSchedulerOperation(
                createBaseClientMonitor(), new ClientMonitorCallback() {});
        mScheduler.mCurrentOperation = fakeOperation;
        startUserClient.mCallback.onClientFinished(startUserClient, true);

        assertThat(fakeOperation).isSameInstanceAs(mScheduler.mCurrentOperation);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void testScheduleOperation_whenSameUser() {
        mCurrentUserId = 10;

        BaseClientMonitor nextClient = createBaseClientMonitor();
        when(nextClient.getTargetUserId()).thenReturn(mCurrentUserId);

        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();

        verify(nextClient).start(any());
        assertThat(mUsersStoppedCount).isEqualTo(0);
        assertThat(mStartedUsers).isEmpty();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void testScheduleOperation_whenDifferentUser() {
        mCurrentUserId = 10;

        final int nextUserId = 11;
        BaseClientMonitor nextClient = createBaseClientMonitor();
        when(nextClient.getTargetUserId()).thenReturn(nextUserId);

        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();
        assertThat(mUsersStoppedCount).isEqualTo(1);

        waitForIdle();
        assertThat(mStartedUsers).containsExactly(nextUserId);

        waitForIdle();
        verify(nextClient).start(any());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void testStartUser_alwaysStartsNextOperation() {
        mCurrentUserId = UserHandle.USER_NULL;

        BaseClientMonitor nextClient = createBaseClientMonitor();
        when(nextClient.getTargetUserId()).thenReturn(10);

        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();
        verify(nextClient).start(any());

        // finish first operation
        mScheduler.getInternalCallback().onClientFinished(nextClient, true /* success */);
        waitForIdle();

        // schedule second operation but swap out the current operation
        // before it runs so that it's not current when it's completion callback runs
        nextClient = createBaseClientMonitor();
        when(nextClient.getTargetUserId()).thenReturn(11);
        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();
        verify(nextClient).start(any());
        assertThat(mStartedUsers).containsExactly(10, 11).inOrder();
        assertThat(mUsersStoppedCount).isEqualTo(1);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void testStartUser_failsClearsStopUserClient() {
        mCurrentUserId = UserHandle.USER_NULL;

        // When a stop user client fails, check that mStopUserClient
        // is set to null to prevent the scheduler from getting stuck.
        BaseClientMonitor nextClient = createBaseClientMonitor();
        when(nextClient.getTargetUserId()).thenReturn(10);

        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();
        verify(nextClient).start(any());

        // finish first operation
        mScheduler.getInternalCallback().onClientFinished(nextClient, true /* success */);
        waitForIdle();

        // schedule second operation but swap out the current operation
        // before it runs so that it's not current when it's completion callback runs
        nextClient = createBaseClientMonitor();
        when(nextClient.getTargetUserId()).thenReturn(11);
        mShouldFailStopUser = true;
        mScheduler.scheduleClientMonitor(nextClient);

        waitForIdle();
        assertThat(mStartedUsers).containsExactly(10, 11).inOrder();
        assertThat(mUsersStoppedCount).isEqualTo(0);
        assertThat(mScheduler.getStopUserClient()).isEqualTo(null);
    }

    private BiometricSchedulerProto getDump(boolean clearSchedulerBuffer) throws Exception {
        return BiometricSchedulerProto.parseFrom(mScheduler.dumpProtoState(clearSchedulerBuffer));
    }

    private BaseClientMonitor createBaseClientMonitor() {
        BaseClientMonitor client = mock(BaseClientMonitor.class);
        when(client.getListener()).thenReturn(mListener);

        return client;
    }

    private void waitForIdle() {
        TestableLooper.get(this).processAllMessages();
    }

    private static class TestAuthenticateOptions implements AuthenticateOptions {
        @Override
        public int getUserId() {
            return 0;
        }

        @Override
        public int getSensorId() {
            return TEST_SENSOR_ID;
        }

        @Override
        public int getDisplayState() {
            return DISPLAY_STATE_UNKNOWN;
        }

        @NonNull
        @Override
        public String getOpPackageName() {
            return "some.test.name";
        }

        @Nullable
        @Override
        public String getAttributionTag() {
            return null;
        }
    }

    private static class TestAuthenticationClient
            extends AuthenticationClient<Object, TestAuthenticateOptions> {
        boolean mStartedHal = false;
        boolean mStoppedHal = false;
        boolean mDestroyed = false;
        int mNumCancels = 0;
        boolean mAuthenticateCalled = false;
        boolean mOnErrorCalled = false;

        TestAuthenticationClient(@NonNull Context context,
                @NonNull Supplier<Object> lazyDaemon, @NonNull IBinder token,
                @NonNull ClientMonitorCallbackConverter listener,
                BiometricContext biometricContext) {
            this(context, lazyDaemon, token, listener, 1 /* cookie */, biometricContext);
        }

        TestAuthenticationClient(@NonNull Context context,
                @NonNull Supplier<Object> lazyDaemon, @NonNull IBinder token,
                @NonNull ClientMonitorCallbackConverter listener, int cookie,
                @NonNull BiometricContext biometricContext) {
            super(context, lazyDaemon, token, listener, 0 /* operationId */,
                    false /* restricted */, new TestAuthenticateOptions(), cookie,
                    false /* requireConfirmation */,
                    mock(BiometricLogger.class), biometricContext,
                    true /* isStrongBiometric */, null /* taskStackListener */,
                    null /* lockoutTracker */, false /* isKeyguard */,
                    true /* shouldVibrate */,
                    0 /* sensorStrength */);
        }

        @Override
        protected void stopHalOperation() {
            mStoppedHal = true;
        }

        @Override
        protected void startHalOperation() {
            mStartedHal = true;
        }

        @Override
        protected void handleLifecycleAfterAuth(boolean authenticated) {
        }

        @Override
        public void onAuthenticated(BiometricAuthenticator.Identifier identifier,
                boolean authenticated, ArrayList<Byte> hardwareAuthToken) {
            mAuthenticateCalled = true;
        }

        @Override
        protected void onErrorInternal(int errorCode, int vendorCode, boolean finish) {
            mOnErrorCalled = true;
        }

        @Override
        public boolean wasUserDetected() {
            return false;
        }

        @Override
        public void destroy() {
            mDestroyed = true;
            super.destroy();
        }

        @Override
        public void cancel() {
            mNumCancels++;
            super.cancel();
        }
    }

    private static class TestEnrollClient extends EnrollClient<Object> {
        boolean mStartedHal = false;
        boolean mStoppedHal = false;
        int mNumCancels = 0;

        TestEnrollClient(@NonNull Context context, @NonNull Supplier<Object> lazyDaemon,
                @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener) {
            super(context, lazyDaemon, token, listener, 0 /* userId */, new byte[69],
                    "test" /* owner */, mock(BiometricUtils.class), 5 /* timeoutSec */,
                    TEST_SENSOR_ID, true /* shouldVibrate */, mock(BiometricLogger.class),
                    mock(BiometricContext.class), 0 /* enrollReason */);
        }

        @Override
        protected void stopHalOperation() {
            mStoppedHal = true;
        }

        @Override
        protected void startHalOperation() {
            mStartedHal = true;
        }

        @Override
        protected boolean hasReachedEnrollmentLimit() {
            return false;
        }

        @Override
        public void cancel() {
            mNumCancels++;
            super.cancel();
        }
    }

    private static class TestHalClientMonitor extends HalClientMonitor<Object> {
        private final int mProtoEnum;
        private boolean mUnableToStart;
        private boolean mStarted;
        private boolean mDestroyed;

        TestHalClientMonitor(@NonNull Context context, @NonNull IBinder token,
                @NonNull Supplier<Object> lazyDaemon) {
            this(context, token, lazyDaemon, 0 /* cookie */, BiometricsProto.CM_UPDATE_ACTIVE_USER);
        }

        TestHalClientMonitor(@NonNull Context context, @NonNull IBinder token,
                @NonNull Supplier<Object> lazyDaemon, int cookie, int protoEnum) {
            super(context, lazyDaemon, token /* token */, null /* listener */, 0 /* userId */, TAG,
                    cookie, TEST_SENSOR_ID, mock(BiometricLogger.class),
                    mock(BiometricContext.class));
            mProtoEnum = protoEnum;
        }

        @Override
        public void unableToStart() {
            assertFalse(mUnableToStart);
            mUnableToStart = true;
        }

        @Override
        public int getProtoEnum() {
            return mProtoEnum;
        }

        @Override
        public void start(@NonNull ClientMonitorCallback callback) {
            super.start(callback);
            assertFalse(mStarted);
            mStarted = true;
        }

        @Override
        protected void startHalOperation() {
            mStarted = true;
        }

        @Override
        public void destroy() {
            super.destroy();
            mDestroyed = true;
        }
    }

    private static class TestInternalEnumerateClient extends InternalEnumerateClient<Object> {
        private static final String TAG = "TestInternalEnumerateClient";


        protected TestInternalEnumerateClient(@NonNull Context context,
                @NonNull Supplier<Object> lazyDaemon, @NonNull IBinder token, int userId,
                @NonNull String owner, @NonNull List<Fingerprint> enrolledList,
                @NonNull BiometricUtils<Fingerprint> utils, int sensorId,
                @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext) {
            super(context, lazyDaemon, token, userId, owner, enrolledList, utils, sensorId,
                    logger, biometricContext);
        }

        @Override
        protected void startHalOperation() {
            Slog.d(TAG, "TestInternalEnumerateClient#startHalOperation");
            onEnumerationResult(TEST_FINGERPRINT, 0 /* remaining */);
        }

        @Override
        protected int getModality() {
            return BiometricsProtoEnums.MODALITY_FINGERPRINT;
        }
    }

    private static class TestRemovalClient extends RemovalClient<Fingerprint, Object> {
        private static final String TAG = "TestRemovalClient";

        TestRemovalClient(@NonNull Context context,
                @NonNull Supplier<Object> lazyDaemon, @NonNull IBinder token,
                @Nullable ClientMonitorCallbackConverter listener, int[] biometricIds, int userId,
                @NonNull String owner, @NonNull BiometricUtils<Fingerprint> utils, int sensorId,
                @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
                @NonNull Map<Integer, Long> authenticatorIds) {
            super(context, lazyDaemon, token, listener, userId, owner, utils, sensorId,
                    logger, biometricContext, authenticatorIds);
        }

        @Override
        protected void startHalOperation() {
            Slog.d(TAG, "Removing template from hw");
            onRemoved(TEST_FINGERPRINT, 0);
        }
    }

    private static class TestInternalCleanupClient extends
            InternalCleanupClient<Fingerprint, Object> {
        private List<Fingerprint> mFingerprints = new ArrayList<>();

        TestInternalCleanupClient(@NonNull Context context,
                @NonNull Supplier<Object> lazyDaemon,
                int userId, @NonNull String owner, int sensorId,
                @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
                @NonNull FingerprintUtils utils, @NonNull Map<Integer, Long> authenticatorIds) {
            super(context, lazyDaemon, userId, owner, sensorId, logger, biometricContext,
                    utils, authenticatorIds);
        }

        @Override
        protected InternalEnumerateClient<Object> getEnumerateClient(Context context,
                Supplier<Object> lazyDaemon, IBinder token, int userId, String owner,
                List<Fingerprint> enrolledList, BiometricUtils<Fingerprint> utils, int sensorId,
                @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext) {
            return new TestInternalEnumerateClient(context, lazyDaemon, token, userId, owner,
                    enrolledList, utils, sensorId,
                    logger.swapAction(context, BiometricsProtoEnums.ACTION_ENUMERATE),
                    biometricContext);
        }

        @Override
        protected RemovalClient<Fingerprint, Object> getRemovalClient(Context context,
                Supplier<Object> lazyDaemon, IBinder token, int biometricId, int userId,
                String owner, BiometricUtils<Fingerprint> utils, int sensorId,
                @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
                Map<Integer, Long> authenticatorIds) {
            return new TestRemovalClient(context, lazyDaemon, token, null,
                    new int[]{biometricId}, userId, owner, utils, sensorId, logger,
                    biometricContext, authenticatorIds);
        }

        @Override
        protected void onAddUnknownTemplate(int userId,
                @NonNull BiometricAuthenticator.Identifier identifier) {
            mFingerprints.add((Fingerprint) identifier);
        }

        public List<Fingerprint> getFingerprints() {
            return mFingerprints;
        }
    }

    private interface StopUserClientShouldFail {
        boolean shouldFail();
    }

    private class TestStopUserClient extends StopUserClient<ISession> {
        private StopUserClientShouldFail mShouldFailClient;
        TestStopUserClient(@NonNull Context context,
                @NonNull Supplier<ISession> lazyDaemon, @Nullable IBinder token, int userId,
                int sensorId, @NonNull BiometricLogger logger,
                @NonNull BiometricContext biometricContext,
                @NonNull UserStoppedCallback callback, StopUserClientShouldFail shouldFail) {
            super(context, lazyDaemon, token, userId, sensorId, logger, biometricContext, callback);
            mShouldFailClient = shouldFail;
        }

        @Override
        protected void startHalOperation() {

        }

        @Override
        public void start(@NonNull ClientMonitorCallback callback) {
            super.start(callback);
            if (mShouldFailClient.shouldFail()) {
                getCallback().onClientFinished(this, false /* success */);
                // When the above fails, it means that the HAL has died, in this case we
                // need to ensure the UserSwitchCallback correctly returns the NULL user handle.
                mCurrentUserId = UserHandle.USER_NULL;
            } else {
                onUserStopped();
            }
        }

        @Override
        public void unableToStart() {

        }
    }

    private static class TestStartUserClient extends StartUserClient<IFingerprint, ISession> {

        @Mock
        private ISession mSession;
        private final boolean mShouldFinish;
        ClientMonitorCallback mCallback;

        TestStartUserClient(@NonNull Context context,
                @NonNull Supplier<IFingerprint> lazyDaemon, @Nullable IBinder token, int userId,
                int sensorId, @NonNull BiometricLogger logger,
                @NonNull BiometricContext biometricContext,
                @NonNull UserStartedCallback<ISession> callback, boolean shouldFinish) {
            super(context, lazyDaemon, token, userId, sensorId, logger, biometricContext, callback);
            mShouldFinish = shouldFinish;
        }

        @Override
        protected void startHalOperation() {

        }

        @Override
        public void start(@NonNull ClientMonitorCallback callback) {
            super.start(callback);

            mCallback = callback;
            if (mShouldFinish) {
                mUserStartedCallback.onUserStarted(
                        getTargetUserId(), mSession, 1 /* halInterfaceVersion */);
                callback.onClientFinished(this, true /* success */);
            }
        }

        @Override
        public void unableToStart() {

        }
    }
}
