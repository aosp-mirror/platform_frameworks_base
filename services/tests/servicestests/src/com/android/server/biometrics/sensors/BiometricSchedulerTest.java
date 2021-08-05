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

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.content.Context;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.IBiometricService;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.nano.BiometricSchedulerProto;
import com.android.server.biometrics.nano.BiometricsProto;
import com.android.server.biometrics.sensors.BiometricScheduler.Operation;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@SmallTest
public class BiometricSchedulerTest {

    private static final String TAG = "BiometricSchedulerTest";
    private static final int TEST_SENSOR_ID = 1;
    private static final int LOG_NUM_RECENT_OPERATIONS = 2;

    private BiometricScheduler mScheduler;
    private IBinder mToken;

    @Mock
    private IBiometricService mBiometricService;

    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext(), null);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mToken = new Binder();
        mScheduler = new BiometricScheduler(TAG, BiometricScheduler.SENSOR_TYPE_UNKNOWN,
                null /* gestureAvailabilityTracker */, mBiometricService, LOG_NUM_RECENT_OPERATIONS,
                CoexCoordinator.getInstance());
    }

    @Test
    public void testClientDuplicateFinish_ignoredBySchedulerAndDoesNotCrash() {
        final HalClientMonitor.LazyDaemon<Object> nonNullDaemon = () -> mock(Object.class);

        final HalClientMonitor<Object> client1 =
                new TestClientMonitor(mContext, mToken, nonNullDaemon);
        final HalClientMonitor<Object> client2 =
                new TestClientMonitor(mContext, mToken, nonNullDaemon);
        mScheduler.scheduleClientMonitor(client1);
        mScheduler.scheduleClientMonitor(client2);

        client1.mCallback.onClientFinished(client1, true /* success */);
        client1.mCallback.onClientFinished(client1, true /* success */);
    }

    @Test
    public void testRemovesPendingOperations_whenNullHal_andNotBiometricPrompt() {
        // Even if second client has a non-null daemon, it needs to be canceled.
        Object daemon2 = mock(Object.class);

        final HalClientMonitor.LazyDaemon<Object> lazyDaemon1 = () -> null;
        final HalClientMonitor.LazyDaemon<Object> lazyDaemon2 = () -> daemon2;

        final TestClientMonitor client1 = new TestClientMonitor(mContext, mToken, lazyDaemon1);
        final TestClientMonitor client2 = new TestClientMonitor(mContext, mToken, lazyDaemon2);

        final BaseClientMonitor.Callback callback1 = mock(BaseClientMonitor.Callback.class);
        final BaseClientMonitor.Callback callback2 = mock(BaseClientMonitor.Callback.class);

        // Pretend the scheduler is busy so the first operation doesn't start right away. We want
        // to pretend like there are two operations in the queue before kicking things off
        mScheduler.mCurrentOperation = new BiometricScheduler.Operation(
                mock(BaseClientMonitor.class), mock(BaseClientMonitor.Callback.class));

        mScheduler.scheduleClientMonitor(client1, callback1);
        assertEquals(1, mScheduler.mPendingOperations.size());
        // client1 is pending. Allow the scheduler to start once second client is added.
        mScheduler.mCurrentOperation = null;
        mScheduler.scheduleClientMonitor(client2, callback2);
        waitForIdle();

        assertTrue(client1.wasUnableToStart());
        verify(callback1).onClientFinished(eq(client1), eq(false) /* success */);
        verify(callback1, never()).onClientStarted(any());

        assertTrue(client2.wasUnableToStart());
        verify(callback2).onClientFinished(eq(client2), eq(false) /* success */);
        verify(callback2, never()).onClientStarted(any());

        assertTrue(mScheduler.mPendingOperations.isEmpty());
    }

    @Test
    public void testRemovesOnlyBiometricPromptOperation_whenNullHal() throws Exception {
        // Second non-BiometricPrompt client has a valid daemon
        final Object daemon2 = mock(Object.class);

        final HalClientMonitor.LazyDaemon<Object> lazyDaemon1 = () -> null;
        final HalClientMonitor.LazyDaemon<Object> lazyDaemon2 = () -> daemon2;

        final ClientMonitorCallbackConverter listener1 = mock(ClientMonitorCallbackConverter.class);

        final BiometricPromptClientMonitor client1 =
                new BiometricPromptClientMonitor(mContext, mToken, lazyDaemon1, listener1);
        final TestClientMonitor client2 = new TestClientMonitor(mContext, mToken, lazyDaemon2);

        final BaseClientMonitor.Callback callback1 = mock(BaseClientMonitor.Callback.class);
        final BaseClientMonitor.Callback callback2 = mock(BaseClientMonitor.Callback.class);

        // Pretend the scheduler is busy so the first operation doesn't start right away. We want
        // to pretend like there are two operations in the queue before kicking things off
        mScheduler.mCurrentOperation = new BiometricScheduler.Operation(
                mock(BaseClientMonitor.class), mock(BaseClientMonitor.Callback.class));

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
        assertFalse(client2.wasUnableToStart());
        assertTrue(client2.hasStarted());
        verify(callback2).onClientStarted(eq(client2));
    }

    @Test
    public void testCancelNotInvoked_whenOperationWaitingForCookie() {
        final HalClientMonitor.LazyDaemon<Object> lazyDaemon1 = () -> mock(Object.class);
        final BiometricPromptClientMonitor client1 = new BiometricPromptClientMonitor(mContext,
                mToken, lazyDaemon1, mock(ClientMonitorCallbackConverter.class));
        final BaseClientMonitor.Callback callback1 = mock(BaseClientMonitor.Callback.class);

        // Schedule a BiometricPrompt authentication request
        mScheduler.scheduleClientMonitor(client1, callback1);

        assertEquals(Operation.STATE_WAITING_FOR_COOKIE, mScheduler.mCurrentOperation.mState);
        assertEquals(client1, mScheduler.mCurrentOperation.mClientMonitor);
        assertEquals(0, mScheduler.mPendingOperations.size());

        // Request it to be canceled. The operation can be canceled immediately, and the scheduler
        // should go back to idle, since in this case the framework has not even requested the HAL
        // to authenticate yet.
        mScheduler.cancelAuthenticationOrDetection(mToken);
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
        final TestClientMonitor2 client = new TestClientMonitor2(mContext, mToken,
                () -> mock(Object.class), BiometricsProto.CM_ENROLL);
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
        final TestClientMonitor2 client = new TestClientMonitor2(mContext, mToken,
                () -> mock(Object.class), BiometricsProto.CM_ENROLL);
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
        final TestClientMonitor2 client2 = new TestClientMonitor2(mContext, mToken,
                () -> mock(Object.class), BiometricsProto.CM_REMOVE);
        mScheduler.scheduleClientMonitor(client2);
        waitForIdle();
        bsp = getDump(false /* clearSchedulerBuffer */);
        assertEquals(BiometricsProto.CM_REMOVE, bsp.currentOperation);
        assertEquals(1, bsp.totalOperations); // Enroll finished
        assertEquals(1, bsp.recentOperations.length);
        assertEquals(BiometricsProto.CM_ENROLL, bsp.recentOperations[0]);
        client2.getCallback().onClientFinished(client2, true);

        // And another operation
        final TestClientMonitor2 client3 = new TestClientMonitor2(mContext, mToken,
                () -> mock(Object.class), BiometricsProto.CM_AUTHENTICATE);
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
        final HalClientMonitor.LazyDaemon<Object> lazyDaemon = () -> mock(Object.class);

        final TestClientMonitor client1 = new TestClientMonitor(mContext, mToken, lazyDaemon);
        final ClientMonitorCallbackConverter callback = mock(ClientMonitorCallbackConverter.class);
        final TestAuthenticationClient client2 = new TestAuthenticationClient(mContext, lazyDaemon,
                mToken, callback);

        // Add a non-cancellable client, then add the auth client
        mScheduler.scheduleClientMonitor(client1);
        mScheduler.scheduleClientMonitor(client2);
        waitForIdle();

        assertEquals(mScheduler.getCurrentClient(), client1);
        assertEquals(Operation.STATE_WAITING_IN_QUEUE,
                mScheduler.mPendingOperations.getFirst().mState);

        // Request cancel before the authentication client has started
        mScheduler.cancelAuthenticationOrDetection(mToken);
        waitForIdle();
        assertEquals(Operation.STATE_WAITING_IN_QUEUE_CANCELING,
                mScheduler.mPendingOperations.getFirst().mState);

        // Finish the blocking client. The authentication client should send ERROR_CANCELED
        client1.getCallback().onClientFinished(client1, true /* success */);
        waitForIdle();
        verify(callback).onError(anyInt(), anyInt(),
                eq(BiometricConstants.BIOMETRIC_ERROR_CANCELED),
                eq(0) /* vendorCode */);
        assertNull(mScheduler.getCurrentClient());
    }

    @Test
    public void testInterruptPrecedingClients_whenExpected() {
        final BaseClientMonitor interruptableMonitor = mock(BaseClientMonitor.class,
                withSettings().extraInterfaces(Interruptable.class));

        final BaseClientMonitor interrupter = mock(BaseClientMonitor.class);
        when(interrupter.interruptsPrecedingClients()).thenReturn(true);

        mScheduler.scheduleClientMonitor(interruptableMonitor);
        mScheduler.scheduleClientMonitor(interrupter);
        waitForIdle();

        verify((Interruptable) interruptableMonitor).cancel();
        mScheduler.getInternalCallback().onClientFinished(interruptableMonitor, true /* success */);
    }

    @Test
    public void testDoesNotInterruptPrecedingClients_whenNotExpected() {
        final BaseClientMonitor interruptableMonitor = mock(BaseClientMonitor.class,
                withSettings().extraInterfaces(Interruptable.class));

        final BaseClientMonitor interrupter = mock(BaseClientMonitor.class);
        when(interrupter.interruptsPrecedingClients()).thenReturn(false);

        mScheduler.scheduleClientMonitor(interruptableMonitor);
        mScheduler.scheduleClientMonitor(interrupter);
        waitForIdle();

        verify((Interruptable) interruptableMonitor, never()).cancel();
    }

    private BiometricSchedulerProto getDump(boolean clearSchedulerBuffer) throws Exception {
        return BiometricSchedulerProto.parseFrom(mScheduler.dumpProtoState(clearSchedulerBuffer));
    }

    private static class BiometricPromptClientMonitor extends AuthenticationClient<Object> {

        public BiometricPromptClientMonitor(@NonNull Context context, @NonNull IBinder token,
                @NonNull LazyDaemon<Object> lazyDaemon, ClientMonitorCallbackConverter listener) {
            super(context, lazyDaemon, token, listener, 0 /* targetUserId */, 0 /* operationId */,
                    false /* restricted */, TAG, 1 /* cookie */, false /* requireConfirmation */,
                    TEST_SENSOR_ID, true /* isStrongBiometric */, 0 /* statsModality */,
                    0 /* statsClient */, null /* taskStackListener */, mock(LockoutTracker.class),
                    false /* isKeyguard */, true /* shouldVibrate */,
                    false /* isKeyguardBypassEnabled */);
        }

        @Override
        protected void stopHalOperation() {

        }

        @Override
        protected void startHalOperation() {

        }

        @Override
        protected void handleLifecycleAfterAuth(boolean authenticated) {

        }

        @Override
        public boolean wasUserDetected() {
            return false;
        }
    }

    private static class TestAuthenticationClient extends AuthenticationClient<Object> {

        public TestAuthenticationClient(@NonNull Context context,
                @NonNull LazyDaemon<Object> lazyDaemon, @NonNull IBinder token,
                @NonNull ClientMonitorCallbackConverter listener) {
            super(context, lazyDaemon, token, listener, 0 /* targetUserId */, 0 /* operationId */,
                    false /* restricted */, TAG, 1 /* cookie */, false /* requireConfirmation */,
                    TEST_SENSOR_ID, true /* isStrongBiometric */, 0 /* statsModality */,
                    0 /* statsClient */, null /* taskStackListener */, mock(LockoutTracker.class),
                    false /* isKeyguard */, true /* shouldVibrate */,
                    false /* isKeyguardBypassEnabled */);
        }

        @Override
        protected void stopHalOperation() {

        }

        @Override
        protected void startHalOperation() {

        }

        @Override
        protected void handleLifecycleAfterAuth(boolean authenticated) {

        }

        @Override
        public boolean wasUserDetected() {
            return false;
        }
    }

    private static class TestClientMonitor2 extends TestClientMonitor {
        private final int mProtoEnum;

        public TestClientMonitor2(@NonNull Context context, @NonNull IBinder token,
                @NonNull LazyDaemon<Object> lazyDaemon, int protoEnum) {
            super(context, token, lazyDaemon);
            mProtoEnum = protoEnum;
        }

        @Override
        public int getProtoEnum() {
            return mProtoEnum;
        }
    }

    private static class TestClientMonitor extends HalClientMonitor<Object> {
        private boolean mUnableToStart;
        private boolean mStarted;

        public TestClientMonitor(@NonNull Context context, @NonNull IBinder token,
                @NonNull LazyDaemon<Object> lazyDaemon) {
            this(context, token, lazyDaemon, 0 /* cookie */);
        }

        public TestClientMonitor(@NonNull Context context, @NonNull IBinder token,
                @NonNull LazyDaemon<Object> lazyDaemon, int cookie) {
            super(context, lazyDaemon, token /* token */, null /* listener */, 0 /* userId */,
                    TAG, cookie, TEST_SENSOR_ID, 0 /* statsModality */,
                    0 /* statsAction */, 0 /* statsClient */);
        }

        @Override
        public void unableToStart() {
            assertFalse(mUnableToStart);
            mUnableToStart = true;
        }

        @Override
        public int getProtoEnum() {
            // Anything other than CM_NONE, which is used to represent "idle". Tests that need
            // real proto enums should use TestClientMonitor2
            return BiometricsProto.CM_UPDATE_ACTIVE_USER;
        }

        @Override
        public void start(@NonNull Callback callback) {
            super.start(callback);
            assertFalse(mStarted);
            mStarted = true;
        }

        @Override
        protected void startHalOperation() {

        }

        public boolean wasUnableToStart() {
            return mUnableToStart;
        }

        public boolean hasStarted() {
            return mStarted;
        }
    }

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
