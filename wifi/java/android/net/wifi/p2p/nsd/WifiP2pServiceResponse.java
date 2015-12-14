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

package android.net.wifi.p2p.nsd;

import android.net.wifi.p2p.WifiP2pDevice;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The class for a response of service discovery.
 *
 * @hide
 */
public class WifiP2pServiceResponse implements Parcelable {

    private static int MAX_BUF_SIZE = 1024;

    /**
     * Service type. It's defined in table63 in Wi-Fi Direct specification.
     */
    protected int mServiceType;

    /**
     * Status code of service discovery response.
     * It's defined in table65 in Wi-Fi Direct specification.
     * @see Status
     */
    protected int mStatus;

    /**
     * Service transaction ID.
     * This is a nonzero value used to match the service request/response TLVs.
     */
    protected int mTransId;

    /**
     * Source device.
     */
    protected WifiP2pDevice mDevice;

    /**
     * Service discovery response data based on the requested on
     * the service protocol type. The protocol format depends on the service type.
     */
    protected byte[] mData;


    /**
     * The status code of service discovery response.
     * Currently 4 status codes are defined and the status codes from  4 to 255
     * are reserved.
     *
     * See Wi-Fi Direct specification for the detail.
     */
    public static class Status {
        /** success */
        public static final int SUCCESS = 0;

        /** the service protocol type is not available */
        public static final int SERVICE_PROTOCOL_NOT_AVAILABLE = 1;

        /** the requested information is not available */
        public static final int REQUESTED_INFORMATION_NOT_AVAILABLE = 2;

        /** bad request */
        public static final int BAD_REQUEST = 3;

        /** @hide */
        public static String toString(int status) {
            switch(status) {
            case SUCCESS:
                return "SUCCESS";
            case SERVICE_PROTOCOL_NOT_AVAILABLE:
                return "SERVICE_PROTOCOL_NOT_AVAILABLE";
            case REQUESTED_INFORMATION_NOT_AVAILABLE:
                return "REQUESTED_INFORMATION_NOT_AVAILABLE";
            case BAD_REQUEST:
                return "BAD_REQUEST";
            default:
                return "UNKNOWN";
            }
        }

        /** not used */
        private Status() {}
    }

    /**
     * Hidden constructor. This is only used in framework.
     *
     * @param serviceType service discovery type.
     * @param status status code.
     * @param transId transaction id.
     * @param device source device.
     * @param data query data.
     */
    protected WifiP2pServiceResponse(int serviceType, int status, int transId,
            WifiP2pDevice device, byte[] data) {
        mServiceType = serviceType;
        mStatus = status;
        mTransId = transId;
        mDevice = device;
        mData = data;
    }

    /**
     * Return the service type of service discovery response.
     *
     * @return service discovery type.<br>
     * e.g) {@link WifiP2pServiceInfo#SERVICE_TYPE_BONJOUR}
     */
    public int getServiceType() {
        return mServiceType;
    }

    /**
     * Return the status code of service discovery response.
     *
     * @return status code.
     * @see Status
     */
    public int getStatus() {
        return mStatus;
    }

    /**
     * Return the transaction id of service discovery response.
     *
     * @return transaction id.
     * @hide
     */
    public int getTransactionId() {
        return mTransId;
    }

    /**
     * Return response data.
     *
     * <pre>Data format depends on service type
     *
     * @return a query or response data.
     */
    public byte[] getRawData() {
        return mData;
    }

    /**
     * Returns the source device of service discovery response.
     *
     * <pre>This is valid only when service discovery response.
     *
     * @return the source device of service discovery response.
     */
    public WifiP2pDevice getSrcDevice() {
        return mDevice;
    }

    /** @hide */
    public void setSrcDevice(WifiP2pDevice dev) {
        if (dev == null) return;
        this.mDevice = dev;
    }


    /**
     * Create the list of  WifiP2pServiceResponse instance from supplicant event.
     *
     * <pre>The format is as follows.
     * P2P-SERV-DISC-RESP &lt;address&gt; &lt;update indicator&gt; &lt;response data&gt;
     * e.g) P2P-SERV-DISC-RESP 02:03:7f:11:62:da 1 0300000101
     *
     * @param supplicantEvent wpa_supplicant event string.
     * @return if parse failed, return null
     * @hide
     */
    public static List<WifiP2pServiceResponse> newInstance(String supplicantEvent) {

        List<WifiP2pServiceResponse> respList = new ArrayList<WifiP2pServiceResponse>();
        String[] args = supplicantEvent.split(" ");
        if (args.length != 4) {
            return null;
        }
        WifiP2pDevice dev = new WifiP2pDevice();
        String srcAddr = args[1];
        dev.deviceAddress = srcAddr;
        //String updateIndicator = args[2];//not used.
        byte[] bin = hexStr2Bin(args[3]);
        if (bin == null) {
            return null;
        }

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bin));
        try {
            while (dis.available() > 0) {
                /*
                 * Service discovery header is as follows.
                 * ______________________________________________________________
                 * |           Length(2byte)     | Type(1byte) | TransId(1byte)}|
                 * ______________________________________________________________
                 * | status(1byte)  |            vendor specific(variable)      |
                 */
                // The length equals to 3 plus the number of octets in the vendor
                // specific content field. And this is little endian.
                int length = (dis.readUnsignedByte() +
                        (dis.readUnsignedByte() << 8)) - 3;
                int type = dis.readUnsignedByte();
                int transId = dis.readUnsignedByte();
                int status = dis.readUnsignedByte();
                if (length < 0) {
                    return null;
                }
                if (length == 0) {
                    if (status == Status.SUCCESS) {
                        respList.add(new WifiP2pServiceResponse(type, status,
                            transId, dev, null));
                    }
                    continue;
                }
                if (length > MAX_BUF_SIZE) {
                    dis.skip(length);
                    continue;
                }
                byte[] data = new byte[length];
                dis.readFully(data);

                WifiP2pServiceResponse resp;
                if (type ==  WifiP2pServiceInfo.SERVICE_TYPE_BONJOUR) {
                    resp = WifiP2pDnsSdServiceResponse.newInstance(status,
                            transId, dev, data);
                } else if (type == WifiP2pServiceInfo.SERVICE_TYPE_UPNP) {
                    resp = WifiP2pUpnpServiceResponse.newInstance(status,
                            transId, dev, data);
                } else {
                    resp = new WifiP2pServiceResponse(type, status, transId, dev, data);
                }
                if (resp != null && resp.getStatus() == Status.SUCCESS) {
                    respList.add(resp);
                }
            }
            return respList;
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (respList.size() > 0) {
            return respList;
        }
        return null;
    }

    /**
     * Converts hex string to byte array.
     *
     * @param hex hex string. if invalid, return null.
     * @return binary data.
     */
    private static byte[] hexStr2Bin(String hex) {
        int sz = hex.length()/2;
        byte[] b = new byte[hex.length()/2];

        for (int i=0;i<sz;i++) {
            try {
                b[i] = (byte)Integer.parseInt(hex.substring(i*2, i*2+2), 16);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
        return b;
    }

    @Override
    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("serviceType:").append(mServiceType);
        sbuf.append(" status:").append(Status.toString(mStatus));
        sbuf.append(" srcAddr:").append(mDevice.deviceAddress);
        sbuf.append(" data:").append(Arrays.toString(mData));
        return sbuf.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof WifiP2pServiceResponse)) {
            return false;
        }

        WifiP2pServiceResponse req = (WifiP2pServiceResponse)o;

        return (req.mServiceType == mServiceType) &&
            (req.mStatus == mStatus) &&
                equals(req.mDevice.deviceAddress, mDevice.deviceAddress) &&
                Arrays.equals(req.mData, mData);
    }

    private boolean equals(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        } else if (a != null) {
            return a.equals(b);
        }
        return false;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + mServiceType;
        result = 31 * result + mStatus;
        result = 31 * result + mTransId;
        result = 31 * result + (mDevice.deviceAddress == null ?
                0 : mDevice.deviceAddress.hashCode());
        result = 31 * result + (mData == null ? 0 : Arrays.hashCode(mData));
        return result;
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mServiceType);
        dest.writeInt(mStatus);
        dest.writeInt(mTransId);
        dest.writeParcelable(mDevice, flags);
        if (mData == null || mData.length == 0) {
            dest.writeInt(0);
        } else {
            dest.writeInt(mData.length);
            dest.writeByteArray(mData);
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiP2pServiceResponse> CREATOR =
        new Creator<WifiP2pServiceResponse>() {
            public WifiP2pServiceResponse createFromParcel(Parcel in) {

                int type = in.readInt();
                int status = in.readInt();
                int transId = in.readInt();
                WifiP2pDevice dev = (WifiP2pDevice)in.readParcelable(null);
                int len = in.readInt();
                byte[] data = null;
                if (len > 0) {
                    data = new byte[len];
                    in.readByteArray(data);
                }
                if (type ==  WifiP2pServiceInfo.SERVICE_TYPE_BONJOUR) {
                    return WifiP2pDnsSdServiceResponse.newInstance(status,
                            transId, dev, data);
                } else if (type == WifiP2pServiceInfo.SERVICE_TYPE_UPNP) {
                    return WifiP2pUpnpServiceResponse.newInstance(status,
                            transId, dev, data);
                }
                return new WifiP2pServiceResponse(type, status, transId, dev, data);
            }

            public WifiP2pServiceResponse[] newArray(int size) {
                return new WifiP2pServiceResponse[size];
            }
        };
}
