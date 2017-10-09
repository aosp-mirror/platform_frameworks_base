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

/**
 * A optional listener class used by download clients to track progress. Apps should extend this
 * class and pass an instance into
 * {@link MbmsDownloadSession#download(DownloadRequest)}
 *
 * This is optionally specified when requesting a download and will only be called while the app
 * is running.
 * @hide
 */
public class DownloadStateCallback {

    /**
     * Bitmask flags used for filtering out callback methods. Used when constructing the
     * DownloadStateCallback as an optional parameter.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ALL_UPDATES, PROGRESS_UPDATES, STATE_UPDATES})
    public @interface FilterFlag {}

    /**
     * Receive all callbacks.
     * Default value.
     */
    public static final int ALL_UPDATES = 0x00;
    /**
     * Receive callbacks for {@link #onProgressUpdated}.
     */
    public static final int PROGRESS_UPDATES = 0x01;
    /**
     * Receive callbacks for {@link #onStateUpdated}.
     */
    public static final int STATE_UPDATES = 0x02;

    private final int mCallbackFilterFlags;

    /**
     * Creates a DownloadStateCallback that will receive all callbacks.
     */
    public DownloadStateCallback() {
        mCallbackFilterFlags = ALL_UPDATES;
    }

    /**
     * Creates a DownloadStateCallback that will only receive callbacks for the methods specified
     * via the filterFlags parameter.
     * @param filterFlags A bitmask of filter flags that will specify which callback this instance
     *     is interested in.
     */
    public DownloadStateCallback(int filterFlags) {
        mCallbackFilterFlags = filterFlags;
    }

    /**
     * Return the currently set filter flags.
     * @return An integer containing the bitmask of flags that this instance is interested in.
     * @hide
     */
    public int getCallbackFilterFlags() {
        return mCallbackFilterFlags;
    }

    /**
     * Returns true if a filter flag is set for a particular callback method. If the flag is set,
     * the callback will be delivered to the listening process.
     * @param flag A filter flag specifying whether or not a callback method is registered to
     *     receive callbacks.
     * @return true if registered to receive callbacks in the listening process, false if not.
     */
    public final boolean isFilterFlagSet(@FilterFlag int flag) {
        if (mCallbackFilterFlags == ALL_UPDATES) {
            return true;
        }
        return (mCallbackFilterFlags & flag) > 0;
    }

    /**
     * Called when the middleware wants to report progress for a file in a {@link DownloadRequest}.
     *
     * @param request a {@link DownloadRequest}, indicating which download is being referenced.
     * @param fileInfo a {@link FileInfo} specifying the file to report progress on.  Note that
     *   the request may result in many files being downloaded and the client
     *   may not have been able to get a list of them in advance.
     * @param currentDownloadSize is the current amount downloaded.
     * @param fullDownloadSize is the total number of bytes that make up the downloaded content.
     *   This may be different from the decoded final size, but is useful in gauging download
     *   progress.
     * @param currentDecodedSize is the number of bytes that have been decoded.
     * @param fullDecodedSize is the total number of bytes that make up the final decoded content.
     */
    public void onProgressUpdated(DownloadRequest request, FileInfo fileInfo,
            int currentDownloadSize, int fullDownloadSize,
            int currentDecodedSize, int fullDecodedSize) {
    }

    /**
     * Gives download state callbacks for a file in a {@link DownloadRequest}.
     *
     * @param request a {@link DownloadRequest}, indicating which download is being referenced.
     * @param fileInfo a {@link FileInfo} specifying the file to report progress on.  Note that
     *   the request may result in many files being downloaded and the client
     *   may not have been able to get a list of them in advance.
     * @param state The current state of the download.
     */
    public void onStateUpdated(DownloadRequest request, FileInfo fileInfo,
            @MbmsDownloadSession.DownloadStatus int state) {
    }
}
