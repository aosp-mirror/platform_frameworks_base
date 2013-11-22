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

package android.hardware.camera2.utils;

import static android.hardware.camera2.CameraAccessException.CAMERA_DISABLED;
import static android.hardware.camera2.CameraAccessException.CAMERA_DISCONNECTED;
import static android.hardware.camera2.CameraAccessException.CAMERA_IN_USE;
import static android.hardware.camera2.CameraAccessException.MAX_CAMERAS_IN_USE;
import static android.hardware.camera2.CameraAccessException.CAMERA_DEPRECATED_HAL;

import android.os.DeadObjectException;
import android.os.RemoteException;

import java.lang.reflect.Method;

/**
 * Translate camera service status_t return values into exceptions.
 *
 * @see android.hardware.camera2.utils.CameraBinderDecorator#newInstance
 * @hide
 */
public class CameraBinderDecorator {

    public static final int NO_ERROR = 0;
    public static final int PERMISSION_DENIED = -1;
    public static final int ALREADY_EXISTS = -17;
    public static final int BAD_VALUE = -22;
    public static final int DEAD_OBJECT = -32;

    /**
     * TODO: add as error codes in Errors.h
     * - POLICY_PROHIBITS
     * - RESOURCE_BUSY
     * - NO_SUCH_DEVICE
     */
    public static final int EACCES = -13;
    public static final int EBUSY = -16;
    public static final int ENODEV = -19;
    public static final int EOPNOTSUPP = -95;
    public static final int EUSERS = -87;

    private static class CameraBinderDecoratorListener implements Decorator.DecoratorListener {

        @Override
        public void onBeforeInvocation(Method m, Object[] args) {
        }

        @Override
        public void onAfterInvocation(Method m, Object[] args, Object result) {
            // int return type => status_t => convert to exception
            if (m.getReturnType() == Integer.TYPE) {
                int returnValue = (Integer) result;

                switch (returnValue) {
                    case NO_ERROR:
                        return;
                    case PERMISSION_DENIED:
                        throw new SecurityException("Lacking privileges to access camera service");
                    case ALREADY_EXISTS:
                        // This should be handled at the call site. Typically this isn't bad,
                        // just means we tried to do an operation that already completed.
                        return;
                    case BAD_VALUE:
                        throw new IllegalArgumentException("Bad argument passed to camera service");
                    case DEAD_OBJECT:
                        UncheckedThrow.throwAnyException(new CameraRuntimeException(
                                CAMERA_DISCONNECTED));
                    case EACCES:
                        UncheckedThrow.throwAnyException(new CameraRuntimeException(
                                CAMERA_DISABLED));
                    case EBUSY:
                        UncheckedThrow.throwAnyException(new CameraRuntimeException(
                                CAMERA_IN_USE));
                    case EUSERS:
                        UncheckedThrow.throwAnyException(new CameraRuntimeException(
                                MAX_CAMERAS_IN_USE));
                    case ENODEV:
                        UncheckedThrow.throwAnyException(new CameraRuntimeException(
                                CAMERA_DISCONNECTED));
                    case EOPNOTSUPP:
                        UncheckedThrow.throwAnyException(new CameraRuntimeException(
                                CAMERA_DEPRECATED_HAL));
                }

                /**
                 * Trap the rest of the negative return values. If we have known
                 * error codes i.e. ALREADY_EXISTS that aren't really runtime
                 * errors, then add them to the top switch statement
                 */
                if (returnValue < 0) {
                    throw new UnsupportedOperationException(String.format("Unknown error %d",
                            returnValue));
                }
            }
        }

        @Override
        public boolean onCatchException(Method m, Object[] args, Throwable t) {

            if (t instanceof DeadObjectException) {
                UncheckedThrow.throwAnyException(new CameraRuntimeException(
                        CAMERA_DISCONNECTED,
                        "Process hosting the camera service has died unexpectedly",
                        t));
            } else if (t instanceof RemoteException) {
                throw new UnsupportedOperationException("An unknown RemoteException was thrown" +
                        " which should never happen.", t);
            }

            return false;
        }

        @Override
        public void onFinally(Method m, Object[] args) {
        }

    }

    /**
     * <p>
     * Wraps the type T with a proxy that will check 'status_t' return codes
     * from the native side of the camera service, and throw Java exceptions
     * automatically based on the code.
     * </p>
     * <p>
     * In addition it also rewrites binder's RemoteException into either a
     * CameraAccessException or an UnsupportedOperationException.
     * </p>
     * <p>
     * As a result of calling any method on the proxy, RemoteException is
     * guaranteed never to be thrown.
     * </p>
     *
     * @param obj object that will serve as the target for all method calls
     * @param <T> the type of the element you want to wrap. This must be an interface.
     * @return a proxy that will intercept all invocations to obj
     */
    public static <T> T newInstance(T obj) {
        return Decorator.<T> newInstance(obj, new CameraBinderDecoratorListener());
    }
}
