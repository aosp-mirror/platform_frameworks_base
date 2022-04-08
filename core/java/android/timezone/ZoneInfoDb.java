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

package android.timezone;

import android.annotation.NonNull;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/**
 * Android's internal factory for java.util.TimeZone objects. Provides access to core library time
 * zone metadata not available via {@link java.util.TimeZone}.
 *
 * @hide
 */
public final class ZoneInfoDb {

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static ZoneInfoDb sInstance;

    /**
     * Obtains the singleton instance.
     */
    @NonNull
    public static ZoneInfoDb getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new ZoneInfoDb(libcore.timezone.ZoneInfoDb.getInstance());
            }
        }
        return sInstance;
    }

    @NonNull
    private final libcore.timezone.ZoneInfoDb mDelegate;

    private ZoneInfoDb(libcore.timezone.ZoneInfoDb delegate) {
        mDelegate = Objects.requireNonNull(delegate);
    }

    /**
     * Returns the tzdb version in use.
     */
    @NonNull
    public String getVersion() {
        return mDelegate.getVersion();
    }
}
