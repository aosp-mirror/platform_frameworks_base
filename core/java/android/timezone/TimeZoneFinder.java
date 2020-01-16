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
import android.annotation.SystemApi;

import com.android.internal.annotations.GuardedBy;

/**
 * A class that can be used to find time zones.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class TimeZoneFinder {

    private static Object sLock = new Object();
    @GuardedBy("sLock")
    private static TimeZoneFinder sInstance;

    private final libcore.timezone.TimeZoneFinder mDelegate;

    private TimeZoneFinder(libcore.timezone.TimeZoneFinder delegate) {
        mDelegate = delegate;
    }

    /**
     * Obtains an instance for use when resolving telephony time zone information. This method never
     * returns {@code null}.
     */
    @NonNull
    public static TimeZoneFinder getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new TimeZoneFinder(libcore.timezone.TimeZoneFinder.getInstance());
            }
        }
        return sInstance;
    }

    /**
     * Returns a {@link CountryTimeZones} object associated with the specified country code.
     * Caching is handled as needed. If the country code is not recognized or there is an error
     * during lookup this method can return null.
     */
    @Nullable
    public CountryTimeZones lookupCountryTimeZones(@NonNull String countryIso) {
        libcore.timezone.CountryTimeZones delegate = mDelegate.lookupCountryTimeZones(countryIso);
        return delegate == null ? null : new CountryTimeZones(delegate);
    }
}
