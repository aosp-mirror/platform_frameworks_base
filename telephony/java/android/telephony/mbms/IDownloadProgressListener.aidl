/*
** Copyright 2017, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.telephony.mbms;

import android.telephony.mbms.DownloadRequest;
import android.telephony.mbms.FileInfo;

/**
 * The optional interface used by download clients to track progress.
 * @hide
 */
interface IDownloadProgressListener
{
    /**
     * Gives progress callbacks for a given DownloadRequest.  Includes a FileInfo
     * as the list of files may not have been known at request-time.
     */
    void onProgressUpdated(in DownloadRequest request, in FileInfo fileInfo,
            int currentDownloadSize, int fullDownloadSize,
            int currentDecodedSize, int fullDecodedSize);
}
