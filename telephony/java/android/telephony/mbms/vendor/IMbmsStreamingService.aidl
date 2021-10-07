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
import android.telephony.mbms.IMbmsStreamingSessionCallback;
import android.telephony.mbms.IStreamingServiceCallback;
import android.telephony.mbms.StreamingServiceInfo;

/**
 * @hide
 */
interface IMbmsStreamingService
{
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    int initialize(IMbmsStreamingSessionCallback callback, int subId);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    int requestUpdateStreamingServices(int subId, in List<String> serviceClasses);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    int startStreaming(int subId, String serviceId,
            IStreamingServiceCallback callback);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    Uri getPlaybackUri(int subId, String serviceId);

    void stopStreaming(int subId, String serviceId);

    void dispose(int subId);
}
