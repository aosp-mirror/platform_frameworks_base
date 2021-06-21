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

package android.timezone;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/**
 * A class that can be used to find time zones using information like country and offset.
 *
 * @hide
 */
public final class TimeZoneFinder {

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static TimeZoneFinder sInstance;

    /**
     * Obtains the singleton instance.
     */
    @NonNull
    public static TimeZoneFinder getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new TimeZoneFinder(com.android.i18n.timezone.TimeZoneFinder
                    .getInstance());
            }
        }
        return sInstance;
    }

    @NonNull
    private final com.android.i18n.timezone.TimeZoneFinder mDelegate;

    private TimeZoneFinder(@NonNull com.android.i18n.timezone.TimeZoneFinder delegate) {
        mDelegate = Objects.requireNonNull(delegate);
    }

    /**
     * Returns the IANA rules version associated with the data. If there is no version information
     * or there is a problem reading the file then {@code null} is returned.
     */
    @Nullable
    public String getIanaVersion() {
        return mDelegate.getIanaVersion();
    }

    /**
     * Returns a {@link CountryTimeZones} object associated with the specified country code.
     * Caching is handled as needed. If the country code is not recognized or there is an error
     * during lookup this method can return null.
     */
    @Nullable
    public CountryTimeZones lookupCountryTimeZones(@NonNull String countryIso) {
        com.android.i18n.timezone.CountryTimeZones delegate = mDelegate
                .lookupCountryTimeZones(countryIso);
        return delegate == null ? null : new CountryTimeZones(delegate);
    }
}
