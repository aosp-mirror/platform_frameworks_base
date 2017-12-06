/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.lowpan;

import android.net.IpPrefix;
import android.net.lowpan.LowpanIdentity;

/** {@hide} */
interface ILowpanInterfaceListener {
    oneway void onEnabledChanged(boolean value);

    oneway void onConnectedChanged(boolean value);

    oneway void onUpChanged(boolean value);

    oneway void onRoleChanged(@utf8InCpp String value);

    oneway void onStateChanged(@utf8InCpp String value);

    oneway void onLowpanIdentityChanged(in LowpanIdentity value);

    oneway void onLinkNetworkAdded(in IpPrefix value);

    oneway void onLinkNetworkRemoved(in IpPrefix value);

    oneway void onLinkAddressAdded(@utf8InCpp String value);

    oneway void onLinkAddressRemoved(@utf8InCpp String value);

    oneway void onReceiveFromCommissioner(in byte[] packet);
}
