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

package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
import static android.net.NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Container for VPN-specific transport information.
 *
 * @see android.net.TransportInfo
 * @see NetworkCapabilities#getTransportInfo()
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class VpnTransportInfo implements TransportInfo, Parcelable {
    /** Type of this VPN. */
    public final int type;

    @Nullable
    public final String sessionId;

    @Override
    public long getApplicableRedactions() {
        return REDACT_FOR_NETWORK_SETTINGS;
    }

    /**
     * Create a copy of a {@link VpnTransportInfo} with the sessionId redacted if necessary.
     */
    @NonNull
    public VpnTransportInfo makeCopy(long redactions) {
        return new VpnTransportInfo(type,
            ((redactions & REDACT_FOR_NETWORK_SETTINGS) != 0) ? null : sessionId);
    }

    public VpnTransportInfo(int type, @Nullable String sessionId) {
        this.type = type;
        this.sessionId = sessionId;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VpnTransportInfo)) return false;

        VpnTransportInfo that = (VpnTransportInfo) o;
        return (this.type == that.type) && TextUtils.equals(this.sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, sessionId);
    }

    @Override
    public String toString() {
        return String.format("VpnTransportInfo{type=%d, sessionId=%s}", type, sessionId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(type);
        dest.writeString(sessionId);
    }

    public static final @NonNull Creator<VpnTransportInfo> CREATOR =
            new Creator<VpnTransportInfo>() {
        public VpnTransportInfo createFromParcel(Parcel in) {
            return new VpnTransportInfo(in.readInt(), in.readString());
        }
        public VpnTransportInfo[] newArray(int size) {
            return new VpnTransportInfo[size];
        }
    };
}
