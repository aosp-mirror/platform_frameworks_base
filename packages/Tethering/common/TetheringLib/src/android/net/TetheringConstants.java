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

package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.SystemApi;
import android.os.ResultReceiver;

/**
 * Collections of constants for internal tethering usage.
 *
 * <p>These hidden constants are not in TetheringManager as they are not part of the API stubs
 * generated for TetheringManager, which prevents the tethering module from linking them at
 * build time.
 * TODO: investigate changing the tethering build rules so that Tethering can reference hidden
 * symbols from framework-tethering even when they are in a non-hidden class.
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class TetheringConstants {
    /** An explicit private class to avoid exposing constructor.*/
    private TetheringConstants() { }

    /**
     * Extra used for communicating with the TetherService. Includes the type of tethering to
     * enable if any.
     */
    public static final String EXTRA_ADD_TETHER_TYPE = "extraAddTetherType";
    /**
     * Extra used for communicating with the TetherService. Includes the type of tethering for
     * which to cancel provisioning.
     */
    public static final String EXTRA_REM_TETHER_TYPE = "extraRemTetherType";
    /**
     * Extra used for communicating with the TetherService. True to schedule a recheck of tether
     * provisioning.
     */
    public static final String EXTRA_SET_ALARM = "extraSetAlarm";
    /**
     * Tells the TetherService to run a provision check now.
     */
    public static final String EXTRA_RUN_PROVISION = "extraRunProvision";
    /**
     * Extra used for communicating with the TetherService. Contains the {@link ResultReceiver}
     * which will receive provisioning results. Can be left empty.
     */
    public static final String EXTRA_PROVISION_CALLBACK = "extraProvisionCallback";
}
