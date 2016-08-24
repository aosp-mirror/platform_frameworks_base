package com.android.hotspot2.pps;

import com.android.hotspot2.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HomeSP {
    private final Map<String, Long> mSSIDs;        // SSID, HESSID, [0,N]
    private final String mFQDN;
    private final DomainMatcher mDomainMatcher;
    private final Set<String> mOtherHomePartners;
    private final HashSet<Long> mRoamingConsortiums;    // [0,N]
    private final Set<Long> mMatchAnyOIs;           // [0,N]
    private final List<Long> mMatchAllOIs;          // [0,N]

    private final Credential mCredential;

    // Informational:
    private final String mFriendlyName;             // [1]
    private final String mIconURL;                  // [0,1]

    private final Policy mPolicy;
    private final int mCredentialPriority;
    private final Map<String, String> mAAATrustRoots;
    private final UpdateInfo mSubscriptionUpdate;
    private final SubscriptionParameters mSubscriptionParameters;
    private final int mUpdateIdentifier;

    @Deprecated
    public HomeSP(Map<String, Long> ssidMap,
                   /*@NotNull*/ String fqdn,
                   /*@NotNull*/ HashSet<Long> roamingConsortiums,
                   /*@NotNull*/ Set<String> otherHomePartners,
                   /*@NotNull*/ Set<Long> matchAnyOIs,
                   /*@NotNull*/ List<Long> matchAllOIs,
                   String friendlyName,
                   String iconURL,
                   Credential credential) {

        mSSIDs = ssidMap;
        List<List<String>> otherPartners = new ArrayList<>(otherHomePartners.size());
        for (String otherPartner : otherHomePartners) {
            otherPartners.add(Utils.splitDomain(otherPartner));
        }
        mOtherHomePartners = otherHomePartners;
        mFQDN = fqdn;
        mDomainMatcher = new DomainMatcher(Utils.splitDomain(fqdn), otherPartners);
        mRoamingConsortiums = roamingConsortiums;
        mMatchAnyOIs = matchAnyOIs;
        mMatchAllOIs = matchAllOIs;
        mFriendlyName = friendlyName;
        mIconURL = iconURL;
        mCredential = credential;

        mPolicy = null;
        mCredentialPriority = -1;
        mAAATrustRoots = null;
        mSubscriptionUpdate = null;
        mSubscriptionParameters = null;
        mUpdateIdentifier = -1;
    }

    public HomeSP(Map<String, Long> ssidMap,
                   /*@NotNull*/ String fqdn,
                   /*@NotNull*/ HashSet<Long> roamingConsortiums,
                   /*@NotNull*/ Set<String> otherHomePartners,
                   /*@NotNull*/ Set<Long> matchAnyOIs,
                   /*@NotNull*/ List<Long> matchAllOIs,
                   String friendlyName,
                   String iconURL,
                   Credential credential,

                   Policy policy,
                   int credentialPriority,
                   Map<String, String> AAATrustRoots,
                   UpdateInfo subscriptionUpdate,
                   SubscriptionParameters subscriptionParameters,
                   int updateIdentifier) {

        mSSIDs = ssidMap;
        List<List<String>> otherPartners = new ArrayList<>(otherHomePartners.size());
        for (String otherPartner : otherHomePartners) {
            otherPartners.add(Utils.splitDomain(otherPartner));
        }
        mOtherHomePartners = otherHomePartners;
        mFQDN = fqdn;
        mDomainMatcher = new DomainMatcher(Utils.splitDomain(fqdn), otherPartners);
        mRoamingConsortiums = roamingConsortiums;
        mMatchAnyOIs = matchAnyOIs;
        mMatchAllOIs = matchAllOIs;
        mFriendlyName = friendlyName;
        mIconURL = iconURL;
        mCredential = credential;

        mPolicy = policy;
        mCredentialPriority = credentialPriority;
        mAAATrustRoots = AAATrustRoots;
        mSubscriptionUpdate = subscriptionUpdate;
        mSubscriptionParameters = subscriptionParameters;
        mUpdateIdentifier = updateIdentifier;
    }

    public int getUpdateIdentifier() {
        return mUpdateIdentifier;
    }

    public UpdateInfo getSubscriptionUpdate() {
        return mSubscriptionUpdate;
    }

    public Policy getPolicy() {
        return mPolicy;
    }

    private String imsiMatch(List<String> imsis, String mccMnc) {
        if (mCredential.getImsi().matchesMccMnc(mccMnc)) {
            for (String imsi : imsis) {
                if (imsi.startsWith(mccMnc)) {
                    return imsi;
                }
            }
        }
        return null;
    }

    public String getFQDN() {
        return mFQDN;
    }

    public String getFriendlyName() {
        return mFriendlyName;
    }

    public HashSet<Long> getRoamingConsortiums() {
        return mRoamingConsortiums;
    }

    public Credential getCredential() {
        return mCredential;
    }

    public Map<String, Long> getSSIDs() {
        return mSSIDs;
    }

    public Collection<String> getOtherHomePartners() {
        return mOtherHomePartners;
    }

    public Set<Long> getMatchAnyOIs() {
        return mMatchAnyOIs;
    }

    public List<Long> getMatchAllOIs() {
        return mMatchAllOIs;
    }

    public String getIconURL() {
        return mIconURL;
    }

    public boolean deepEquals(HomeSP other) {
        return mFQDN.equals(other.mFQDN) &&
                mSSIDs.equals(other.mSSIDs) &&
                mOtherHomePartners.equals(other.mOtherHomePartners) &&
                mRoamingConsortiums.equals(other.mRoamingConsortiums) &&
                mMatchAnyOIs.equals(other.mMatchAnyOIs) &&
                mMatchAllOIs.equals(other.mMatchAllOIs) &&
                mFriendlyName.equals(other.mFriendlyName) &&
                Utils.compare(mIconURL, other.mIconURL) == 0 &&
                mCredential.equals(other.mCredential);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        } else if (thatObject == null || getClass() != thatObject.getClass()) {
            return false;
        }

        HomeSP that = (HomeSP) thatObject;
        return mFQDN.equals(that.mFQDN);
    }

    @Override
    public int hashCode() {
        return mFQDN.hashCode();
    }

    @Override
    public String toString() {
        return "HomeSP{" +
                "SSIDs=" + mSSIDs +
                ", FQDN='" + mFQDN + '\'' +
                ", DomainMatcher=" + mDomainMatcher +
                ", RoamingConsortiums={" + Utils.roamingConsortiumsToString(mRoamingConsortiums) +
                '}' +
                ", MatchAnyOIs={" + Utils.roamingConsortiumsToString(mMatchAnyOIs) + '}' +
                ", MatchAllOIs={" + Utils.roamingConsortiumsToString(mMatchAllOIs) + '}' +
                ", Credential=" + mCredential +
                ", FriendlyName='" + mFriendlyName + '\'' +
                ", IconURL='" + mIconURL + '\'' +
                '}';
    }
}
