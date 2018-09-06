/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.telephony;


interface IAnas {

    /**
    * Enable or disable Alternative Network Access service.
    *
    * This method should be called to enable or disable
    * AlternativeNetworkAccess service on the device.
    *
    * <p>
    * Requires Permission:
    *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
    * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
    *
    * @param enable enable(True) or disable(False)
    * @param callingPackage caller's package name
    * @return returns true if successfully set.
    */
    boolean setEnable(boolean enable, String callingPackage);

    /**
     * is Alternative Network Access service enabled
     *
     * This method should be called to determine if the Alternative Network Access service is enabled
    *
    * <p>
    * Requires Permission:
    *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
    * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
    *
    * @param callingPackage caller's package name
    */
    boolean isEnabled(String callingPackage);
}
