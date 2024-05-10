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

package com.android.settingslib.display;

import android.os.AsyncTask;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

/** Utility methods for controlling the display density. */
public class DisplayDensityConfiguration {
    private static final String LOG_TAG = "DisplayDensityConfig";

    /**
     * Returns the default density for the specified display.
     *
     * @param displayId the identifier of the display
     * @return the default density of the specified display, or {@code -1} if the display does not
     *     exist or the density could not be obtained
     */
    static int getDefaultDisplayDensity(int displayId) {
        try {
            final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            return wm.getInitialDisplayDensity(displayId);
        } catch (RemoteException exc) {
            return -1;
        }
    }

    /**
     * Asynchronously applies display density changes to the specified display.
     *
     * <p>The change will be applied to the user specified by the value of {@link
     * UserHandle#myUserId()} at the time the method is called.
     *
     * @param displayId the identifier of the display to modify
     */
    public static void clearForcedDisplayDensity(final int displayId) {
        final int userId = UserHandle.myUserId();
        AsyncTask.execute(
                () -> {
                    try {
                        final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                        wm.clearForcedDisplayDensityForUser(displayId, userId);
                    } catch (RemoteException exc) {
                        Log.w(LOG_TAG, "Unable to clear forced display density setting");
                    }
                });
    }

    /**
     * Asynchronously applies display density changes to the specified display.
     *
     * <p>The change will be applied to the user specified by the value of {@link
     * UserHandle#myUserId()} at the time the method is called.
     *
     * @param displayId the identifier of the display to modify
     * @param density the density to force for the specified display
     */
    public static void setForcedDisplayDensity(final int displayId, final int density) {
        final int userId = UserHandle.myUserId();
        AsyncTask.execute(
                () -> {
                    try {
                        final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                        wm.setForcedDisplayDensityForUser(displayId, density, userId);
                    } catch (RemoteException exc) {
                        Log.w(LOG_TAG, "Unable to save forced display density setting");
                    }
                });
    }
}
