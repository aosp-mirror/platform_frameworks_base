/*
** Copyright 2007, The Android Open Source Project
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

package com.android.internal.telephony;

import android.os.IBinder;

/**
 * A request to open or close a logical channel to the ICC card.
 *
 * @hide
 */
@JavaDerive(toString=true, equals=true)
parcelable IccLogicalChannelRequest {

    /** Subscription id. */
    int subId = -1;

    /** Physical slot index of the ICC card. */
    int slotIndex = -1;

    /** The unique index referring to a port belonging to the ICC card slot. */
    int portIndex = 0;

    /** Package name for the calling app, used only when open channel. */
    @nullable String callingPackage;

    /** Application id, used only when open channel. */
    @nullable String aid;

    /** The P2 parameter described in ISO 7816-4, used only when open channel. */
    int p2 = 0;

    /** Channel number */
    int channel = -1;

    /** A IBinder object for server side to check if the request client is still living. */
    @nullable IBinder binder;
}
