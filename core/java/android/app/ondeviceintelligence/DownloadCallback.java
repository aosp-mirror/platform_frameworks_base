/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence;

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.PersistableBundle;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Callback functions used for feature downloading via the
 * {@link OnDeviceIntelligenceManager#requestFeatureDownload}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public interface DownloadCallback {
    int DOWNLOAD_FAILURE_STATUS_UNKNOWN = 0;

    /**
     * Sent when feature download could not succeed due to there being no available disk space on
     * the device.
     */
    int DOWNLOAD_FAILURE_STATUS_NOT_ENOUGH_DISK_SPACE = 1;

    /**
     * Sent when feature download could not succeed due to a network error.
     */
    int DOWNLOAD_FAILURE_STATUS_NETWORK_FAILURE = 2;

    /**
     * Sent when feature download has been initiated already, hence no need to request download
     * again. Caller can query {@link OnDeviceIntelligenceManager#getFeatureStatus} to check if
     * download has been completed.
     */
    int DOWNLOAD_FAILURE_STATUS_DOWNLOADING = 3;

    /**
     * Sent when feature download did not start due to errors (e.g. remote exception of features not
     * available). Caller can query {@link OnDeviceIntelligenceManager#getFeatureStatus} to check
     * if feature-status is {@link FeatureDetails#FEATURE_STATUS_DOWNLOADABLE}.
     */
    int DOWNLOAD_FAILURE_STATUS_UNAVAILABLE = 4;

    /** @hide */
    @IntDef(value = {
            DOWNLOAD_FAILURE_STATUS_UNKNOWN,
            DOWNLOAD_FAILURE_STATUS_NOT_ENOUGH_DISK_SPACE,
            DOWNLOAD_FAILURE_STATUS_NETWORK_FAILURE,
            DOWNLOAD_FAILURE_STATUS_DOWNLOADING,
            DOWNLOAD_FAILURE_STATUS_UNAVAILABLE
    }, open = true)
    @Retention(RetentionPolicy.SOURCE)
    @interface DownloadFailureStatus {
    }

    /**
     * Called when model download started properly.
     *
     * @param bytesToDownload the total bytes to be downloaded for this {@link Feature}
     */
    default void onDownloadStarted(long bytesToDownload) {
    }

    /**
     * Called when model download failed.
     *
     * @param failureStatus the download failure status
     * @param errorMessage  the error message associated with the download failure
     */
    void onDownloadFailed(
            @DownloadFailureStatus int failureStatus,
            @Nullable String errorMessage,
            @NonNull PersistableBundle errorParams);

    /**
     * Called when model download is in progress.
     *
     * @param totalBytesDownloaded the already downloaded bytes for this {@link Feature}
     */
    default void onDownloadProgress(long totalBytesDownloaded) {
    }

    /**
     * Called when model download is completed. The remote implementation can populate any
     * associated download params like file stats etc. in this callback to inform the client.
     *
     * @param downloadParams params containing info about the completed download.
     */
    void onDownloadCompleted(@NonNull PersistableBundle downloadParams);
}
