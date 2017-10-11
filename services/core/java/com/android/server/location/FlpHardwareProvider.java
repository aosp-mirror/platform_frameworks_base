/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.location;

import android.content.Context;
import android.hardware.location.IFusedLocationHardware;
import android.location.IFusedGeofenceHardware;
import android.util.Log;

/**
 * This class was an interop layer for JVM types and the JNI code that interacted
 * with the FLP HAL implementation.
 *
 * Now, after Treble FLP & GNSS HAL simplification, it is a thin shell that acts like the
 * pre-existing cases where there was no FLP Hardware support, to keep legacy users of this
 * class operating.
 *
 * {@hide}
 * {@Deprecated}
 */
public class FlpHardwareProvider {
    private static FlpHardwareProvider sSingletonInstance = null;

    private final static String TAG = "FlpHardwareProvider";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static FlpHardwareProvider getInstance(Context context) {
        if (sSingletonInstance == null) {
            sSingletonInstance = new FlpHardwareProvider();
            if (DEBUG) Log.d(TAG, "getInstance() created empty provider");
        }
        return sSingletonInstance;
    }

    private FlpHardwareProvider() {
    }

    public static boolean isSupported() {
        if (DEBUG) Log.d(TAG, "isSupported() returning false");
        return false;
    }

    /**
     * Interface implementations for services built on top of this functionality.
     */
    public static final String LOCATION = "Location";

    public IFusedLocationHardware getLocationHardware() {
        return null;
    }

    public IFusedGeofenceHardware getGeofenceHardware() {
        return null;
    }

    public void cleanup() {
        if (DEBUG) Log.d(TAG, "empty cleanup()");
    }
}
