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

import android.telephony.mbms.FileServiceInfo;

import java.util.List;

/**
 * The interface the clients top-level file download listener will satisfy.
 * @hide
 */
interface IMbmsDownloadManagerListener
{
    void error(int errorCode, String message);

    /**
     * Called to indicate published File Services have changed.
     *
     * This will only be called after the application has requested
     * a list of file services and specified a service class list
     * of interest AND the results of a subsequent getFileServices
     * call with the same service class list would
     * return different
     * results.
     */
    void fileServicesUpdated(in List<FileServiceInfo> services);
}
