package com.android.hotspot2.pps;

import com.android.hotspot2.Utils;
import com.android.hotspot2.omadm.MOManager;
import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.OMANode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.android.hotspot2.omadm.MOManager.TAG_Country;
import static com.android.hotspot2.omadm.MOManager.TAG_DLBandwidth;
import static com.android.hotspot2.omadm.MOManager.TAG_FQDN_Match;
import static com.android.hotspot2.omadm.MOManager.TAG_IPProtocol;
import static com.android.hotspot2.omadm.MOManager.TAG_MaximumBSSLoadValue;
import static com.android.hotspot2.omadm.MOManager.TAG_MinBackhaulThreshold;
import static com.android.hotspot2.omadm.MOManager.TAG_NetworkType;
import static com.android.hotspot2.omadm.MOManager.TAG_PolicyUpdate;
import static com.android.hotspot2.omadm.MOManager.TAG_PortNumber;
import static com.android.hotspot2.omadm.MOManager.TAG_PreferredRoamingPartnerList;
import static com.android.hotspot2.omadm.MOManager.TAG_Priority;
import static com.android.hotspot2.omadm.MOManager.TAG_RequiredProtoPortTuple;
import static com.android.hotspot2.omadm.MOManager.TAG_SPExclusionList;
import static com.android.hotspot2.omadm.MOManager.TAG_SSID;
import static com.android.hotspot2.omadm.MOManager.TAG_ULBandwidth;

public class Policy {
    private final List<PreferredRoamingPartner> mPreferredRoamingPartners;
    private final List<MinBackhaul> mMinBackhaulThresholds;
    private final UpdateInfo mPolicyUpdate;
    private final List<String> mSPExclusionList;
    private final Map<Integer, List<Integer>> mRequiredProtos;
    private final int mMaxBSSLoad;

    public Policy(OMANode node) throws OMAException {

        OMANode rpNode = node.getChild(TAG_PreferredRoamingPartnerList);
        if (rpNode == null) {
            mPreferredRoamingPartners = null;
        } else {
            mPreferredRoamingPartners = new ArrayList<>(rpNode.getChildren().size());
            for (OMANode instance : rpNode.getChildren()) {
                if (instance.isLeaf()) {
                    throw new OMAException("Not expecting leaf node in " +
                            TAG_PreferredRoamingPartnerList);
                }
                mPreferredRoamingPartners.add(new PreferredRoamingPartner(instance));
            }
        }

        OMANode bhtNode = node.getChild(TAG_MinBackhaulThreshold);
        if (bhtNode == null) {
            mMinBackhaulThresholds = null;
        } else {
            mMinBackhaulThresholds = new ArrayList<>(bhtNode.getChildren().size());
            for (OMANode instance : bhtNode.getChildren()) {
                if (instance.isLeaf()) {
                    throw new OMAException("Not expecting leaf node in " +
                            TAG_MinBackhaulThreshold);
                }
                mMinBackhaulThresholds.add(new MinBackhaul(instance));
            }
        }

        mPolicyUpdate = new UpdateInfo(node.getChild(TAG_PolicyUpdate));

        OMANode sxNode = node.getChild(TAG_SPExclusionList);
        if (sxNode == null) {
            mSPExclusionList = null;
        } else {
            mSPExclusionList = new ArrayList<>(sxNode.getChildren().size());
            for (OMANode instance : sxNode.getChildren()) {
                if (instance.isLeaf()) {
                    throw new OMAException("Not expecting leaf node in " + TAG_SPExclusionList);
                }
                mSPExclusionList.add(MOManager.getString(instance, TAG_SSID));
            }
        }

        OMANode rptNode = node.getChild(TAG_RequiredProtoPortTuple);
        if (rptNode == null) {
            mRequiredProtos = null;
        } else {
            mRequiredProtos = new HashMap<>(rptNode.getChildren().size());
            for (OMANode instance : rptNode.getChildren()) {
                if (instance.isLeaf()) {
                    throw new OMAException("Not expecting leaf node in " +
                            TAG_RequiredProtoPortTuple);
                }
                int protocol = (int) MOManager.getLong(instance, TAG_IPProtocol, null);
                String[] portSegments = MOManager.getString(instance, TAG_PortNumber).split(",");
                List<Integer> ports = new ArrayList<>(portSegments.length);
                for (String portSegment : portSegments) {
                    try {
                        ports.add(Integer.parseInt(portSegment));
                    } catch (NumberFormatException nfe) {
                        throw new OMAException("Port is not a number: " + portSegment);
                    }
                }
                mRequiredProtos.put(protocol, ports);
            }
        }

        mMaxBSSLoad = (int) MOManager.getLong(node, TAG_MaximumBSSLoadValue, Long.MAX_VALUE);
    }

    public List<PreferredRoamingPartner> getPreferredRoamingPartners() {
        return mPreferredRoamingPartners;
    }

    public List<MinBackhaul> getMinBackhaulThresholds() {
        return mMinBackhaulThresholds;
    }

    public UpdateInfo getPolicyUpdate() {
        return mPolicyUpdate;
    }

    public List<String> getSPExclusionList() {
        return mSPExclusionList;
    }

    public Map<Integer, List<Integer>> getRequiredProtos() {
        return mRequiredProtos;
    }

    public int getMaxBSSLoad() {
        return mMaxBSSLoad;
    }

    private static class PreferredRoamingPartner {
        private final List<String> mDomain;
        private final Boolean mIncludeSubDomains;
        private final int mPriority;
        private final String mCountry;

        private PreferredRoamingPartner(OMANode node)
                throws OMAException {

            String[] segments = MOManager.getString(node, TAG_FQDN_Match).split(",");
            if (segments.length != 2) {
                throw new OMAException("Bad FQDN match string: " + TAG_FQDN_Match);
            }
            mDomain = Utils.splitDomain(segments[0]);
            mIncludeSubDomains = MOManager.getSelection(TAG_FQDN_Match, segments[1]);
            mPriority = (int) MOManager.getLong(node, TAG_Priority, null);
            mCountry = MOManager.getString(node, TAG_Country);
        }

        @Override
        public String toString() {
            return "PreferredRoamingPartner{" +
                    "domain=" + mDomain +
                    ", includeSubDomains=" + mIncludeSubDomains +
                    ", priority=" + mPriority +
                    ", country='" + mCountry + '\'' +
                    '}';
        }
    }

    private static class MinBackhaul {
        private final Boolean mHome;
        private final long mDL;
        private final long mUL;

        private MinBackhaul(OMANode node) throws OMAException {
            mHome = MOManager.getSelection(node, TAG_NetworkType);
            mDL = MOManager.getLong(node, TAG_DLBandwidth, Long.MAX_VALUE);
            mUL = MOManager.getLong(node, TAG_ULBandwidth, Long.MAX_VALUE);
        }

        @Override
        public String toString() {
            return "MinBackhaul{" +
                    "home=" + mHome +
                    ", DL=" + mDL +
                    ", UL=" + mUL +
                    '}';
        }
    }

    @Override
    public String toString() {
        return "Policy{" +
                "preferredRoamingPartners=" + mPreferredRoamingPartners +
                ", minBackhaulThresholds=" + mMinBackhaulThresholds +
                ", policyUpdate=" + mPolicyUpdate +
                ", SPExclusionList=" + mSPExclusionList +
                ", requiredProtos=" + mRequiredProtos +
                ", maxBSSLoad=" + mMaxBSSLoad +
                '}';
    }
}
