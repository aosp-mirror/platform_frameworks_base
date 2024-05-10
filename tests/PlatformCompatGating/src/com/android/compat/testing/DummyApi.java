/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.compat.testing;

import android.compat.Compatibility;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.compat.IPlatformCompat;

/**
 * This is a placeholder API to test gating
 *
 * @hide
 */
public class DummyApi {

    public static final long CHANGE_ID = 666013;
    public static final long CHANGE_ID_1 = 666014;
    public static final long CHANGE_ID_2 = 666015;
    public static final long CHANGE_SYSTEM_SERVER = 666016;

    /**
     * Placeholder method
     * @return "A" if change is enabled, "B" otherwise.
     */
    public static String dummyFunc() {
        if (Compatibility.isChangeEnabled(CHANGE_ID)) {
            return "A";
        }
        return "B";
    }

    /**
     * Placeholder combined method
     * @return "0" if {@link CHANGE_ID_1} is disabled and {@link CHANGE_ID_2} is disabled,
               "1" if {@link CHANGE_ID_1} is disabled and {@link CHANGE_ID_2} is enabled,
               "2" if {@link CHANGE_ID_1} is enabled and {@link CHANGE_ID_2} is disabled,
               "3" if {@link CHANGE_ID_1} is enabled and {@link CHANGE_ID_2} is enabled.
     */
    public static String dummyCombinedFunc() {
        if (!Compatibility.isChangeEnabled(CHANGE_ID_1)
                && !Compatibility.isChangeEnabled(CHANGE_ID_2)) {
            return "0";
        } else if (!Compatibility.isChangeEnabled(CHANGE_ID_1)
                && Compatibility.isChangeEnabled(CHANGE_ID_2)) {
            return "1";
        } else if (Compatibility.isChangeEnabled(CHANGE_ID_1)
                && !Compatibility.isChangeEnabled(CHANGE_ID_2)) {
            return "2";
        }
        return "3";
    }

    /**
     * Placeholder api using system server API.
     */
    public static boolean dummySystemServer(Context context) {
        IPlatformCompat platformCompat = IPlatformCompat.Stub
                .asInterface(ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        if (platformCompat == null) {
            throw new RuntimeException("Could not obtain IPlatformCompat instance!");
        }
        String packageName = context.getPackageName();
        try {
            return platformCompat.isChangeEnabledByPackageName(CHANGE_SYSTEM_SERVER, packageName,
                                                            context.getUserId());
        } catch (RemoteException e) {
            throw new RuntimeException("Could not get change value!", e);
        }
    }
}
