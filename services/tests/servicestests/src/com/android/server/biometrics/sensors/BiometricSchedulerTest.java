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

import static android.testing.TestableLooper.RunWithLooper;

import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.nano.BiometricSchedulerProto;
import com.android.server.biometrics.nano.BiometricsProto;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
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
        mScheduler = new BiometricScheduler(TAG, new Handler(TestableLooper.get(this).getLooper()),
                BiometricScheduler.SENSOR_TYPE_UNKNOWN, null /* gestureAvailabilityTracker */,
                mBiometricService, LOG_NUM_RECENT_OPERATIONS,
                CoexCoordinator.getInstance());
    }

    @Test
    public void testClientDuplicateFinish_ignoredBySchedulerAndDoesNotCrash() {
        final HalClientMonitor.LazyDaemon<Object> nonNullDaemon = () -> mock(Object.class);

        final HalClientMonitor<Object> client1 =
                new TestHalClientMonitor(mContext, mToken, nonNullDaemon);
        final HalClientMonitor<Object> client2 =
                new TestHalClientMonitor(mContext, mToken, nonNullDaemon);
        mScheduler.scheduleClientMonitor(client1);
        mScheduler.scheduleClientMonitor(client2);

        client1.mCallback.onClientFinished(client1, true /* success */);
        client1.mCallback.onClientFinished(client1, true /* success */);
    }

    @Test
    public void testRemovesPendingOperations_whenNullHal_andNotBiometricPrompt() {
        // Even if second client has a non-null daemon, it needs to be canceled.
        final TestHalClientMonitor client1 = new TestHalClientMonitor(
                mContext, mToken, () -> null);
        final TestHalClientMonitor client2 = new TestHalClientMonitor(
                mContext, mToken, () -> mock(Object.class));

        final BaseClientMonitor.Callback callback1 = mock(BaseClientMonitor.Callback.class);
        final BaseClientMonitor.Callback callback2 = mock(BaseClientMonitor.Callback.class);

        // Pretend the scheduler is busy so the first operation doesn't start right away. We want
        // to pretend like there are two operations in the queue before kicking things off
        mScheduler.mCurrentOperation = new BiometricSchedulerOperation(
                mock(BaseClientMonitor.class), mock(BaseClientMonitor.Callback.class));

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

        final TestAuthenticationClient client1 =
                new TestAuthenticationClient(mContext, () -> null, mToken, listener1);
        final TestHalClientMonitor client2 =
                new TestHalClientMonitor(mContext, mToken, () -> daemon2);

        final BaseClientMonitor.Callback callback1 = mock(BaseClientMonitor.Callback.class);
        final BaseClientMonitor.Callback callback2 = mock(BaseClientMonitor.Callback.class);

        // Pretend the scheduler is busy so the first operation doesn't start right away. We want
        // to pretend like there are two operations in the queue before kicking things off
        mScheduler.mCurrentOperation = new BiometricSchedulerOperation(
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
        assertFalse(client2.mUnableToStart);
        assertTrue(client2.mStarted);
        verify(callback2).onClientStarted(eq(client2));
    }

    @Test
    public void testCancelNotInvoked_whenOperationWaitingForCookie() {
        final HalClientMonitor.LazyDaemon<Object> lazyDaemon1 = () -> mock(Object.class);
        final TestAuthenticationClient client1 = new TestAuthenticationClient(mContext,
                lazyDaemon1, mToken, mock(ClientMonitorCallbackConverter.class));
        final BaseClientMonitor.Callback callback1 = mock(BaseClientMonitor.Callback.class);

        // Schedule a BiometricPrompt authentication request
        mScheduler.scheduleClientMonitor(client1, callback1);

        assertNotEquals(0, mScheduler.mCurrentOperation.isReadyToStart());
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
        final HalClientMonitor.LazyDaemon<Object> lazyDaemon = () -> mock(Object.class);
        final TestHalClientMonitor client1 = new TestHalClientMonitor(mContext, mToken, lazyDaemon);
        final ClientMonitorCallbackConverter callback = mock(ClientMonitorCallbackConverter.class);
        final TestAuthenticationClient client2 = new TestAuthenticationClient(mContext, lazyDaemon,
                mToken, callback);

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
                eq(BiometricConstants.BIOMETRIC_ERROR_CANCELED),
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
        final HalClientMonitor.LazyDaemon<Object> lazyDaemon = () -> mock(Object.class);
        final ClientMonitorCallbackConverter callback = mock(ClientMonitorCallbackConverter.class);
        testCancelsWhenRequestId(requestId, cancelRequestId, started,
                new TestAuthenticationClient(mContext, lazyDaemon, mToken, callback));
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

    private void testCancelsEnrollWhenRequestId(@Nullable Long requestId, long cancelRequestId,
            boolean started) {
        final HalClientMonitor.LazyDaemon<Object> lazyDaemon = () -> mock(Object.class);
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
                assertNotEquals(0, mScheduler.mCurrentOperation.isReadyToStart());
            }
        }
    }

    @Test
    public void testCancelsPending_whenAuthRequestIdsSet() {
        final long requestId1 = 10;
        final long requestId2 = 20;
        final HalClientMonitor.LazyDaemon<Object> lazyDaemon = () -> mock(Object.class);
        final ClientMonitorCallbackConverter callback = mock(ClientMonitorCallbackConverter.class);
        final TestAuthenticationClient client1 = new TestAuthenticationClient(
                mContext, lazyDaemon, mToken, callback);
        client1.setRequestId(requestId1);
        final TestAuthenticationClient client2 = new TestAuthenticationClient(
                mContext, lazyDaemon, mToken, callback);
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

    @Test
    public void testClientDestroyed_afterFinish() {
        final HalClientMonitor.LazyDaemon<Object> nonNullDaemon = () -> mock(Object.class);
        final TestHalClientMonitor client =
                new TestHalClientMonitor(mContext, mToken, nonNullDaemon);
        mScheduler.scheduleClientMonitor(client);
        client.mCallback.onClientFinished(client, true /* success */);
        waitForIdle();
        assertTrue(client.mDestroyed);
    }

    private BiometricSchedulerProto getDump(boolean clearSchedulerBuffer) throws Exception {
        return BiometricSchedulerProto.parseFrom(mScheduler.dumpProtoState(clearSchedulerBuffer));
    }

    private static class TestAuthenticationClient extends AuthenticationClient<Object> {
        boolean mStartedHal = false;
        boolean mStoppedHal = false;
        boolean mDestroyed = false;
        int mNumCancels = 0;

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
            mStoppedHal = true;
        }

        @Override
        protected void startHalOperation() {
            mStartedHal = true;
        }

        @Override
        protected void handleLifecycleAfterAuth(boolean authenticated) {}

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

        TestEnrollClient(@NonNull Context context,
                @NonNull LazyDaemon<Object> lazyDaemon, @NonNull IBinder token,
                @NonNull ClientMonitorCallbackConverter listener) {
            super(context, lazyDaemon, token, listener, 0 /* userId */, new byte[69],
                    "test" /* owner */, mock(BiometricUtils.class),
                    5 /* timeoutSec */, 0 /* statsModality */, TEST_SENSOR_ID,
                    true /* shouldVibrate */);
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
                @NonNull LazyDaemon<Object> lazyDaemon) {
            this(context, token, lazyDaemon, 0 /* cookie */, BiometricsProto.CM_UPDATE_ACTIVE_USER);
        }

        TestHalClientMonitor(@NonNull Context context, @NonNull IBinder token,
                @NonNull LazyDaemon<Object> lazyDaemon, int cookie, int protoEnum) {
            super(context, lazyDaemon, token /* token */, null /* listener */, 0 /* userId */,
                    TAG, cookie, TEST_SENSOR_ID, 0 /* statsModality */,
                    0 /* statsAction */, 0 /* statsClient */);
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
        public void start(@NonNull Callback callback) {
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

    private void waitForIdle() {
        TestableLooper.get(this).processAllMessages();
    }
}
