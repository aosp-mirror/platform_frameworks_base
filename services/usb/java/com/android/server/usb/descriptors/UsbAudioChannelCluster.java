/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.usb.descriptors;

/**
 * @hide
 * Group of logical audio channels that carry tightly related synchronous audio information.
 * See Audio10.pdf section 3.7.2.3 Audio Channel Cluster format and Audio20.pdf section 3.13.1
 * audio channel cluster.
 */
public interface UsbAudioChannelCluster {
    /**
     * @return logical channels in the cluster.
     */
    byte getChannelCount();

    /**
     * @return a bit field that indicates which spatial locations are present in the cluster.
     */
    int getChannelConfig();

    /**
     * @return index to a string descriptor that describes the spatial location of the first
     *         non-predefined logical channel in the cluster.
     */
    byte getChannelNames();
}
