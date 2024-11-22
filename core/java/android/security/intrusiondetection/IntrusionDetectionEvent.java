/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.security.intrusiondetection;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.admin.ConnectEvent;
import android.app.admin.DnsEvent;
import android.app.admin.SecurityLog.SecurityEvent;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.security.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class that represents a intrusiondetection event.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_AFL_API)
public final class IntrusionDetectionEvent implements Parcelable {
    private static final String TAG = "IntrusionDetectionEvent";

    /**
     * Event type representing a security-related event.
     * This type is associated with a {@link SecurityEvent} object.
     *
     * @see SecurityEvent
     */
    public static final int SECURITY_EVENT = 0;

    /**
     * Event type representing a network DNS event.
     * This type is associated with a {@link DnsEvent} object.
     *
     * @see DnsEvent
     */
    public static final int NETWORK_EVENT_DNS = 1;

    /**
     * Event type representing a network connection event.
     * This type is associated with a {@link ConnectEvent} object.
     *
     * @see ConnectEvent
     */
    public static final int NETWORK_EVENT_CONNECT = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        IntrusionDetectionEvent.SECURITY_EVENT,
        IntrusionDetectionEvent.NETWORK_EVENT_DNS,
        IntrusionDetectionEvent.NETWORK_EVENT_CONNECT,
    })
    public @interface EventType {}

    @NonNull @EventType private final int mType;

    private final SecurityEvent mSecurityEvent;
    private final DnsEvent mNetworkEventDns;
    private final ConnectEvent mNetworkEventConnect;

    public static final @NonNull Parcelable.Creator<IntrusionDetectionEvent> CREATOR =
            new Parcelable.Creator<>() {
                public IntrusionDetectionEvent createFromParcel(Parcel in) {
                    return new IntrusionDetectionEvent(in);
                }

                public IntrusionDetectionEvent[] newArray(int size) {
                    return new IntrusionDetectionEvent[size];
                }
            };

    /**
     * Creates an IntrusionDetectionEvent object with a
     * {@link SecurityEvent} object as the event source.
     *
     * @param securityEvent The SecurityEvent object.
     */
    public IntrusionDetectionEvent(@NonNull SecurityEvent securityEvent) {
        mType = SECURITY_EVENT;
        mSecurityEvent = securityEvent;
        mNetworkEventDns = null;
        mNetworkEventConnect = null;
    }

    /**
     * Creates an IntrusionDetectionEvent object with a
     * {@link DnsEvent} object as the event source.
     *
     * @param dnsEvent The DnsEvent object.
     */
    public IntrusionDetectionEvent(@NonNull DnsEvent dnsEvent) {
        mType = NETWORK_EVENT_DNS;
        mNetworkEventDns = dnsEvent;
        mSecurityEvent = null;
        mNetworkEventConnect = null;
    }

    /**
     * Creates an IntrusionDetectionEvent object with a
     * {@link ConnectEvent} object as the event source.
     *
     * @param connectEvent The ConnectEvent object.
     */
    public IntrusionDetectionEvent(@NonNull ConnectEvent connectEvent) {
        mType = NETWORK_EVENT_CONNECT;
        mNetworkEventConnect = connectEvent;
        mSecurityEvent = null;
        mNetworkEventDns = null;
    }

    private IntrusionDetectionEvent(@NonNull Parcel in) {
        mType = in.readInt();
        switch (mType) {
            case SECURITY_EVENT:
                mSecurityEvent = SecurityEvent.CREATOR.createFromParcel(in);
                mNetworkEventDns = null;
                mNetworkEventConnect = null;
                break;
            case NETWORK_EVENT_DNS:
                mNetworkEventDns = DnsEvent.CREATOR.createFromParcel(in);
                mSecurityEvent = null;
                mNetworkEventConnect = null;
                break;
            case NETWORK_EVENT_CONNECT:
                mNetworkEventConnect = ConnectEvent.CREATOR.createFromParcel(in);
                mSecurityEvent = null;
                mNetworkEventDns = null;
                break;
            default:
                throw new IllegalArgumentException("Invalid event type: " + mType);
        }
    }

    /** Returns the type of the IntrusionDetectionEvent. */
    @NonNull
    public @EventType int getType() {
        return mType;
    }

    /** Returns the SecurityEvent object. */
    @NonNull
    public SecurityEvent getSecurityEvent() {
        if (mType == SECURITY_EVENT) {
            return mSecurityEvent;
        }
        throw new IllegalArgumentException("Event type is not security event: " + mType);
    }

    /** Returns the DnsEvent object. */
    @NonNull
    public DnsEvent getDnsEvent() {
        if (mType == NETWORK_EVENT_DNS) {
            return mNetworkEventDns;
        }
        throw new IllegalArgumentException("Event type is not network DNS event: " + mType);
    }

    /** Returns the ConnectEvent object. */
    @NonNull
    public ConnectEvent getConnectEvent() {
        if (mType == NETWORK_EVENT_CONNECT) {
            return mNetworkEventConnect;
        }
        throw new IllegalArgumentException("Event type is not network connect event: " + mType);
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mType);
        switch (mType) {
            case SECURITY_EVENT:
                out.writeParcelable(mSecurityEvent, flags);
                break;
            case NETWORK_EVENT_DNS:
                out.writeParcelable(mNetworkEventDns, flags);
                break;
            case NETWORK_EVENT_CONNECT:
                out.writeParcelable(mNetworkEventConnect, flags);
                break;
            default:
                throw new IllegalArgumentException("Invalid event type: " + mType);
        }
    }

    @FlaggedApi(Flags.FLAG_AFL_API)
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "IntrusionDetectionEvent{"
                + "mType=" + mType
                + '}';
    }
}
