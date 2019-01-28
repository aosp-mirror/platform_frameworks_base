/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Parameters that specify what kind of bugreport should be taken.
 *
 * @hide
 */
@SystemApi
public final class BugreportParams {
    private final int mMode;

    public BugreportParams(@BugreportMode int mode) {
        mMode = mode;
    }

    public int getMode() {
        return mMode;
    }

    /**
     * Defines acceptable types of bugreports.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "BUGREPORT_MODE_" }, value = {
            BUGREPORT_MODE_FULL,
            BUGREPORT_MODE_INTERACTIVE,
            BUGREPORT_MODE_REMOTE,
            BUGREPORT_MODE_WEAR,
            BUGREPORT_MODE_TELEPHONY,
            BUGREPORT_MODE_WIFI
    })
    public @interface BugreportMode {}

    /**
     * Options for a bugreport without user interference (and hence causing less
     * interference to the system), but includes all sections.
     */
    public static final int BUGREPORT_MODE_FULL = IDumpstate.BUGREPORT_MODE_FULL;

    /**
     * Options that allow user to monitor progress and enter additional data; might not
     * include all sections.
     */
    public static final int BUGREPORT_MODE_INTERACTIVE = IDumpstate.BUGREPORT_MODE_INTERACTIVE;

    /**
     * Options for a bugreport requested remotely by administrator of the Device Owner app,
     * not the device's user.
     */
    public static final int BUGREPORT_MODE_REMOTE = IDumpstate.BUGREPORT_MODE_REMOTE;

    /**
     * Options for a bugreport on a wearable device.
     */
    public static final int BUGREPORT_MODE_WEAR = IDumpstate.BUGREPORT_MODE_WEAR;

    /**
     * Options for a lightweight version of bugreport that only includes a few, urgent
     * sections used to report telephony bugs.
     */
    public static final int BUGREPORT_MODE_TELEPHONY = IDumpstate.BUGREPORT_MODE_TELEPHONY;

    /**
     * Options for a lightweight bugreport that only includes a few sections related to
     * Wifi.
     */
    public static final int BUGREPORT_MODE_WIFI = IDumpstate.BUGREPORT_MODE_WIFI;
}
