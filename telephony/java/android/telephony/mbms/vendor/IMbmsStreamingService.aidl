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
import android.telephony.mbms.IMbmsStreamingManagerListener;
import android.telephony.mbms.IStreamingServiceListener;
import android.telephony.mbms.StreamingService;
import android.telephony.mbms.StreamingServiceInfo;
import android.telephony.SignalStrength;

/**
 * The interface the opaque MbmsStreamingService will satisfy.
 * @hide
 */
interface IMbmsStreamingService
{
    /**
     * Initialize streaming service
     * Registers this listener, subId with this appName
     *
     */
    int initialize(IMbmsStreamingManagerListener listener, String appName, int subId);


    /**
     * - Registers serviceClasses of interest with the uid/appName/subId key.
     * - Starts asynch fetching data on streaming services of matching classes to be reported
     * later by callback.
     *
     * Note that subsequent calls with the same callback, appName, subId and uid will replace
     * the service class list.
     */
    int getStreamingServices(String appName, int subId, in List<String> serviceClasses);

    /**
     * - Starts streaming the serviceId given.
     * - if the uid/appName/subId don't match a previously registered callback an error will
     *   be returned
     * - Streaming status will be sent via the included listener, including an initial
     *   URL-change and State-change pair.
     */
    StreamingService startStreaming(String appName, int subId, String serviceId,
            IStreamingServiceListener listener);

    /**
     * Asynchronously fetches all Services being streamed by this uid/appName/subId.
     */
    int getActiveStreamingServices(String appName, int subId);


    /**
     * Per-stream api.  Note each specifies what stream they apply to.
     */

    Uri getPlaybackUri(String appName, int subId, String serviceId);

    void switchStreams(String appName, int subId, String oldServiceId, String newServiceId);

    int getState(String appName, int subId, String serviceId);

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
