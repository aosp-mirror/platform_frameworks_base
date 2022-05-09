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
import android.telephony.mbms.IMbmsGroupCallSessionCallback;
import android.telephony.mbms.IGroupCallCallback;

/**
 * @hide
 */
interface IMbmsGroupCallService
{
    int initialize(IMbmsGroupCallSessionCallback callback, int subId);

    void stopGroupCall(int subId, long tmgi);

    @SuppressWarnings(value={"untyped-collection"})
    void updateGroupCall(int subscriptionId, long tmgi, in List saiList,
        in List frequencyList);

    @SuppressWarnings(value={"untyped-collection"})
    int startGroupCall(int subscriptionId, long tmgi, in List saiList,
        in List frequencyList, IGroupCallCallback callback);

    void dispose(int subId);
}
