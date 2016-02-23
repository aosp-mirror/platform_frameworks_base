package com.android.hotspot2.omadm;

import android.util.Base64;
import android.util.Log;

import com.android.anqp.eap.EAP;
import com.android.anqp.eap.EAPMethod;
import com.android.anqp.eap.ExpandedEAPMethod;
import com.android.anqp.eap.InnerAuthEAP;
import com.android.anqp.eap.NonEAPInnerAuth;
import com.android.hotspot2.IMSIParameter;
import com.android.hotspot2.Utils;
import com.android.hotspot2.osu.OSUManager;
import com.android.hotspot2.osu.commands.MOData;
import com.android.hotspot2.pps.Credential;
import com.android.hotspot2.pps.HomeSP;
import com.android.hotspot2.pps.Policy;
import com.android.hotspot2.pps.SubscriptionParameters;
import com.android.hotspot2.pps.UpdateInfo;

import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Handles provisioning of PerProviderSubscription data.
 */
public class MOManager {

    public static final String TAG_AAAServerTrustRoot = "AAAServerTrustRoot";
    public static final String TAG_AbleToShare = "AbleToShare";
    public static final String TAG_CertificateType = "CertificateType";
    public static final String TAG_CertSHA256Fingerprint = "CertSHA256Fingerprint";
    public static final String TAG_CertURL = "CertURL";
    public static final String TAG_CheckAAAServerCertStatus = "CheckAAAServerCertStatus";
    public static final String TAG_Country = "Country";
    public static final String TAG_CreationDate = "CreationDate";
    public static final String TAG_Credential = "Credential";
    public static final String TAG_CredentialPriority = "CredentialPriority";
    public static final String TAG_DataLimit = "DataLimit";
    public static final String TAG_DigitalCertificate = "DigitalCertificate";
    public static final String TAG_DLBandwidth = "DLBandwidth";
    public static final String TAG_EAPMethod = "EAPMethod";
    public static final String TAG_EAPType = "EAPType";
    public static final String TAG_ExpirationDate = "ExpirationDate";
    public static final String TAG_Extension = "Extension";
    public static final String TAG_FQDN = "FQDN";
    public static final String TAG_FQDN_Match = "FQDN_Match";
    public static final String TAG_FriendlyName = "FriendlyName";
    public static final String TAG_HESSID = "HESSID";
    public static final String TAG_HomeOI = "HomeOI";
    public static final String TAG_HomeOIList = "HomeOIList";
    public static final String TAG_HomeOIRequired = "HomeOIRequired";
    public static final String TAG_HomeSP = "HomeSP";
    public static final String TAG_IconURL = "IconURL";
    public static final String TAG_IMSI = "IMSI";
    public static final String TAG_InnerEAPType = "InnerEAPType";
    public static final String TAG_InnerMethod = "InnerMethod";
    public static final String TAG_InnerVendorID = "InnerVendorID";
    public static final String TAG_InnerVendorType = "InnerVendorType";
    public static final String TAG_IPProtocol = "IPProtocol";
    public static final String TAG_MachineManaged = "MachineManaged";
    public static final String TAG_MaximumBSSLoadValue = "MaximumBSSLoadValue";
    public static final String TAG_MinBackhaulThreshold = "MinBackhaulThreshold";
    public static final String TAG_NetworkID = "NetworkID";
    public static final String TAG_NetworkType = "NetworkType";
    public static final String TAG_Other = "Other";
    public static final String TAG_OtherHomePartners = "OtherHomePartners";
    public static final String TAG_Password = "Password";
    public static final String TAG_PerProviderSubscription = "PerProviderSubscription";
    public static final String TAG_Policy = "Policy";
    public static final String TAG_PolicyUpdate = "PolicyUpdate";
    public static final String TAG_PortNumber = "PortNumber";
    public static final String TAG_PreferredRoamingPartnerList = "PreferredRoamingPartnerList";
    public static final String TAG_Priority = "Priority";
    public static final String TAG_Realm = "Realm";
    public static final String TAG_RequiredProtoPortTuple = "RequiredProtoPortTuple";
    public static final String TAG_Restriction = "Restriction";
    public static final String TAG_RoamingConsortiumOI = "RoamingConsortiumOI";
    public static final String TAG_SIM = "SIM";
    public static final String TAG_SoftTokenApp = "SoftTokenApp";
    public static final String TAG_SPExclusionList = "SPExclusionList";
    public static final String TAG_SSID = "SSID";
    public static final String TAG_StartDate = "StartDate";
    public static final String TAG_SubscriptionParameters = "SubscriptionParameters";
    public static final String TAG_SubscriptionUpdate = "SubscriptionUpdate";
    public static final String TAG_TimeLimit = "TimeLimit";
    public static final String TAG_TrustRoot = "TrustRoot";
    public static final String TAG_TypeOfSubscription = "TypeOfSubscription";
    public static final String TAG_ULBandwidth = "ULBandwidth";
    public static final String TAG_UpdateIdentifier = "UpdateIdentifier";
    public static final String TAG_UpdateInterval = "UpdateInterval";
    public static final String TAG_UpdateMethod = "UpdateMethod";
    public static final String TAG_URI = "URI";
    public static final String TAG_UsageLimits = "UsageLimits";
    public static final String TAG_UsageTimePeriod = "UsageTimePeriod";
    public static final String TAG_Username = "Username";
    public static final String TAG_UsernamePassword = "UsernamePassword";
    public static final String TAG_VendorId = "VendorId";
    public static final String TAG_VendorType = "VendorType";

    public static final long IntervalFactor = 60000L;  // All MO intervals are in minutes

    private static final DateFormat DTFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private static final Map<String, Map<String, Object>> sSelectionMap;

    static {
        DTFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        sSelectionMap = new HashMap<>();

        setSelections(TAG_FQDN_Match,
                "exactmatch", Boolean.FALSE,
                "includesubdomains", Boolean.TRUE);
        setSelections(TAG_UpdateMethod,
                "oma-dm-clientinitiated", Boolean.FALSE,
                "spp-clientinitiated", Boolean.TRUE);
        setSelections(TAG_Restriction,
                "homesp", UpdateInfo.UpdateRestriction.HomeSP,
                "roamingpartner", UpdateInfo.UpdateRestriction.RoamingPartner,
                "unrestricted", UpdateInfo.UpdateRestriction.Unrestricted);
    }

    private static void setSelections(String key, Object... pairs) {
        Map<String, Object> kvp = new HashMap<>();
        sSelectionMap.put(key, kvp);
        for (int n = 0; n < pairs.length; n += 2) {
            kvp.put(pairs[n].toString(), pairs[n + 1]);
        }
    }

    private final File mPpsFile;
    private final boolean mEnabled;
    private final Map<String, HomeSP> mSPs;

    public MOManager(File ppsFile, boolean hs2enabled) {
        mPpsFile = ppsFile;
        mEnabled = hs2enabled;
        mSPs = new HashMap<>();
    }

    public File getPpsFile() {
        return mPpsFile;
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public boolean isConfigured() {
        return mEnabled && !mSPs.isEmpty();
    }

    public Map<String, HomeSP> getLoadedSPs() {
        return Collections.unmodifiableMap(mSPs);
    }

    public List<HomeSP> loadAllSPs() throws IOException {

        if (!mEnabled || !mPpsFile.exists()) {
            return Collections.emptyList();
        }

        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(mPpsFile))) {
            MOTree moTree = MOTree.unmarshal(in);
            mSPs.clear();
            if (moTree == null) {
                return Collections.emptyList();     // Empty file
            }

            List<HomeSP> sps = buildSPs(moTree);
            if (sps != null) {
                for (HomeSP sp : sps) {
                    if (mSPs.put(sp.getFQDN(), sp) != null) {
                        throw new OMAException("Multiple SPs for FQDN '" + sp.getFQDN() + "'");
                    } else {
                        Log.d(OSUManager.TAG, "retrieved " + sp.getFQDN() + " from PPS");
                    }
                }
                return sps;

            } else {
                throw new OMAException("Failed to build HomeSP");
            }
        }
    }

    public static HomeSP buildSP(String xml) throws IOException, SAXException {
        OMAParser omaParser = new OMAParser();
        MOTree tree = omaParser.parse(xml, OMAConstants.PPS_URN);
        List<HomeSP> spList = buildSPs(tree);
        if (spList.size() != 1) {
            throw new OMAException("Expected exactly one HomeSP, got " + spList.size());
        }
        return spList.iterator().next();
    }

    public HomeSP addSP(String xml, OSUManager osuManager) throws IOException, SAXException {
        OMAParser omaParser = new OMAParser();
        return addSP(omaParser.parse(xml, OMAConstants.PPS_URN));
    }

    private static final List<String> FQDNPath = Arrays.asList(TAG_HomeSP, TAG_FQDN);

    /**
     * R1 *only* addSP method.
     *
     * @param homeSP
     * @throws IOException
     */
    public void addSP(HomeSP homeSP) throws IOException {
        if (!mEnabled) {
            throw new IOException("HS2.0 not enabled on this device");
        }
        if (mSPs.containsKey(homeSP.getFQDN())) {
            Log.d(OSUManager.TAG, "HS20 profile for " +
                    homeSP.getFQDN() + " already exists");
            return;
        }
        Log.d(OSUManager.TAG, "Adding new HS20 profile for " + homeSP.getFQDN());

        OMAConstructed dummyRoot = new OMAConstructed(null, TAG_PerProviderSubscription, null);
        buildHomeSPTree(homeSP, dummyRoot, mSPs.size() + 1);
        try {
            addSP(dummyRoot);
        } catch (FileNotFoundException fnfe) {
            MOTree tree =
                    MOTree.buildMgmtTree(OMAConstants.PPS_URN, OMAConstants.OMAVersion, dummyRoot);
            // No file to load a pre-build MO tree from, create a new one and save it.
            //MOTree tree = new MOTree(OMAConstants.PPS_URN, OMAConstants.OMAVersion, dummyRoot);
            writeMO(tree, mPpsFile);
        }
        mSPs.put(homeSP.getFQDN(), homeSP);
    }

    public HomeSP addSP(MOTree instanceTree) throws IOException {
        List<HomeSP> spList = buildSPs(instanceTree);
        if (spList.size() != 1) {
            throw new OMAException("Expected exactly one HomeSP, got " + spList.size());
        }

        HomeSP sp = spList.iterator().next();
        String fqdn = sp.getFQDN();
        if (mSPs.put(fqdn, sp) != null) {
            throw new OMAException("SP " + fqdn + " already exists");
        }

        OMAConstructed pps = (OMAConstructed) instanceTree.getRoot().
                getChild(TAG_PerProviderSubscription);

        try {
            addSP(pps);
        } catch (FileNotFoundException fnfe) {
            MOTree tree = new MOTree(instanceTree.getUrn(), instanceTree.getDtdRev(),
                    instanceTree.getRoot());
            writeMO(tree, mPpsFile);
        }

        return sp;
    }

    /**
     * Add an SP sub-tree. mo must be PPS with an immediate instance child (e.g. Cred01) and an
     * optional UpdateIdentifier,
     *
     * @param mo The new MO
     * @throws IOException
     */
    private void addSP(OMANode mo) throws IOException {
        MOTree moTree;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(mPpsFile))) {
            moTree = MOTree.unmarshal(in);
            moTree.getRoot().addChild(mo);

                /*
            OMAConstructed ppsRoot = (OMAConstructed)
                    moTree.getRoot().addChild(TAG_PerProviderSubscription, "", null, null);
            for (OMANode child : mo.getChildren()) {
                ppsRoot.addChild(child);
                if (!child.isLeaf()) {
                    moTree.getRoot().addChild(child);
                }
                else if (child.getName().equals(TAG_UpdateIdentifier)) {
                    OMANode currentUD = moTree.getRoot().getChild(TAG_UpdateIdentifier);
                    if (currentUD != null) {
                        moTree.getRoot().replaceNode(currentUD, child);
                    }
                    else {
                        moTree.getRoot().addChild(child);
                    }
                }
            }
                */
        }
        writeMO(moTree, mPpsFile);
    }

    private static OMAConstructed findTargetTree(MOTree moTree, String fqdn) throws OMAException {
        OMANode pps = moTree.getRoot();
        for (OMANode node : pps.getChildren()) {
            OMANode instance = null;
            if (node.getName().equals(TAG_PerProviderSubscription)) {
                instance = getInstanceNode((OMAConstructed) node);
            } else if (!node.isLeaf()) {
                instance = node;
            }
            if (instance != null) {
                String nodeFqdn = getString(instance.getListValue(FQDNPath.iterator()));
                if (fqdn.equalsIgnoreCase(nodeFqdn)) {
                    return (OMAConstructed) node;
                    // targetTree is rooted at the PPS
                }
            }
        }
        return null;
    }

    private static OMAConstructed getInstanceNode(OMAConstructed root) throws OMAException {
        for (OMANode child : root.getChildren()) {
            if (!child.isLeaf()) {
                return (OMAConstructed) child;
            }
        }
        throw new OMAException("Cannot find instance node");
    }

    public static HomeSP modifySP(HomeSP homeSP, MOTree moTree, Collection<MOData> mods)
            throws OMAException {

        OMAConstructed ppsTree =
                (OMAConstructed) moTree.getRoot().getChildren().iterator().next();
        OMAConstructed instance = getInstanceNode(ppsTree);

        int ppsMods = 0;
        int updateIdentifier = homeSP.getUpdateIdentifier();
        for (MOData mod : mods) {
            LinkedList<String> tailPath =
                    getTailPath(mod.getBaseURI(), TAG_PerProviderSubscription);
            OMAConstructed modRoot = mod.getMOTree().getRoot();
            // modRoot is the MgmtTree with the actual object as a direct child
            // (e.g. Credential)

            if (tailPath.getFirst().equals(TAG_UpdateIdentifier)) {
                updateIdentifier = getInteger(modRoot.getChildren().iterator().next());
                OMANode oldUdi = ppsTree.getChild(TAG_UpdateIdentifier);
                if (getInteger(oldUdi) != updateIdentifier) {
                    ppsMods++;
                }
                if (oldUdi != null) {
                    ppsTree.replaceNode(oldUdi, modRoot.getChild(TAG_UpdateIdentifier));
                } else {
                    ppsTree.addChild(modRoot.getChild(TAG_UpdateIdentifier));
                }
            } else {
                tailPath.removeFirst();     // Drop the instance
                OMANode current = instance.getListValue(tailPath.iterator());
                if (current == null) {
                    throw new OMAException("No previous node for " + tailPath + " in "
                            + homeSP.getFQDN());
                }
                for (OMANode newNode : modRoot.getChildren()) {
                    // newNode is something like Credential
                    // current is the same existing node
                    OMANode old = current.getParent().replaceNode(current, newNode);
                    ppsMods++;
                }
            }
        }

        return ppsMods > 0 ? buildHomeSP(instance, updateIdentifier) : null;
    }

    public HomeSP modifySP(HomeSP homeSP, Collection<MOData> mods)
            throws IOException {

        Log.d(OSUManager.TAG, "modifying SP: " + mods);
        MOTree moTree;
        int ppsMods = 0;
        int updateIdentifier = 0;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(mPpsFile))) {
            moTree = MOTree.unmarshal(in);
            // moTree is PPS/?/provider-data

            OMAConstructed targetTree = findTargetTree(moTree, homeSP.getFQDN());
            if (targetTree == null) {
                throw new IOException("Failed to find PPS tree for " + homeSP.getFQDN());
            }
            OMAConstructed instance = getInstanceNode(targetTree);

            for (MOData mod : mods) {
                LinkedList<String> tailPath =
                        getTailPath(mod.getBaseURI(), TAG_PerProviderSubscription);
                OMAConstructed modRoot = mod.getMOTree().getRoot();
                // modRoot is the MgmtTree with the actual object as a direct child
                // (e.g. Credential)

                if (tailPath.getFirst().equals(TAG_UpdateIdentifier)) {
                    updateIdentifier = getInteger(modRoot.getChildren().iterator().next());
                    OMANode oldUdi = targetTree.getChild(TAG_UpdateIdentifier);
                    if (getInteger(oldUdi) != updateIdentifier) {
                        ppsMods++;
                    }
                    if (oldUdi != null) {
                        targetTree.replaceNode(oldUdi, modRoot.getChild(TAG_UpdateIdentifier));
                    } else {
                        targetTree.addChild(modRoot.getChild(TAG_UpdateIdentifier));
                    }
                } else {
                    tailPath.removeFirst();     // Drop the instance
                    OMANode current = instance.getListValue(tailPath.iterator());
                    if (current == null) {
                        throw new IOException("No previous node for " + tailPath + " in " +
                                homeSP.getFQDN());
                    }
                    for (OMANode newNode : modRoot.getChildren()) {
                        // newNode is something like Credential
                        // current is the same existing node
                        OMANode old = current.getParent().replaceNode(current, newNode);
                        ppsMods++;
                    }
                }
            }
        }
        writeMO(moTree, mPpsFile);

        if (ppsMods == 0) {
            return null;    // HomeSP not modified.
        }

        // Return a new rebuilt HomeSP
        List<HomeSP> sps = buildSPs(moTree);
        if (sps != null) {
            for (HomeSP sp : sps) {
                if (sp.getFQDN().equals(homeSP.getFQDN())) {
                    return sp;
                }
            }
        } else {
            throw new OMAException("Failed to build HomeSP");
        }
        return null;
    }

    private static LinkedList<String> getTailPath(String pathString, String rootName)
            throws OMAException {
        String[] path = pathString.split("/");
        int pathIndex;
        for (pathIndex = 0; pathIndex < path.length; pathIndex++) {
            if (path[pathIndex].equalsIgnoreCase(rootName)) {
                pathIndex++;
                break;
            }
        }
        if (pathIndex >= path.length) {
            throw new OMAException("Bad node-path: " + pathString);
        }
        LinkedList<String> tailPath = new LinkedList<>();
        while (pathIndex < path.length) {
            tailPath.add(path[pathIndex]);
            pathIndex++;
        }
        return tailPath;
    }

    public HomeSP getHomeSP(String fqdn) {
        return mSPs.get(fqdn);
    }

    public void removeSP(String fqdn) throws IOException {
        if (mSPs.remove(fqdn) == null) {
            Log.d(OSUManager.TAG, "No HS20 profile to delete for " + fqdn);
            return;
        }

        Log.d(OSUManager.TAG, "Deleting HS20 profile for " + fqdn);

        MOTree moTree;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(mPpsFile))) {
            moTree = MOTree.unmarshal(in);
            OMAConstructed tbd = findTargetTree(moTree, fqdn);
            if (tbd == null) {
                throw new IOException("Node " + fqdn + " doesn't exist in MO tree");
            }
            OMAConstructed pps = moTree.getRoot();
            OMANode removed = pps.removeNode("?", tbd);
            if (removed == null) {
                throw new IOException("Failed to remove " + fqdn + " out of MO tree");
            }
        }
        writeMO(moTree, mPpsFile);
    }

    public MOTree getMOTree(HomeSP homeSP) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(mPpsFile))) {
            MOTree moTree = MOTree.unmarshal(in);
            OMAConstructed target = findTargetTree(moTree, homeSP.getFQDN());
            if (target == null) {
                throw new IOException("Can't find " + homeSP.getFQDN() + " in MO tree");
            }
            return MOTree.buildMgmtTree(OMAConstants.PPS_URN, OMAConstants.OMAVersion, target);
        }
    }

    private static void writeMO(MOTree moTree, File f) throws IOException {
        try (BufferedOutputStream out =
                     new BufferedOutputStream(new FileOutputStream(f, false))) {
            moTree.marshal(out);
            out.flush();
        }
    }

    private static String fqdnList(Collection<HomeSP> sps) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (HomeSP sp : sps) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append(sp.getFQDN());
        }
        return sb.toString();
    }

    private static OMANode buildHomeSPTree(HomeSP homeSP, OMAConstructed root, int instanceID)
            throws IOException {
        OMANode providerSubNode = root.addChild(getInstanceString(instanceID),
                null, null, null);

        // The HomeSP:
        OMANode homeSpNode = providerSubNode.addChild(TAG_HomeSP, null, null, null);
        if (!homeSP.getSSIDs().isEmpty()) {
            OMAConstructed nwkIDNode =
                    (OMAConstructed) homeSpNode.addChild(TAG_NetworkID, null, null, null);
            int instance = 0;
            for (Map.Entry<String, Long> entry : homeSP.getSSIDs().entrySet()) {
                OMAConstructed inode =
                        (OMAConstructed) nwkIDNode
                                .addChild(getInstanceString(instance++), null, null, null);
                inode.addChild(TAG_SSID, null, entry.getKey(), null);
                if (entry.getValue() != null) {
                    inode.addChild(TAG_HESSID, null,
                            String.format("%012x", entry.getValue()), null);
                }
            }
        }

        homeSpNode.addChild(TAG_FriendlyName, null, homeSP.getFriendlyName(), null);

        if (homeSP.getIconURL() != null) {
            homeSpNode.addChild(TAG_IconURL, null, homeSP.getIconURL(), null);
        }

        homeSpNode.addChild(TAG_FQDN, null, homeSP.getFQDN(), null);

        if (!homeSP.getMatchAllOIs().isEmpty() || !homeSP.getMatchAnyOIs().isEmpty()) {
            OMAConstructed homeOIList =
                    (OMAConstructed) homeSpNode.addChild(TAG_HomeOIList, null, null, null);

            int instance = 0;
            for (Long oi : homeSP.getMatchAllOIs()) {
                OMAConstructed inode =
                        (OMAConstructed) homeOIList.addChild(getInstanceString(instance++),
                                null, null, null);
                inode.addChild(TAG_HomeOI, null, String.format("%x", oi), null);
                inode.addChild(TAG_HomeOIRequired, null, "TRUE", null);
            }
            for (Long oi : homeSP.getMatchAnyOIs()) {
                OMAConstructed inode =
                        (OMAConstructed) homeOIList.addChild(getInstanceString(instance++),
                                null, null, null);
                inode.addChild(TAG_HomeOI, null, String.format("%x", oi), null);
                inode.addChild(TAG_HomeOIRequired, null, "FALSE", null);
            }
        }

        if (!homeSP.getOtherHomePartners().isEmpty()) {
            OMAConstructed otherPartners =
                    (OMAConstructed) homeSpNode.addChild(TAG_OtherHomePartners, null, null, null);
            int instance = 0;
            for (String fqdn : homeSP.getOtherHomePartners()) {
                OMAConstructed inode =
                        (OMAConstructed) otherPartners.addChild(getInstanceString(instance++),
                                null, null, null);
                inode.addChild(TAG_FQDN, null, fqdn, null);
            }
        }

        if (!homeSP.getRoamingConsortiums().isEmpty()) {
            homeSpNode.addChild(TAG_RoamingConsortiumOI, null,
                    getRCList(homeSP.getRoamingConsortiums()), null);
        }

        // The Credential:
        OMANode credentialNode = providerSubNode.addChild(TAG_Credential, null, null, null);
        Credential cred = homeSP.getCredential();
        EAPMethod method = cred.getEAPMethod();

        if (cred.getCtime() > 0) {
            credentialNode.addChild(TAG_CreationDate,
                    null, DTFormat.format(new Date(cred.getCtime())), null);
        }
        if (cred.getExpTime() > 0) {
            credentialNode.addChild(TAG_ExpirationDate,
                    null, DTFormat.format(new Date(cred.getExpTime())), null);
        }

        if (method.getEAPMethodID() == EAP.EAPMethodID.EAP_SIM
                || method.getEAPMethodID() == EAP.EAPMethodID.EAP_AKA
                || method.getEAPMethodID() == EAP.EAPMethodID.EAP_AKAPrim) {

            OMANode simNode = credentialNode.addChild(TAG_SIM, null, null, null);
            simNode.addChild(TAG_IMSI, null, cred.getImsi().toString(), null);
            simNode.addChild(TAG_EAPType, null,
                    Integer.toString(EAP.mapEAPMethod(method.getEAPMethodID())), null);

        } else if (method.getEAPMethodID() == EAP.EAPMethodID.EAP_TTLS) {

            OMANode unpNode = credentialNode.addChild(TAG_UsernamePassword, null, null, null);
            unpNode.addChild(TAG_Username, null, cred.getUserName(), null);
            unpNode.addChild(TAG_Password, null,
                    Base64.encodeToString(cred.getPassword().getBytes(StandardCharsets.UTF_8),
                            Base64.DEFAULT), null);
            OMANode eapNode = unpNode.addChild(TAG_EAPMethod, null, null, null);
            eapNode.addChild(TAG_EAPType, null,
                    Integer.toString(EAP.mapEAPMethod(method.getEAPMethodID())), null);
            eapNode.addChild(TAG_InnerMethod, null,
                    ((NonEAPInnerAuth) method.getAuthParam()).getOMAtype(), null);

        } else if (method.getEAPMethodID() == EAP.EAPMethodID.EAP_TLS) {

            OMANode certNode = credentialNode.addChild(TAG_DigitalCertificate, null, null, null);
            certNode.addChild(TAG_CertificateType, null, Credential.CertTypeX509, null);
            certNode.addChild(TAG_CertSHA256Fingerprint, null,
                    Utils.toHex(cred.getFingerPrint()), null);

        } else {
            throw new OMAException("Invalid credential on " + homeSP.getFQDN());
        }

        credentialNode.addChild(TAG_Realm, null, cred.getRealm(), null);

        // !!! Note: This node defines CRL checking through OSCP, I suspect we won't be able
        // to do that so it is commented out:
        //credentialNode.addChild(TAG_CheckAAAServerCertStatus, null, "TRUE", null);
        return providerSubNode;
    }

    private static String getInstanceString(int instance) {
        return "r1i" + instance;
    }

    private static String getRCList(Collection<Long> rcs) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Long roamingConsortium : rcs) {
            if (first) {
                first = false;
            } else {
                builder.append(',');
            }
            builder.append(String.format("%x", roamingConsortium));
        }
        return builder.toString();
    }

    public static List<HomeSP> buildSPs(MOTree moTree) throws OMAException {
        OMAConstructed spList;
        List<HomeSP> homeSPs = new ArrayList<>();
        if (moTree.getRoot().getName().equals(TAG_PerProviderSubscription)) {
            // The old PPS file was rooted at PPS instead of MgmtTree to conserve space
            spList = moTree.getRoot();

            if (spList == null) {
                return homeSPs;
            }

            for (OMANode node : spList.getChildren()) {
                if (!node.isLeaf()) {
                    homeSPs.add(buildHomeSP(node, 0));
                }
            }
        } else {
            for (OMANode ppsRoot : moTree.getRoot().getChildren()) {
                if (ppsRoot.getName().equals(TAG_PerProviderSubscription)) {
                    Integer updateIdentifier = null;
                    OMANode instance = null;
                    for (OMANode child : ppsRoot.getChildren()) {
                        if (child.getName().equals(TAG_UpdateIdentifier)) {
                            updateIdentifier = getInteger(child);
                        } else if (!child.isLeaf()) {
                            instance = child;
                        }
                    }
                    if (instance == null) {
                        throw new OMAException("PPS node missing instance node");
                    }
                    homeSPs.add(buildHomeSP(instance,
                            updateIdentifier != null ? updateIdentifier : 0));
                }
            }
        }

        return homeSPs;
    }

    private static HomeSP buildHomeSP(OMANode ppsRoot, int updateIdentifier) throws OMAException {
        OMANode spRoot = ppsRoot.getChild(TAG_HomeSP);

        String fqdn = spRoot.getScalarValue(Arrays.asList(TAG_FQDN).iterator());
        String friendlyName = spRoot.getScalarValue(Arrays.asList(TAG_FriendlyName).iterator());
        String iconURL = spRoot.getScalarValue(Arrays.asList(TAG_IconURL).iterator());

        HashSet<Long> roamingConsortiums = new HashSet<>();
        String oiString = spRoot.getScalarValue(Arrays.asList(TAG_RoamingConsortiumOI).iterator());
        if (oiString != null) {
            for (String oi : oiString.split(",")) {
                roamingConsortiums.add(Long.parseLong(oi.trim(), 16));
            }
        }

        Map<String, Long> ssids = new HashMap<>();

        OMANode ssidListNode = spRoot.getListValue(Arrays.asList(TAG_NetworkID).iterator());
        if (ssidListNode != null) {
            for (OMANode ssidRoot : ssidListNode.getChildren()) {
                OMANode hessidNode = ssidRoot.getChild(TAG_HESSID);
                ssids.put(ssidRoot.getChild(TAG_SSID).getValue(), getMac(hessidNode));
            }
        }

        Set<Long> matchAnyOIs = new HashSet<>();
        List<Long> matchAllOIs = new ArrayList<>();
        OMANode homeOIListNode = spRoot.getListValue(Arrays.asList(TAG_HomeOIList).iterator());
        if (homeOIListNode != null) {
            for (OMANode homeOIRoot : homeOIListNode.getChildren()) {
                String homeOI = homeOIRoot.getChild(TAG_HomeOI).getValue();
                if (Boolean.parseBoolean(homeOIRoot.getChild(TAG_HomeOIRequired).getValue())) {
                    matchAllOIs.add(Long.parseLong(homeOI, 16));
                } else {
                    matchAnyOIs.add(Long.parseLong(homeOI, 16));
                }
            }
        }

        Set<String> otherHomePartners = new HashSet<>();
        OMANode otherListNode =
                spRoot.getListValue(Arrays.asList(TAG_OtherHomePartners).iterator());
        if (otherListNode != null) {
            for (OMANode fqdnNode : otherListNode.getChildren()) {
                otherHomePartners.add(fqdnNode.getChild(TAG_FQDN).getValue());
            }
        }

        Credential credential = buildCredential(ppsRoot.getChild(TAG_Credential));

        OMANode policyNode = ppsRoot.getChild(TAG_Policy);
        Policy policy = policyNode != null ? new Policy(policyNode) : null;

        Map<String, String> aaaTrustRoots;
        OMANode aaaRootNode = ppsRoot.getChild(TAG_AAAServerTrustRoot);
        if (aaaRootNode == null) {
            aaaTrustRoots = null;
        } else {
            aaaTrustRoots = new HashMap<>(aaaRootNode.getChildren().size());
            for (OMANode child : aaaRootNode.getChildren()) {
                aaaTrustRoots.put(getString(child, TAG_CertURL),
                        getString(child, TAG_CertSHA256Fingerprint));
            }
        }

        OMANode updateNode = ppsRoot.getChild(TAG_SubscriptionUpdate);
        UpdateInfo subscriptionUpdate = updateNode != null ? new UpdateInfo(updateNode) : null;
        OMANode subNode = ppsRoot.getChild(TAG_SubscriptionParameters);
        SubscriptionParameters subscriptionParameters = subNode != null ?
                new SubscriptionParameters(subNode) : null;

        return new HomeSP(ssids, fqdn, roamingConsortiums, otherHomePartners,
                matchAnyOIs, matchAllOIs, friendlyName, iconURL, credential,
                policy, getInteger(ppsRoot.getChild(TAG_CredentialPriority), 0),
                aaaTrustRoots, subscriptionUpdate, subscriptionParameters, updateIdentifier);
    }

    private static Credential buildCredential(OMANode credNode) throws OMAException {
        long ctime = getTime(credNode.getChild(TAG_CreationDate));
        long expTime = getTime(credNode.getChild(TAG_ExpirationDate));
        String realm = getString(credNode.getChild(TAG_Realm));
        boolean checkAAACert = getBoolean(credNode.getChild(TAG_CheckAAAServerCertStatus));

        OMANode unNode = credNode.getChild(TAG_UsernamePassword);
        OMANode certNode = credNode.getChild(TAG_DigitalCertificate);
        OMANode simNode = credNode.getChild(TAG_SIM);

        int alternatives = 0;
        alternatives += unNode != null ? 1 : 0;
        alternatives += certNode != null ? 1 : 0;
        alternatives += simNode != null ? 1 : 0;
        if (alternatives != 1) {
            throw new OMAException("Expected exactly one credential type, got " + alternatives);
        }

        if (unNode != null) {
            String userName = getString(unNode.getChild(TAG_Username));
            String password = getString(unNode.getChild(TAG_Password));
            boolean machineManaged = getBoolean(unNode.getChild(TAG_MachineManaged));
            String softTokenApp = getString(unNode.getChild(TAG_SoftTokenApp));
            boolean ableToShare = getBoolean(unNode.getChild(TAG_AbleToShare));

            OMANode eapMethodNode = unNode.getChild(TAG_EAPMethod);
            int eapID = getInteger(eapMethodNode.getChild(TAG_EAPType));

            EAP.EAPMethodID eapMethodID = EAP.mapEAPMethod(eapID);
            if (eapMethodID == null) {
                throw new OMAException("Unknown EAP method: " + eapID);
            }

            Long vid = getOptionalInteger(eapMethodNode.getChild(TAG_VendorId));
            Long vtype = getOptionalInteger(eapMethodNode.getChild(TAG_VendorType));
            Long innerEAPType = getOptionalInteger(eapMethodNode.getChild(TAG_InnerEAPType));
            EAP.EAPMethodID innerEAPMethod = null;
            if (innerEAPType != null) {
                innerEAPMethod = EAP.mapEAPMethod(innerEAPType.intValue());
                if (innerEAPMethod == null) {
                    throw new OMAException("Bad inner EAP method: " + innerEAPType);
                }
            }

            Long innerVid = getOptionalInteger(eapMethodNode.getChild(TAG_InnerVendorID));
            Long innerVtype = getOptionalInteger(eapMethodNode.getChild(TAG_InnerVendorType));
            String innerNonEAPMethod = getString(eapMethodNode.getChild(TAG_InnerMethod));

            EAPMethod eapMethod;
            if (innerEAPMethod != null) {
                eapMethod = new EAPMethod(eapMethodID, new InnerAuthEAP(innerEAPMethod));
            } else if (vid != null) {
                eapMethod = new EAPMethod(eapMethodID,
                        new ExpandedEAPMethod(EAP.AuthInfoID.ExpandedEAPMethod,
                                vid.intValue(), vtype));
            } else if (innerVid != null) {
                eapMethod =
                        new EAPMethod(eapMethodID, new ExpandedEAPMethod(EAP.AuthInfoID
                                .ExpandedInnerEAPMethod, innerVid.intValue(), innerVtype));
            } else if (innerNonEAPMethod != null) {
                eapMethod = new EAPMethod(eapMethodID, new NonEAPInnerAuth(innerNonEAPMethod));
            } else {
                throw new OMAException("Incomplete set of EAP parameters");
            }

            return new Credential(ctime, expTime, realm, checkAAACert, eapMethod, userName,
                    password, machineManaged, softTokenApp, ableToShare);
        }
        if (certNode != null) {
            try {
                String certTypeString = getString(certNode.getChild(TAG_CertificateType));
                byte[] fingerPrint = getOctets(certNode.getChild(TAG_CertSHA256Fingerprint));

                EAPMethod eapMethod = new EAPMethod(EAP.EAPMethodID.EAP_TLS, null);

                return new Credential(ctime, expTime, realm, checkAAACert, eapMethod,
                        Credential.mapCertType(certTypeString), fingerPrint);
            } catch (NumberFormatException nfe) {
                throw new OMAException("Bad hex string: " + nfe.toString());
            }
        }
        if (simNode != null) {
            try {
                IMSIParameter imsi = new IMSIParameter(getString(simNode.getChild(TAG_IMSI)));

                EAPMethod eapMethod =
                        new EAPMethod(EAP.mapEAPMethod(getInteger(simNode.getChild(TAG_EAPType))),
                                null);

                return new Credential(ctime, expTime, realm, checkAAACert, eapMethod, imsi);
            } catch (IOException ioe) {
                throw new OMAException("Failed to parse IMSI: " + ioe);
            }
        }
        throw new OMAException("Missing credential parameters");
    }

    public static OMANode getChild(OMANode node, String key) throws OMAException {
        OMANode child = node.getChild(key);
        if (child == null) {
            throw new OMAException("No such node: " + key);
        }
        return child;
    }

    public static String getString(OMANode node, String key) throws OMAException {
        OMANode child = node.getChild(key);
        if (child == null) {
            throw new OMAException("Missing value for " + key);
        } else if (!child.isLeaf()) {
            throw new OMAException(key + " is not a leaf node");
        }
        return child.getValue();
    }

    public static long getLong(OMANode node, String key, Long dflt) throws OMAException {
        OMANode child = node.getChild(key);
        if (child == null) {
            if (dflt != null) {
                return dflt;
            } else {
                throw new OMAException("Missing value for " + key);
            }
        } else {
            if (!child.isLeaf()) {
                throw new OMAException(key + " is not a leaf node");
            }
            String value = child.getValue();
            try {
                long result = Long.parseLong(value);
                if (result < 0) {
                    throw new OMAException("Negative value for " + key);
                }
                return result;
            } catch (NumberFormatException nfe) {
                throw new OMAException("Value for " + key + " is non-numeric: " + value);
            }
        }
    }

    public static <T> T getSelection(OMANode node, String key) throws OMAException {
        OMANode child = node.getChild(key);
        if (child == null) {
            throw new OMAException("Missing value for " + key);
        } else if (!child.isLeaf()) {
            throw new OMAException(key + " is not a leaf node");
        }
        return getSelection(key, child.getValue());
    }

    public static <T> T getSelection(String key, String value) throws OMAException {
        if (value == null) {
            throw new OMAException("No value for " + key);
        }
        Map<String, Object> kvp = sSelectionMap.get(key);
        T result = (T) kvp.get(value.toLowerCase());
        if (result == null) {
            throw new OMAException("Invalid value '" + value + "' for " + key);
        }
        return result;
    }

    private static boolean getBoolean(OMANode boolNode) {
        return boolNode != null && Boolean.parseBoolean(boolNode.getValue());
    }

    public static String getString(OMANode stringNode) {
        return stringNode != null ? stringNode.getValue() : null;
    }

    private static int getInteger(OMANode intNode, int dflt) throws OMAException {
        if (intNode == null) {
            return dflt;
        }
        return getInteger(intNode);
    }

    private static int getInteger(OMANode intNode) throws OMAException {
        if (intNode == null) {
            throw new OMAException("Missing integer value");
        }
        try {
            return Integer.parseInt(intNode.getValue());
        } catch (NumberFormatException nfe) {
            throw new OMAException("Invalid integer: " + intNode.getValue());
        }
    }

    private static Long getMac(OMANode macNode) throws OMAException {
        if (macNode == null) {
            return null;
        }
        try {
            return Long.parseLong(macNode.getValue(), 16);
        } catch (NumberFormatException nfe) {
            throw new OMAException("Invalid MAC: " + macNode.getValue());
        }
    }

    private static Long getOptionalInteger(OMANode intNode) throws OMAException {
        if (intNode == null) {
            return null;
        }
        try {
            return Long.parseLong(intNode.getValue());
        } catch (NumberFormatException nfe) {
            throw new OMAException("Invalid integer: " + intNode.getValue());
        }
    }

    public static long getTime(OMANode timeNode) throws OMAException {
        if (timeNode == null) {
            return Utils.UNSET_TIME;
        }
        String timeText = timeNode.getValue();
        try {
            Date date = DTFormat.parse(timeText);
            return date.getTime();
        } catch (ParseException pe) {
            throw new OMAException("Badly formatted time: " + timeText);
        }
    }

    private static byte[] getOctets(OMANode octetNode) throws OMAException {
        if (octetNode == null) {
            throw new OMAException("Missing byte value");
        }
        return Utils.hexToBytes(octetNode.getValue());
    }
}
