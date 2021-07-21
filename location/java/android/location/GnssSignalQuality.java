/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.location;

import android.server.location.ServerLocationProtoEnums;

/**
 * Gnss signal quality information.
 *
 * @hide
 */
public interface GnssSignalQuality {

    int GNSS_SIGNAL_QUALITY_UNKNOWN =
            ServerLocationProtoEnums.GPS_SIGNAL_QUALITY_UNKNOWN; // -1

    int GNSS_SIGNAL_QUALITY_POOR =
            ServerLocationProtoEnums.GPS_SIGNAL_QUALITY_POOR; // 0

    int GNSS_SIGNAL_QUALITY_GOOD =
            ServerLocationProtoEnums.GPS_SIGNAL_QUALITY_GOOD; // 1

    int NUM_GNSS_SIGNAL_QUALITY_LEVELS = GNSS_SIGNAL_QUALITY_GOOD + 1;
}
