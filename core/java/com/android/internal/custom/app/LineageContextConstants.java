/**
 * Copyright (c) 2015, The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.custom.app;

import android.annotation.SdkConstant;

/**
 * @hide
 * TODO: We need to somehow make these managers accessible via getSystemService
 */
public final class LineageContextConstants {

    /**
     * @hide
     */
    private LineageContextConstants() {
        // Empty constructor
    }

    /**
     * Use with {@link android.content.Context#getSystemService} to retrieve a
     * {@link com.android.internal.custom.LineageHardwareManager} to manage the extended
     * hardware features of the device.
     *
     * @see android.content.Context#getSystemService
     * @see com.android.internal.custom.LineageHardwareManager
     *
     * @hide
     */
    public static final String LINEAGE_HARDWARE_SERVICE = "lineagehardware";

    /**
     * Manages display color adjustments
     *
     * @hide
     */
    public static final String LINEAGE_LIVEDISPLAY_SERVICE = "lineagelivedisplay";

}
