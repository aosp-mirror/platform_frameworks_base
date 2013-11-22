/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.mediaframeworktest.unit;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.utils.CameraBinderDecorator;
import android.hardware.camera2.utils.CameraRuntimeException;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.os.TransactionTooLargeException;
import android.test.suitebuilder.annotation.SmallTest;

import static org.mockito.Mockito.*;
import static android.hardware.camera2.utils.CameraBinderDecorator.*;
import static android.hardware.camera2.CameraAccessException.*;

import junit.framework.Assert;

public class CameraUtilsBinderDecoratorTest extends junit.framework.TestCase {

    private interface ICameraBinderStereotype {

        double doNothing();

        // int is a 'status_t'
        int doSomethingPositive();

        int doSomethingNoError();

        int doSomethingPermissionDenied();

        int doSomethingAlreadyExists();

        int doSomethingBadValue();

        int doSomethingDeadObject() throws CameraRuntimeException;

        int doSomethingBadPolicy() throws CameraRuntimeException;

        int doSomethingDeviceBusy() throws CameraRuntimeException;

        int doSomethingNoSuchDevice() throws CameraRuntimeException;

        int doSomethingUnknownErrorCode();

        int doSomethingThrowDeadObjectException() throws RemoteException;

        int doSomethingThrowTransactionTooLargeException() throws RemoteException;
    }

    private static final double SOME_ARBITRARY_DOUBLE = 1.0;
    private static final int SOME_ARBITRARY_POSITIVE_INT = 5;
    private static final int SOME_ARBITRARY_NEGATIVE_INT = -0xC0FFEE;

    @SmallTest
    public void testStereotypes() {

        ICameraBinderStereotype mock = mock(ICameraBinderStereotype.class);
        try {
            when(mock.doNothing()).thenReturn(SOME_ARBITRARY_DOUBLE);
            when(mock.doSomethingPositive()).thenReturn(SOME_ARBITRARY_POSITIVE_INT);
            when(mock.doSomethingNoError()).thenReturn(NO_ERROR);
            when(mock.doSomethingPermissionDenied()).thenReturn(PERMISSION_DENIED);
            when(mock.doSomethingAlreadyExists()).thenReturn(ALREADY_EXISTS);
            when(mock.doSomethingBadValue()).thenReturn(BAD_VALUE);
            when(mock.doSomethingDeadObject()).thenReturn(DEAD_OBJECT);
            when(mock.doSomethingBadPolicy()).thenReturn(EACCES);
            when(mock.doSomethingDeviceBusy()).thenReturn(EBUSY);
            when(mock.doSomethingNoSuchDevice()).thenReturn(ENODEV);
            when(mock.doSomethingUnknownErrorCode()).thenReturn(SOME_ARBITRARY_NEGATIVE_INT);
            when(mock.doSomethingThrowDeadObjectException()).thenThrow(new DeadObjectException());
            when(mock.doSomethingThrowTransactionTooLargeException()).thenThrow(
                    new TransactionTooLargeException());
        } catch (RemoteException e) {
            Assert.fail("Unreachable");
        }

        ICameraBinderStereotype decoratedMock = CameraBinderDecorator.newInstance(mock);

        // ignored by decorator because return type is double, not int
        assertEquals(SOME_ARBITRARY_DOUBLE, decoratedMock.doNothing());

        // pass through for positive values
        assertEquals(SOME_ARBITRARY_POSITIVE_INT, decoratedMock.doSomethingPositive());

        // pass through NO_ERROR
        assertEquals(NO_ERROR, decoratedMock.doSomethingNoError());

        try {
            decoratedMock.doSomethingPermissionDenied();
            Assert.fail("Should've thrown SecurityException");
        } catch (SecurityException e) {
        }

        assertEquals(ALREADY_EXISTS, decoratedMock.doSomethingAlreadyExists());

        try {
            decoratedMock.doSomethingBadValue();
            Assert.fail("Should've thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
        }

        try {
            decoratedMock.doSomethingDeadObject();
            Assert.fail("Should've thrown CameraRuntimeException");
        } catch (CameraRuntimeException e) {
            assertEquals(CAMERA_DISCONNECTED, e.getReason());
        }

        try {
            decoratedMock.doSomethingBadPolicy();
            Assert.fail("Should've thrown CameraRuntimeException");
        } catch (CameraRuntimeException e) {
            assertEquals(CAMERA_DISABLED, e.getReason());
        }

        try {
            decoratedMock.doSomethingDeviceBusy();
            Assert.fail("Should've thrown CameraRuntimeException");
        } catch (CameraRuntimeException e) {
            assertEquals(CAMERA_IN_USE, e.getReason());
        }

        try {
            decoratedMock.doSomethingNoSuchDevice();
            Assert.fail("Should've thrown CameraRuntimeException");
        } catch (CameraRuntimeException e) {
            assertEquals(CAMERA_DISCONNECTED, e.getReason());
        }

        try {
            decoratedMock.doSomethingUnknownErrorCode();
            Assert.fail("Should've thrown UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertEquals(String.format("Unknown error %d",
                    SOME_ARBITRARY_NEGATIVE_INT), e.getMessage());
        }

        try {
            decoratedMock.doSomethingThrowDeadObjectException();
            Assert.fail("Should've thrown CameraRuntimeException");
        } catch (CameraRuntimeException e) {
            assertEquals(CAMERA_DISCONNECTED, e.getReason());
        } catch (RemoteException e) {
            Assert.fail("Should not throw a DeadObjectException directly, but rethrow");
        }

        try {
            decoratedMock.doSomethingThrowTransactionTooLargeException();
            Assert.fail("Should've thrown UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getCause() instanceof TransactionTooLargeException);
        } catch (RemoteException e) {
            Assert.fail("Should not throw a TransactionTooLargeException directly, but rethrow");
        }
    }

}
