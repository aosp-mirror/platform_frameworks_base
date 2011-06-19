/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net;

import static android.net.ConnectivityManager.isNetworkTypeMobile;

import android.content.Context;
import android.telephony.TelephonyManager;

import com.android.internal.util.Objects;

/**
 * Network definition that includes strong identity. Analogous to combining
 * {@link NetworkInfo} and an IMSI.
 *
 * @hide
 */
public class NetworkIdentity {
    final int mType;
    final int mSubType;
    final String mSubscriberId;

    public NetworkIdentity(int type, int subType, String subscriberId) {
        this.mType = type;
        this.mSubType = subType;
        this.mSubscriberId = subscriberId;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mType, mSubType, mSubscriberId);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NetworkIdentity) {
            final NetworkIdentity ident = (NetworkIdentity) obj;
            return mType == ident.mType && mSubType == ident.mSubType
                    && Objects.equal(mSubscriberId, ident.mSubscriberId);
        }
        return false;
    }

    @Override
    public String toString() {
        final String typeName = ConnectivityManager.getNetworkTypeName(mType);
        final String subTypeName;
        if (ConnectivityManager.isNetworkTypeMobile(mType)) {
            subTypeName = TelephonyManager.getNetworkTypeName(mSubType);
        } else {
            subTypeName = Integer.toString(mSubType);
        }

        final String scrubSubscriberId = mSubscriberId != null ? "valid" : "null";
        return "[type=" + typeName + ", subType=" + subTypeName + ", subscriberId="
                + scrubSubscriberId + "]";
    }

    public int getType() {
        return mType;
    }

    public int getSubType() {
        return mSubType;
    }

    public String getSubscriberId() {
        return mSubscriberId;
    }

    /**
     * Build a {@link NetworkIdentity} from the given {@link NetworkState},
     * assuming that any mobile networks are using the current IMSI.
     */
    public static NetworkIdentity buildNetworkIdentity(Context context, NetworkState state) {
        final int type = state.networkInfo.getType();
        final int subType = state.networkInfo.getSubtype();

        // TODO: consider moving subscriberId over to LinkCapabilities, so it
        // comes from an authoritative source.

        final String subscriberId;
        if (isNetworkTypeMobile(type)) {
            if (state.subscriberId != null) {
                subscriberId = state.subscriberId;
            } else {
                final TelephonyManager telephony = (TelephonyManager) context.getSystemService(
                        Context.TELEPHONY_SERVICE);
                subscriberId = telephony.getSubscriberId();
            }
        } else {
            subscriberId = null;
        }
        return new NetworkIdentity(type, subType, subscriberId);
    }

}
