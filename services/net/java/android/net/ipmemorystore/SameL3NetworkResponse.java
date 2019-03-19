/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.ipmemorystore;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * An object representing the answer to a query whether two given L2 networks represent the
 * same L3 network. Parcels as a SameL3NetworkResponseParceled object.
 * @hide
 */
public class SameL3NetworkResponse {
    @IntDef(prefix = "NETWORK_",
            value = {NETWORK_SAME, NETWORK_DIFFERENT, NETWORK_NEVER_CONNECTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface NetworkSameness {}

    /**
     * Both L2 networks represent the same L3 network.
     */
    public static final int NETWORK_SAME = 1;

    /**
     * The two L2 networks represent a different L3 network.
     */
    public static final int NETWORK_DIFFERENT = 2;

    /**
     * The device has never connected to at least one of these two L2 networks, or data
     * has been wiped. Therefore the device has never seen the L3 network behind at least
     * one of these two L2 networks, and can't evaluate whether it's the same as the other.
     */
    public static final int NETWORK_NEVER_CONNECTED = 3;

    /**
     * The first L2 key specified in the query.
     */
    @NonNull
    public final String l2Key1;

    /**
     * The second L2 key specified in the query.
     */
    @NonNull
    public final String l2Key2;

    /**
     * A confidence value indicating whether the two L2 networks represent the same L3 network.
     *
     * If both L2 networks were known, this value will be between 0.0 and 1.0, with 0.0
     * representing complete confidence that the given L2 networks represent a different
     * L3 network, and 1.0 representing complete confidence that the given L2 networks
     * represent the same L3 network.
     * If at least one of the L2 networks was not known, this value will be outside of the
     * 0.0~1.0 range.
     *
     * Most apps should not be interested in this, and are encouraged to use the collapsing
     * {@link #getNetworkSameness()} function below.
     */
    public final float confidence;

    /**
     * @return whether the two L2 networks represent the same L3 network. Either
     *     {@code NETWORK_SAME}, {@code NETWORK_DIFFERENT} or {@code NETWORK_NEVER_CONNECTED}.
     */
    @NetworkSameness
    public final int getNetworkSameness() {
        if (confidence > 1.0 || confidence < 0.0) return NETWORK_NEVER_CONNECTED;
        return confidence > 0.5 ? NETWORK_SAME : NETWORK_DIFFERENT;
    }

    /** @hide */
    public SameL3NetworkResponse(@NonNull final String l2Key1, @NonNull final String l2Key2,
            final float confidence) {
        this.l2Key1 = l2Key1;
        this.l2Key2 = l2Key2;
        this.confidence = confidence;
    }

    /** Builds a SameL3NetworkResponse from a parcelable object */
    @VisibleForTesting
    public SameL3NetworkResponse(@NonNull final SameL3NetworkResponseParcelable parceled) {
        this(parceled.l2Key1, parceled.l2Key2, parceled.confidence);
    }

    /** Converts this SameL3NetworkResponse to a parcelable object */
    @NonNull
    public SameL3NetworkResponseParcelable toParcelable() {
        final SameL3NetworkResponseParcelable parcelable = new SameL3NetworkResponseParcelable();
        parcelable.l2Key1 = l2Key1;
        parcelable.l2Key2 = l2Key2;
        parcelable.confidence = confidence;
        return parcelable;
    }

    // Note key1 and key2 have to match each other for this to return true. If
    // key1 matches o.key2 and the other way around this returns false.
    @Override
    public boolean equals(@Nullable final Object o) {
        if (!(o instanceof SameL3NetworkResponse)) return false;
        final SameL3NetworkResponse other = (SameL3NetworkResponse) o;
        return l2Key1.equals(other.l2Key1) && l2Key2.equals(other.l2Key2)
                && confidence == other.confidence;
    }

    @Override
    public int hashCode() {
        return Objects.hash(l2Key1, l2Key2, confidence);
    }

    @Override
    /** Pretty print */
    public String toString() {
        switch (getNetworkSameness()) {
            case NETWORK_SAME:
                return "\"" + l2Key1 + "\" same L3 network as \"" + l2Key2 + "\"";
            case NETWORK_DIFFERENT:
                return "\"" + l2Key1 + "\" different L3 network from \"" + l2Key2 + "\"";
            case NETWORK_NEVER_CONNECTED:
                return "\"" + l2Key1 + "\" can't be tested against \"" + l2Key2 + "\"";
            default:
                return "Buggy sameness value ? \"" + l2Key1 + "\", \"" + l2Key2 + "\"";
        }
    }
}
