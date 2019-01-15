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

import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * A class for storing service information that is advertised
 * over a Wi-Fi peer-to-peer setup
 *
 * @see WifiP2pUpnpServiceInfo
 * @see WifiP2pDnsSdServiceInfo
 */
public class WifiP2pServiceInfo implements Parcelable {

    /**
     * All service protocol types.
     */
    public static final int SERVICE_TYPE_ALL             = 0;

    /**
     * DNS based service discovery protocol.
     */
    public static final int SERVICE_TYPE_BONJOUR         = 1;

    /**
     * UPnP protocol.
     */
    public static final int SERVICE_TYPE_UPNP            = 2;

    /**
     * WS-Discovery protocol
     * @hide
     */
    public static final int SERVICE_TYPE_WS_DISCOVERY    = 3;

    /**
     * Vendor Specific protocol
     */
    public static final int SERVICE_TYPE_VENDOR_SPECIFIC = 255;

    /**
     * the list of query string for wpa_supplicant
     *
     * e.g)
     * # IP Printing over TCP (PTR) (RDATA=MyPrinter._ipp._tcp.local.)
     * {"bonjour", "045f697070c00c000c01", "094d795072696e746572c027"
     *
     * # IP Printing over TCP (TXT) (RDATA=txtvers=1,pdl=application/postscript)
     * {"bonjour", "096d797072696e746572045f697070c00c001001",
     *  "09747874766572733d311a70646c3d6170706c69636174696f6e2f706f7374736372797074"}
     *
     * [UPnP]
     * # UPnP uuid
     * {"upnp", "10", "uuid:6859dede-8574-59ab-9332-123456789012"}
     *
     * # UPnP rootdevice
     * {"upnp", "10", "uuid:6859dede-8574-59ab-9332-123456789012::upnp:rootdevice"}
     *
     * # UPnP device
     * {"upnp", "10", "uuid:6859dede-8574-59ab-9332-123456789012::urn:schemas-upnp
     * -org:device:InternetGatewayDevice:1"}
     *
     *  # UPnP service
     * {"upnp", "10", "uuid:6859dede-8574-59ab-9322-123456789012::urn:schemas-upnp
     * -org:service:ContentDirectory:2"}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private List<String> mQueryList;

    /**
     * This is only used in subclass.
     *
     * @param queryList query string for wpa_supplicant
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    protected WifiP2pServiceInfo(List<String> queryList) {
        if (queryList == null) {
            throw new IllegalArgumentException("query list cannot be null");
        }
        mQueryList = queryList;
    }

   /**
    * Return the list of the query string for wpa_supplicant.
    *
    * @return the list of the query string for wpa_supplicant.
    * @hide
    */
   public List<String> getSupplicantQueryList() {
       return mQueryList;
   }

   /**
    * Converts byte array to hex string.
    *
    * @param data
    * @return hex string.
    * @hide
    */
   static String bin2HexStr(byte[] data) {
       StringBuffer sb = new StringBuffer();

       for (byte b: data) {
           String s = null;
           try {
               s = Integer.toHexString(b & 0xff);
           } catch (Exception e) {
               e.printStackTrace();
               return null;
           }
           //add 0 padding
           if (s.length() == 1) {
               sb.append('0');
           }
           sb.append(s);
       }
       return sb.toString();
   }

   @Override
   public boolean equals(Object o) {
       if (o == this) {
           return true;
       }
       if (!(o instanceof WifiP2pServiceInfo)) {
           return false;
       }

       WifiP2pServiceInfo servInfo = (WifiP2pServiceInfo)o;
       return  mQueryList.equals(servInfo.mQueryList);
   }

   @Override
   public int hashCode() {
       int result = 17;
       result = 31 * result + (mQueryList == null ? 0 : mQueryList.hashCode());
       return result;
   }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStringList(mQueryList);
    }

    /** Implement the Parcelable interface {@hide} */
    @UnsupportedAppUsage
    public static final Creator<WifiP2pServiceInfo> CREATOR =
        new Creator<WifiP2pServiceInfo>() {
            public WifiP2pServiceInfo createFromParcel(Parcel in) {

                List<String> data = new ArrayList<String>();
                in.readStringList(data);
                return new WifiP2pServiceInfo(data);
            }

            public WifiP2pServiceInfo[] newArray(int size) {
                return new WifiP2pServiceInfo[size];
            }
        };
}
