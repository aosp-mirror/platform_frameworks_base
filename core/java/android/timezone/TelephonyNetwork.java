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

import java.util.Objects;

/**
 * Information about a telephony network.
 *
 * @hide
 */
public final class TelephonyNetwork {

    @NonNull
    private final libcore.timezone.TelephonyNetwork mDelegate;

    TelephonyNetwork(@NonNull libcore.timezone.TelephonyNetwork delegate) {
        mDelegate = Objects.requireNonNull(delegate);
    }

    /**
     * Returns the Mobile Country Code of the network.
     */
    @NonNull
    public String getMcc() {
        return mDelegate.getMcc();
    }

    /**
     * Returns the Mobile Network Code of the network.
     */
    @NonNull
    public String getMnc() {
        return mDelegate.getMnc();
    }

    /**
     * Returns the country in which the network operates as an ISO 3166 alpha-2 (lower case).
     */
    @NonNull
    public String getCountryIsoCode() {
        return mDelegate.getCountryIsoCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TelephonyNetwork that = (TelephonyNetwork) o;
        return mDelegate.equals(that.mDelegate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDelegate);
    }

    @Override
    public String toString() {
        return "TelephonyNetwork{"
                + "mDelegate=" + mDelegate
                + '}';
    }
}
