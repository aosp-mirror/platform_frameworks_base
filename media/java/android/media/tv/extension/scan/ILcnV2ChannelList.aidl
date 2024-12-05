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

import android.media.tv.extension.scan.ILcnV2ChannelListListener;
import android.os.Bundle;

/**
 * Country: (NorDig etc.)
 * Broadcast Type: BROADCAST_TYPE_DVB_T, BROADCAST_TYPE_DVB_C
 *
 * @hide
 */
interface ILcnV2ChannelList {
    // Get the LCN V2 channel list information. If there are no conflicts, the array of Bundle is empty.
    Bundle[] getLcnV2ChannelLists();
    // Select and set one of two or more LCN V2 channel list detected by the service scan.
    int setLcnV2ChannelList(in Bundle lcnV2ChannelListSettings);
    // Set the listener to be invoked when two or more LCN V2 channel list are detected.
    int setListener(in ILcnV2ChannelListListener listener);
}
