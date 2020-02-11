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

import java.util.Objects;

/**
 * A class that can find telephony network information loaded via {@link TelephonyLookup}.
 *
 * @hide
 */
public final class TelephonyNetworkFinder {

    @NonNull
    private final libcore.timezone.TelephonyNetworkFinder mDelegate;

    TelephonyNetworkFinder(libcore.timezone.TelephonyNetworkFinder delegate) {
        mDelegate = Objects.requireNonNull(delegate);
    }

    /**
     * Returns information held about a specific MCC + MNC combination. It is expected for this
     * method to return {@code null}. Only known, unusual networks will typically have information
     * returned, e.g. if they operate in countries other than the one suggested by their MCC.
     */
    @Nullable
    public TelephonyNetwork findNetworkByMccMnc(@NonNull String mcc, @NonNull String mnc) {
        Objects.requireNonNull(mcc);
        Objects.requireNonNull(mnc);

        libcore.timezone.TelephonyNetwork telephonyNetworkDelegate =
                mDelegate.findNetworkByMccMnc(mcc, mnc);
        return telephonyNetworkDelegate != null
                ? new TelephonyNetwork(telephonyNetworkDelegate) : null;
    }
}
