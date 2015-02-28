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
 * limitations under the License.
 */

package android.net.wifi.passpoint;

import android.net.wifi.WifiConfiguration;
import android.os.Parcelable;
import android.os.Parcel;
import android.security.Credentials;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


/** @hide */
public class WifiPasspointPolicy implements Parcelable {

    private final static String TAG = "PasspointPolicy";

    /** @hide */
    public static final int HOME_SP = 0;

    /** @hide */
    public static final int ROAMING_PARTNER = 1;

    /** @hide */
    public static final int UNRESTRICTED = 2;

    private String mName;
    private int mCredentialPriority;
    private int mRoamingPriority;
    private String mBssid;
    private String mSsid;
    private WifiPasspointCredential mCredential;
    private int mRestriction;// Permitted values are "HomeSP", "RoamingPartner", or "Unrestricted"
    private boolean mIsHomeSp;

    private final String INT_PRIVATE_KEY = "private_key";
    private final String INT_PHASE2 = "phase2";
    private final String INT_PASSWORD = "password";
    private final String INT_IDENTITY = "identity";
    private final String INT_EAP = "eap";
    private final String INT_CLIENT_CERT = "client_cert";
    private final String INT_CA_CERT = "ca_cert";
    private final String INT_ANONYMOUS_IDENTITY = "anonymous_identity";
    private final String INT_SIM_SLOT = "sim_slot";
    private final String INT_ENTERPRISEFIELD_NAME ="android.net.wifi.WifiConfiguration$EnterpriseField";
    private final String ISO8601DATEFORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    private final String ENTERPRISE_PHASE2_MSCHAPV2 = "auth=MSCHAPV2";
    private final String ENTERPRISE_PHASE2_MSCHAP = "auth=MSCHAP";

    /** @hide */
    public WifiPasspointPolicy(String name, String ssid,
            String bssid, WifiPasspointCredential pc,
            int restriction, boolean ishomesp) {
        mName = name;
        if (pc != null) {
            mCredentialPriority = pc.getPriority();
        }
        //PerProviderSubscription/<X+>/Policy/PreferredRoamingPartnerList/<X+>/Priority
        mRoamingPriority = 128; //default priority value of 128
        mSsid = ssid;
        mCredential = pc;
        mBssid = bssid;
        mRestriction = restriction;
        mIsHomeSp = ishomesp;
    }

    public String getSsid() {
        return mSsid;
    }

    /** @hide */
    public void setBssid(String bssid) {
        mBssid = bssid;
    }

    public String getBssid() {
        return mBssid;
    }

    /** @hide */
    public void setRestriction(int r) {
        mRestriction = r;
    }

    /** @hide */
    public int getRestriction() {
        return mRestriction;
    }

    /** @hide */
    public void setHomeSp(boolean b) {
        mIsHomeSp = b;
    }

    /** @hide */
    public boolean isHomeSp() {
        return mIsHomeSp;
    }

    /** @hide */
    public void setCredential(WifiPasspointCredential newCredential) {
        mCredential = newCredential;
    }

    public WifiPasspointCredential getCredential() {
        // TODO: return a copy
        return mCredential;
    }

    /** @hide */
    public void setCredentialPriority(int priority) {
        mCredentialPriority = priority;
    }

    /** @hide */
    public void setRoamingPriority(int priority) {
        mRoamingPriority = priority;
    }

    public int getCredentialPriority() {
        return mCredentialPriority;
    }

    public int getRoamingPriority() {
        return mRoamingPriority;
    }

    public WifiConfiguration createWifiConfiguration() {
        WifiConfiguration wfg = new WifiConfiguration();
        if (mBssid != null) {
            Log.d(TAG, "create bssid:" + mBssid);
            wfg.BSSID = mBssid;
        }

        if (mSsid != null) {
            Log.d(TAG, "create ssid:" + mSsid);
            wfg.SSID = mSsid;
        }
        //TODO: 1. add pmf configuration
        //      2. add ocsp configuration
        //      3. add eap-sim configuration
        /*Key management*/
        wfg.status = WifiConfiguration.Status.ENABLED;
        wfg.allowedKeyManagement.clear();
        wfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        wfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);

        /*Group Ciphers*/
        wfg.allowedGroupCiphers.clear();
        wfg.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        wfg.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);

        /*Protocols*/
        wfg.allowedProtocols.clear();
        wfg.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        wfg.allowedProtocols.set(WifiConfiguration.Protocol.WPA);

        Class[] enterpriseFieldArray  = WifiConfiguration.class.getClasses();
        Class<?> enterpriseFieldClass = null;


        for(Class<?> myClass : enterpriseFieldArray) {
            if(myClass.getName().equals(INT_ENTERPRISEFIELD_NAME)) {
                enterpriseFieldClass = myClass;
                break;
            }
        }
        Log.d(TAG, "class chosen " + enterpriseFieldClass.getName() );


        Field anonymousId = null, caCert = null, clientCert = null,
              eap = null, identity = null, password = null,
              phase2 = null, privateKey =  null;

        Field[] fields = WifiConfiguration.class.getFields();


        for (Field tempField : fields) {
            if (tempField.getName().trim().equals(INT_ANONYMOUS_IDENTITY)) {
                anonymousId = tempField;
                Log.d(TAG, "field " + anonymousId.getName() );
            } else if (tempField.getName().trim().equals(INT_CA_CERT)) {
                caCert = tempField;
            } else if (tempField.getName().trim().equals(INT_CLIENT_CERT)) {
                clientCert = tempField;
                Log.d(TAG, "field " + clientCert.getName() );
            } else if (tempField.getName().trim().equals(INT_EAP)) {
                eap = tempField;
                Log.d(TAG, "field " + eap.getName() );
            } else if (tempField.getName().trim().equals(INT_IDENTITY)) {
                identity = tempField;
                Log.d(TAG, "field " + identity.getName() );
            } else if (tempField.getName().trim().equals(INT_PASSWORD)) {
                password = tempField;
                Log.d(TAG, "field " + password.getName() );
            } else if (tempField.getName().trim().equals(INT_PHASE2)) {
                phase2 = tempField;
                Log.d(TAG, "field " + phase2.getName() );

            } else if (tempField.getName().trim().equals(INT_PRIVATE_KEY)) {
                privateKey = tempField;
            }
        }


        Method setValue = null;

        for(Method m: enterpriseFieldClass.getMethods()) {
            if(m.getName().trim().equals("setValue")) {
                Log.d(TAG, "method " + m.getName() );
                setValue = m;
                break;
            }
        }

        try {
            // EAP
            String eapmethod = mCredential.getType();
            Log.d(TAG, "eapmethod:" + eapmethod);
            setValue.invoke(eap.get(wfg), eapmethod);

            // Username, password, EAP Phase 2
            if ("TTLS".equals(eapmethod)) {
                setValue.invoke(phase2.get(wfg), ENTERPRISE_PHASE2_MSCHAPV2);
                setValue.invoke(identity.get(wfg), mCredential.getUserName());
                setValue.invoke(password.get(wfg), mCredential.getPassword());
                setValue.invoke(anonymousId.get(wfg), "anonymous@" + mCredential.getRealm());
            }

            // EAP CA Certificate
            String cacertificate = null;
            String rootCA = mCredential.getCaRootCertPath();
            if (rootCA == null){
                cacertificate = null;
            } else {
                cacertificate = "keystore://" + Credentials.WIFI + "HS20" + Credentials.CA_CERTIFICATE + rootCA;
            }
            Log.d(TAG, "cacertificate:" + cacertificate);
            setValue.invoke(caCert.get(wfg), cacertificate);

            //User certificate
            if ("TLS".equals(eapmethod)) {
                String usercertificate = null;
                String privatekey = null;
                String clientCertPath = mCredential.getClientCertPath();
                if (clientCertPath != null){
                    privatekey = "keystore://" + Credentials.WIFI + "HS20" + Credentials.USER_PRIVATE_KEY + clientCertPath;
                    usercertificate = "keystore://" + Credentials.WIFI + "HS20" + Credentials.USER_CERTIFICATE + clientCertPath;
                }
                Log.d(TAG, "privatekey:" + privatekey);
                Log.d(TAG, "usercertificate:" + usercertificate);
                if (privatekey != null && usercertificate != null) {
                    setValue.invoke(privateKey.get(wfg), privatekey);
                    setValue.invoke(clientCert.get(wfg), usercertificate);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "createWifiConfiguration err:" + e);
        }

        return wfg;
    }

    /** {@inheritDoc} @hide */
    public int compareTo(WifiPasspointPolicy another) {
        Log.d(TAG, "this:" + this);
        Log.d(TAG, "another:" + another);

        if (another == null) {
            return -1;
        } else if (this.mIsHomeSp == true && another.isHomeSp() == false) {
            //home sp priority is higher then roaming
            Log.d(TAG, "compare HomeSP  first, this is HomeSP, another isn't");
            return -1;
        } else if ((this.mIsHomeSp == true && another.isHomeSp() == true)) {
            Log.d(TAG, "both HomeSP");
            //if both home sp, compare credential priority
            if (this.mCredentialPriority < another.getCredentialPriority()) {
                Log.d(TAG, "this priority is higher");
                return -1;
            } else if (this.mCredentialPriority == another.getCredentialPriority()) {
                Log.d(TAG, "both priorities equal");
                //if priority still the same, compare name(ssid)
                if (this.mName.compareTo(another.mName) != 0) {
                    Log.d(TAG, "compare mName return:" + this.mName.compareTo(another.mName));
                    return this.mName.compareTo(another.mName);
                }
                /**
                 *if name still the same, compare credential
                 *the device may has two more credentials(TLS,SIM..etc)
                 *it can associate to one AP(same ssid). so we should compare by credential
                 */
                if (this.mCredential != null && another.mCredential != null) {
                    if (this.mCredential.compareTo(another.mCredential) != 0) {
                        Log.d(TAG,
                                "compare mCredential return:" + this.mName.compareTo(another.mName));
                        return this.mCredential.compareTo(another.mCredential);
                    }
                }
            } else {
                return 1;
            }
        } else if ((this.mIsHomeSp == false && another.isHomeSp() == false)) {
            Log.d(TAG, "both RoamingSp");
            //if both roaming sp, compare roaming priority(preferredRoamingPartnerList/<X+>/priority)
            if (this.mRoamingPriority < another.getRoamingPriority()) {
                Log.d(TAG, "this priority is higher");
                return -1;
            } else if (this.mRoamingPriority == another.getRoamingPriority()) {//priority equals, compare name
                Log.d(TAG, "both priorities equal");
                //if priority still the same, compare name(ssid)
                if (this.mName.compareTo(another.mName) != 0) {
                    Log.d(TAG, "compare mName return:" + this.mName.compareTo(another.mName));
                    return this.mName.compareTo(another.mName);
                }
                //if name still the same, compare credential
                if (this.mCredential != null && another.mCredential != null) {
                    if (this.mCredential.compareTo(another.mCredential) != 0) {
                        Log.d(TAG,
                                "compare mCredential return:"
                                        + this.mCredential.compareTo(another.mCredential));
                        return this.mCredential.compareTo(another.mCredential);
                    }
                }
            } else {
                return 1;
            }
        }

        Log.d(TAG, "both policies equal");
        return 0;
    }

    @Override
    /** @hide */
    public String toString() {
        return "PasspointPolicy: name=" + mName + " CredentialPriority=" + mCredentialPriority +
                " mRoamingPriority" + mRoamingPriority +
                " ssid=" + mSsid + " restriction=" + mRestriction +
                " ishomesp=" + mIsHomeSp + " Credential=" + mCredential;
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // TODO
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiPasspointPolicy> CREATOR =
            new Creator<WifiPasspointPolicy>() {
                @Override
                public WifiPasspointPolicy createFromParcel(Parcel in) {
                    return null;
                }

                @Override
                public WifiPasspointPolicy[] newArray(int size) {
                    return new WifiPasspointPolicy[size];
                }
            };
}
