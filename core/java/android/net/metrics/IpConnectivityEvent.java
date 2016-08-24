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

package android.net.metrics;

import android.net.ConnectivityMetricsLogger;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * {@hide}
 */
public abstract class IpConnectivityEvent {
    private static final int COMPONENT_TAG = ConnectivityMetricsLogger.COMPONENT_TAG_CONNECTIVITY;

    private static final ConnectivityMetricsLogger sMetricsLogger = new ConnectivityMetricsLogger();

    public static <T extends IpConnectivityEvent & Parcelable> void logEvent(T event) {
        // TODO: consider using different component for DNS event.
        sMetricsLogger.logEvent(System.currentTimeMillis(), COMPONENT_TAG, 0, event);
    }
};
