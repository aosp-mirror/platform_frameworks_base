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

package android.net.wifi.hotspot2;

import android.os.Handler;

/**
 * Base class for provisioning callbacks. Should be extended by applications and set when calling
 * {@link WifiManager#startSubscriptionProvisiong(OsuProvider, ProvisioningCallback, Handler)}.
 *
 * @hide
 */
public abstract class ProvisioningCallback {

    /**
     * Provisioning status for OSU failure
     * @param status indicates error condition
     */
    public abstract void onProvisioningFailure(int status);

    /**
     * Provisioning status when OSU is in progress
     * @param status indicates status of OSU flow
     */
    public abstract void onProvisioningStatus(int status);
}

