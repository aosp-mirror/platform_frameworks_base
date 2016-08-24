/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.metrics;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import com.android.internal.util.MessageUtils;

/**
 * {@hide} Event class used to record error events when parsing DHCP response packets.
 */
@SystemApi
public final class DhcpErrorEvent extends IpConnectivityEvent implements Parcelable {
    public static final int L2_ERROR   = 1;
    public static final int L3_ERROR   = 2;
    public static final int L4_ERROR   = 3;
    public static final int DHCP_ERROR = 4;
    public static final int MISC_ERROR = 5;

    public static final int L2_TOO_SHORT               = makeErrorCode(L2_ERROR, 1);
    public static final int L2_WRONG_ETH_TYPE          = makeErrorCode(L2_ERROR, 2);

    public static final int L3_TOO_SHORT               = makeErrorCode(L3_ERROR, 1);
    public static final int L3_NOT_IPV4                = makeErrorCode(L3_ERROR, 2);
    public static final int L3_INVALID_IP              = makeErrorCode(L3_ERROR, 3);

    public static final int L4_NOT_UDP                 = makeErrorCode(L4_ERROR, 1);
    public static final int L4_WRONG_PORT              = makeErrorCode(L4_ERROR, 2);

    public static final int BOOTP_TOO_SHORT            = makeErrorCode(DHCP_ERROR, 1);
    public static final int DHCP_BAD_MAGIC_COOKIE      = makeErrorCode(DHCP_ERROR, 2);
    public static final int DHCP_INVALID_OPTION_LENGTH = makeErrorCode(DHCP_ERROR, 3);
    public static final int DHCP_NO_MSG_TYPE           = makeErrorCode(DHCP_ERROR, 4);
    public static final int DHCP_UNKNOWN_MSG_TYPE      = makeErrorCode(DHCP_ERROR, 5);

    public static final int BUFFER_UNDERFLOW           = makeErrorCode(MISC_ERROR, 1);
    public static final int RECEIVE_ERROR              = makeErrorCode(MISC_ERROR, 2);

    public final String ifName;
    // error code byte format (MSB to LSB):
    // byte 0: error type
    // byte 1: error subtype
    // byte 2: unused
    // byte 3: optional code
    public final int errorCode;

    private DhcpErrorEvent(String ifName, int errorCode) {
        this.ifName = ifName;
        this.errorCode = errorCode;
    }

    private DhcpErrorEvent(Parcel in) {
        this.ifName = in.readString();
        this.errorCode = in.readInt();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(ifName);
        out.writeInt(errorCode);
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<DhcpErrorEvent> CREATOR
        = new Parcelable.Creator<DhcpErrorEvent>() {
        public DhcpErrorEvent createFromParcel(Parcel in) {
            return new DhcpErrorEvent(in);
        }

        public DhcpErrorEvent[] newArray(int size) {
            return new DhcpErrorEvent[size];
        }
    };

    public static void logParseError(String ifName, int errorCode) {
        logEvent(new DhcpErrorEvent(ifName, errorCode));
    }

    public static void logReceiveError(String ifName) {
        logEvent(new DhcpErrorEvent(ifName, RECEIVE_ERROR));
    }

    public static int errorCodeWithOption(int errorCode, int option) {
        return (0xFFFF0000 & errorCode) | (0xFF & option);
    }

    private static int makeErrorCode(int type, int subtype) {
        return (type << 24) | ((0xFF & subtype) << 16);
    }

    @Override
    public String toString() {
        return String.format("DhcpErrorEvent(%s, %s)", ifName, Decoder.constants.get(errorCode));
    }

    final static class Decoder {
        static final SparseArray<String> constants =
                MessageUtils.findMessageNames(new Class[]{DhcpErrorEvent.class},
                new String[]{"L2_", "L3_", "L4_", "BOOTP_", "DHCP_", "BUFFER_", "RECEIVE_"});
    }
}
