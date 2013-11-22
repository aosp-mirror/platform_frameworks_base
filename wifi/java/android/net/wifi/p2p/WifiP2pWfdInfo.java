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

import android.os.Parcelable;
import android.os.Parcel;

import java.util.Locale;

/**
 * A class representing Wifi Display information for a device
 * @hide
 */
public class WifiP2pWfdInfo implements Parcelable {

    private static final String TAG = "WifiP2pWfdInfo";

    private boolean mWfdEnabled;

    private int mDeviceInfo;

    public static final int WFD_SOURCE              = 0;
    public static final int PRIMARY_SINK            = 1;
    public static final int SECONDARY_SINK          = 2;
    public static final int SOURCE_OR_PRIMARY_SINK  = 3;

    /* Device information bitmap */
    /** One of {@link #WFD_SOURCE}, {@link #PRIMARY_SINK}, {@link #SECONDARY_SINK}
     * or {@link #SOURCE_OR_PRIMARY_SINK}
     */
    private static final int DEVICE_TYPE                            = 0x3;
    private static final int COUPLED_SINK_SUPPORT_AT_SOURCE         = 0x4;
    private static final int COUPLED_SINK_SUPPORT_AT_SINK           = 0x8;
    private static final int SESSION_AVAILABLE                      = 0x30;
    private static final int SESSION_AVAILABLE_BIT1                 = 0x10;
    private static final int SESSION_AVAILABLE_BIT2                 = 0x20;

    private int mCtrlPort;

    private int mMaxThroughput;

    public WifiP2pWfdInfo() {
    }

    public WifiP2pWfdInfo(int devInfo, int ctrlPort, int maxTput) {
        mWfdEnabled = true;
        mDeviceInfo = devInfo;
        mCtrlPort = ctrlPort;
        mMaxThroughput = maxTput;
    }

    public boolean isWfdEnabled() {
        return mWfdEnabled;
    }

    public void setWfdEnabled(boolean enabled) {
        mWfdEnabled = enabled;
    }

    public int getDeviceType() {
        return (mDeviceInfo & DEVICE_TYPE);
    }

    public boolean setDeviceType(int deviceType) {
        if (deviceType >= WFD_SOURCE && deviceType <= SOURCE_OR_PRIMARY_SINK) {
            mDeviceInfo |= deviceType;
            return true;
        }
        return false;
    }

    public boolean isCoupledSinkSupportedAtSource() {
        return (mDeviceInfo & COUPLED_SINK_SUPPORT_AT_SINK) != 0;
    }

    public void setCoupledSinkSupportAtSource(boolean enabled) {
        if (enabled ) {
            mDeviceInfo |= COUPLED_SINK_SUPPORT_AT_SINK;
        } else {
            mDeviceInfo &= ~COUPLED_SINK_SUPPORT_AT_SINK;
        }
    }

    public boolean isCoupledSinkSupportedAtSink() {
        return (mDeviceInfo & COUPLED_SINK_SUPPORT_AT_SINK) != 0;
    }

    public void setCoupledSinkSupportAtSink(boolean enabled) {
        if (enabled ) {
            mDeviceInfo |= COUPLED_SINK_SUPPORT_AT_SINK;
        } else {
            mDeviceInfo &= ~COUPLED_SINK_SUPPORT_AT_SINK;
        }
    }

    public boolean isSessionAvailable() {
        return (mDeviceInfo & SESSION_AVAILABLE) != 0;
    }

    public void setSessionAvailable(boolean enabled) {
        if (enabled) {
            mDeviceInfo |= SESSION_AVAILABLE_BIT1;
            mDeviceInfo &= ~SESSION_AVAILABLE_BIT2;
        } else {
            mDeviceInfo &= ~SESSION_AVAILABLE;
        }
    }

    public int getControlPort() {
        return mCtrlPort;
    }

    public void setControlPort(int port) {
        mCtrlPort = port;
    }

    public void setMaxThroughput(int maxThroughput) {
        mMaxThroughput = maxThroughput;
    }

    public int getMaxThroughput() {
        return mMaxThroughput;
    }

    public String getDeviceInfoHex() {
        return String.format(
                Locale.US, "%04x%04x%04x%04x", 6, mDeviceInfo, mCtrlPort, mMaxThroughput);
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("WFD enabled: ").append(mWfdEnabled);
        sbuf.append("WFD DeviceInfo: ").append(mDeviceInfo);
        sbuf.append("\n WFD CtrlPort: ").append(mCtrlPort);
        sbuf.append("\n WFD MaxThroughput: ").append(mMaxThroughput);
        return sbuf.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** copy constructor */
    public WifiP2pWfdInfo(WifiP2pWfdInfo source) {
        if (source != null) {
            mWfdEnabled = source.mWfdEnabled;
            mDeviceInfo = source.mDeviceInfo;
            mCtrlPort = source.mCtrlPort;
            mMaxThroughput = source.mMaxThroughput;
        }
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mWfdEnabled ? 1 : 0);
        dest.writeInt(mDeviceInfo);
        dest.writeInt(mCtrlPort);
        dest.writeInt(mMaxThroughput);
    }

    public void readFromParcel(Parcel in) {
        mWfdEnabled = (in.readInt() == 1);
        mDeviceInfo = in.readInt();
        mCtrlPort = in.readInt();
        mMaxThroughput = in.readInt();
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiP2pWfdInfo> CREATOR =
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
