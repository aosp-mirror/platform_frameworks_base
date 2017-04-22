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

/**
 * A optional listener class used by download clients to track progress.
 * @hide
 */
public class DownloadListener extends IDownloadListener.Stub {
    /**
     * Gives process callbacks for a given DownloadRequest.
     * request indicates which download is being referenced.
     * fileInfo gives information about the file being downloaded.  Note that
     *   the request may result in many files being downloaded and the client
     *   may not have been able to get a list of them in advance.
     * downloadSize is the final amount to be downloaded.  This may be different
     *   from the decoded final size, but is useful in gauging download progress.
     * currentSize is the amount currently downloaded.
     * decodedPercent is the percent from 0 to 100 of the file decoded.  After the
     *   download completes the contents needs to be processed.  It is perhaps
     *   uncompressed, transcoded and/or decrypted.  Generally the download completes
     *   before the decode is started, but that's not required.
     */
    public void progress(DownloadRequest request, FileInfo fileInfo,
            int downloadSize, int currentSize, int decodedPercent) {
    }
}
