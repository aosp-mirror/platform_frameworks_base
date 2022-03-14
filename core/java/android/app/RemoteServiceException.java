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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Bundle;
import android.util.AndroidRuntimeException;

/**
 * Exception used by {@link ActivityThread} to crash an app process for an unknown cause.
 * An exception of this class is no longer supposed to be thrown. Instead, we use fine-grained
 * sub-exceptions.
 *
 * Subclasses must be registered in
 * {@link android.app.ActivityThread#throwRemoteServiceException(java.lang.String, int)}.
 *
 * @hide
 */
public class RemoteServiceException extends AndroidRuntimeException {
    public RemoteServiceException(String msg) {
        super(msg);
    }

    public RemoteServiceException(String msg, Throwable cause) {
        super(msg, cause);
    }

    /**
     * Exception used to crash an app process when it didn't call {@link Service#startForeground}
     * in time after the service was started with
     * {@link android.content.Context#startForegroundService}.
     *
     * @hide
     */
    public static class ForegroundServiceDidNotStartInTimeException extends RemoteServiceException {
        /** The type ID passed to {@link IApplicationThread#scheduleCrash}. */
        public static final int TYPE_ID = 1;

        private static final String KEY_SERVICE_CLASS_NAME = "serviceclassname";

        public ForegroundServiceDidNotStartInTimeException(String msg, Throwable cause) {
            super(msg, cause);
        }

        public static Bundle createExtrasForService(@NonNull ComponentName service) {
            Bundle b = new Bundle();
            b.putString(KEY_SERVICE_CLASS_NAME, service.getClassName());
            return b;
        }

        @Nullable
        public static String getServiceClassNameFromExtras(@Nullable Bundle extras) {
            return (extras == null) ? null : extras.getString(KEY_SERVICE_CLASS_NAME);
        }
    }

    /**
     * Exception used to crash an app process when the system received a RemoteException
     * while delivering a broadcast to an app process.
     *
     * @hide
     */
    public static class CannotDeliverBroadcastException extends RemoteServiceException {
        /** The type ID passed to {@link IApplicationThread#scheduleCrash}. */
        public static final int TYPE_ID = 2;

        public CannotDeliverBroadcastException(String msg) {
            super(msg);
        }
    }

    /**
     * Exception used to crash an app process when the system received a RemoteException
     * while posting a notification of a foreground service.
     *
     * @hide
     */
    public static class CannotPostForegroundServiceNotificationException
            extends RemoteServiceException {
        /** The type ID passed to {@link IApplicationThread#scheduleCrash}. */
        public static final int TYPE_ID = 3;

        public CannotPostForegroundServiceNotificationException(String msg) {
            super(msg);
        }
    }

    /**
     * Exception used to crash an app process when the system finds an error in a foreground service
     * notification.
     *
     * @hide
     */
    public static class BadForegroundServiceNotificationException extends RemoteServiceException {
        /** The type ID passed to {@link IApplicationThread#scheduleCrash}. */
        public static final int TYPE_ID = 4;

        public BadForegroundServiceNotificationException(String msg) {
            super(msg);
        }
    }

    /**
     * Exception used to crash an app process when it calls a setting activity that requires
     * the {@code REQUEST_PASSWORD_COMPLEXITY} permission.
     *
     * @hide
     */
    public static class MissingRequestPasswordComplexityPermissionException
            extends RemoteServiceException {
        /** The type ID passed to {@link IApplicationThread#scheduleCrash}. */
        public static final int TYPE_ID = 5;

        public MissingRequestPasswordComplexityPermissionException(String msg) {
            super(msg);
        }
    }

    /**
     * Exception used to crash an app process by {@code adb shell am crash}.
     *
     * @hide
     */
    public static class CrashedByAdbException extends RemoteServiceException {
        /** The type ID passed to {@link IApplicationThread#scheduleCrash}. */
        public static final int TYPE_ID = 6;

        public CrashedByAdbException(String msg) {
            super(msg);
        }
    }
}
