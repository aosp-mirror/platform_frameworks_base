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

import android.net.wifi.WifiEnterpriseConfig;
import android.os.Parcelable;
import android.os.Parcel;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;


/**
 * A class representing a Wi-Fi Passpoint credential.
 * @hide
 */
public class WifiPasspointCredential implements Parcelable {

    private final static String TAG = "PasspointCredential";
    private final static boolean DBG = true;

    /** Wi-Fi nodes**/
    private String mWifiSpFqdn;

    /** PerProviderSubscription nodes **/
    private String mCredentialName;

    /** SubscriptionUpdate nodes **/
    private String mSubscriptionUpdateInterval;
    private String mSubscriptionUpdateMethod;
    private String mSubscriptionUpdateRestriction;
    private String mSubscriptionUpdateURI;
    private String mSubscriptionUpdateUsername;
    private String mSubscriptionUpdatePassword;

    /** HomeSP nodes **/
    private String mHomeSpFqdn;
    private String mFriendlyName;
    private Collection<WifiPasspointDmTree.HomeOIList> mHomeOIList;
    private Collection<WifiPasspointDmTree.OtherHomePartners> mOtherHomePartnerList;

    /** SubscriptionParameters nodes**/
    private String mCreationDate;
    private String mExpirationDate;

    /** Credential nodes **/
    private String mType;
    private String mInnerMethod;
    private String mCertType;
    private String mCertSha256Fingerprint;
    private String mUpdateIdentifier;
    private String mUsername;
    private String mPasswd;
    private String mRealm;
    private String mImsi;
    private String mMcc;
    private String mMnc;
    private String mCaRootCert;
    private String mClientCert;
    private boolean mCheckAaaServerCertStatus;

    /** Policy nodes **/
    private String mPolicyUpdateUri;
    private String mPolicyUpdateInterval;
    private String mPolicyUpdateUsername;
    private String mPolicyUpdatePassword;
    private String mPolicyUpdateRestriction;
    private String mPolicyUpdateMethod;
    private Collection<WifiPasspointDmTree.PreferredRoamingPartnerList> mPreferredRoamingPartnerList;
    private Collection<WifiPasspointDmTree.MinBackhaulThresholdNetwork> mMinBackhaulThresholdNetwork;
    private Collection<WifiPasspointDmTree.SPExclusionList> mSpExclusionList;
    private Collection<WifiPasspointDmTree.RequiredProtoPortTuple> mRequiredProtoPortTuple;
    private String mMaxBssLoad;

    /** CrednetialPriority node **/
    private int mCrednetialPriority;

    /** AAAServerTrustRoot nodes **/
    private String mAaaCertUrl;
    private String mAaaSha256Fingerprint;

    /** Others **/
    private boolean mIsMachineRemediation;
    private boolean mUserPreferred = false;
    private String mWifiTreePath;
    private WifiEnterpriseConfig mEnterpriseConfig;

    /** @hide */
    public WifiPasspointCredential() {}

    /**
     * Constructor
     * @param realm Realm of the passpoint credential
     * @param fqdn Fully qualified domain name (FQDN) of the credential
     * @param config Enterprise config, must be either EAP-TLS or EAP-TTLS
     * @see WifiEnterpriseConfig
     */
    public WifiPasspointCredential(String realm, String fqdn, WifiEnterpriseConfig config) {
        mRealm = realm;
        switch (config.getEapMethod()) {
            case WifiEnterpriseConfig.Eap.TLS:
            case WifiEnterpriseConfig.Eap.TTLS:
                mEnterpriseConfig = new WifiEnterpriseConfig(config);
                break;
            default:
                // ignore
        }
    }

    /** @hide */
    public WifiPasspointCredential(String type,
            String caroot,
            String clientcert,
            String mcc,
            String mnc,
            WifiPasspointDmTree.SpFqdn sp,
            WifiPasspointDmTree.CredentialInfo credinfo) {

        if (credinfo == null) {
            return;
        }

        mType = type;
        mCaRootCert = caroot;
        mClientCert = clientcert;

        mWifiSpFqdn = sp.nodeName;
        mUpdateIdentifier = sp.perProviderSubscription.UpdateIdentifier;

        mCredentialName = credinfo.nodeName;
        mOtherHomePartnerList = credinfo.homeSP.otherHomePartners.values();

        Set set = credinfo.aAAServerTrustRoot.entrySet();
        Iterator i = set.iterator();
        if (i.hasNext()) {
            Map.Entry entry3 = (Map.Entry) i.next();
            WifiPasspointDmTree.AAAServerTrustRoot aaa = (WifiPasspointDmTree.AAAServerTrustRoot) entry3.getValue();
            mAaaCertUrl = aaa.CertURL;
            mAaaSha256Fingerprint = aaa.CertSHA256Fingerprint;
        }

        mCertType = credinfo.credential.digitalCertificate.CertificateType;
        mCertSha256Fingerprint = credinfo.credential.digitalCertificate.CertSHA256Fingerprint;
        mUsername = credinfo.credential.usernamePassword.Username;
        mPasswd = credinfo.credential.usernamePassword.Password;
        mIsMachineRemediation = credinfo.credential.usernamePassword.MachineManaged;
        mInnerMethod = credinfo.credential.usernamePassword.eAPMethod.InnerMethod;
        mImsi = credinfo.credential.sim.IMSI;
        mMcc = mcc;
        mMnc = mnc;
        mCreationDate = credinfo.credential.CreationDate;
        mExpirationDate = credinfo.credential.ExpirationDate;
        mRealm = credinfo.credential.Realm;

        if (credinfo.credentialPriority == null) {
            mCrednetialPriority = 128;
        } else {
            mCrednetialPriority = Integer.parseInt(credinfo.credentialPriority);
        }

        mHomeSpFqdn = credinfo.homeSP.FQDN;

        mSubscriptionUpdateInterval = credinfo.subscriptionUpdate.UpdateInterval;
        mSubscriptionUpdateMethod = credinfo.subscriptionUpdate.UpdateMethod;
        mSubscriptionUpdateRestriction = credinfo.subscriptionUpdate.Restriction;
        mSubscriptionUpdateURI = credinfo.subscriptionUpdate.URI;
        mSubscriptionUpdateUsername = credinfo.subscriptionUpdate.usernamePassword.Username;
        mSubscriptionUpdatePassword = credinfo.subscriptionUpdate.usernamePassword.Password;

        mPolicyUpdateUri = credinfo.policy.policyUpdate.URI;
        mPolicyUpdateInterval = credinfo.policy.policyUpdate.UpdateInterval;
        mPolicyUpdateUsername = credinfo.policy.policyUpdate.usernamePassword.Username;
        mPolicyUpdatePassword = credinfo.policy.policyUpdate.usernamePassword.Password;
        mPolicyUpdateRestriction = credinfo.policy.policyUpdate.Restriction;
        mPolicyUpdateMethod = credinfo.policy.policyUpdate.UpdateMethod;
        mPreferredRoamingPartnerList = credinfo.policy.preferredRoamingPartnerList.values();
        mMinBackhaulThresholdNetwork = credinfo.policy.minBackhaulThreshold.values();
        mRequiredProtoPortTuple = credinfo.policy.requiredProtoPortTuple.values();
        mMaxBssLoad = credinfo.policy.maximumBSSLoadValue;
        mSpExclusionList = credinfo.policy.sPExclusionList.values();

        mHomeOIList = credinfo.homeSP.homeOIList.values();
        mFriendlyName = credinfo.homeSP.FriendlyName;
        mCheckAaaServerCertStatus = credinfo.credential.CheckAAAServerCertStatus;
    }

    /** @hide */
    public String getUpdateIdentifier() {
        return mUpdateIdentifier;
    }

    /** @hide */
    public String getUpdateMethod() {
        return mSubscriptionUpdateMethod;
    }

    /** @hide */
    public void setUpdateMethod(String method) {
        mSubscriptionUpdateMethod = method;
    }

    /** @hide */
    public String getWifiSpFqdn() {
        return mWifiSpFqdn;
    }

    /** @hide */
    public String getCredName() {
        return mCredentialName;
    }

    /** @hide */
    public String getType() {
        return mType;
    }

    /**
     * Get enterprise config of this Passpoint credential.
     * @return Enterprise config
     * @see WifiEnterpriseConfig
     */
    public WifiEnterpriseConfig getEnterpriseConfig() {
        return new WifiEnterpriseConfig(mEnterpriseConfig);
    }

    /**
     * Set enterprise config of this Passpoint credential.
     * @param config Enterprise config, must be either EAP-TLS or EAP-TTLS
     * @see WifiEnterpriseConfig
     */
    public void setEnterpriseConfig(WifiEnterpriseConfig config) {
        // TODO
    }

    /** @hide */
    public String getCertType() {
        return mCertType;
    }

    /** @hide */
    public String getCertSha256Fingerprint() {
        return mCertSha256Fingerprint;
    }

    /** @hide */
    public String getUserName() {
        return mUsername;
    }

    /** @hide */
    public String getPassword() {
        // TODO: guarded by connectivity internal
        return mPasswd;
    }

    /** @hide */
    public String getImsi() {
        return mImsi;
    }

    /** @hide */
    public String getMcc() {
        return mMcc;
    }

    /** @hide */
    public String getMnc() {
        return mMnc;
    }

    /** @hide */
    public String getCaRootCertPath() {
        return mCaRootCert;
    }

    /** @hide */
    public String getClientCertPath() {
        return mClientCert;
    }

    /**
     * Get the realm of this Passpoint credential.
     * @return Realm
     */
    public String getRealm() {
        return mRealm;
    }

    /**
     * Set the ream of this Passpoint credential.
     * @param realm Realm
     */
    public void setRealm(String realm) {
        mRealm = realm;
    }

    /** @hide */
    public int getPriority() {
        if (mUserPreferred) {
            return 0;
        }

        return mCrednetialPriority;
    }

    /**
     * Get the fully qualified domain name (FQDN) of this Passpoint credential.
     * @return FQDN
     */
    public String getHomeSpFqdn() {
        return mHomeSpFqdn;
    }

    /**
     * Set the fully qualified domain name (FQDN) of this Passpoint credential.
     * @param fqdn FQDN
     */
    public void setHomeFqdn(String fqdn) {
        mHomeSpFqdn = fqdn;
    }


    /** @hide */
    public Collection<WifiPasspointDmTree.OtherHomePartners> getOtherHomePartnerList() {
        return mOtherHomePartnerList;
    }

    /** @hide */
    public String getSubscriptionUpdateUsername() {
        return mSubscriptionUpdateUsername;
    }

    /** @hide */
    public String getSubscriptionUpdatePassword() {
        return mSubscriptionUpdatePassword;
    }

    /** @hide */
    public String getPolicyUpdateUri() {
        return mPolicyUpdateUri;
    }

    /** @hide */
    public String getPolicyUpdateInterval() {
        return mPolicyUpdateInterval;
    }

    /** @hide */
    public String getPolicyUpdateUsername() {
        return mPolicyUpdateUsername;
    }

    /** @hide */
    public String getPolicyUpdatePassword() {
        return mPolicyUpdatePassword;
    }

    /** @hide */
    public String getPolicyUpdateRestriction() {
        return mPolicyUpdateRestriction;
    }

    /** @hide */
    public String getPolicyUpdateMethod() {
        return mPolicyUpdateMethod;
    }

    /** @hide */
    public String getCreationDate() {
        return mCreationDate;
    }

    /** @hide */
    public String getExpirationDate() {
        return mExpirationDate;
    }

    /** @hide */
    public void setExpirationDate(String expirationdate) {
        mExpirationDate = expirationdate;
    }

    /** @hide */
    public Collection<WifiPasspointDmTree.PreferredRoamingPartnerList> getPreferredRoamingPartnerList() {
        return mPreferredRoamingPartnerList;
    }

    /** @hide */
    public Collection<WifiPasspointDmTree.HomeOIList> getHomeOiList() {
        return mHomeOIList;
    }

    /** @hide */
    public Collection<WifiPasspointDmTree.MinBackhaulThresholdNetwork> getBackhaulThresholdList() {
        return mMinBackhaulThresholdNetwork;
    }

    /** @hide */
    public Collection<WifiPasspointDmTree.RequiredProtoPortTuple> getRequiredProtoPortList() {
        return mRequiredProtoPortTuple;
    }

    /** @hide */
    public Collection<WifiPasspointDmTree.SPExclusionList> getSPExclusionList() {
        return mSpExclusionList;
    }

    /** @hide */
    public boolean getIsMachineRemediation() {
        return mIsMachineRemediation;
    }

    /** @hide */
    public String getAaaCertUrl() {
        return mAaaCertUrl;
    }

    /** @hide */
    public String getAaaSha256Fingerprint() {
        return mAaaSha256Fingerprint;
    }

    /** @hide */
    public String getSubscriptionUpdateRestriction() {
        return mSubscriptionUpdateRestriction;
    }

    /** @hide */
    public String getSubscriptionUpdateURI() {
        return mSubscriptionUpdateURI;
    }

    /** @hide */
    public String getSubscriptionUpdateInterval() {
        return mSubscriptionUpdateInterval;
    }

    /** @hide */
    public String getFriendlyName() {
        return mFriendlyName;
    }

    /** @hide */
    public String getMaxBssLoad() {
        return mMaxBssLoad;
    }

    /** @hide */
    public boolean getUserPreference() {
        return mUserPreferred;
    }

    /** @hide */
    public boolean getCheckAaaServerCertStatus() {
        return mCheckAaaServerCertStatus;
    }

    /** @hide */
    public void setUserPreference(boolean value) {
        mUserPreferred = value;
    }

    @Override
    /** @hide */
    public boolean equals(Object obj) {
        boolean result = false;
        if (obj instanceof WifiPasspointCredential) {
            final WifiPasspointCredential other = (WifiPasspointCredential) obj;
            if (this.mType.equals(other.mType)) {
                if (this.mType.equals("TTLS")) {
                    result = this.mUsername.equals(other.mUsername) &&
                            this.mPasswd.equals(other.mPasswd) &&
                            this.mRealm.equals(other.mRealm) &&
                            this.mHomeSpFqdn.equals(other.mHomeSpFqdn);
                }
                if (this.mType.equals("TLS")) {
                    result = this.mRealm.equals(other.mRealm) &&
                            this.mHomeSpFqdn.equals(other.mHomeSpFqdn) &&
                            this.mClientCert.equals(other.mClientCert);
                }
                if (this.mType.equals("SIM")) {
                    result = this.mMcc.equals(other.mMcc) &&
                            this.mMnc.equals(other.mMnc) &&
                            this.mImsi.equals(other.mImsi) &&
                            this.mHomeSpFqdn.equals(other.mHomeSpFqdn);
                }
            }
        }
        return result;
    }

    @Override
    /** @hide */
    public String toString() {
        StringBuffer sb = new StringBuffer();
        String none = "<none>";

        if (!DBG) {
            sb.append(none);
        } else {
            sb.append(", UpdateIdentifier: ")
            .append(mUpdateIdentifier == null ? none : mUpdateIdentifier)
            .append(", SubscriptionUpdateMethod: ")
            .append(mSubscriptionUpdateMethod == null ? none : mSubscriptionUpdateMethod)
            .append(", Type: ").append(mType == null ? none : mType)
            .append(", Username: ").append(mUsername == null ? none : mUsername)
            .append(", Passwd: ").append(mPasswd == null ? none : mPasswd)
            .append(", SubDMAccUsername: ")
            .append(mSubscriptionUpdateUsername == null ? none : mSubscriptionUpdateUsername)
            .append(", SubDMAccPassword: ")
            .append(mSubscriptionUpdatePassword == null ? none : mSubscriptionUpdatePassword)
            .append(", PolDMAccUsername: ")
            .append(mPolicyUpdateUsername == null ? none : mPolicyUpdateUsername)
            .append(", PolDMAccPassword: ")
            .append(mPolicyUpdatePassword == null ? none : mPolicyUpdatePassword)
            .append(", Imsi: ").append(mImsi == null ? none : mImsi)
            .append(", Mcc: ").append(mMcc == null ? none : mMcc)
            .append(", Mnc: ").append(mMnc == null ? none : mMnc)
            .append(", CaRootCert: ").append(mCaRootCert == null ? none : mCaRootCert)
            .append(", Realm: ").append(mRealm == null ? none : mRealm)
            .append(", Priority: ").append(mCrednetialPriority)
            .append(", Fqdn: ").append(mHomeSpFqdn == null ? none : mHomeSpFqdn)
            .append(", Otherhomepartners: ")
            .append(mOtherHomePartnerList == null ? none : mOtherHomePartnerList)
            .append(", ExpirationDate: ")
            .append(mExpirationDate == null ? none : mExpirationDate)
            .append(", MaxBssLoad: ").append(mMaxBssLoad == null ? none : mMaxBssLoad)
            .append(", SPExclusionList: ").append(mSpExclusionList);

            if (mPreferredRoamingPartnerList != null) {
                sb.append("PreferredRoamingPartnerList:");
                for (WifiPasspointDmTree.PreferredRoamingPartnerList prpListItem : mPreferredRoamingPartnerList) {
                    sb.append("[fqdnmatch:").append(prpListItem.FQDN_Match).
                            append(", priority:").append(prpListItem.Priority).
                            append(", country:").append(prpListItem.Country).append("]");
                }
            }

            if (mHomeOIList != null) {
                sb.append("HomeOIList:");
                for (WifiPasspointDmTree.HomeOIList HomeOIListItem : mHomeOIList) {
                    sb.append("[HomeOI:").append(HomeOIListItem.HomeOI).
                            append(", HomeOIRequired:").append(HomeOIListItem.HomeOIRequired).
                            append("]");
                }
            }

            if (mMinBackhaulThresholdNetwork != null) {
                sb.append("BackHaulThreshold:");
                for (WifiPasspointDmTree.MinBackhaulThresholdNetwork BhtListItem : mMinBackhaulThresholdNetwork) {
                    sb.append("[networkType:").append(BhtListItem.NetworkType).
                            append(", dlBandwidth:").append(BhtListItem.DLBandwidth).
                            append(", ulBandwidth:").append(BhtListItem.ULBandwidth).
                            append("]");
                }
            }

            if (mRequiredProtoPortTuple != null) {
                sb.append("WifiMORequiredProtoPortTupleList:");
                for (WifiPasspointDmTree.RequiredProtoPortTuple RpptListItem : mRequiredProtoPortTuple) {
                    sb.append("[IPProtocol:").append(RpptListItem.IPProtocol).
                            append(", PortNumber:").append(RpptListItem.PortNumber).
                            append("]");
                }
            }
        }
        return sb.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mWifiSpFqdn);
        dest.writeString(mCredentialName);
        dest.writeString(mType);
        dest.writeInt(mCrednetialPriority);
        dest.writeString(mHomeSpFqdn);
        dest.writeString(mRealm);
    }

    /** Implement the Parcelable interface {@hide} */
    public void readFromParcel(Parcel in) {
        mWifiSpFqdn = in.readString();
        mCredentialName = in.readString();
        mType = in.readString();
        mCrednetialPriority = in.readInt();
        mHomeSpFqdn = in.readString();
        mRealm = in.readString();
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiPasspointCredential> CREATOR =
            new Creator<WifiPasspointCredential>() {
                public WifiPasspointCredential createFromParcel(Parcel in) {
                    WifiPasspointCredential pc = new WifiPasspointCredential();
                    pc.mWifiSpFqdn = in.readString();
                    pc.mCredentialName = in.readString();
                    pc.mType = in.readString();
                    pc.mCrednetialPriority = in.readInt();
                    pc.mHomeSpFqdn = in.readString();
                    pc.mRealm = in.readString();
                    return pc;
                }

                public WifiPasspointCredential[] newArray(int size) {
                    return new WifiPasspointCredential[size];
                }
            };

    /** @hide */
    public int compareTo(WifiPasspointCredential another) {

        //The smaller the higher
        if (mCrednetialPriority < another.mCrednetialPriority) {
            return -1;
        } else if (mCrednetialPriority == another.mCrednetialPriority) {
            return this.mType.compareTo(another.mType);
        } else {
            return 1;
        }
    }

    @Override
    /** @hide */
    public int hashCode() {
        int hash = 208;
        if (mType != null) {
            hash += mType.hashCode();
        }
        if (mRealm != null) {
            hash += mRealm.hashCode();
        }
        if (mHomeSpFqdn != null) {
            hash += mHomeSpFqdn.hashCode();
        }
        if (mUsername != null) {
            hash += mUsername.hashCode();
        }
        if (mPasswd != null) {
            hash += mPasswd.hashCode();
        }

        return hash;
    }
}
