/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.display;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.DisplayInfo;

/**
 * Manages the properties, media routing and power state of attached displays.
 * <p>
 * Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String)
 * Context.getSystemService()} with the argument
 * {@link android.content.Context#DISPLAY_SERVICE}.
 * </p>
 */
public final class DisplayManager {
    private static final String TAG = "DisplayManager";

    private static DisplayManager sInstance;

    private final IDisplayManager mDm;

    private DisplayManager(IDisplayManager dm) {
        mDm = dm;
    }

    /**
     * Gets an instance of the display manager.
     * @return The display manager instance.
     * @hide
     */
    public static DisplayManager getInstance() {
        synchronized (DisplayManager.class) {
            if (sInstance == null) {
                IBinder b = ServiceManager.getService(Context.DISPLAY_SERVICE);
                sInstance = new DisplayManager(IDisplayManager.Stub.asInterface(b));
            }
            return sInstance;
        }
    }

    /**
     * Get information about a particular logical display.
     *
     * @param displayId The logical display id.
     * @param outInfo A structure to populate with the display info.
     * @return True if the logical display exists, false otherwise.
     * @hide
     */
    public boolean getDisplayInfo(int displayId, DisplayInfo outInfo) {
        try {
            return mDm.getDisplayInfo(displayId, outInfo);
        } catch (RemoteException ex) {
            Log.e(TAG, "Could not get display information from display manager.", ex);
            return false;
        }
    }
}
