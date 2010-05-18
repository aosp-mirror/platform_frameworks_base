/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.cdma;

public class TtyIntent {

    private static final String TAG = "TtyIntent";


    /** Event for TTY mode change */

    /**
     * Broadcast intent action indicating that the TTY has either been
     * enabled or disabled. An intent extra provides this state as a boolean,
     * where {@code true} means enabled.
     * @see #TTY_ENABLED
     *
     * {@hide}
     */
    public static final String TTY_ENABLED_CHANGE_ACTION =
        "com.android.internal.telephony.cdma.intent.action.TTY_ENABLED_CHANGE";

    /**
     * The lookup key for a boolean that indicates whether TTY mode is enabled or
     * disabled. {@code true} means TTY mode is enabled. Retrieve it with
     * {@link android.content.Intent#getBooleanExtra(String,boolean)}.
     *
     * {@hide}
     */
    public static final String TTY_ENABLED = "ttyEnabled";

    /**
     * Broadcast intent action indicating that the TTY preferred operating mode
     * has changed. An intent extra provides the new mode as an int.
     * @see #TTY_PREFFERED_MODE
     *
     * {@hide}
     */
    public static final String TTY_PREFERRED_MODE_CHANGE_ACTION =
        "com.android.internal.telephony.cdma.intent.action.TTY_PREFERRED_MODE_CHANGE";

    /**
     * The lookup key for an int that indicates preferred TTY mode.
     * Valid modes are:
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_OFF}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_FULL}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_HCO}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_VCO}
     *
     * {@hide}
     */
    public static final String TTY_PREFFERED_MODE = "ttyPreferredMode";
}
