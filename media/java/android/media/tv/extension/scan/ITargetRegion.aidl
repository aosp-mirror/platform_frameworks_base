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

import android.media.tv.extension.scan.ITargetRegionListener;

import android.os.Bundle;

/**
 * Country: U.K.
 * Broadcast Type: BROADCAST_TYPE_DVB_T
 *
 * @hide
 */
interface ITargetRegion {
    // Get the target regions information. If there are no conflicts, the array of Bundle is empty.
    Bundle[] getTargetRegions();
    // Select and set one of two or more target region detected by the service scan.
    int setTargetRegion(in Bundle targetRegionSettings);
    // Set the listener to be invoked when two or more regions are detected.
    int setListener(in ITargetRegionListener listener);
}
