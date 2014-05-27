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
import java.util.Set;
import java.util.Iterator;
import java.util.Map;

/**
 * A class representing a Wi-Fi Passpoint credential.
 * @hide
 */
public class WifiPasspointCredential implements Parcelable {

    private final static String TAG = "PasspointCredential";
    private String mWifiTreePath;
    private String mWifiSPFQDN;
    private String mCredentialName;
    private String mUpdateIdentifier;
    private String mSubscriptionUpdateMethod;
    private WifiEnterpriseConfig mEnterpriseConfig;
    private String mType;
    private String mInnerMethod;
    private String mCertType;
    private String mCertSha256Fingerprint;
    private String mUsername;
    private String mPasswd;
    private String mImsi;
    private String mMcc;
    private String mMnc;
    private String mCaRootCert;
    private String mRealm;
    private int mPriority; //User preferred priority; The smaller, the higher
    private boolean mUserPreferred = false;
    private String mHomeSpFqdn;
    private String mFriendlyName;
    private String mOtherhomepartnerFqdn;
    private String mClientCert;
    private String mCreationDate;
    private String mExpirationDate;

    private String mSubscriptionDMAccUsername;
    private String mSubscriptionDMAccPassword;
    private String mSubscriptionUpdateInterval;

    private String mPolicyUpdateURI;
    private String mPolicyUpdateInterval;
    private String mPolicyDMAccUsername;
    private String mPolicyDMAccPassword;
    private String mPolicyUpdateRestriction;
    private String mPolicyUpdateMethod;

    private Collection<WifiPasspointDmTree.PreferredRoamingPartnerList> mPreferredRoamingPartnerList;
    private Collection<WifiPasspointDmTree.HomeOIList> mHomeOIList;
    private Collection<WifiPasspointDmTree.MinBackhaulThresholdNetwork> mMinBackhaulThresholdNetwork;
    private Collection<WifiPasspointDmTree.RequiredProtoPortTuple> mRequiredProtoPortTuple;
    private Collection<WifiPasspointDmTree.SPExclusionList> mSpExclusionList;
    private String mMaxBssLoad;

    private boolean mIsMachineRemediation;

    private String mAAACertURL;
    private String mAAASha256Fingerprint;

    private String mSubscriptionUpdateRestriction;
    private String mSubscriptionUpdateURI;

    private boolean mCheckAaaServerCertStatus;

    /** @hide */
    public WifiPasspointCredential() {

    }

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
            WifiPasspointDmTree.SpFqdn sp,
            WifiPasspointDmTree.CredentialInfo credinfo) {

        if (credinfo == null) {
            return;
        }

        mType = type;
        mCaRootCert = caroot;
        mClientCert = clientcert;

        mWifiSPFQDN = sp.nodeName;
        mUpdateIdentifier = sp.perProviderSubscription.UpdateIdentifier;

        mCredentialName = credinfo.nodeName;
        Set set = credinfo.homeSP.otherHomePartners.entrySet();
        Iterator i = set.iterator();
        if (i.hasNext()) {
            Map.Entry entry3 = (Map.Entry) i.next();
            WifiPasspointDmTree.OtherHomePartners ohp = (WifiPasspointDmTree.OtherHomePartners) entry3.getValue();
            mOtherhomepartnerFqdn = ohp.FQDN;
        }

        set = credinfo.aAAServerTrustRoot.entrySet();
        i = set.iterator();
        if (i.hasNext()) {
            Map.Entry entry3 = (Map.Entry) i.next();
            WifiPasspointDmTree.AAAServerTrustRoot aaa = (WifiPasspointDmTree.AAAServerTrustRoot) entry3.getValue();
            mAAACertURL = aaa.CertURL;
            mAAASha256Fingerprint = aaa.CertSHA256Fingerprint;
        }

        mCertType = credinfo.credential.digitalCertificate.CertificateType;
        mCertSha256Fingerprint = credinfo.credential.digitalCertificate.CertSHA256Fingerprint;
        mUsername = credinfo.credential.usernamePassword.Username;
        mPasswd = credinfo.credential.usernamePassword.Password;
        mIsMachineRemediation = credinfo.credential.usernamePassword.MachineManaged;
        mInnerMethod = credinfo.credential.usernamePassword.eAPMethod.InnerMethod;
        mImsi = credinfo.credential.sim.IMSI;
        mCreationDate = credinfo.credential.CreationDate;
        mExpirationDate = credinfo.credential.ExpirationDate;
        mRealm = credinfo.credential.Realm;

        if (credinfo.credentialPriority == null) {
            credinfo.credentialPriority = "128";
        }
        mPriority = Integer.parseInt(credinfo.credentialPriority);

        mHomeSpFqdn = credinfo.homeSP.FQDN;

        mSubscriptionUpdateInterval = credinfo.subscriptionUpdate.UpdateInterval;
        mSubscriptionUpdateMethod = credinfo.subscriptionUpdate.UpdateMethod;
        mSubscriptionUpdateRestriction = credinfo.subscriptionUpdate.Restriction;
        mSubscriptionUpdateURI = credinfo.subscriptionUpdate.URI;
        mSubscriptionDMAccUsername = credinfo.subscriptionUpdate.usernamePassword.Username;
        mSubscriptionDMAccPassword = credinfo.subscriptionUpdate.usernamePassword.Password;

        mPolicyUpdateURI = credinfo.policy.policyUpdate.URI;
        mPolicyUpdateInterval = credinfo.policy.policyUpdate.UpdateInterval;
        mPolicyDMAccUsername = credinfo.policy.policyUpdate.usernamePassword.Username;
        mPolicyDMAccPassword = credinfo.policy.policyUpdate.usernamePassword.Password;
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

        mWifiSPFQDN = sp.nodeName;
        mUpdateIdentifier = sp.perProviderSubscription.UpdateIdentifier;

        mCredentialName = credinfo.nodeName;
        Set set = credinfo.homeSP.otherHomePartners.entrySet();
        Iterator i = set.iterator();
        if (i.hasNext()) {
            Map.Entry entry3 = (Map.Entry) i.next();
            WifiPasspointDmTree.OtherHomePartners ohp = (WifiPasspointDmTree.OtherHomePartners) entry3.getValue();
            mOtherhomepartnerFqdn = ohp.FQDN;
        }

        set = credinfo.aAAServerTrustRoot.entrySet();
        i = set.iterator();
        if (i.hasNext()) {
            Map.Entry entry3 = (Map.Entry) i.next();
            WifiPasspointDmTree.AAAServerTrustRoot aaa = (WifiPasspointDmTree.AAAServerTrustRoot) entry3.getValue();
            mAAACertURL = aaa.CertURL;
            mAAASha256Fingerprint = aaa.CertSHA256Fingerprint;
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
            credinfo.credentialPriority = "128";
        }
        mPriority = Integer.parseInt(credinfo.credentialPriority);

        mHomeSpFqdn = credinfo.homeSP.FQDN;

        mSubscriptionUpdateMethod = credinfo.subscriptionUpdate.UpdateMethod;
        mSubscriptionUpdateRestriction = credinfo.subscriptionUpdate.Restriction;
        mSubscriptionUpdateURI = credinfo.subscriptionUpdate.URI;
        mSubscriptionDMAccUsername = credinfo.subscriptionUpdate.usernamePassword.Username;
        mSubscriptionDMAccPassword = credinfo.subscriptionUpdate.usernamePassword.Password;

        mPolicyUpdateURI = credinfo.policy.policyUpdate.URI;
        mPolicyUpdateInterval = credinfo.policy.policyUpdate.UpdateInterval;
        mPolicyDMAccUsername = credinfo.policy.policyUpdate.usernamePassword.Username;
        mPolicyDMAccPassword = credinfo.policy.policyUpdate.usernamePassword.Password;
        mPolicyUpdateRestriction = credinfo.policy.policyUpdate.Restriction;
        mPolicyUpdateMethod = credinfo.policy.policyUpdate.UpdateMethod;
        mPreferredRoamingPartnerList = credinfo.policy.preferredRoamingPartnerList.values();
        mMinBackhaulThresholdNetwork = credinfo.policy.minBackhaulThreshold.values();
        mRequiredProtoPortTuple = credinfo.policy.requiredProtoPortTuple.values();
        mMaxBssLoad = credinfo.policy.maximumBSSLoadValue;
        mSpExclusionList = credinfo.policy.sPExclusionList.values();

        mHomeOIList = credinfo.homeSP.homeOIList.values();
        mFriendlyName = credinfo.homeSP.FriendlyName;
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
    public String getWifiSPFQDN() {
        return mWifiSPFQDN;
    }

    /** @hide */
    public String getCredName() {
        return mCredentialName;
    }

    /** @hide */
    public String getEapMethodStr() {
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

        return mPriority;
    }

    /**
     * Get the fully qualified domain name (FQDN) of this Passpoint credential.
     * @return FQDN
     */
    public String getFqdn() {
        return mHomeSpFqdn;
    }

    /**
     * Set the fully qualified domain name (FQDN) of this Passpoint credential.
     * @param fqdn FQDN
     */
    public void setFqdn(String fqdn) {
        mHomeSpFqdn = fqdn;
    }


    /** @hide */
    public String getOtherhomepartners() {
        return mOtherhomepartnerFqdn;
    }

    /** @hide */
    public String getSubscriptionDMAccUsername() {
        return mSubscriptionDMAccUsername;
    }

    /** @hide */
    public String getSubscriptionDMAccPassword() {
        return mSubscriptionDMAccPassword;
    }

    /** @hide */
    public String getPolicyUpdateURI() {
        return mPolicyUpdateURI;
    }

    /** @hide */
    public String getPolicyUpdateInterval() {
        return mPolicyUpdateInterval;
    }

    /** @hide */
    public String getPolicyDMAccUsername() {
        return mPolicyDMAccUsername;
    }

    /** @hide */
    public String getPolicyDMAccPassword() {
        return mPolicyDMAccPassword;
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
    public Collection<WifiPasspointDmTree.PreferredRoamingPartnerList> getPrpList() {
        return mPreferredRoamingPartnerList;
    }

    /** @hide */
    public Collection<WifiPasspointDmTree.HomeOIList> getHomeOIList() {
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
    public String getAAACertURL() {
        return mAAACertURL;
    }

    /** @hide */
    public String getAAASha256Fingerprint() {
        return mAAASha256Fingerprint;
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
                            this.mHomeSpFqdn.equals(other.mHomeSpFqdn);
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

        sb.append(", UpdateIdentifier: ")
                .append(mUpdateIdentifier == null ? none : mUpdateIdentifier).
                append(", SubscriptionUpdateMethod: ")
                .append(mSubscriptionUpdateMethod == null ? none : mSubscriptionUpdateMethod).
                append(", Type: ").append(mType == null ? none : mType).
                append(", Username: ").append(mUsername == null ? none : mUsername).
                append(", Passwd: ").append(mPasswd == null ? none : mPasswd).
                append(", SubDMAccUsername: ")
                .append(mSubscriptionDMAccUsername == null ? none : mSubscriptionDMAccUsername).
                append(", SubDMAccPassword: ")
                .append(mSubscriptionDMAccPassword == null ? none : mSubscriptionDMAccPassword).
                append(", PolDMAccUsername: ")
                .append(mPolicyDMAccUsername == null ? none : mPolicyDMAccUsername).
                append(", PolDMAccPassword: ")
                .append(mPolicyDMAccPassword == null ? none : mPolicyDMAccPassword).
                append(", Imsi: ").append(mImsi == null ? none : mImsi).
                append(", Mcc: ").append(mMcc == null ? none : mMcc).
                append(", Mnc: ").append(mMnc == null ? none : mMnc).
                append(", CaRootCert: ").append(mCaRootCert == null ? none : mCaRootCert).
                append(", Realm: ").append(mRealm == null ? none : mRealm).
                append(", Priority: ").append(mPriority).
                append(", Fqdn: ").append(mHomeSpFqdn == null ? none : mHomeSpFqdn).
                append(", Otherhomepartners: ")
                .append(mOtherhomepartnerFqdn == null ? none : mOtherhomepartnerFqdn).
                append(", ExpirationDate: ")
                .append(mExpirationDate == null ? none : mExpirationDate).
                append(", MaxBssLoad: ").append(mMaxBssLoad == null ? none : mMaxBssLoad).
                append(", SPExclusionList: ").append(mSpExclusionList);

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

        return sb.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mType);
        dest.writeString(mUsername);
        dest.writeString(mPasswd);
        dest.writeString(mImsi);
        dest.writeString(mMcc);
        dest.writeString(mMnc);
        dest.writeString(mCaRootCert);
        dest.writeString(mRealm);
        dest.writeInt(mPriority);
        dest.writeString(mHomeSpFqdn);
        dest.writeString(mOtherhomepartnerFqdn);
        dest.writeString(mClientCert);
        dest.writeString(mExpirationDate);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiPasspointCredential> CREATOR =
            new Creator<WifiPasspointCredential>() {
                public WifiPasspointCredential createFromParcel(Parcel in) {
                    WifiPasspointCredential pc = new WifiPasspointCredential();
                    pc.mType = in.readString();
                    pc.mUsername = in.readString();
                    pc.mPasswd = in.readString();
                    pc.mImsi = in.readString();
                    pc.mMcc = in.readString();
                    pc.mMnc = in.readString();
                    pc.mCaRootCert = in.readString();
                    pc.mRealm = in.readString();
                    pc.mPriority = in.readInt();
                    pc.mHomeSpFqdn = in.readString();
                    pc.mOtherhomepartnerFqdn = in.readString();
                    pc.mClientCert = in.readString();
                    pc.mExpirationDate = in.readString();
                    return pc;
                }

                public WifiPasspointCredential[] newArray(int size) {
                    return new WifiPasspointCredential[size];
                }
            };

    /** @hide */
    public int compareTo(WifiPasspointCredential another) {

        //The smaller the higher
        if (mPriority < another.mPriority) {
            return -1;
        } else if (mPriority == another.mPriority) {
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
