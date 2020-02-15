/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.net.wifi.p2p;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Locale;

/**
 * A class representing Wifi Display information for a device.
 *
 * See Wifi Display technical specification v1.0.0, section 5.1.2.
 */
public final class WifiP2pWfdInfo implements Parcelable {

    private boolean mEnabled;

    /** Device information bitmap */
    private int mDeviceInfo;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "DEVICE_TYPE_" }, value = {
            DEVICE_TYPE_WFD_SOURCE,
            DEVICE_TYPE_PRIMARY_SINK,
            DEVICE_TYPE_SECONDARY_SINK,
            DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK})
    public @interface DeviceType {}

    /** The device is a Wifi Display Source. */
    public static final int DEVICE_TYPE_WFD_SOURCE = 0;
    /** The device is a primary sink. */
    public static final int DEVICE_TYPE_PRIMARY_SINK = 1;
    /** The device is a secondary sink. */
    public static final int DEVICE_TYPE_SECONDARY_SINK = 2;
    /** The device is dual-role capable i.e. either a WFD source or a primary sink. */
    public static final int DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK = 3;

    /**
     * {@link #mDeviceInfo} & {@link #DEVICE_TYPE} is one of {@link #DEVICE_TYPE_WFD_SOURCE},
     * {@link #DEVICE_TYPE_PRIMARY_SINK}, {@link #DEVICE_TYPE_SECONDARY_SINK} or
     * {@link #DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK}.
     *
     * The bit definition is listed in 5.1.2 WFD Device Information Subelement in
     * Wi-Fi Display Technical Specification.
     */
    private static final int DEVICE_TYPE                            = 1 << 1 | 1 << 0;
    private static final int COUPLED_SINK_SUPPORT_AT_SOURCE         = 1 << 2;
    private static final int COUPLED_SINK_SUPPORT_AT_SINK           = 1 << 3;
    private static final int SESSION_AVAILABLE_BIT1                 = 1 << 4;
    private static final int SESSION_AVAILABLE_BIT2                 = 1 << 5;
    private static final int SESSION_AVAILABLE                      =
            SESSION_AVAILABLE_BIT2 | SESSION_AVAILABLE_BIT1;
    /* The support of Content Protection using the HDCP system 2.0/2.1. */
    private static final int CONTENT_PROTECTION_SUPPORT             = 1 << 8;

    private int mCtrlPort;

    private int mMaxThroughput;

    /** Default constructor. */
    public WifiP2pWfdInfo() {}

    /** @hide */
    @UnsupportedAppUsage
    public WifiP2pWfdInfo(int devInfo, int ctrlPort, int maxTput) {
        mEnabled = true;
        mDeviceInfo = devInfo;
        mCtrlPort = ctrlPort;
        mMaxThroughput = maxTput;
    }

    /** Returns true is Wifi Display is enabled, false otherwise. */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Sets whether Wifi Display should be enabled.
     *
     * @param enabled true to enable Wifi Display, false to disable
     */
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    /**
     * Get the type of the device.
     * One of {@link #DEVICE_TYPE_WFD_SOURCE}, {@link #DEVICE_TYPE_PRIMARY_SINK},
     * {@link #DEVICE_TYPE_SECONDARY_SINK}, {@link #DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK}
     */
    @DeviceType
    public int getDeviceType() {
        return mDeviceInfo & DEVICE_TYPE;
    }

    /**
     * Sets the type of the device.
     *
     * @param deviceType One of {@link #DEVICE_TYPE_WFD_SOURCE}, {@link #DEVICE_TYPE_PRIMARY_SINK},
     * {@link #DEVICE_TYPE_SECONDARY_SINK}, {@link #DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK}
     * @return true if the device type was successfully set, false otherwise
     */
    public boolean setDeviceType(@DeviceType int deviceType) {
        if (DEVICE_TYPE_WFD_SOURCE <= deviceType
                && deviceType <= DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK) {
            mDeviceInfo &= ~DEVICE_TYPE;
            mDeviceInfo |= deviceType;
            return true;
        }
        return false;
    }

    /** Returns true if a session is available, false otherwise. */
    public boolean isSessionAvailable() {
        return (mDeviceInfo & SESSION_AVAILABLE) != 0;
    }

    /**
     * Sets whether a session is available.
     *
     * @param enabled true to indicate that a session is available, false otherwise.
     */
    public void setSessionAvailable(boolean enabled) {
        if (enabled) {
            mDeviceInfo |= SESSION_AVAILABLE_BIT1;
            mDeviceInfo &= ~SESSION_AVAILABLE_BIT2;
        } else {
            mDeviceInfo &= ~SESSION_AVAILABLE;
        }
    }

    /**
     * @return true if Content Protection using the HDCP system 2.0/2.1 is supported.
     */
    public boolean isContentProtectionSupported() {
        return (mDeviceInfo & CONTENT_PROTECTION_SUPPORT) != 0;
    }

    /**
     * Sets whether Content Protection using the HDCP system 2.0/2.1 is supported.
     *
     * @param enabled true to indicate that Content Protection is supported, false otherwise.
     */
    public void setContentProtectionSupported(boolean enabled) {
        if (enabled) {
            mDeviceInfo |= CONTENT_PROTECTION_SUPPORT;
        } else {
            mDeviceInfo &= ~CONTENT_PROTECTION_SUPPORT;
        }
    }

    /** Returns the TCP port at which the WFD Device listens for RTSP messages. */
    public int getControlPort() {
        return mCtrlPort;
    }

    /** Sets the TCP port at which the WFD Device listens for RTSP messages. */
    public void setControlPort(@IntRange(from = 0) int port) {
        mCtrlPort = port;
    }

    /** Sets the maximum average throughput capability of the WFD Device, in megabits/second. */
    public void setMaxThroughput(@IntRange(from = 0) int maxThroughput) {
        mMaxThroughput = maxThroughput;
    }

    /** Returns the maximum average throughput capability of the WFD Device, in megabits/second. */
    public int getMaxThroughput() {
        return mMaxThroughput;
    }

    /** @hide */
    public String getDeviceInfoHex() {
        return String.format(
                Locale.US, "%04x%04x%04x", mDeviceInfo, mCtrlPort, mMaxThroughput);
    }

    @Override
    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("WFD enabled: ").append(mEnabled);
        sbuf.append("WFD DeviceInfo: ").append(mDeviceInfo);
        sbuf.append("\n WFD CtrlPort: ").append(mCtrlPort);
        sbuf.append("\n WFD MaxThroughput: ").append(mMaxThroughput);
        return sbuf.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Copy constructor. */
    public WifiP2pWfdInfo(@Nullable WifiP2pWfdInfo source) {
        if (source != null) {
            mEnabled = source.mEnabled;
            mDeviceInfo = source.mDeviceInfo;
            mCtrlPort = source.mCtrlPort;
            mMaxThroughput = source.mMaxThroughput;
        }
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mEnabled ? 1 : 0);
        dest.writeInt(mDeviceInfo);
        dest.writeInt(mCtrlPort);
        dest.writeInt(mMaxThroughput);
    }

    private void readFromParcel(Parcel in) {
        mEnabled = (in.readInt() == 1);
        mDeviceInfo = in.readInt();
        mCtrlPort = in.readInt();
        mMaxThroughput = in.readInt();
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<WifiP2pWfdInfo> CREATOR =
        new Creator<WifiP2pWfdInfo>() {
            public WifiP2pWfdInfo createFromParcel(Parcel in) {
                WifiP2pWfdInfo device = new WifiP2pWfdInfo();
                device.readFromParcel(in);
                return device;
            }

            public WifiP2pWfdInfo[] newArray(int size) {
                return new WifiP2pWfdInfo[size];
            }
        };
}
