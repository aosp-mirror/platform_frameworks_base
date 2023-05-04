/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.security.rkp;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.os.Binder;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.security.rkp.IGetKeyCallback;
import android.security.rkp.IStoreUpgradedKeyCallback;
import android.security.rkp.service.RegistrationProxy;
import android.security.rkp.service.RemotelyProvisionedKey;
import android.security.rkp.service.RkpProxyException;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.VoidAnswer4;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Build/Install/Run:
 * atest FrameworksServicesTests:RemoteProvisioningRegistrationTest
 */
@RunWith(AndroidJUnit4.class)
public class RemoteProvisioningRegistrationTest {
    private RegistrationProxy mRegistrationProxy;
    private RemoteProvisioningRegistration mRegistration;

    @Before
    public void setUp() {
        mRegistrationProxy = mock(RegistrationProxy.class);
        mRegistration = new RemoteProvisioningRegistration(mRegistrationProxy, Runnable::run);
    }

    // answerVoid wrapper with explicit types, avoiding long signatures when mocking getKeyAsync.
    static Answer<Void> answerGetKeyAsync(
            VoidAnswer4<Integer, CancellationSignal, Executor,
                    OutcomeReceiver<RemotelyProvisionedKey, Exception>> answer) {
        return answerVoid(answer);
    }

    // answerVoid wrapper for mocking storeUpgradeKeyAsync.
    static Answer<Void> answerStoreUpgradedKeyAsync(
            VoidAnswer4<byte[], byte[], Executor, OutcomeReceiver<Void, Exception>> answer) {
        return answerVoid(answer);
    }

    // matcher helper, making it easier to match the different key types
    private android.security.rkp.RemotelyProvisionedKey matches(
            RemotelyProvisionedKey expectedKey) {
        return argThat((android.security.rkp.RemotelyProvisionedKey key) ->
                Arrays.equals(key.keyBlob, expectedKey.getKeyBlob())
                        && Arrays.equals(key.encodedCertChain, expectedKey.getEncodedCertChain())
        );
    }

    @Test
    public void getKeySuccess() throws Exception {
        RemotelyProvisionedKey expectedKey = mock(RemotelyProvisionedKey.class);
        doAnswer(
                answerGetKeyAsync((keyId, cancelSignal, executor, receiver) ->
                        executor.execute(() -> receiver.onResult(expectedKey))))
                .when(mRegistrationProxy).getKeyAsync(eq(42), any(), any(), any());

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doReturn(new Binder()).when(callback).asBinder();
        mRegistration.getKey(42, callback);
        verify(callback).onSuccess(matches(expectedKey));
        verify(callback, atLeastOnce()).asBinder();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyHandlesArbitraryException() throws Exception {
        Exception expectedException = new Exception("oops!");
        doAnswer(
                answerGetKeyAsync((keyId, cancelSignal, executor, receiver) ->
                        executor.execute(() -> receiver.onError(expectedException))))
                .when(mRegistrationProxy).getKeyAsync(eq(0), any(), any(), any());
        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doReturn(new Binder()).when(callback).asBinder();
        mRegistration.getKey(0, callback);
        verify(callback).onError(eq(IGetKeyCallback.ErrorCode.ERROR_UNKNOWN), eq("oops!"));
        verify(callback, atLeastOnce()).asBinder();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyMapsRkpErrorsCorrectly() throws Exception {
        Map<Byte, Integer> expectedConversions = Map.of(
                IGetKeyCallback.ErrorCode.ERROR_UNKNOWN,
                RkpProxyException.ERROR_UNKNOWN,
                IGetKeyCallback.ErrorCode.ERROR_REQUIRES_SECURITY_PATCH,
                RkpProxyException.ERROR_REQUIRES_SECURITY_PATCH,
                IGetKeyCallback.ErrorCode.ERROR_PENDING_INTERNET_CONNECTIVITY,
                RkpProxyException.ERROR_PENDING_INTERNET_CONNECTIVITY,
                IGetKeyCallback.ErrorCode.ERROR_PERMANENT,
                RkpProxyException.ERROR_PERMANENT);

        for (Field errorField: IGetKeyCallback.ErrorCode.class.getFields()) {
            byte error = (Byte) errorField.get(null);
            Exception expectedException = new RkpProxyException(expectedConversions.get(error),
                    errorField.getName());
            doAnswer(
                    answerGetKeyAsync((keyId, cancelSignal, executor, receiver) ->
                            executor.execute(() -> receiver.onError(expectedException))))
                    .when(mRegistrationProxy).getKeyAsync(eq(0), any(), any(), any());
            IGetKeyCallback callback = mock(IGetKeyCallback.class);
            doReturn(new Binder()).when(callback).asBinder();
            mRegistration.getKey(0, callback);
            verify(callback).onError(eq(error), contains(errorField.getName()));
            verify(callback, atLeastOnce()).asBinder();
            verifyNoMoreInteractions(callback);
        }
    }

    @Test
    public void getKeyCancelDuringProxyOperation() throws Exception {
        final Binder theBinder = new Binder();
        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doReturn(theBinder).when(callback).asBinder();
        doAnswer(
                answerGetKeyAsync((keyId, cancelSignal, executor, receiver) -> {
                    // Use a different callback object to ensure that the callback equivalence
                    // relies on the actual IBinder object.
                    IGetKeyCallback differentCallback = mock(IGetKeyCallback.class);
                    doReturn(theBinder).when(differentCallback).asBinder();
                    mRegistration.cancelGetKey(differentCallback);
                    verify(differentCallback, atLeastOnce()).asBinder();
                    verifyNoMoreInteractions(differentCallback);
                    assertThat(cancelSignal.isCanceled()).isTrue();
                    executor.execute(() -> receiver.onError(new OperationCanceledException()));
                }))
                .when(mRegistrationProxy).getKeyAsync(eq(Integer.MAX_VALUE), any(), any(), any());

        mRegistration.getKey(Integer.MAX_VALUE, callback);
        verify(callback).onCancel();
        verify(callback, atLeastOnce()).asBinder();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void cancelGetKeyWithInvalidCallback() throws Exception {
        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doReturn(new Binder()).when(callback).asBinder();
        assertThrows(IllegalArgumentException.class, () -> mRegistration.cancelGetKey(callback));
    }

    @Test
    public void getKeyRejectsDuplicateCallback() throws Exception {
        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doReturn(new Binder()).when(callback).asBinder();
        doAnswer(
                answerGetKeyAsync((keyId, cancelSignal, executor, receiver) -> {
                    assertThrows(IllegalArgumentException.class, () ->
                            mRegistration.getKey(0, callback));
                    executor.execute(() -> receiver.onResult(mock(RemotelyProvisionedKey.class)));
                }))
                .when(mRegistrationProxy).getKeyAsync(anyInt(), any(), any(), any());

        mRegistration.getKey(0, callback);
        verify(callback, times(1)).onSuccess(any());
        verify(callback, atLeastOnce()).asBinder();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyCancelAfterCompleteFails() throws Exception {
        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doReturn(new Binder()).when(callback).asBinder();
        doAnswer(
                answerGetKeyAsync((keyId, cancelSignal, executor, receiver) ->
                        executor.execute(() ->
                                receiver.onResult(mock(RemotelyProvisionedKey.class))
                        )))
                .when(mRegistrationProxy).getKeyAsync(eq(Integer.MIN_VALUE), any(), any(), any());

        mRegistration.getKey(Integer.MIN_VALUE, callback);
        verify(callback).onSuccess(any());
        assertThrows(IllegalArgumentException.class, () -> mRegistration.cancelGetKey(callback));
        verify(callback, atLeastOnce()).asBinder();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyCatchesExceptionFromProxy() throws Exception {
        Exception expectedException = new RuntimeException("oops! bad input!");
        doThrow(expectedException)
                .when(mRegistrationProxy)
                .getKeyAsync(anyInt(), any(), any(), any());

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doReturn(new Binder()).when(callback).asBinder();
        mRegistration.getKey(0, callback);
        verify(callback).onError(eq(IGetKeyCallback.ErrorCode.ERROR_UNKNOWN),
                eq(expectedException.getMessage()));
        assertThrows(IllegalArgumentException.class, () -> mRegistration.cancelGetKey(callback));
        verify(callback, atLeastOnce()).asBinder();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void storeUpgradedKeySuccess() throws Exception {
        doAnswer(
                answerStoreUpgradedKeyAsync((oldBlob, newBlob, executor, receiver) ->
                        executor.execute(() -> receiver.onResult(null))))
                .when(mRegistrationProxy)
                .storeUpgradedKeyAsync(any(), any(), any(), any());

        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        doReturn(new Binder()).when(callback).asBinder();
        mRegistration.storeUpgradedKeyAsync(new byte[0], new byte[0], callback);
        verify(callback).onSuccess();
        verify(callback, atLeastOnce()).asBinder();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void storeUpgradedKeyFails() throws Exception {
        final String errorString = "this is a failure";
        doAnswer(
                answerStoreUpgradedKeyAsync((oldBlob, newBlob, executor, receiver) ->
                        executor.execute(() -> receiver.onError(new RemoteException(errorString)))))
                .when(mRegistrationProxy)
                .storeUpgradedKeyAsync(any(), any(), any(), any());

        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        doReturn(new Binder()).when(callback).asBinder();
        mRegistration.storeUpgradedKeyAsync(new byte[0], new byte[0], callback);
        verify(callback).onError(errorString);
        verify(callback, atLeastOnce()).asBinder();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void storeUpgradedKeyHandlesException() throws Exception {
        final String errorString = "all aboard the failboat, toot toot";
        doThrow(new IllegalArgumentException(errorString))
                .when(mRegistrationProxy)
                .storeUpgradedKeyAsync(any(), any(), any(), any());

        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        doReturn(new Binder()).when(callback).asBinder();
        mRegistration.storeUpgradedKeyAsync(new byte[0], new byte[0], callback);
        verify(callback).onError(errorString);
        verify(callback, atLeastOnce()).asBinder();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void storeUpgradedKeyDuplicateCallback() throws Exception {
        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        doReturn(new Binder()).when(callback).asBinder();

        doAnswer(
                answerStoreUpgradedKeyAsync((oldBlob, newBlob, executor, receiver) -> {
                    assertThrows(IllegalArgumentException.class,
                            () -> mRegistration.storeUpgradedKeyAsync(new byte[0], new byte[0],
                                    callback));
                    executor.execute(() -> receiver.onResult(null));
                }))
                .when(mRegistrationProxy)
                .storeUpgradedKeyAsync(any(), any(), any(), any());

        mRegistration.storeUpgradedKeyAsync(new byte[0], new byte[0], callback);
        verify(callback).onSuccess();
        verify(callback, atLeastOnce()).asBinder();
        verifyNoMoreInteractions(callback);
    }

}
