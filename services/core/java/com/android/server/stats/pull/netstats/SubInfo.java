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

package com.android.server.stats.pull.netstats;

import android.annotation.NonNull;

import java.util.Objects;

/**
 * Information for a subscription that needed for sending NetworkStats related atoms.
 *
 * @hide
 */
public final class SubInfo {
    public final int subId;
    public final int carrierId;
    @NonNull
    public final String mcc;
    @NonNull
    public final String mnc;
    @NonNull
    public final String subscriberId;
    public final boolean isOpportunistic;

    public SubInfo(int subId, int carrierId, @NonNull String mcc, @NonNull String mnc,
            @NonNull String subscriberId, boolean isOpportunistic) {
        this.subId = subId;
        this.carrierId = carrierId;
        this.mcc = mcc;
        this.mnc = mnc;
        this.subscriberId = subscriberId;
        this.isOpportunistic = isOpportunistic;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final SubInfo other = (SubInfo) o;
        return subId == other.subId
                && carrierId == other.carrierId
                && isOpportunistic == other.isOpportunistic
                && mcc.equals(other.mcc)
                && mnc.equals(other.mnc)
                && subscriberId.equals(other.subscriberId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subId, mcc, mnc, carrierId, subscriberId, isOpportunistic);
    }
}
