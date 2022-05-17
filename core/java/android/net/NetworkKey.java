/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.net;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Information which identifies a specific network.
 *
 * @deprecated as part of the {@link NetworkScoreManager} deprecation.
 * @hide
 */
@Deprecated
@SystemApi
// NOTE: Ideally, we would abstract away the details of what identifies a network of a specific
// type, so that all networks appear the same and can be scored without concern to the network type
// itself. However, because no such cross-type identifier currently exists in the Android framework,
// and because systems might obtain information about networks from sources other than Android
// devices, we need to provide identifying details about each specific network type (wifi, cell,
// etc.) so that clients can pull out these details depending on the type of network.
public class NetworkKey implements Parcelable {

    private static final String TAG = "NetworkKey";

    /** A wifi network, for which {@link #wifiKey} will be populated. */
    public static final int TYPE_WIFI = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"TYPE_"}, value = {
            TYPE_WIFI
    })
    public @interface NetworkType {}

    /**
     * The type of this network.
     * @see #TYPE_WIFI
     */
    public final int type;

    /**
     * Information identifying a Wi-Fi network. Only set when {@link #type} equals
     * {@link #TYPE_WIFI}.
     */
    public final WifiKey wifiKey;

    /**
     * Constructs a new NetworkKey for the given wifi {@link ScanResult}.
     *
     * @return A new {@link NetworkKey} instance or <code>null</code> if the given
     *         {@link ScanResult} instance is malformed.
     * @throws NullPointerException
     */
    @Nullable
    public static NetworkKey createFromScanResult(@NonNull ScanResult result) {
        Objects.requireNonNull(result);
        final String ssid = result.SSID;
        if (TextUtils.isEmpty(ssid) || ssid.equals(WifiManager.UNKNOWN_SSID)) {
            return null;
        }
        final String bssid = result.BSSID;
        if (TextUtils.isEmpty(bssid)) {
            return null;
        }

        try {
            WifiKey wifiKey = new WifiKey(String.format("\"%s\"", ssid), bssid);
            return new NetworkKey(wifiKey);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Unable to create WifiKey.", e);
            return null;
        }
    }

    /**
     * Constructs a new NetworkKey for the given {@link WifiInfo}.
     *
     * @param wifiInfo the {@link WifiInfo} to create a {@link NetworkKey} for.
     * @return A new {@link NetworkKey} instance or <code>null</code> if the given {@link WifiInfo}
     *         instance doesn't represent a connected WiFi network.
     * @hide
     */
    @Nullable
    public static NetworkKey createFromWifiInfo(@Nullable WifiInfo wifiInfo) {
        if (wifiInfo != null) {
            final String ssid = wifiInfo.getSSID();
            final String bssid = wifiInfo.getBSSID();
            if (!TextUtils.isEmpty(ssid) && !ssid.equals(WifiManager.UNKNOWN_SSID)
                    && !TextUtils.isEmpty(bssid)) {
                WifiKey wifiKey;
                try {
                    wifiKey = new WifiKey(ssid, bssid);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Unable to create WifiKey.", e);
                    return null;
                }
                return new NetworkKey(wifiKey);
            }
        }
        return null;
    }

    /**
     * Construct a new {@link NetworkKey} for a Wi-Fi network.
     * @param wifiKey the {@link WifiKey} identifying this Wi-Fi network.
     */
    public NetworkKey(WifiKey wifiKey) {
        this.type = TYPE_WIFI;
        this.wifiKey = wifiKey;
    }

    private NetworkKey(Parcel in) {
        type = in.readInt();
        switch (type) {
            case TYPE_WIFI:
                wifiKey = WifiKey.CREATOR.createFromParcel(in);
                break;
            default:
                throw new IllegalArgumentException("Parcel has unknown type: " + type);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(type);
        switch (type) {
            case TYPE_WIFI:
                wifiKey.writeToParcel(out, flags);
                break;
            default:
                throw new IllegalStateException("NetworkKey has unknown type " + type);
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NetworkKey that = (NetworkKey) o;

        return type == that.type && Objects.equals(wifiKey, that.wifiKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, wifiKey);
    }

    @NonNull
    @Override
    public String toString() {
        switch (type) {
            case TYPE_WIFI:
                return wifiKey.toString();
            default:
                // Don't throw an exception here in case someone is logging this object in a catch
                // block for debugging purposes.
                return "InvalidKey";
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<NetworkKey> CREATOR =
            new Parcelable.Creator<NetworkKey>() {
                @Override
                public NetworkKey createFromParcel(Parcel in) {
                    return new NetworkKey(in);
                }

                @Override
                public NetworkKey[] newArray(int size) {
                    return new NetworkKey[size];
                }
            };
}
