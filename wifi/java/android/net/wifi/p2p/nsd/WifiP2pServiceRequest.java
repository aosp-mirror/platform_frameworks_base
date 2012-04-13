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
 * A class for a request of service discovery.
 *
 * <p>This class is used when you create customized service discovery request.
 * e.g) vendor specific request/ws discovery etc.
 *
 * <p>If you want to create UPnP or Bonjour service request, then you had better
 * use {@link WifiP2pUpnpServiceRequest} or {@link WifiP2pBonjourServiceRequest}.
 *
 * @see WifiP2pUpnpServiceRequest
 * @see WifiP2pBonjourServiceRequest
 * @hide
 */
public class WifiP2pServiceRequest implements Parcelable {

    /**
     * Service type. It's defined in table63 in Wi-Fi Direct specification.
     */
    private int mServiceType;

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
     * e.g) Bonjour apple file sharing over tcp (dns name=_afpovertcp._tcp.local.)
     * 0b5f6166706f766572746370c00c000c01
     */
    private String mQuery;

    /**
     * This constructor is only used in newInstance().
     *
     * @param serviceType service discovery type.
     * @param query The part of service specific query.
     * @hide
     */
    protected WifiP2pServiceRequest(int serviceType, String query) {
        validateQuery(query);

        mServiceType = serviceType;
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
        mServiceType = serviceType;
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
        sb.append(String.format("%02x", mServiceType));
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
     * Create service discovery request.
     *
     * <p>The created instance is set to framework by
     * {@link WifiP2pManager#addLocalService}.
     *
     * @param serviceType service type.<br>
     * e.g) {@link WifiP2pServiceInfo#SERVICE_TYPE_ALL},
     *  {@link WifiP2pServiceInfo#SERVICE_TYPE_WS_DISCOVERY},
     * {@link WifiP2pServiceInfo#SERVICE_TYPE_VENDOR_SPECIFIC}.
     * If you want to use UPnP or Bonjour, you create  the request by
     * {@link WifiP2pUpnpServiceRequest} or {@link WifiP2pBonjourServiceRequest}
     *
     * @param query hex string. if null, all specified services are requested.
     * @return service discovery request.
     */
    public static WifiP2pServiceRequest newInstance(int serviceType, String query) {
        return new WifiP2pServiceRequest(serviceType, query);
    }

    /**
     * Create all service discovery request.
     *
     * <p>The created instance is set to framework by
     * {@link WifiP2pManager#addLocalService}.
     *
     * @param serviceType service type.<br>
     * e.g) {@link WifiP2pServiceInfo#SERVICE_TYPE_ALL},
     *  {@link WifiP2pServiceInfo#SERVICE_TYPE_WS_DISCOVERY},
     * {@link WifiP2pServiceInfo#SERVICE_TYPE_VENDOR_SPECIFIC}.
     * If you want to use UPnP or Bonjour, you create  the request by
     * {@link WifiP2pUpnpServiceRequest} or {@link WifiP2pBonjourServiceRequest}
     *
     * @return service discovery request.
     */
    public static WifiP2pServiceRequest newInstance(int serviceType) {
        return new WifiP2pServiceRequest(serviceType, null);
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
        if ((req.mServiceType != mServiceType) ||
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
        result = 31 * result + mServiceType;
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
        dest.writeInt(mServiceType);
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
