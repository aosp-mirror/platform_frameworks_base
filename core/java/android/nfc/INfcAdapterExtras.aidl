/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.nfc;

import android.os.Bundle;


/**
 * {@hide}
 */
interface INfcAdapterExtras {
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    Bundle open(in String pkg, IBinder b);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    Bundle close(in String pkg, IBinder b);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    Bundle transceive(in String pkg, in byte[] data_in);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    int getCardEmulationRoute(in String pkg);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setCardEmulationRoute(in String pkg, int route);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void authenticate(in String pkg, in byte[] token);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    String getDriverName(in String pkg);
}
