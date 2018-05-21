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
 */
public class DownloadStatusListener {
    /**
     * Gives download status callbacks for a file in a {@link DownloadRequest}.
     *
     * @param request a {@link DownloadRequest}, indicating which download is being referenced.
     * @param fileInfo a {@link FileInfo} specifying the file to report progress on.  Note that
     *   the request may result in many files being downloaded and the client
     *   may not have been able to get a list of them in advance.
     * @param status The current status of the download.
     */
    public void onStatusUpdated(DownloadRequest request, FileInfo fileInfo,
            @MbmsDownloadSession.DownloadStatus int status) {
    }
}
