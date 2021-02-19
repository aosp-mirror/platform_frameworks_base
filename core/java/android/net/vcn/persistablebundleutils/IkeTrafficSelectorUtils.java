/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net.vcn.persistablebundleutils;

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.NonNull;
import android.net.InetAddresses;
import android.net.ipsec.ike.IkeTrafficSelector;
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * Provides utility methods to convert IkeTrafficSelector to/from PersistableBundle.
 *
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PRIVATE)
public final class IkeTrafficSelectorUtils {
    private static final String START_PORT_KEY = "START_PORT_KEY";
    private static final String END_PORT_KEY = "END_PORT_KEY";
    private static final String START_ADDRESS_KEY = "START_ADDRESS_KEY";
    private static final String END_ADDRESS_KEY = "END_ADDRESS_KEY";

    /** Constructs an IkeTrafficSelector by deserializing a PersistableBundle. */
    @NonNull
    public static IkeTrafficSelector fromPersistableBundle(@NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle was null");

        final int startPort = in.getInt(START_PORT_KEY);
        final int endPort = in.getInt(END_PORT_KEY);

        final String startingAddress = in.getString(START_ADDRESS_KEY);
        final String endingAddress = in.getString(END_ADDRESS_KEY);
        Objects.requireNonNull(startingAddress, "startAddress was null");
        Objects.requireNonNull(startingAddress, "endAddress was null");

        return new IkeTrafficSelector(
                startPort,
                endPort,
                InetAddresses.parseNumericAddress(startingAddress),
                InetAddresses.parseNumericAddress(endingAddress));
    }

    /** Serializes an IkeTrafficSelector to a PersistableBundle. */
    @NonNull
    public static PersistableBundle toPersistableBundle(@NonNull IkeTrafficSelector ts) {
        final PersistableBundle result = new PersistableBundle();

        result.putInt(START_PORT_KEY, ts.startPort);
        result.putInt(END_PORT_KEY, ts.endPort);
        result.putString(START_ADDRESS_KEY, ts.startingAddress.getHostAddress());
        result.putString(END_ADDRESS_KEY, ts.endingAddress.getHostAddress());

        return result;
    }
}
