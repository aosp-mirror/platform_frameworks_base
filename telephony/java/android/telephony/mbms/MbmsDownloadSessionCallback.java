/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.telephony.mbms;

import android.annotation.IntDef;
import android.telephony.MbmsDownloadSession;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * A callback class that apps should use to receive information on file downloads over
 * cell-broadcast.
 */
public class MbmsDownloadSessionCallback {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            MbmsErrors.ERROR_NO_UNIQUE_MIDDLEWARE,
            MbmsErrors.ERROR_MIDDLEWARE_LOST,
            MbmsErrors.ERROR_MIDDLEWARE_NOT_BOUND,
            MbmsErrors.InitializationErrors.ERROR_APP_PERMISSIONS_NOT_GRANTED,
            MbmsErrors.InitializationErrors.ERROR_DUPLICATE_INITIALIZE,
            MbmsErrors.InitializationErrors.ERROR_UNABLE_TO_INITIALIZE,
            MbmsErrors.GeneralErrors.ERROR_MIDDLEWARE_NOT_YET_READY,
            MbmsErrors.GeneralErrors.ERROR_OUT_OF_MEMORY,
            MbmsErrors.GeneralErrors.ERROR_MIDDLEWARE_TEMPORARILY_UNAVAILABLE,
            MbmsErrors.GeneralErrors.ERROR_IN_E911,
            MbmsErrors.GeneralErrors.ERROR_NOT_CONNECTED_TO_HOME_CARRIER_LTE,
            MbmsErrors.GeneralErrors.ERROR_UNABLE_TO_READ_SIM,
            MbmsErrors.GeneralErrors.ERROR_CARRIER_CHANGE_NOT_ALLOWED,
            MbmsErrors.DownloadErrors.ERROR_CANNOT_CHANGE_TEMP_FILE_ROOT,
            MbmsErrors.DownloadErrors.ERROR_UNKNOWN_DOWNLOAD_REQUEST,
            MbmsErrors.DownloadErrors.ERROR_UNKNOWN_FILE_INFO}, prefix = { "ERROR_" })
    private @interface DownloadError{}

    /**
     * Indicates that the middleware has encountered an asynchronous error.
     * @param errorCode Any error code listed in {@link MbmsErrors}
     * @param message A message, intended for debugging purposes, describing the error in further
     *                detail.
     */
    public void onError(@DownloadError int errorCode, String message) {
        // default implementation empty
    }

    /**
     * Called to indicate published File Services have changed.
     *
     * This will only be called after the application has requested a list of file services and
     * specified a service class list of interest via
     * {@link MbmsDownloadSession#requestUpdateFileServices(List)}. If there are subsequent calls to
     * {@link MbmsDownloadSession#requestUpdateFileServices(List)},
     * this method may not be called again if
     * the list of service classes would remain the same.
     *
     * @param services The most recently updated list of available file services.
     */
    public void onFileServicesUpdated(List<FileServiceInfo> services) {
        // default implementation empty
    }

    /**
     * Called to indicate that the middleware has been initialized and is ready.
     *
     * Before this method is called, calling any method on an instance of
     * {@link MbmsDownloadSession} will result in an {@link IllegalStateException}
     * being thrown or {@link #onError(int, String)} being called with error code
     * {@link MbmsErrors.GeneralErrors#ERROR_MIDDLEWARE_NOT_YET_READY}
     */
    public void onMiddlewareReady() {
        // default implementation empty
    }
}
