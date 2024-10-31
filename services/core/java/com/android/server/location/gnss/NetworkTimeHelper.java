/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.location.gnss;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.content.Context;
import android.location.flags.Flags;
import android.os.Looper;

import java.io.PrintWriter;

/**
 * An abstraction for use by {@link GnssLocationProvider}. This class allows switching between
 * implementations with a compile-time constant change, which is less risky than rolling back a
 * whole class. When there is a single implementation again this class can be replaced by that
 * implementation.
 */
abstract class NetworkTimeHelper {

    /**
     * This compile-time value can be changed to switch between new and old ways to obtain network
     * time for GNSS. If you have to turn this from {@code true} to {@code false} then please create
     * a platform bug. This switch will be removed in a future release. If there are problems with
     * the new impl we'd like to hear about them.
     */
    static final boolean USE_TIME_DETECTOR_IMPL = true;

    /**
     * The callback interface used by {@link NetworkTimeHelper} to report the time to {@link
     * GnssLocationProvider}. The callback can happen at any time using the thread associated with
     * the looper passed to {@link #create(Context, Looper, InjectTimeCallback)}.
     */
    interface InjectTimeCallback {
        void injectTime(@CurrentTimeMillisLong long unixEpochTimeMillis,
                @ElapsedRealtimeLong long elapsedRealtimeMillis, int uncertaintyMillis);
    }

    /**
     * Creates the {@link NetworkTimeHelper} instance for use by {@link GnssLocationProvider}.
     */
    static NetworkTimeHelper create(
            @NonNull Context context, @NonNull Looper looper,
            @NonNull InjectTimeCallback injectTimeCallback) {
        if (!Flags.useLegacyNtpTime()) {
            TimeDetectorNetworkTimeHelper.Environment environment =
                    new TimeDetectorNetworkTimeHelper.EnvironmentImpl(looper);
            return new TimeDetectorNetworkTimeHelper(environment, injectTimeCallback);
        } else {
            return new NtpNetworkTimeHelper(context, looper, injectTimeCallback);
        }
    }

    /**
     * Sets the "on demand time injection" mode.
     *
     * <p>Called by {@link GnssLocationProvider} to set the expected time injection behavior.
     * When {@code enablePeriodicTimeInjection == true}, the time helper should periodically send
     * the time on an undefined schedule. The time can be injected at other times for other reasons
     * as well as be requested via {@link #demandUtcTimeInjection()}.
     *
     * @param periodicTimeInjectionEnabled {@code true} if the GNSS implementation requires periodic
     *   time signals
     */
    abstract void setPeriodicTimeInjectionMode(boolean periodicTimeInjectionEnabled);

    /**
     * Requests an asynchronous time injection via {@link InjectTimeCallback#injectTime}, if a
     * network time is available. {@link InjectTimeCallback#injectTime} may not be called if a
     * network time is not available.
     */
    abstract void demandUtcTimeInjection();

    /**
     * Notifies that network connectivity has been established.
     *
     * <p>Called by {@link GnssLocationProvider} when the device establishes a data network
     * connection. This call should be removed eventually because it should be handled by the {@link
     * NetworkTimeHelper} implementation itself, but has been retained for compatibility while
     * switching implementations.
     */
    abstract void onNetworkAvailable();

    /**
     * Dumps internal state during bugreports useful for debugging.
     */
    abstract void dump(@NonNull PrintWriter pw);

}
