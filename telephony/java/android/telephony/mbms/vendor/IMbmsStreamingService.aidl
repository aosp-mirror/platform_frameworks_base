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

package android.telephony.mbms.vendor;

import android.net.Uri;
import android.telephony.mbms.IMbmsStreamingManagerCallback;
import android.telephony.mbms.IStreamingServiceCallback;
import android.telephony.mbms.StreamingServiceInfo;

/**
 * The interface the opaque MbmsStreamingService will satisfy.
 * @hide
 */
interface IMbmsStreamingService
{
    int initialize(IMbmsStreamingManagerCallback listener, String appName, int subId);

    int getStreamingServices(String appName, int subId, in List<String> serviceClasses);

    int startStreaming(String appName, int subId, String serviceId,
            IStreamingServiceCallback listener);

    /**
     * Per-stream api.  Note each specifies what stream they apply to.
     */

    Uri getPlaybackUri(String appName, int subId, String serviceId);

    void stopStreaming(String appName, int subId, String serviceId);

    void disposeStream(String appName, int subId, String serviceId);

    /**
     * End of life for all MbmsStreamingManager's created by this uid/appName/subId.
     * Ends any streams run under this uid/appname/subId and calls the disposed methods
     * an callbacks registered for this uid/appName/subId and the disposed methods on any
     * listeners registered with startStreaming.
     */
    void dispose(String appName, int subId);
}
