/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv.extension.scan;

import android.media.tv.extension.scan.IFavoriteNetworkListener;
import android.os.Bundle;

/**
 * Country: Norway
 * Broadcast Type: BROADCAST_TYPE_DVB_T
 * (Operator: RiksTV)
 *
 * @hide
 */
interface IFavoriteNetwork {
    // Get the favorite network information,If there are no conflicts, the array of Bundle is empty.
    Bundle[] getFavoriteNetworks();
    // Select and set one of two or more favorite networks detected by the service scan.
    int setFavoriteNetwork(in Bundle favoriteNetworkSettings);
    // Set the listener to be invoked when two or more favorite networks are detected.
    int setListener(in IFavoriteNetworkListener listener);
}
