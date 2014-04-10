/* Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
 *
 * Not a Contribution, Apache license notifications and license are retained
 * for attribution purposes only.
 *
 * Copyright (C) 2007 The Android Open Source Project
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

import android.location.GeoFenceParams;
import android.app.PendingIntent;

/**
 * {@hide}
 */
interface IGeoFencer {
    boolean setGeoFence(in IBinder who, in GeoFenceParams params);
    void clearGeoFence(in IBinder who, in PendingIntent fence);
    void clearGeoFenceUser(int uid);
}
