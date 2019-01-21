/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.shared;

import android.annotation.Nullable;
import android.net.Network;
import android.net.NetworkParcelable;

/**
 * Utility methods to convert to/from stable AIDL parcelables for network attribute classes.
 * @hide
 */
public final class NetworkParcelableUtil {
    /**
     * Convert from a Network to a NetworkParcelable.
     */
    public static NetworkParcelable toStableParcelable(@Nullable Network network) {
        if (network == null) {
            return null;
        }
        final NetworkParcelable p = new NetworkParcelable();
        p.networkHandle = network.getNetworkHandle();

        return p;
    }

    /**
     * Convert from a NetworkParcelable to a Network.
     */
    public static Network fromStableParcelable(@Nullable NetworkParcelable p) {
        if (p == null) {
            return null;
        }
        return Network.fromNetworkHandle(p.networkHandle);
    }
}
