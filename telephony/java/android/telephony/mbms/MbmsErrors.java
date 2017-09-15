/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony.mbms;

import android.telephony.MbmsStreamingSession;

/** @hide */
public class MbmsErrors {
    /** Indicates that the operation was successful. */
    public static final int SUCCESS = 0;

    // Following errors are generated in the manager and should not be returned from the
    // middleware
    /**
     * Indicates that either no MBMS middleware app is installed on the device or multiple
     * middleware apps are installed on the device.
     */
    public static final int ERROR_NO_UNIQUE_MIDDLEWARE = 1;

    /**
     * Indicates that the app attempted to perform an operation on an instance of
     * {@link android.telephony.MbmsDownloadSession} or
     * {@link MbmsStreamingSession} without being bound to the middleware.
     */
    public static final int ERROR_MIDDLEWARE_NOT_BOUND = 2;

    /** Indicates that the middleware has died and the requested operation was not completed.*/
    public static final int ERROR_MIDDLEWARE_LOST = 3;

    /**
     * Indicates errors that may be generated during initialization by the
     * middleware. They are applicable to both streaming and file-download use-cases.
     */
    public static class InitializationErrors {
        private InitializationErrors() {}
        /**
         * Indicates that the app tried to create more than one instance each of
         * {@link MbmsStreamingSession} or {@link android.telephony.MbmsDownloadSession}.
         */
        public static final int ERROR_DUPLICATE_INITIALIZE = 101;
        /** Indicates that the app is not authorized to access media via MBMS.*/
        public static final int ERROR_APP_PERMISSIONS_NOT_GRANTED = 102;
        /** Indicates that the middleware was unable to initialize for this app. */
        public static final int ERROR_UNABLE_TO_INITIALIZE = 103;
    }

    /**
     * Indicates the errors that may occur at any point and are applicable to both
     * streaming and file-download.
     */
    public static class GeneralErrors {
        private GeneralErrors() {}
        /**
         * Indicates that the app attempted to perform an operation before receiving notification
         * that the middleware is ready via {@link MbmsStreamingSessionCallback#onMiddlewareReady()}
         * or {@link MbmsDownloadSessionCallback#onMiddlewareReady()}.
         */
        public static final int ERROR_MIDDLEWARE_NOT_YET_READY = 201;
        /**
         * Indicates that the middleware ran out of memory and was unable to complete the requested
         * operation.
         */
        public static final int ERROR_OUT_OF_MEMORY = 202;
        /**
         * Indicates that the requested operation failed due to the middleware being unavailable due
         * to a transient condition. The app may retry the operation at a later time.
         */
        public static final int ERROR_MIDDLEWARE_TEMPORARILY_UNAVAILABLE = 203;
        /**
         * Indicates that the requested operation was not performed due to being in emergency
         * callback mode.
         */
        public static final int ERROR_IN_E911 = 204;
        /** Indicates that MBMS is not available due to the device being in roaming. */
        public static final int ERROR_NOT_CONNECTED_TO_HOME_CARRIER_LTE = 205;
        /** Indicates that MBMS is not available due to a SIM read error. */
        public static final int ERROR_UNABLE_TO_READ_SIM = 206;
        /**
         * Indicates that MBMS is not available due to the inserted SIM being from an unsupported
         * carrier.
         */
        public static final int ERROR_CARRIER_CHANGE_NOT_ALLOWED = 207;
    }

    /**
     * Indicates the errors that are applicable only to the streaming use-case
     */
    public static class StreamingErrors {
        private StreamingErrors() {}
        /** Indicates that the middleware cannot start a stream due to too many ongoing streams */
        public static final int ERROR_CONCURRENT_SERVICE_LIMIT_REACHED = 301;

        /** Indicates that the middleware was unable to start the streaming service */
        public static final int ERROR_UNABLE_TO_START_SERVICE = 302;

        /**
         * Indicates that the app called
         * {@link MbmsStreamingSession#startStreaming(
         * StreamingServiceInfo, StreamingServiceCallback, android.os.Handler)}
         * more than once for the same {@link StreamingServiceInfo}.
         */
        public static final int ERROR_DUPLICATE_START_STREAM = 303;
    }

    /**
     * Indicates the errors that are applicable only to the file-download use-case
     */
    public static class DownloadErrors {
        private DownloadErrors() { }
        /**
         * Indicates that the app is not allowed to change the temp file root at this time due to
         * outstanding download requests.
         */
        public static final int ERROR_CANNOT_CHANGE_TEMP_FILE_ROOT = 401;

        /** Indicates that the middleware has no record of the supplied {@link DownloadRequest}. */
        public static final int ERROR_UNKNOWN_DOWNLOAD_REQUEST = 402;
    }

    private MbmsErrors() {}
}
