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

import android.net.wifi.p2p.WifiP2pManager;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class for creating a service discovery request for use with
 * {@link WifiP2pManager#addServiceRequest} and {@link WifiP2pManager#removeServiceRequest}
 *
 * <p>This class is used to create service discovery request for custom
 * vendor specific service discovery protocol {@link WifiP2pServiceInfo#SERVICE_TYPE_VENDOR_SPECIFIC}
 * or to search all service protocols {@link WifiP2pServiceInfo#SERVICE_TYPE_ALL}.
 *
 * <p>For the purpose of creating a UPnP or Bonjour service request, use
 * {@link WifiP2pUpnpServiceRequest} or {@link WifiP2pDnsSdServiceRequest} respectively.
 *
 * {@see WifiP2pManager}
 * {@see WifiP2pUpnpServiceRequest}
 * {@see WifiP2pDnsSdServiceRequest}
 */
public class WifiP2pServiceRequest implements Parcelable {

    /**
     * Service discovery protocol. It's defined in table63 in Wi-Fi Direct specification.
     */
    private int mProtocolType;

    /**
     * The length of the service request TLV.
     * The value is equal to 2 plus the number of octets in the
     * query data field.
     */
    private int mLength;

    /**
     * Service transaction ID.
     * This is a nonzero value used to match the service request/response TLVs.
     */
    private int mTransId;

    /**
     * The hex dump string of query data for the requested service information.
     *
     * e.g) DnsSd apple file sharing over tcp (dns name=_afpovertcp._tcp.local.)
     * 0b5f6166706f766572746370c00c000c01
     */
    private String mQuery;

    /**
     * This constructor is only used in newInstance().
     *
     * @param protocolType service discovery protocol.
     * @param query The part of service specific query.
     * @hide
     */
    protected WifiP2pServiceRequest(int protocolType, String query) {
        validateQuery(query);

        mProtocolType = protocolType;
        mQuery = query;
        if (query != null) {
            mLength = query.length()/2 + 2;
        } else {
            mLength = 2;
        }
    }

    /**
     * This constructor is only used in Parcelable.
     *
     * @param serviceType service discovery type.
     * @param length the length of service discovery packet.
     * @param transId the transaction id
     * @param query The part of service specific query.
     */
    private WifiP2pServiceRequest(int serviceType, int length,
            int transId, String query) {
        mProtocolType = serviceType;
        mLength = length;
        mTransId = transId;
        mQuery = query;
    }

    /**
     * Return transaction id.
     *
     * @return transaction id
     * @hide
     */
    public int getTransactionId() {
        return mTransId;
    }

    /**
     * Set transaction id.
     *
     * @param id
     * @hide
     */
    public void setTransactionId(int id) {
        mTransId = id;
    }

    /**
     * Return wpa_supplicant request string.
     *
     * The format is the hex dump of the following frame.
     * <pre>
     * _______________________________________________________________
     * |        Length (2)        |   Type (1)   | Transaction ID (1) |
     * |                  Query Data (variable)                       |
     * </pre>
     *
     * @return wpa_supplicant request string.
     * @hide
     */
    public String getSupplicantQuery() {
        StringBuffer sb = new StringBuffer();
        // length is retained as little endian format.
        sb.append(String.format("%02x", (mLength) & 0xff));
        sb.append(String.format("%02x", (mLength >> 8) & 0xff));
        sb.append(String.format("%02x", mProtocolType));
        sb.append(String.format("%02x", mTransId));
        if (mQuery != null) {
            sb.append(mQuery);
        }

        return sb.toString();
    }

    /**
     * Validate query.
     *
     * <p>If invalid, throw IllegalArgumentException.
     * @param query The part of service specific query.
     */
    private void validateQuery(String query) {
        if (query == null) {
            return;
        }

        int UNSIGNED_SHORT_MAX = 0xffff;
        if (query.length()%2 == 1) {
            throw new IllegalArgumentException(
                    "query size is invalid. query=" + query);
        }
        if (query.length()/2 > UNSIGNED_SHORT_MAX) {
            throw new IllegalArgumentException(
                    "query size is too large. len=" + query.length());
        }

        // check whether query is hex string.
        query = query.toLowerCase();
        char[] chars = query.toCharArray();
        for (char c: chars) {
            if (!((c >= '0' && c <= '9') ||
                    (c >= 'a' && c <= 'f'))){
                throw new IllegalArgumentException(
                        "query should be hex string. query=" + query);
            }
        }
    }

    /**
     * Create a service discovery request.
     *
     * @param protocolType can be {@link WifiP2pServiceInfo#SERVICE_TYPE_ALL}
     * or {@link WifiP2pServiceInfo#SERVICE_TYPE_VENDOR_SPECIFIC}.
     * In order to create a UPnP or Bonjour service request, use
     * {@link WifiP2pUpnpServiceRequest} or {@link WifiP2pDnsSdServiceRequest}
     * respectively
     *
     * @param queryData hex string that is vendor specific.  Can be null.
     * @return service discovery request.
     */
    public static WifiP2pServiceRequest newInstance(int protocolType, String queryData) {
        return new WifiP2pServiceRequest(protocolType, queryData);
    }

    /**
     * Create a service discovery request.
     *
     * @param protocolType can be {@link WifiP2pServiceInfo#SERVICE_TYPE_ALL}
     * or {@link WifiP2pServiceInfo#SERVICE_TYPE_VENDOR_SPECIFIC}.
     * In order to create a UPnP or Bonjour service request, use
     * {@link WifiP2pUpnpServiceRequest} or {@link WifiP2pDnsSdServiceRequest}
     * respectively
     *
     * @return service discovery request.
     */
    public static WifiP2pServiceRequest newInstance(int protocolType ) {
        return new WifiP2pServiceRequest(protocolType, null);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof WifiP2pServiceRequest)) {
            return false;
        }

        WifiP2pServiceRequest req = (WifiP2pServiceRequest)o;

        /*
         * Not compare transaction id.
         * Transaction id may be changed on each service discovery operation.
         */
        if ((req.mProtocolType != mProtocolType) ||
                (req.mLength != mLength)) {
            return false;
        }

        if (req.mQuery == null && mQuery == null) {
            return true;
        } else if (req.mQuery != null) {
            return req.mQuery.equals(mQuery);
        }
        return false;
   }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + mProtocolType;
        result = 31 * result + mLength;
        result = 31 * result + (mQuery == null ? 0 : mQuery.hashCode());
        return result;
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mProtocolType);
        dest.writeInt(mLength);
        dest.writeInt(mTransId);
        dest.writeString(mQuery);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiP2pServiceRequest> CREATOR =
        new Creator<WifiP2pServiceRequest>() {
            public WifiP2pServiceRequest createFromParcel(Parcel in) {
                int servType = in.readInt();
                int length = in.readInt();
                int transId = in.readInt();
                String query = in.readString();
                return new WifiP2pServiceRequest(servType, length, transId, query);
            }

            public WifiP2pServiceRequest[] newArray(int size) {
                return new WifiP2pServiceRequest[size];
            }
        };
}
