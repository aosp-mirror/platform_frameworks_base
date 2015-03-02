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

import android.os.Parcelable;
import android.os.Parcel;
import java.util.HashMap;

/**
 * Required Mobile Device Management Tree Structure
 *
 *                   +----------+
 *                   | ./(Root) |
 *                   +----+-----+
 *                        |
 *  +---------+           |         +---------+  +---------+
 *  | DevInfo |-----------+---------|  Wi-Fi  |--|SP FQDN* |
 *  +---------+           |         +---------+  +---------+
 *  +---------+           |                           |
 *  |DevDetail|-----------+                      +-----------------------+
 *  +---------+                                  |PerproviderSubscription|--<X>+
 *                                               +-----------------------+
 *
 * This class contains all nodes start from Wi-Fi
 * @hide
 **/
public class WifiPasspointDmTree implements Parcelable {
    private final static String TAG = "WifiTree";
    public int PpsMoId;//plugfest used only
    public HashMap<String, SpFqdn> spFqdn = new HashMap<String, SpFqdn>();//Maps.newHashMap();

    public SpFqdn createSpFqdn(String name) {
        SpFqdn obj = new SpFqdn(name);
        spFqdn.put(name, obj);
        return obj;
    }

    public static class SpFqdn implements Parcelable {
        public String nodeName;
        public PerProviderSubscription perProviderSubscription = new PerProviderSubscription();

        public SpFqdn(String name) {
            nodeName = name;
        }

        public SpFqdn() {
        }

        public SpFqdn(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(nodeName);
            out.writeParcelable(perProviderSubscription, flags);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                nodeName = in.readString();
                perProviderSubscription = in.readParcelable(PerProviderSubscription.class
                        .getClassLoader());
            }
        }

        public static final Parcelable.Creator<SpFqdn> CREATOR = new Parcelable.Creator<SpFqdn>() {
            public SpFqdn createFromParcel(Parcel in) {
                return new SpFqdn(in);
            }

            public SpFqdn[] newArray(int size) {
                return new SpFqdn[size];
            }
        };
    }

    /**
     * PerProviderSubscription
     **/
    public static class PerProviderSubscription implements Parcelable {
        /**
         * PerProviderSubscription/UpdateIdentifier
         **/
        public String UpdateIdentifier;
        public HashMap<String, CredentialInfo> credentialInfo = new HashMap<String, CredentialInfo>();

        public CredentialInfo createCredentialInfo(String name) {
            CredentialInfo obj = new CredentialInfo(name);
            credentialInfo.put(name, obj);
            return obj;
        }

        public PerProviderSubscription() {
        }

        public PerProviderSubscription(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(UpdateIdentifier);
            out.writeMap(credentialInfo);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                UpdateIdentifier = in.readString();
                in.readMap(credentialInfo, CredentialInfo.class.getClassLoader());
            }
        }

        public static final Parcelable.Creator<PerProviderSubscription> CREATOR = new Parcelable.Creator<PerProviderSubscription>() {
            public PerProviderSubscription createFromParcel(Parcel in) {
                return new PerProviderSubscription(in);
            }

            public PerProviderSubscription[] newArray(int size) {
                return new PerProviderSubscription[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>
     * This interior node contains the Home SP information, subscription policy, management and credential information.
     **/
    public static class CredentialInfo implements Parcelable {
        public String nodeName;
        public Policy policy = new Policy();
        public String credentialPriority;
        public HashMap<String, AAAServerTrustRoot> aAAServerTrustRoot = new HashMap<String, AAAServerTrustRoot>();
        public SubscriptionUpdate subscriptionUpdate = new SubscriptionUpdate();
        public HomeSP homeSP = new HomeSP();
        public SubscriptionParameters subscriptionParameters = new SubscriptionParameters();
        public Credential credential = new Credential();
        public Extension extension = new Extension();

        public CredentialInfo(String nn) {
            nodeName = nn;
        }

        public AAAServerTrustRoot createAAAServerTrustRoot(String name, String url, String fp) {
            AAAServerTrustRoot obj = new AAAServerTrustRoot(name, url, fp);
            aAAServerTrustRoot.put(name, obj);
            return obj;
        }

        public CredentialInfo() {
        }

        public CredentialInfo(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(nodeName);
            out.writeParcelable(policy, flags);
            out.writeString(credentialPriority);
            out.writeMap(aAAServerTrustRoot);
            out.writeParcelable(subscriptionUpdate, flags);
            out.writeParcelable(homeSP, flags);
            out.writeParcelable(subscriptionParameters, flags);
            out.writeParcelable(credential, flags);
            //out.writeParcelable(extension, flags);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                nodeName = in.readString();
                policy = in.readParcelable(Policy.class.getClassLoader());
                credentialPriority = in.readString();
                in.readMap(aAAServerTrustRoot, AAAServerTrustRoot.class.getClassLoader());
                subscriptionUpdate = in.readParcelable(SubscriptionUpdate.class.getClassLoader());
                homeSP = in.readParcelable(HomeSP.class.getClassLoader());
                subscriptionParameters = in.readParcelable(SubscriptionParameters.class
                        .getClassLoader());
                credential = in.readParcelable(Credential.class.getClassLoader());
                //extension = in.readParcelable(Extension.class.getClassLoader());
            }
        }

        public static final Parcelable.Creator<CredentialInfo> CREATOR = new Parcelable.Creator<CredentialInfo>() {
            public CredentialInfo createFromParcel(Parcel in) {
                return new CredentialInfo(in);
            }

            public CredentialInfo[] newArray(int size) {
                return new CredentialInfo[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/Policy
     **/
    public static class Policy implements Parcelable {
        public HashMap<String, PreferredRoamingPartnerList> preferredRoamingPartnerList = new HashMap<String, PreferredRoamingPartnerList>();
        public HashMap<String, MinBackhaulThresholdNetwork> minBackhaulThreshold = new HashMap<String, MinBackhaulThresholdNetwork>();
        public PolicyUpdate policyUpdate = new PolicyUpdate();
        public HashMap<String, SPExclusionList> sPExclusionList = new HashMap<String, SPExclusionList>();
        public HashMap<String, RequiredProtoPortTuple> requiredProtoPortTuple = new HashMap<String, RequiredProtoPortTuple>();
        public String maximumBSSLoadValue;

        public PreferredRoamingPartnerList createPreferredRoamingPartnerList(String name,
                String fqdn, String priority, String country) {
            PreferredRoamingPartnerList obj = new PreferredRoamingPartnerList(name, fqdn, priority,
                    country);
            preferredRoamingPartnerList.put(name, obj);
            return obj;
        }

        public MinBackhaulThresholdNetwork createMinBackhaulThreshold(String name, String type,
                String dl, String ul) {
            MinBackhaulThresholdNetwork obj = new MinBackhaulThresholdNetwork(name, type, dl, ul);
            minBackhaulThreshold.put(name, obj);
            return obj;
        }

        public SPExclusionList createSPExclusionList(String name, String ssid) {
            SPExclusionList obj = new SPExclusionList(name, ssid);
            sPExclusionList.put(name, obj);
            return obj;
        }

        public RequiredProtoPortTuple createRequiredProtoPortTuple(String name, String proto,
                String port) {
            RequiredProtoPortTuple obj = new RequiredProtoPortTuple(name, proto, port);
            requiredProtoPortTuple.put(name, obj);
            return obj;
        }

        public Policy() {
        }

        public Policy(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeMap(preferredRoamingPartnerList);
            out.writeMap(minBackhaulThreshold);
            out.writeParcelable(policyUpdate, flags);
            out.writeMap(sPExclusionList);
            out.writeMap(requiredProtoPortTuple);
            out.writeString(maximumBSSLoadValue);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                in.readMap(preferredRoamingPartnerList,
                        PreferredRoamingPartnerList.class.getClassLoader());
                in.readMap(minBackhaulThreshold, MinBackhaulThresholdNetwork.class.getClassLoader());
                policyUpdate = in.readParcelable(PolicyUpdate.class.getClassLoader());
                in.readMap(sPExclusionList, SPExclusionList.class.getClassLoader());
                in.readMap(requiredProtoPortTuple, RequiredProtoPortTuple.class.getClassLoader());
                maximumBSSLoadValue = in.readString();

            }
        }

        public static final Parcelable.Creator<Policy> CREATOR = new Parcelable.Creator<Policy>() {
            public Policy createFromParcel(Parcel in) {
                return new Policy(in);
            }

            public Policy[] newArray(int size) {
                return new Policy[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/Policy/PreferredRoamingPartnerList/<X+>
     **/
    public static class PreferredRoamingPartnerList implements Parcelable {
        public String nodeName;
        public String FQDN_Match; //maximum 255 + ",includeSubdomains", equals 273
        public String Priority;
        public String Country; // maximum 600 octets

        public PreferredRoamingPartnerList(String nn, String f, String p, String c) {
            nodeName = nn;
            FQDN_Match = f;
            Priority = p;
            Country = c;
        }

        public PreferredRoamingPartnerList() {
        }

        public PreferredRoamingPartnerList(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(nodeName);
            out.writeString(FQDN_Match);
            out.writeString(Priority);
            out.writeString(Country);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                nodeName = in.readString();
                FQDN_Match = in.readString();
                Priority = in.readString();
                Country = in.readString();
            }
        }

        public static final Parcelable.Creator<PreferredRoamingPartnerList> CREATOR = new Parcelable.Creator<PreferredRoamingPartnerList>() {
            public PreferredRoamingPartnerList createFromParcel(Parcel in) {
                return new PreferredRoamingPartnerList(in);
            }

            public PreferredRoamingPartnerList[] newArray(int size) {
                return new PreferredRoamingPartnerList[size];
            }
        };
    }

    /**
     * PerProviderSubscription/<X+>/Policy/MinBackhaulThreshold
     **/
    public static class MinBackhaulThresholdNetwork implements Parcelable {
        public String nodeName;
        public String NetworkType;
        public String DLBandwidth;
        public String ULBandwidth;

        public MinBackhaulThresholdNetwork(String nn, String nt, String d, String u) {
            nodeName = nn;
            NetworkType = nt;
            DLBandwidth = d;
            ULBandwidth = u;
        }

        public MinBackhaulThresholdNetwork() {
        }

        public MinBackhaulThresholdNetwork(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(nodeName);
            out.writeString(NetworkType);
            out.writeString(DLBandwidth);
            out.writeString(ULBandwidth);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                nodeName = in.readString();
                NetworkType = in.readString();
                DLBandwidth = in.readString();
                ULBandwidth = in.readString();
            }
        }

        public static final Parcelable.Creator<MinBackhaulThresholdNetwork> CREATOR = new Parcelable.Creator<MinBackhaulThresholdNetwork>() {
            public MinBackhaulThresholdNetwork createFromParcel(Parcel in) {
                return new MinBackhaulThresholdNetwork(in);
            }

            public MinBackhaulThresholdNetwork[] newArray(int size) {
                return new MinBackhaulThresholdNetwork[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/Policy/PolicyUpdate
     **/
    public static class PolicyUpdate implements Parcelable {
        public String UpdateInterval;
        public String UpdateMethod;
        public String Restriction;
        public String URI;
        public UsernamePassword usernamePassword = new UsernamePassword();
        public String Other;
        public TrustRoot trustRoot = new TrustRoot();

        public PolicyUpdate() {
        }

        public PolicyUpdate(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(UpdateInterval);
            out.writeString(UpdateMethod);
            out.writeString(Restriction);
            out.writeString(URI);
            out.writeParcelable(usernamePassword, flags);
            out.writeString(Other);
            out.writeParcelable(trustRoot, flags);

        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                UpdateInterval = in.readString();
                UpdateMethod = in.readString();
                Restriction = in.readString();
                URI = in.readString();
                usernamePassword = in.readParcelable(UsernamePassword.class.getClassLoader());
                Other = in.readString();
                trustRoot = in.readParcelable(TrustRoot.class.getClassLoader());
            }
        }

        public static final Parcelable.Creator<PolicyUpdate> CREATOR = new Parcelable.Creator<PolicyUpdate>() {
            public PolicyUpdate createFromParcel(Parcel in) {
                return new PolicyUpdate(in);
            }

            public PolicyUpdate[] newArray(int size) {
                return new PolicyUpdate[size];
            }
        };
    }

    /**
     * PerProviderSubscription/<X+>/Policy/SPExclusionList
     **/
    public static class SPExclusionList implements Parcelable {
        public String nodeName;
        public String SSID;

        public SPExclusionList(String nn, String s) {
            nodeName = nn;
            SSID = s;
        }

        public SPExclusionList() {
        }

        public SPExclusionList(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(nodeName);
            out.writeString(SSID);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                nodeName = in.readString();
                SSID = in.readString();
            }
        }

        public static final Parcelable.Creator<SPExclusionList> CREATOR = new Parcelable.Creator<SPExclusionList>() {
            public SPExclusionList createFromParcel(Parcel in) {
                return new SPExclusionList(in);
            }

            public SPExclusionList[] newArray(int size) {
                return new SPExclusionList[size];
            }
        };
    }

    /**
     * PerProviderSubscription/<X+>/Policy/RequiredProtoPortTuple
     **/
    public static class RequiredProtoPortTuple implements Parcelable {
        public String nodeName;
        public String IPProtocol;
        public String PortNumber;

        public RequiredProtoPortTuple() {
        }

        public RequiredProtoPortTuple(String nn, String protocol, String port) {
            nodeName = nn;
            IPProtocol = protocol;
            PortNumber = port;
        }

        public RequiredProtoPortTuple(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(nodeName);
            out.writeString(IPProtocol);
            out.writeString(PortNumber);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                nodeName = in.readString();
                IPProtocol = in.readString();
                PortNumber = in.readString();
            }
        }

        public static final Parcelable.Creator<RequiredProtoPortTuple> CREATOR = new Parcelable.Creator<RequiredProtoPortTuple>() {
            public RequiredProtoPortTuple createFromParcel(Parcel in) {
                return new RequiredProtoPortTuple(in);
            }

            public RequiredProtoPortTuple[] newArray(int size) {
                return new RequiredProtoPortTuple[size];
            }
        };
    }

    /**
     * PerProviderSubscription/<X+>/AAAServerTrustRoot
     **/
    public static class AAAServerTrustRoot implements Parcelable {
        public String nodeName;
        public String CertURL;
        public String CertSHA256Fingerprint;

        public AAAServerTrustRoot(String nn, String url, String fp) {
            nodeName = nn;
            CertURL = url;
            CertSHA256Fingerprint = fp;
        }

        public AAAServerTrustRoot() {
        }

        public AAAServerTrustRoot(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(nodeName);
            out.writeString(CertURL);
            out.writeString(CertSHA256Fingerprint);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                nodeName = in.readString();
                CertURL = in.readString();
                CertSHA256Fingerprint = in.readString();
            }
        }

        public static final Parcelable.Creator<AAAServerTrustRoot> CREATOR = new Parcelable.Creator<AAAServerTrustRoot>() {
            public AAAServerTrustRoot createFromParcel(Parcel in) {
                return new AAAServerTrustRoot(in);
            }

            public AAAServerTrustRoot[] newArray(int size) {
                return new AAAServerTrustRoot[size];
            }
        };
    }

    /**
     * PerProviderSubscription/<X+>/SubscriptionUpdate
     **/
    public static class SubscriptionUpdate implements Parcelable {
        public String UpdateInterval;
        public String UpdateMethod;
        public String Restriction;
        public String URI;
        public UsernamePassword usernamePassword = new UsernamePassword();
        public String Other;
        public TrustRoot trustRoot = new TrustRoot();

        public SubscriptionUpdate() {
        }

        public SubscriptionUpdate(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(UpdateInterval);
            out.writeString(UpdateMethod);
            out.writeString(Restriction);
            out.writeString(URI);
            out.writeParcelable(usernamePassword, flags);
            out.writeString(Other);
            out.writeParcelable(trustRoot, flags);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                UpdateInterval = in.readString();
                UpdateMethod = in.readString();
                Restriction = in.readString();
                URI = in.readString();
                usernamePassword = in.readParcelable(UsernamePassword.class.getClassLoader());
                Other = in.readString();
                trustRoot = in.readParcelable(TrustRoot.class.getClassLoader());
            }
        }

        public static final Parcelable.Creator<SubscriptionUpdate> CREATOR = new Parcelable.Creator<SubscriptionUpdate>() {
            public SubscriptionUpdate createFromParcel(Parcel in) {
                return new SubscriptionUpdate(in);
            }

            public SubscriptionUpdate[] newArray(int size) {
                return new SubscriptionUpdate[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/Policy/PolicyUpdate/TrustRoot
     * PerProviderSubscription/<X+>/SubscriptionUpdate/TrustRoot
     * PerProviderSubscription/<X+>/AAAServerTrustRoot/<X+>
     **/
    public static class TrustRoot implements Parcelable {
        public String CertURL;
        public String CertSHA256Fingerprint;

        public TrustRoot() {
        }

        public TrustRoot(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(CertURL);
            out.writeString(CertSHA256Fingerprint);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                CertURL = in.readString();
                CertSHA256Fingerprint = in.readString();
            }
        }

        public static final Parcelable.Creator<TrustRoot> CREATOR = new Parcelable.Creator<TrustRoot>() {
            public TrustRoot createFromParcel(Parcel in) {
                return new TrustRoot(in);
            }

            public TrustRoot[] newArray(int size) {
                return new TrustRoot[size];
            }
        };
    }

    /**
     * PerProviderSubscription/<X+>/Policy/PolicyUpdate/UsernamePassword
     * PerProviderSubscription/<X+>/SubscriptionUpdate/UsernamePassword
     * PerProviderSubscription/<X+>/Credential/UsernamePassword
     **/
    public static class UsernamePassword implements Parcelable {
        public String Username;
        public String Password;
        //following are Credential node used only
        public boolean MachineManaged;
        public String SoftTokenApp;
        public String AbleToShare;
        public EAPMethod eAPMethod = new EAPMethod();

        public UsernamePassword() {
        }

        public UsernamePassword(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(Username);
            out.writeString(Password);
            out.writeInt(MachineManaged ? 1 : 0);
            out.writeString(SoftTokenApp);
            out.writeString(AbleToShare);
            out.writeParcelable(eAPMethod, flags);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                Username = in.readString();
                Password = in.readString();
                MachineManaged = (in.readInt() == 1) ? true : false;
                SoftTokenApp = in.readString();
                AbleToShare = in.readString();
                eAPMethod = in.readParcelable(EAPMethod.class.getClassLoader());
            }
        }

        public static final Parcelable.Creator<UsernamePassword> CREATOR = new Parcelable.Creator<UsernamePassword>() {
            public UsernamePassword createFromParcel(Parcel in) {
                return new UsernamePassword(in);
            }

            public UsernamePassword[] newArray(int size) {
                return new UsernamePassword[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/Credential/UsernamePassword/EAPMethod
     **/
    public static class EAPMethod implements Parcelable {
        public String EAPType;
        public String VendorId;
        public String VendorType;
        public String InnerEAPType;
        public String InnerVendorId;
        public String InnerVendorType;
        public String InnerMethod;

        public EAPMethod() {
        }

        public EAPMethod(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(EAPType);
            out.writeString(VendorId);
            out.writeString(VendorType);
            out.writeString(InnerEAPType);
            out.writeString(InnerVendorId);
            out.writeString(InnerVendorType);
            out.writeString(InnerMethod);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                EAPType = in.readString();
                VendorId = in.readString();
                VendorType = in.readString();
                InnerEAPType = in.readString();
                InnerVendorId = in.readString();
                InnerVendorType = in.readString();
                InnerMethod = in.readString();
            }
        }

        public static final Parcelable.Creator<EAPMethod> CREATOR = new Parcelable.Creator<EAPMethod>() {
            public EAPMethod createFromParcel(Parcel in) {
                return new EAPMethod(in);
            }

            public EAPMethod[] newArray(int size) {
                return new EAPMethod[size];
            }
        };
    }

    /**
     * PerProviderSubscription/<X+>/HomeSP
     **/
    public static class HomeSP implements Parcelable {
        public HashMap<String, NetworkID> networkID = new HashMap<String, NetworkID>();
        public String FriendlyName;
        public String IconURL;
        public String FQDN;
        public HashMap<String, HomeOIList> homeOIList = new HashMap<String, HomeOIList>();
        public HashMap<String, OtherHomePartners> otherHomePartners = new HashMap<String, OtherHomePartners>();
        public String RoamingConsortiumOI;

        public NetworkID createNetworkID(String name, String ssid, String hessid) {
            NetworkID obj = new NetworkID(name, ssid, hessid);
            networkID.put(name, obj);
            return obj;
        }

        public HomeOIList createHomeOIList(String name, String homeoi, boolean required) {
            HomeOIList obj = new HomeOIList(name, homeoi, required);
            homeOIList.put(name, obj);
            return obj;
        }

        public OtherHomePartners createOtherHomePartners(String name, String fqdn) {
            OtherHomePartners obj = new OtherHomePartners(name, fqdn);
            otherHomePartners.put(name, obj);
            return obj;
        }

        public HomeSP() {
        }

        public HomeSP(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeMap(networkID);
            out.writeString(FriendlyName);
            out.writeString(IconURL);
            out.writeString(FQDN);
            out.writeMap(homeOIList);
            out.writeMap(otherHomePartners);
            out.writeString(RoamingConsortiumOI);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                in.readMap(networkID, NetworkID.class.getClassLoader());
                FriendlyName = in.readString();
                IconURL = in.readString();
                FQDN = in.readString();
                in.readMap(homeOIList, HomeOIList.class.getClassLoader());
                in.readMap(otherHomePartners, OtherHomePartners.class.getClassLoader());
                RoamingConsortiumOI = in.readString();
            }
        }

        public static final Parcelable.Creator<HomeSP> CREATOR = new Parcelable.Creator<HomeSP>() {
            public HomeSP createFromParcel(Parcel in) {
                return new HomeSP(in);
            }

            public HomeSP[] newArray(int size) {
                return new HomeSP[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/HomeSP/NetworkID
     **/
    public static class NetworkID implements Parcelable {
        public String nodeName;
        public String SSID;
        public String HESSID;

        public NetworkID(String nn, String s, String h) {
            nodeName = nn;
            SSID = s;
            HESSID = h;
        }

        public NetworkID() {
        }

        public NetworkID(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(nodeName);
            out.writeString(SSID);
            out.writeString(HESSID);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                nodeName = in.readString();
                SSID = in.readString();
                HESSID = in.readString();
            }
        }

        public static final Parcelable.Creator<NetworkID> CREATOR = new Parcelable.Creator<NetworkID>() {
            public NetworkID createFromParcel(Parcel in) {
                return new NetworkID(in);
            }

            public NetworkID[] newArray(int size) {
                return new NetworkID[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/HomeSP/HomeOIList
     **/
    public static class HomeOIList implements Parcelable {
        public String nodeName;
        public String HomeOI;
        public boolean HomeOIRequired;

        public HomeOIList(String nn, String h, boolean r) {
            nodeName = nn;
            HomeOI = h;
            HomeOIRequired = r;
        }

        public HomeOIList() {
        }

        public HomeOIList(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(nodeName);
            out.writeString(HomeOI);
            out.writeInt(HomeOIRequired ? 1 : 0);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                nodeName = in.readString();
                HomeOI = in.readString();
                HomeOIRequired = (in.readInt() == 1) ? true : false;
            }
        }

        public static final Parcelable.Creator<HomeOIList> CREATOR = new Parcelable.Creator<HomeOIList>() {
            public HomeOIList createFromParcel(Parcel in) {
                return new HomeOIList(in);
            }

            public HomeOIList[] newArray(int size) {
                return new HomeOIList[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/HomeSP/OtherHomePartners
     **/
    public static class OtherHomePartners implements Parcelable {
        public String nodeName;
        public String FQDN;

        public OtherHomePartners(String nn, String f) {
            nodeName = nn;
            FQDN = f;
        }

        public OtherHomePartners() {
        }

        public OtherHomePartners(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(nodeName);
            out.writeString(FQDN);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                nodeName = in.readString();
                FQDN = in.readString();
            }
        }

        public static final Parcelable.Creator<OtherHomePartners> CREATOR = new Parcelable.Creator<OtherHomePartners>() {
            public OtherHomePartners createFromParcel(Parcel in) {
                return new OtherHomePartners(in);
            }

            public OtherHomePartners[] newArray(int size) {
                return new OtherHomePartners[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/SubscriptionParameters
     **/
    public static class SubscriptionParameters implements Parcelable {
        public String CreationDate;
        public String ExpirationDate;
        public String TypeOfSubscription;
        public UsageLimits usageLimits = new UsageLimits();

        public SubscriptionParameters() {
        }

        public SubscriptionParameters(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(CreationDate);
            out.writeString(ExpirationDate);
            out.writeString(TypeOfSubscription);
            out.writeParcelable(usageLimits, flags);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                CreationDate = in.readString();
                ExpirationDate = in.readString();
                TypeOfSubscription = in.readString();
                usageLimits = in.readParcelable(UsageLimits.class.getClassLoader());
            }
        }

        public static final Parcelable.Creator<SubscriptionParameters> CREATOR = new Parcelable.Creator<SubscriptionParameters>() {
            public SubscriptionParameters createFromParcel(Parcel in) {
                return new SubscriptionParameters(in);
            }

            public SubscriptionParameters[] newArray(int size) {
                return new SubscriptionParameters[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/SubscriptionParameters/UsageLimits
     **/
    public static class UsageLimits implements Parcelable {
        public String DataLimit;
        public String StartDate;
        public String TimeLimit;
        public String UsageTimePeriod;

        public UsageLimits() {
        }

        public UsageLimits(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(DataLimit);
            out.writeString(StartDate);
            out.writeString(TimeLimit);
            out.writeString(UsageTimePeriod);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                DataLimit = in.readString();
                StartDate = in.readString();
                TimeLimit = in.readString();
                UsageTimePeriod = in.readString();
            }
        }

        public static final Parcelable.Creator<UsageLimits> CREATOR = new Parcelable.Creator<UsageLimits>() {
            public UsageLimits createFromParcel(Parcel in) {
                return new UsageLimits(in);
            }

            public UsageLimits[] newArray(int size) {
                return new UsageLimits[size];
            }
        };
    }

    /**
     * PerProviderSubscription/<X+>/Credential
     **/
    public static class Credential implements Parcelable {
        public String CreationDate;
        public String ExpirationDate;
        public UsernamePassword usernamePassword = new UsernamePassword();
        public DigitalCertificate digitalCertificate = new DigitalCertificate();
        public String Realm;
        public boolean CheckAAAServerCertStatus;
        public SIM sim = new SIM();

        public Credential() {
        }

        public Credential(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(CreationDate);
            out.writeString(ExpirationDate);
            out.writeParcelable(usernamePassword, flags);
            out.writeParcelable(digitalCertificate, flags);
            out.writeString(Realm);
            out.writeInt(CheckAAAServerCertStatus ? 1 : 0);
            out.writeParcelable(sim, flags);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                CreationDate = in.readString();
                ExpirationDate = in.readString();
                usernamePassword = in.readParcelable(UsernamePassword.class.getClassLoader());
                digitalCertificate = in.readParcelable(DigitalCertificate.class.getClassLoader());
                Realm = in.readString();
                CheckAAAServerCertStatus = (in.readInt() == 1) ? true : false;
                sim = in.readParcelable(SIM.class.getClassLoader());
            }
        }

        public static final Parcelable.Creator<Credential> CREATOR = new Parcelable.Creator<Credential>() {
            public Credential createFromParcel(Parcel in) {
                return new Credential(in);
            }

            public Credential[] newArray(int size) {
                return new Credential[size];
            }
        };
    }

    /**
     * PerProviderSubscription/<X+>/Credential/DigitalCertificate
     **/
    public static class DigitalCertificate implements Parcelable {
        public String CertificateType;
        public String CertSHA256Fingerprint;

        public DigitalCertificate() {
        }

        public DigitalCertificate(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(CertificateType);
            out.writeString(CertSHA256Fingerprint);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                CertificateType = in.readString();
                CertSHA256Fingerprint = in.readString();
            }
        }

        public static final Parcelable.Creator<DigitalCertificate> CREATOR = new Parcelable.Creator<DigitalCertificate>() {
            public DigitalCertificate createFromParcel(Parcel in) {
                return new DigitalCertificate(in);
            }

            public DigitalCertificate[] newArray(int size) {
                return new DigitalCertificate[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/Credential/SIM
     **/
    public static class SIM implements Parcelable {
        public String IMSI;
        public String EAPType;

        public SIM() {
        }

        public SIM(Parcel in) {
            readFromParcel(in);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(IMSI);
            out.writeString(EAPType);
        }

        public void readFromParcel(Parcel in) {
            if (in == null) {
                //log here
            } else {
                IMSI = in.readString();
                EAPType = in.readString();
            }
        }

        public static final Parcelable.Creator<SIM> CREATOR = new Parcelable.Creator<SIM>() {
            public SIM createFromParcel(Parcel in) {
                return new SIM(in);
            }

            public SIM[] newArray(int size) {
                return new SIM[size];
            }
        };

    }

    /**
     * PerProviderSubscription/<X+>/Extension
     **/
    public static class Extension {
        public String empty;
    }

    public WifiPasspointDmTree() {
    }

    public WifiPasspointDmTree(Parcel in) {
        readFromParcel(in);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeMap(spFqdn);
    }

    public void readFromParcel(Parcel in) {
        if (in == null) {
            //log here
        } else {
            in.readMap(spFqdn, SpFqdn.class.getClassLoader());
        }
    }

    public static final Parcelable.Creator<WifiPasspointDmTree> CREATOR = new Parcelable.Creator<WifiPasspointDmTree>() {
        public WifiPasspointDmTree createFromParcel(Parcel in) {
            return new WifiPasspointDmTree(in);
        }

        public WifiPasspointDmTree[] newArray(int size) {
            return new WifiPasspointDmTree[size];
        }
    };

}
