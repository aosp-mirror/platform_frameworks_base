/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi.hotspot2.omadm;

import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.net.wifi.hotspot2.pps.Policy;
import android.net.wifi.hotspot2.pps.UpdateParameter;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xml.sax.SAXException;

/**
 * Utility class for converting OMA-DM (Open Mobile Alliance's Device Management)
 * PPS-MO (PerProviderSubscription Management Object) XML tree to a
 * {@link PasspointConfiguration} object.
 *
 * Currently this only supports PerProviderSubscription/HomeSP and
 * PerProviderSubscription/Credential subtree for Hotspot 2.0 Release 1 support.
 *
 * For more info, refer to Hotspot 2.0 PPS MO defined in section 9.1 of the Hotspot 2.0
 * Release 2 Technical Specification.
 *
 * Below is a sample XML string for a Release 1 PPS MO tree:
 *
 * <MgmtTree xmlns="syncml:dmddf1.2">
 *   <VerDTD>1.2</VerDTD>
 *   <Node>
 *     <NodeName>PerProviderSubscription</NodeName>
 *     <RTProperties>
 *       <Type>
 *         <DDFName>urn:wfa:mo:hotspot2dot0­perprovidersubscription:1.0</DDFName>
 *       </Type>
 *     </RTProperties>
 *     <Node>
 *       <NodeName>i001</NodeName>
 *       <Node>
 *         <NodeName>HomeSP</NodeName>
 *         <Node>
 *           <NodeName>FriendlyName</NodeName>
 *           <Value>Century House</Value>
 *         </Node>
 *         <Node>
 *           <NodeName>FQDN</NodeName>
 *           <Value>mi6.co.uk</Value>
 *         </Node>
 *         <Node>
 *           <NodeName>RoamingConsortiumOI</NodeName>
 *           <Value>112233,445566</Value>
 *         </Node>
 *       </Node>
 *       <Node>
 *         <NodeName>Credential</NodeName>
 *         <Node>
 *           <NodeName>Realm</NodeName>
 *           <Value>shaken.stirred.com</Value>
 *         </Node>
 *         <Node>
 *           <NodeName>UsernamePassword</NodeName>
 *           <Node>
 *             <NodeName>Username</NodeName>
 *             <Value>james</Value>
 *           </Node>
 *           <Node>
 *             <NodeName>Password</NodeName>
 *             <Value>Ym9uZDAwNw==</Value>
 *           </Node>
 *           <Node>
 *             <NodeName>EAPMethod</NodeName>
 *             <Node>
 *               <NodeName>EAPType</NodeName>
 *               <Value>21</Value>
 *             </Node>
 *             <Node>
 *               <NodeName>InnerMethod</NodeName>
 *               <Value>MS-CHAP-V2</Value>
 *             </Node>
 *           </Node>
 *         </Node>
 *       </Node>
 *     </Node>
 *   </Node>
 * </MgmtTree>
 */
public final class PpsMoParser {
    private static final String TAG = "PpsMoParser";

    /**
     * XML tags expected in the PPS MO (PerProviderSubscription Management Object) XML tree.
     */
    private static final String TAG_MANAGEMENT_TREE = "MgmtTree";
    private static final String TAG_VER_DTD = "VerDTD";
    private static final String TAG_NODE = "Node";
    private static final String TAG_NODE_NAME = "NodeName";
    private static final String TAG_RT_PROPERTIES = "RTProperties";
    private static final String TAG_TYPE = "Type";
    private static final String TAG_DDF_NAME = "DDFName";
    private static final String TAG_VALUE = "Value";

    /**
     * Name for PerProviderSubscription node.
     */
    private static final String NODE_PER_PROVIDER_SUBSCRIPTION = "PerProviderSubscription";

    /**
     * Fields under PerProviderSubscription.
     */
    private static final String NODE_UPDATE_IDENTIFIER = "UpdateIdentifier";
    private static final String NODE_AAA_SERVER_TRUST_ROOT = "AAAServerTrustRoot";
    private static final String NODE_SUBSCRIPTION_UPDATE = "SubscriptionUpdate";
    private static final String NODE_SUBSCRIPTION_PARAMETER = "SubscriptionParameters";
    private static final String NODE_TYPE_OF_SUBSCRIPTION = "TypeOfSubscription";
    private static final String NODE_USAGE_LIMITS = "UsageLimits";
    private static final String NODE_DATA_LIMIT = "DataLimit";
    private static final String NODE_START_DATE = "StartDate";
    private static final String NODE_TIME_LIMIT = "TimeLimit";
    private static final String NODE_USAGE_TIME_PERIOD = "UsageTimePeriod";
    private static final String NODE_CREDENTIAL_PRIORITY = "CredentialPriority";
    private static final String NODE_EXTENSION = "Extension";

    /**
     * Fields under HomeSP subtree.
     */
    private static final String NODE_HOMESP = "HomeSP";
    private static final String NODE_FQDN = "FQDN";
    private static final String NODE_FRIENDLY_NAME = "FriendlyName";
    private static final String NODE_ROAMING_CONSORTIUM_OI = "RoamingConsortiumOI";
    private static final String NODE_NETWORK_ID = "NetworkID";
    private static final String NODE_SSID = "SSID";
    private static final String NODE_HESSID = "HESSID";
    private static final String NODE_ICON_URL = "IconURL";
    private static final String NODE_HOME_OI_LIST = "HomeOIList";
    private static final String NODE_HOME_OI = "HomeOI";
    private static final String NODE_HOME_OI_REQUIRED = "HomeOIRequired";
    private static final String NODE_OTHER_HOME_PARTNERS = "OtherHomePartners";

    /**
     * Fields under Credential subtree.
     */
    private static final String NODE_CREDENTIAL = "Credential";
    private static final String NODE_CREATION_DATE = "CreationDate";
    private static final String NODE_EXPIRATION_DATE = "ExpirationDate";
    private static final String NODE_USERNAME_PASSWORD = "UsernamePassword";
    private static final String NODE_USERNAME = "Username";
    private static final String NODE_PASSWORD = "Password";
    private static final String NODE_MACHINE_MANAGED = "MachineManaged";
    private static final String NODE_SOFT_TOKEN_APP = "SoftTokenApp";
    private static final String NODE_ABLE_TO_SHARE = "AbleToShare";
    private static final String NODE_EAP_METHOD = "EAPMethod";
    private static final String NODE_EAP_TYPE = "EAPType";
    private static final String NODE_VENDOR_ID = "VendorId";
    private static final String NODE_VENDOR_TYPE = "VendorType";
    private static final String NODE_INNER_EAP_TYPE = "InnerEAPType";
    private static final String NODE_INNER_VENDOR_ID = "InnerVendorID";
    private static final String NODE_INNER_VENDOR_TYPE = "InnerVendorType";
    private static final String NODE_INNER_METHOD = "InnerMethod";
    private static final String NODE_DIGITAL_CERTIFICATE = "DigitalCertificate";
    private static final String NODE_CERTIFICATE_TYPE = "CertificateType";
    private static final String NODE_CERT_SHA256_FINGERPRINT = "CertSHA256Fingerprint";
    private static final String NODE_REALM = "Realm";
    private static final String NODE_SIM = "SIM";
    private static final String NODE_SIM_IMSI = "IMSI";
    private static final String NODE_CHECK_AAA_SERVER_CERT_STATUS = "CheckAAAServerCertStatus";

    /**
     * Fields under Policy subtree.
     */
    private static final String NODE_POLICY = "Policy";
    private static final String NODE_PREFERRED_ROAMING_PARTNER_LIST =
            "PreferredRoamingPartnerList";
    private static final String NODE_FQDN_MATCH = "FQDN_Match";
    private static final String NODE_PRIORITY = "Priority";
    private static final String NODE_COUNTRY = "Country";
    private static final String NODE_MIN_BACKHAUL_THRESHOLD = "MinBackhaulThreshold";
    private static final String NODE_NETWORK_TYPE = "NetworkType";
    private static final String NODE_DOWNLINK_BANDWIDTH = "DLBandwidth";
    private static final String NODE_UPLINK_BANDWIDTH = "ULBandwidth";
    private static final String NODE_POLICY_UPDATE = "PolicyUpdate";
    private static final String NODE_UPDATE_INTERVAL = "UpdateInterval";
    private static final String NODE_UPDATE_METHOD = "UpdateMethod";
    private static final String NODE_RESTRICTION = "Restriction";
    private static final String NODE_URI = "URI";
    private static final String NODE_TRUST_ROOT = "TrustRoot";
    private static final String NODE_CERT_URL = "CertURL";
    private static final String NODE_SP_EXCLUSION_LIST = "SPExclusionList";
    private static final String NODE_REQUIRED_PROTO_PORT_TUPLE = "RequiredProtoPortTuple";
    private static final String NODE_IP_PROTOCOL = "IPProtocol";
    private static final String NODE_PORT_NUMBER = "PortNumber";
    private static final String NODE_MAXIMUM_BSS_LOAD_VALUE = "MaximumBSSLoadValue";
    private static final String NODE_OTHER = "Other";

    /**
     * URN (Unique Resource Name) for PerProviderSubscription Management Object Tree.
     */
    private static final String PPS_MO_URN =
            "urn:wfa:mo:hotspot2dot0-perprovidersubscription:1.0";

    /**
     * Exception for generic parsing errors.
     */
    private static class ParsingException extends Exception {
        public ParsingException(String message) {
            super(message);
        }
    }

    /**
     * Class representing a node within the PerProviderSubscription tree.
     * This is used to flatten out and eliminate the extra layering in the XMLNode tree,
     * to make the data parsing easier and cleaner.
     *
     * A PPSNode can be an internal or a leaf node, but not both.
     *
     */
    private static abstract class PPSNode {
        private final String mName;
        public PPSNode(String name) {
            mName = name;
        }

        /**
         * @return the name of the node
         */
        public String getName() {
            return mName;
        }

        /**
         * Applies for internal node only.
         *
         * @return the list of children nodes.
         */
        public abstract List<PPSNode> getChildren();

        /**
         * Applies for leaf node only.
         *
         * @return the string value of the node
         */
        public abstract String getValue();

        /**
         * @return a flag indicating if this is a leaf or an internal node
         */
        public abstract boolean isLeaf();
    }

    /**
     * Class representing a leaf node in a PPS (PerProviderSubscription) tree.
     */
    private static class LeafNode extends PPSNode {
        private final String mValue;
        public LeafNode(String nodeName, String value) {
            super(nodeName);
            mValue = value;
        }

        @Override
        public String getValue() {
            return mValue;
        }

        @Override
        public List<PPSNode> getChildren() {
            return null;
        }

        @Override
        public boolean isLeaf() {
            return true;
        }
    }

    /**
     * Class representing an internal node in a PPS (PerProviderSubscription) tree.
     */
    private static class InternalNode extends PPSNode {
        private final List<PPSNode> mChildren;
        public InternalNode(String nodeName, List<PPSNode> children) {
            super(nodeName);
            mChildren = children;
        }

        @Override
        public String getValue() {
            return null;
        }

        @Override
        public List<PPSNode> getChildren() {
            return mChildren;
        }

        @Override
        public boolean isLeaf() {
            return false;
        }
    }

    /**
     * @hide
     */
    public PpsMoParser() {}

    /**
     * Convert a XML string representation of a PPS MO (PerProviderSubscription
     * Management Object) tree to a {@link PasspointConfiguration} object.
     *
     * @param xmlString XML string representation of a PPS MO tree
     * @return {@link PasspointConfiguration} or null
     */
    public static PasspointConfiguration parseMoText(String xmlString) {
        // Convert the XML string to a XML tree.
        XMLParser xmlParser = new XMLParser();
        XMLNode root = null;
        try {
            root = xmlParser.parse(xmlString);
        } catch(IOException | SAXException e) {
            return null;
        }
        if (root == null) {
            return null;
        }

        // Verify root node is a "MgmtTree" node.
        if (root.getTag() != TAG_MANAGEMENT_TREE) {
            Log.e(TAG, "Root is not a MgmtTree");
            return null;
        }

        String verDtd = null;    // Used for detecting duplicate VerDTD element.
        PasspointConfiguration config = null;
        for (XMLNode child : root.getChildren()) {
            switch(child.getTag()) {
                case TAG_VER_DTD:
                    if (verDtd != null) {
                        Log.e(TAG, "Duplicate VerDTD element");
                        return null;
                    }
                    verDtd = child.getText();
                    break;
                case TAG_NODE:
                    if (config != null) {
                        Log.e(TAG, "Unexpected multiple Node element under MgmtTree");
                        return null;
                    }
                    try {
                        config = parsePpsNode(child);
                    } catch (ParsingException e) {
                        Log.e(TAG, e.getMessage());
                        return null;
                    }
                    break;
                default:
                    Log.e(TAG, "Unknown node: " + child.getTag());
                    return null;
            }
        }
        return config;
    }

    /**
     * Parse a PerProviderSubscription node. Below is the format of the XML tree (with
     * each XML element represent a node in the tree):
     *
     * <Node>
     *   <NodeName>PerProviderSubscription</NodeName>
     *   <RTProperties>
     *     ...
     *   </RTPProperties>
     *   <Node>
     *     <NodeName>UpdateIdentifier</NodeName>
     *     <Value>...</Value>
     *   </Node>
     *   <Node>
     *     ...
     *   </Node>
     * </Node>
     *
     * @param node XMLNode that contains PerProviderSubscription node.
     * @return PasspointConfiguration or null
     * @throws ParsingException
     */
    private static PasspointConfiguration parsePpsNode(XMLNode node)
            throws ParsingException {
        PasspointConfiguration config = null;
        String nodeName = null;
        int updateIdentifier = Integer.MIN_VALUE;
        for (XMLNode child : node.getChildren()) {
            switch (child.getTag()) {
                case TAG_NODE_NAME:
                    if (nodeName != null) {
                        throw new ParsingException("Duplicate NodeName: " + child.getText());
                    }
                    nodeName = child.getText();
                    if (!TextUtils.equals(nodeName, NODE_PER_PROVIDER_SUBSCRIPTION)) {
                        throw new ParsingException("Unexpected NodeName: " + nodeName);
                    }
                    break;
                case TAG_NODE:
                    // A node can be either an UpdateIdentifier node or a PerProviderSubscription
                    // instance node.  Flatten out the XML tree first by converting it to a PPS
                    // tree to reduce the complexity of the parsing code.
                    PPSNode ppsNodeRoot = buildPpsNode(child);
                    if (TextUtils.equals(ppsNodeRoot.getName(), NODE_UPDATE_IDENTIFIER)) {
                        if (updateIdentifier != Integer.MIN_VALUE) {
                            throw new ParsingException("Multiple node for UpdateIdentifier");
                        }
                        updateIdentifier = parseInteger(getPpsNodeValue(ppsNodeRoot));
                    } else {
                        // Only one PerProviderSubscription instance is expected and allowed.
                        if (config != null) {
                            throw new ParsingException("Multiple PPS instance");
                        }
                        config = parsePpsInstance(ppsNodeRoot);
                    }
                    break;
                case TAG_RT_PROPERTIES:
                    // Parse and verify URN stored in the RT (Run Time) Properties.
                    String urn = parseUrn(child);
                    if (!TextUtils.equals(urn, PPS_MO_URN)) {
                        throw new ParsingException("Unknown URN: " + urn);
                    }
                    break;
                default:
                    throw new ParsingException("Unknown tag under PPS node: " + child.getTag());
            }
        }
        if (config != null && updateIdentifier != Integer.MIN_VALUE) {
            config.setUpdateIdentifier(updateIdentifier);
        }
        return config;
    }

    /**
     * Parse the URN stored in the RTProperties. Below is the format of the RTPProperties node:
     *
     * <RTProperties>
     *   <Type>
     *     <DDFName>urn:...</DDFName>
     *   </Type>
     * </RTProperties>
     *
     * @param node XMLNode that contains RTProperties node.
     * @return URN String of URN.
     * @throws ParsingException
     */
    private static String parseUrn(XMLNode node) throws ParsingException {
        if (node.getChildren().size() != 1)
            throw new ParsingException("Expect RTPProperties node to only have one child");

        XMLNode typeNode = node.getChildren().get(0);
        if (typeNode.getChildren().size() != 1) {
            throw new ParsingException("Expect Type node to only have one child");
        }
        if (!TextUtils.equals(typeNode.getTag(), TAG_TYPE)) {
            throw new ParsingException("Unexpected tag for Type: " + typeNode.getTag());
        }

        XMLNode ddfNameNode = typeNode.getChildren().get(0);
        if (!ddfNameNode.getChildren().isEmpty()) {
            throw new ParsingException("Expect DDFName node to have no child");
        }
        if (!TextUtils.equals(ddfNameNode.getTag(), TAG_DDF_NAME)) {
            throw new ParsingException("Unexpected tag for DDFName: " + ddfNameNode.getTag());
        }

        return ddfNameNode.getText();
    }

    /**
     * Convert a XML tree represented by XMLNode to a PPS (PerProviderSubscription) instance tree
     * represented by PPSNode.  This flattens out the XML tree to allow easier and cleaner parsing
     * of the PPS configuration data.  Only three types of XML tag are expected: "NodeName",
     * "Node", and "Value".
     *
     * The original XML tree (each XML element represent a node):
     *
     * <Node>
     *   <NodeName>root</NodeName>
     *   <Node>
     *     <NodeName>child1</NodeName>
     *     <Value>value1</Value>
     *   </Node>
     *   <Node>
     *     <NodeName>child2</NodeName>
     *     <Node>
     *       <NodeName>grandchild1</NodeName>
     *       ...
     *     </Node>
     *   </Node>
     *   ...
     * </Node>
     *
     * The converted PPS tree:
     *
     * [root] --- [child1, value1]
     *   |
     *   ---------[child2] --------[grandchild1] --- ...
     *
     * @param node XMLNode pointed to the root of a XML tree
     * @return PPSNode pointing to the root of a PPS tree
     * @throws ParsingException
     */
    private static PPSNode buildPpsNode(XMLNode node) throws ParsingException {
        String nodeName = null;
        String nodeValue = null;
        List<PPSNode> childNodes = new ArrayList<PPSNode>();
        // Names of parsed child nodes, use for detecting multiple child nodes with the same name.
        Set<String> parsedNodes = new HashSet<String>();

        for (XMLNode child : node.getChildren()) {
            String tag = child.getTag();
            if (TextUtils.equals(tag, TAG_NODE_NAME)) {
                if (nodeName != null) {
                    throw new ParsingException("Duplicate NodeName node");
                }
                nodeName = child.getText();
            } else if (TextUtils.equals(tag, TAG_NODE)) {
                PPSNode ppsNode = buildPpsNode(child);
                if (parsedNodes.contains(ppsNode.getName())) {
                    throw new ParsingException("Duplicate node: " + ppsNode.getName());
                }
                parsedNodes.add(ppsNode.getName());
                childNodes.add(ppsNode);
            } else if (TextUtils.equals(tag, TAG_VALUE)) {
               if (nodeValue != null) {
                   throw new ParsingException("Duplicate Value node");
               }
               nodeValue = child.getText();
            } else {
                throw new ParsingException("Unknown tag: " + tag);
            }
        }

        if (nodeName == null) {
            throw new ParsingException("Invalid node: missing NodeName");
        }
        if (nodeValue == null && childNodes.size() == 0) {
            throw new ParsingException("Invalid node: " + nodeName +
                    " missing both value and children");
        }
        if (nodeValue != null && childNodes.size() > 0) {
            throw new ParsingException("Invalid node: " + nodeName +
                    " contained both value and children");
        }

        if (nodeValue != null) {
            return new LeafNode(nodeName, nodeValue);
        }
        return new InternalNode(nodeName, childNodes);
    }

    /**
     * Return the value of a PPSNode.  An exception will be thrown if the given node
     * is not a leaf node.
     *
     * @param node PPSNode to retrieve the value from
     * @return String representing the value of the node
     * @throws ParsingException
     */
    private static String getPpsNodeValue(PPSNode node) throws ParsingException {
        if (!node.isLeaf()) {
            throw new ParsingException("Cannot get value from a non-leaf node: " + node.getName());
        }
        return node.getValue();
    }

    /**
     * Parse a PPS (PerProviderSubscription) configurations from a PPS tree.
     *
     * @param root PPSNode representing the root of the PPS tree
     * @return PasspointConfiguration
     * @throws ParsingException
     */
    private static PasspointConfiguration parsePpsInstance(PPSNode root)
            throws ParsingException {
        if (root.isLeaf()) {
            throw new ParsingException("Leaf node not expected for PPS instance");
        }

        PasspointConfiguration config = new PasspointConfiguration();
        for (PPSNode child : root.getChildren()) {
            switch(child.getName()) {
                case NODE_HOMESP:
                    config.setHomeSp(parseHomeSP(child));
                    break;
                case NODE_CREDENTIAL:
                    config.setCredential(parseCredential(child));
                    break;
                case NODE_POLICY:
                    config.setPolicy(parsePolicy(child));
                    break;
                case NODE_AAA_SERVER_TRUST_ROOT:
                    config.setTrustRootCertList(parseAAAServerTrustRootList(child));
                    break;
                case NODE_SUBSCRIPTION_UPDATE:
                    config.setSubscriptionUpdate(parseUpdateParameter(child));
                    break;
                case NODE_SUBSCRIPTION_PARAMETER:
                    parseSubscriptionParameter(child, config);
                    break;
                case NODE_CREDENTIAL_PRIORITY:
                    config.setCredentialPriority(parseInteger(getPpsNodeValue(child)));
                    break;
                case NODE_EXTENSION:
                    // All vendor specific information will be under this node.
                    Log.d(TAG, "Ignore Extension node for vendor specific information");
                    break;
                default:
                    throw new ParsingException("Unknown node: " + child.getName());
            }
        }
        return config;
    }

    /**
     * Parse configurations under PerProviderSubscription/HomeSP subtree.
     *
     * @param node PPSNode representing the root of the PerProviderSubscription/HomeSP subtree
     * @return HomeSP
     * @throws ParsingException
     */
    private static HomeSp parseHomeSP(PPSNode node) throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for HomeSP");
        }

        HomeSp homeSp = new HomeSp();
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_FQDN:
                    homeSp.setFqdn(getPpsNodeValue(child));
                    break;
                case NODE_FRIENDLY_NAME:
                    homeSp.setFriendlyName(getPpsNodeValue(child));
                    break;
                case NODE_ROAMING_CONSORTIUM_OI:
                    homeSp.setRoamingConsortiumOis(
                            parseRoamingConsortiumOI(getPpsNodeValue(child)));
                    break;
                case NODE_ICON_URL:
                    homeSp.setIconUrl(getPpsNodeValue(child));
                    break;
                case NODE_NETWORK_ID:
                    homeSp.setHomeNetworkIds(parseNetworkIds(child));
                    break;
                case NODE_HOME_OI_LIST:
                    Pair<List<Long>, List<Long>> homeOIs = parseHomeOIList(child);
                    homeSp.setMatchAllOis(convertFromLongList(homeOIs.first));
                    homeSp.setMatchAnyOis(convertFromLongList(homeOIs.second));
                    break;
                case NODE_OTHER_HOME_PARTNERS:
                    homeSp.setOtherHomePartners(parseOtherHomePartners(child));
                    break;
                default:
                    throw new ParsingException("Unknown node under HomeSP: " + child.getName());
            }
        }
        return homeSp;
    }

    /**
     * Parse the roaming consortium OI string, which contains a list of OIs separated by ",".
     *
     * @param oiStr string containing list of OIs (Organization Identifiers) separated by ","
     * @return long[]
     * @throws ParsingException
     */
    private static long[] parseRoamingConsortiumOI(String oiStr)
            throws ParsingException {
        String[] oiStrArray = oiStr.split(",");
        long[] oiArray = new long[oiStrArray.length];
        for (int i = 0; i < oiStrArray.length; i++) {
            oiArray[i] = parseLong(oiStrArray[i], 16);
        }
        return oiArray;
    }

    /**
     * Parse configurations under PerProviderSubscription/HomeSP/NetworkID subtree.
     *
     * @param node PPSNode representing the root of the PerProviderSubscription/HomeSP/NetworkID
     *             subtree
     * @return HashMap<String, Long> representing list of <SSID, HESSID> pair.
     * @throws ParsingException
     */
    static private Map<String, Long> parseNetworkIds(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for NetworkID");
        }

        Map<String, Long> networkIds = new HashMap<>();
        for (PPSNode child : node.getChildren()) {
            Pair<String, Long> networkId = parseNetworkIdInstance(child);
            networkIds.put(networkId.first, networkId.second);
        }
        return networkIds;
    }

    /**
     * Parse configurations under PerProviderSubscription/HomeSP/NetworkID/<X+> subtree.
     * The instance name (<X+>) is irrelevant and must be unique for each instance, which
     * is verified when the PPS tree is constructed {@link #buildPpsNode}.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/HomeSP/NetworkID/<X+> subtree
     * @return Pair<String, Long> representing <SSID, HESSID> pair.
     * @throws ParsingException
     */
    static private Pair<String, Long> parseNetworkIdInstance(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for NetworkID instance");
        }

        String ssid = null;
        Long hessid = null;
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_SSID:
                    ssid = getPpsNodeValue(child);
                    break;
                case NODE_HESSID:
                    hessid = parseLong(getPpsNodeValue(child), 16);
                    break;
                default:
                    throw new ParsingException("Unknown node under NetworkID instance: " +
                            child.getName());
            }
        }
        if (ssid == null)
            throw new ParsingException("NetworkID instance missing SSID");

        return new Pair<String, Long>(ssid, hessid);
    }

    /**
     * Parse configurations under PerProviderSubscription/HomeSP/HomeOIList subtree.
     *
     * @param node PPSNode representing the root of the PerProviderSubscription/HomeSP/HomeOIList
     *             subtree
     * @return Pair<List<Long>, List<Long>> containing both MatchAllOIs and MatchAnyOIs list.
     * @throws ParsingException
     */
    private static Pair<List<Long>, List<Long>> parseHomeOIList(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for HomeOIList");
        }

        List<Long> matchAllOIs = new ArrayList<Long>();
        List<Long> matchAnyOIs = new ArrayList<Long>();
        for (PPSNode child : node.getChildren()) {
            Pair<Long, Boolean> homeOI = parseHomeOIInstance(child);
            if (homeOI.second.booleanValue()) {
                matchAllOIs.add(homeOI.first);
            } else {
                matchAnyOIs.add(homeOI.first);
            }
        }
        return new Pair<List<Long>, List<Long>>(matchAllOIs, matchAnyOIs);
    }

    /**
     * Parse configurations under PerProviderSubscription/HomeSP/HomeOIList/<X+> subtree.
     * The instance name (<X+>) is irrelevant and must be unique for each instance, which
     * is verified when the PPS tree is constructed {@link #buildPpsNode}.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/HomeSP/HomeOIList/<X+> subtree
     * @return Pair<Long, Boolean> containing a HomeOI and a HomeOIRequired flag
     * @throws ParsingException
     */
    private static Pair<Long, Boolean> parseHomeOIInstance(PPSNode node) throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for HomeOI instance");
        }

        Long oi = null;
        Boolean required = null;
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_HOME_OI:
                    try {
                        oi = Long.valueOf(getPpsNodeValue(child), 16);
                    } catch (NumberFormatException e) {
                        throw new ParsingException("Invalid HomeOI: " + getPpsNodeValue(child));
                    }
                    break;
                case NODE_HOME_OI_REQUIRED:
                    required = Boolean.valueOf(getPpsNodeValue(child));
                    break;
                default:
                    throw new ParsingException("Unknown node under NetworkID instance: " +
                            child.getName());
            }
        }
        if (oi == null) {
            throw new ParsingException("HomeOI instance missing OI field");
        }
        if (required == null) {
            throw new ParsingException("HomeOI instance missing required field");
        }
        return new Pair<Long, Boolean>(oi, required);
    }

    /**
     * Parse configurations under PerProviderSubscription/HomeSP/OtherHomePartners subtree.
     * This contains a list of FQDN (Fully Qualified Domain Name) that are considered
     * home partners.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/HomeSP/OtherHomePartners subtree
     * @return String[] list of partner's FQDN
     * @throws ParsingException
     */
    private static String[] parseOtherHomePartners(PPSNode node) throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for OtherHomePartners");
        }
        List<String> otherHomePartners = new ArrayList<String>();
        for (PPSNode child : node.getChildren()) {
            String fqdn = parseOtherHomePartnerInstance(child);
            otherHomePartners.add(fqdn);
        }
        return otherHomePartners.toArray(new String[otherHomePartners.size()]);
    }

    /**
     * Parse configurations under PerProviderSubscription/HomeSP/OtherHomePartners/<X+> subtree.
     * The instance name (<X+>) is irrelevant and must be unique for each instance, which
     * is verified when the PPS tree is constructed {@link #buildPpsNode}.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/HomeSP/OtherHomePartners/<X+> subtree
     * @return String FQDN of the partner
     * @throws ParsingException
     */
    private static String parseOtherHomePartnerInstance(PPSNode node) throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for OtherHomePartner instance");
        }
        String fqdn = null;
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_FQDN:
                    fqdn = getPpsNodeValue(child);
                    break;
                default:
                    throw new ParsingException(
                            "Unknown node under OtherHomePartner instance: " + child.getName());
            }
        }
        if (fqdn == null) {
            throw new ParsingException("OtherHomePartner instance missing FQDN field");
        }
        return fqdn;
    }

    /**
     * Parse configurations under PerProviderSubscription/Credential subtree.
     *
     * @param node PPSNode representing the root of the PerProviderSubscription/Credential subtree
     * @return Credential
     * @throws ParsingException
     */
    private static Credential parseCredential(PPSNode node) throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for HomeSP");
        }

        Credential credential = new Credential();
        for (PPSNode child: node.getChildren()) {
            switch (child.getName()) {
                case NODE_CREATION_DATE:
                    credential.setCreationTimeInMillis(parseDate(getPpsNodeValue(child)));
                    break;
                case NODE_EXPIRATION_DATE:
                    credential.setExpirationTimeInMillis(parseDate(getPpsNodeValue(child)));
                    break;
                case NODE_USERNAME_PASSWORD:
                    credential.setUserCredential(parseUserCredential(child));
                    break;
                case NODE_DIGITAL_CERTIFICATE:
                    credential.setCertCredential(parseCertificateCredential(child));
                    break;
                case NODE_REALM:
                    credential.setRealm(getPpsNodeValue(child));
                    break;
                case NODE_CHECK_AAA_SERVER_CERT_STATUS:
                    credential.setCheckAaaServerCertStatus(
                            Boolean.parseBoolean(getPpsNodeValue(child)));
                    break;
                case NODE_SIM:
                    credential.setSimCredential(parseSimCredential(child));
                    break;
                default:
                    throw new ParsingException("Unknown node under Credential: " +
                            child.getName());
            }
        }
        return credential;
    }

    /**
     * Parse configurations under PerProviderSubscription/Credential/UsernamePassword subtree.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/Credential/UsernamePassword subtree
     * @return Credential.UserCredential
     * @throws ParsingException
     */
    private static Credential.UserCredential parseUserCredential(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for UsernamePassword");
        }

        Credential.UserCredential userCred = new Credential.UserCredential();
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_USERNAME:
                    userCred.setUsername(getPpsNodeValue(child));
                    break;
                case NODE_PASSWORD:
                    userCred.setPassword(getPpsNodeValue(child));
                    break;
                case NODE_MACHINE_MANAGED:
                    userCred.setMachineManaged(Boolean.parseBoolean(getPpsNodeValue(child)));
                    break;
                case NODE_SOFT_TOKEN_APP:
                    userCred.setSoftTokenApp(getPpsNodeValue(child));
                    break;
                case NODE_ABLE_TO_SHARE:
                    userCred.setAbleToShare(Boolean.parseBoolean(getPpsNodeValue(child)));
                    break;
                case NODE_EAP_METHOD:
                    parseEAPMethod(child, userCred);
                    break;
                default:
                    throw new ParsingException("Unknown node under UsernamPassword: " +
                            child.getName());
            }
        }
        return userCred;
    }

    /**
     * Parse configurations under PerProviderSubscription/Credential/UsernamePassword/EAPMethod
     * subtree.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/Credential/UsernamePassword/EAPMethod subtree
     * @param userCred UserCredential to be updated with EAP method values.
     * @throws ParsingException
     */
    private static void parseEAPMethod(PPSNode node, Credential.UserCredential userCred)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for EAPMethod");
        }

        for (PPSNode child : node.getChildren()) {
            switch(child.getName()) {
                case NODE_EAP_TYPE:
                    userCred.setEapType(parseInteger(getPpsNodeValue(child)));
                    break;
                case NODE_INNER_METHOD:
                    userCred.setNonEapInnerMethod(getPpsNodeValue(child));
                    break;
                case NODE_VENDOR_ID:
                case NODE_VENDOR_TYPE:
                case NODE_INNER_EAP_TYPE:
                case NODE_INNER_VENDOR_ID:
                case NODE_INNER_VENDOR_TYPE:
                    // Only EAP-TTLS is currently supported for user credential, which doesn't
                    // use any of these parameters.
                    Log.d(TAG, "Ignore unsupported EAP method parameter: " + child.getName());
                    break;
                default:
                    throw new ParsingException("Unknown node under EAPMethod: " + child.getName());
            }
        }
    }

    /**
     * Parse configurations under PerProviderSubscription/Credential/DigitalCertificate subtree.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/Credential/DigitalCertificate subtree
     * @return Credential.CertificateCredential
     * @throws ParsingException
     */
    private static Credential.CertificateCredential parseCertificateCredential(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for DigitalCertificate");
        }

        Credential.CertificateCredential certCred = new Credential.CertificateCredential();
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_CERTIFICATE_TYPE:
                    certCred.setCertType(getPpsNodeValue(child));
                    break;
                case NODE_CERT_SHA256_FINGERPRINT:
                    certCred.setCertSha256Fingerprint(parseHexString(getPpsNodeValue(child)));
                    break;
                default:
                    throw new ParsingException("Unknown node under DigitalCertificate: " +
                            child.getName());
            }
        }
        return certCred;
    }

    /**
     * Parse configurations under PerProviderSubscription/Credential/SIM subtree.
     *
     * @param node PPSNode representing the root of the PerProviderSubscription/Credential/SIM
     *             subtree
     * @return Credential.SimCredential
     * @throws ParsingException
     */
    private static Credential.SimCredential parseSimCredential(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for SIM");
        }

        Credential.SimCredential simCred = new Credential.SimCredential();
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_SIM_IMSI:
                    simCred.setImsi(getPpsNodeValue(child));
                    break;
                case NODE_EAP_TYPE:
                    simCred.setEapType(parseInteger(getPpsNodeValue(child)));
                    break;
                default:
                    throw new ParsingException("Unknown node under SIM: " + child.getName());
            }
        }
        return simCred;
    }

    /**
     * Parse configurations under PerProviderSubscription/Policy subtree.
     *
     * @param node PPSNode representing the root of the PerProviderSubscription/Policy subtree
     * @return {@link Policy}
     * @throws ParsingException
     */
    private static Policy parsePolicy(PPSNode node) throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for Policy");
        }

        Policy policy = new Policy();
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_PREFERRED_ROAMING_PARTNER_LIST:
                    policy.setPreferredRoamingPartnerList(parsePreferredRoamingPartnerList(child));
                    break;
                case NODE_MIN_BACKHAUL_THRESHOLD:
                    parseMinBackhaulThreshold(child, policy);
                    break;
                case NODE_POLICY_UPDATE:
                    policy.setPolicyUpdate(parseUpdateParameter(child));
                    break;
                case NODE_SP_EXCLUSION_LIST:
                    policy.setExcludedSsidList(parseSpExclusionList(child));
                    break;
                case NODE_REQUIRED_PROTO_PORT_TUPLE:
                    policy.setRequiredProtoPortMap(parseRequiredProtoPortTuple(child));
                    break;
                case NODE_MAXIMUM_BSS_LOAD_VALUE:
                    policy.setMaximumBssLoadValue(parseInteger(getPpsNodeValue(child)));
                    break;
                default:
                    throw new ParsingException("Unknown node under Policy: " + child.getName());
            }
        }
        return policy;
    }

    /**
     * Parse configurations under PerProviderSubscription/Policy/PreferredRoamingPartnerList
     * subtree.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/Policy/PreferredRoamingPartnerList subtree
     * @return List of {@link Policy#RoamingPartner}
     * @throws ParsingException
     */
    private static List<Policy.RoamingPartner> parsePreferredRoamingPartnerList(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for PreferredRoamingPartnerList");
        }
        List<Policy.RoamingPartner> partnerList = new ArrayList<>();
        for (PPSNode child : node.getChildren()) {
            partnerList.add(parsePreferredRoamingPartner(child));
        }
        return partnerList;
    }

    /**
     * Parse configurations under PerProviderSubscription/Policy/PreferredRoamingPartnerList/<X+>
     * subtree.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/Policy/PreferredRoamingPartnerList/<X+> subtree
     * @return {@link Policy#RoamingPartner}
     * @throws ParsingException
     */
    private static Policy.RoamingPartner parsePreferredRoamingPartner(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for PreferredRoamingPartner "
                    + "instance");
        }

        Policy.RoamingPartner roamingPartner = new Policy.RoamingPartner();
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_FQDN_MATCH:
                    // FQDN_Match field is in the format of "[FQDN],[MatchInfo]", where [MatchInfo]
                    // is either "exactMatch" for exact match of FQDN or "includeSubdomains" for
                    // matching all FQDNs with the same sub-domain.
                    String fqdnMatch = getPpsNodeValue(child);
                    String[] fqdnMatchArray = fqdnMatch.split(",");
                    if (fqdnMatchArray.length != 2) {
                        throw new ParsingException("Invalid FQDN_Match: " + fqdnMatch);
                    }
                    roamingPartner.setFqdn(fqdnMatchArray[0]);
                    if (TextUtils.equals(fqdnMatchArray[1], "exactMatch")) {
                        roamingPartner.setFqdnExactMatch(true);
                    } else if (TextUtils.equals(fqdnMatchArray[1], "includeSubdomains")) {
                        roamingPartner.setFqdnExactMatch(false);
                    } else {
                        throw new ParsingException("Invalid FQDN_Match: " + fqdnMatch);
                    }
                    break;
                case NODE_PRIORITY:
                    roamingPartner.setPriority(parseInteger(getPpsNodeValue(child)));
                    break;
                case NODE_COUNTRY:
                    roamingPartner.setCountries(getPpsNodeValue(child));
                    break;
                default:
                    throw new ParsingException("Unknown node under PreferredRoamingPartnerList "
                            + "instance " + child.getName());
            }
        }
        return roamingPartner;
    }

    /**
     * Parse configurations under PerProviderSubscription/Policy/MinBackhaulThreshold subtree
     * into the given policy.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/Policy/MinBackhaulThreshold subtree
     * @param policy The policy to store the MinBackhualThreshold configuration
     * @throws ParsingException
     */
    private static void parseMinBackhaulThreshold(PPSNode node, Policy policy)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for MinBackhaulThreshold");
        }
        for (PPSNode child : node.getChildren()) {
            parseMinBackhaulThresholdInstance(child, policy);
        }
    }

    /**
     * Parse configurations under PerProviderSubscription/Policy/MinBackhaulThreshold/<X+> subtree
     * into the given policy.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/Policy/MinBackhaulThreshold/<X+> subtree
     * @param policy The policy to store the MinBackhaulThreshold configuration
     * @throws ParsingException
     */
    private static void parseMinBackhaulThresholdInstance(PPSNode node, Policy policy)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for MinBackhaulThreshold instance");
        }
        String networkType = null;
        long downlinkBandwidth = Long.MIN_VALUE;
        long uplinkBandwidth = Long.MIN_VALUE;
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_NETWORK_TYPE:
                    networkType = getPpsNodeValue(child);
                    break;
                case NODE_DOWNLINK_BANDWIDTH:
                    downlinkBandwidth = parseLong(getPpsNodeValue(child), 10);
                    break;
                case NODE_UPLINK_BANDWIDTH:
                    uplinkBandwidth = parseLong(getPpsNodeValue(child), 10);
                    break;
                default:
                    throw new ParsingException("Unknown node under MinBackhaulThreshold instance "
                            + child.getName());
            }
        }
        if (networkType == null) {
            throw new ParsingException("Missing NetworkType field");
        }

        if (TextUtils.equals(networkType, "home")) {
            policy.setMinHomeDownlinkBandwidth(downlinkBandwidth);
            policy.setMinHomeUplinkBandwidth(uplinkBandwidth);
        } else if (TextUtils.equals(networkType, "roaming")) {
            policy.setMinRoamingDownlinkBandwidth(downlinkBandwidth);
            policy.setMinRoamingUplinkBandwidth(uplinkBandwidth);
        } else {
            throw new ParsingException("Invalid network type: " + networkType);
        }
    }

    /**
     * Parse update parameters. This contained configurations from either
     * PerProviderSubscription/Policy/PolicyUpdate or PerProviderSubscription/SubscriptionUpdate
     * subtree.
     *
     * @param node PPSNode representing the root of the PerProviderSubscription/Policy/PolicyUpdate
     *             or PerProviderSubscription/SubscriptionUpdate subtree
     * @return {@link UpdateParameter}
     * @throws ParsingException
     */
    private static UpdateParameter parseUpdateParameter(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for Update Parameters");
        }

        UpdateParameter updateParam = new UpdateParameter();
        for (PPSNode child : node.getChildren()) {
            switch(child.getName()) {
                case NODE_UPDATE_INTERVAL:
                    updateParam.setUpdateIntervalInMinutes(parseLong(getPpsNodeValue(child), 10));
                    break;
                case NODE_UPDATE_METHOD:
                    updateParam.setUpdateMethod(getPpsNodeValue(child));
                    break;
                case NODE_RESTRICTION:
                    updateParam.setRestriction(getPpsNodeValue(child));
                    break;
                case NODE_URI:
                    updateParam.setServerUri(getPpsNodeValue(child));
                    break;
                case NODE_USERNAME_PASSWORD:
                    Pair<String, String> usernamePassword = parseUpdateUserCredential(child);
                    updateParam.setUsername(usernamePassword.first);
                    updateParam.setBase64EncodedPassword(usernamePassword.second);
                    break;
                case NODE_TRUST_ROOT:
                    Pair<String, byte[]> trustRoot = parseTrustRoot(child);
                    updateParam.setTrustRootCertUrl(trustRoot.first);
                    updateParam.setTrustRootCertSha256Fingerprint(trustRoot.second);
                    break;
                case NODE_OTHER:
                    Log.d(TAG, "Ignore unsupported paramter: " + child.getName());
                    break;
                default:
                    throw new ParsingException("Unknown node under Update Parameters: "
                            + child.getName());
            }
        }
        return updateParam;
    }

    /**
     * Parse username and password parameters associated with policy or subscription update.
     * This contained configurations under either
     * PerProviderSubscription/Policy/PolicyUpdate/UsernamePassword or
     * PerProviderSubscription/SubscriptionUpdate/UsernamePassword subtree.
     *
     * @param node PPSNode representing the root of the UsernamePassword subtree
     * @return Pair of username and password
     * @throws ParsingException
     */
    private static Pair<String, String> parseUpdateUserCredential(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for UsernamePassword");
        }

        String username = null;
        String password = null;
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_USERNAME:
                    username = getPpsNodeValue(child);
                    break;
                case NODE_PASSWORD:
                    password = getPpsNodeValue(child);
                    break;
                default:
                    throw new ParsingException("Unknown node under UsernamePassword: "
                            + child.getName());
            }
        }
        return Pair.create(username, password);
    }

    /**
     * Parse the trust root parameters associated with policy update, subscription update, or AAA
     * server trust root.
     *
     * This contained configurations under either
     * PerProviderSubscription/Policy/PolicyUpdate/TrustRoot or
     * PerProviderSubscription/SubscriptionUpdate/TrustRoot or
     * PerProviderSubscription/AAAServerTrustRoot/<X+> subtree.
     *
     * @param node PPSNode representing the root of the TrustRoot subtree
     * @return Pair of Certificate URL and fingerprint
     * @throws ParsingException
     */
    private static Pair<String, byte[]> parseTrustRoot(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for TrustRoot");
        }

        String certUrl = null;
        byte[] certFingerprint = null;
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_CERT_URL:
                    certUrl = getPpsNodeValue(child);
                    break;
                case NODE_CERT_SHA256_FINGERPRINT:
                    certFingerprint = parseHexString(getPpsNodeValue(child));
                    break;
                default:
                    throw new ParsingException("Unknown node under TrustRoot: "
                            + child.getName());
            }
        }
        return Pair.create(certUrl, certFingerprint);
    }

    /**
     * Parse configurations under PerProviderSubscription/Policy/SPExclusionList subtree.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/Policy/SPExclusionList subtree
     * @return Array of excluded SSIDs
     * @throws ParsingException
     */
    private static String[] parseSpExclusionList(PPSNode node) throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for SPExclusionList");
        }
        List<String> ssidList = new ArrayList<>();
        for (PPSNode child : node.getChildren()) {
            ssidList.add(parseSpExclusionInstance(child));
        }
        return ssidList.toArray(new String[ssidList.size()]);
    }

    /**
     * Parse configurations under PerProviderSubscription/Policy/SPExclusionList/<X+> subtree.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/Policy/SPExclusionList/<X+> subtree
     * @return String
     * @throws ParsingException
     */
    private static String parseSpExclusionInstance(PPSNode node) throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for SPExclusion instance");
        }
        String ssid = null;
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_SSID:
                    ssid = getPpsNodeValue(child);
                    break;
                default:
                    throw new ParsingException("Unknown node under SPExclusion instance");
            }
        }
        return ssid;
    }

    /**
     * Parse configurations under PerProviderSubscription/Policy/RequiredProtoPortTuple subtree.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/Policy/RequiredProtoPortTuple subtree
     * @return Map of IP Protocol to Port Number tuples
     * @throws ParsingException
     */
    private static Map<Integer, String> parseRequiredProtoPortTuple(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for RequiredProtoPortTuple");
        }
        Map<Integer, String> protoPortTupleMap = new HashMap<>();
        for (PPSNode child : node.getChildren()) {
            Pair<Integer, String> protoPortTuple = parseProtoPortTuple(child);
            protoPortTupleMap.put(protoPortTuple.first, protoPortTuple.second);
        }
        return protoPortTupleMap;
    }

    /**
     * Parse configurations under PerProviderSubscription/Policy/RequiredProtoPortTuple/<X+>
     * subtree.
     *
     * @param node PPSNode representing the root of the
     *             PerProviderSubscription/Policy/RequiredProtoPortTuple/<X+> subtree
     * @return Pair of IP Protocol to Port Number tuple
     * @throws ParsingException
     */
    private static Pair<Integer, String> parseProtoPortTuple(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for RequiredProtoPortTuple "
                    + "instance");
        }
        int proto = Integer.MIN_VALUE;
        String ports = null;
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_IP_PROTOCOL:
                    proto = parseInteger(getPpsNodeValue(child));
                    break;
                case NODE_PORT_NUMBER:
                    ports = getPpsNodeValue(child);
                    break;
                default:
                    throw new ParsingException("Unknown node under RequiredProtoPortTuple instance"
                            + child.getName());
            }
        }
        if (proto == Integer.MIN_VALUE) {
            throw new ParsingException("Missing IPProtocol field");
        }
        if (ports == null) {
            throw new ParsingException("Missing PortNumber field");
        }
        return Pair.create(proto, ports);
    }

    /**
     * Parse configurations under PerProviderSubscription/AAAServerTrustRoot subtree.
     *
     * @param node PPSNode representing the root of PerProviderSubscription/AAAServerTrustRoot
     *             subtree
     * @return Map of certificate URL with the corresponding certificate fingerprint
     * @throws ParsingException
     */
    private static Map<String, byte[]> parseAAAServerTrustRootList(PPSNode node)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for AAAServerTrustRoot");
        }
        Map<String, byte[]> certList = new HashMap<>();
        for (PPSNode child : node.getChildren()) {
            Pair<String, byte[]> certTuple = parseTrustRoot(child);
            certList.put(certTuple.first, certTuple.second);
        }
        return certList;
    }

    /**
     * Parse configurations under PerProviderSubscription/SubscriptionParameter subtree.
     *
     * @param node PPSNode representing the root of PerProviderSubscription/SubscriptionParameter
     *             subtree
     * @param config Instance of {@link PasspointConfiguration}
     * @throws ParsingException
     */
    private static void parseSubscriptionParameter(PPSNode node, PasspointConfiguration config)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for SubscriptionParameter");
        }
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_CREATION_DATE:
                    config.setSubscriptionCreationTimeInMillis(parseDate(getPpsNodeValue(child)));
                    break;
                case NODE_EXPIRATION_DATE:
                    config.setSubscriptionExpirationTimeInMillis(parseDate(getPpsNodeValue(child)));
                    break;
                case NODE_TYPE_OF_SUBSCRIPTION:
                    config.setSubscriptionType(getPpsNodeValue(child));
                    break;
                case NODE_USAGE_LIMITS:
                    parseUsageLimits(child, config);
                    break;
                default:
                    throw new ParsingException("Unknown node under SubscriptionParameter"
                            + child.getName());
            }
        }
    }

    /**
     * Parse configurations under PerProviderSubscription/SubscriptionParameter/UsageLimits
     * subtree.
     *
     * @param node PPSNode representing the root of
     *             PerProviderSubscription/SubscriptionParameter/UsageLimits subtree
     * @param config Instance of {@link PasspointConfiguration}
     * @throws ParsingException
     */
    private static void parseUsageLimits(PPSNode node, PasspointConfiguration config)
            throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for UsageLimits");
        }
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_DATA_LIMIT:
                    config.setUsageLimitDataLimit(parseLong(getPpsNodeValue(child), 10));
                    break;
                case NODE_START_DATE:
                    config.setUsageLimitStartTimeInMillis(parseDate(getPpsNodeValue(child)));
                    break;
                case NODE_TIME_LIMIT:
                    config.setUsageLimitTimeLimitInMinutes(parseLong(getPpsNodeValue(child), 10));
                    break;
                case NODE_USAGE_TIME_PERIOD:
                    config.setUsageLimitUsageTimePeriodInMinutes(
                            parseLong(getPpsNodeValue(child), 10));
                    break;
                default:
                    throw new ParsingException("Unknown node under UsageLimits"
                            + child.getName());
            }
        }
    }

    /**
     * Convert a hex string to a byte array.
     *
     * @param str String containing hex values
     * @return byte[]
     * @throws ParsingException
     */
    private static byte[] parseHexString(String str) throws ParsingException {
        if ((str.length() & 1) == 1) {
            throw new ParsingException("Odd length hex string: " + str.length());
        }

        byte[] result = new byte[str.length() / 2];
        for (int i = 0; i < result.length; i++) {
          int index = i * 2;
          try {
              result[i] = (byte) Integer.parseInt(str.substring(index, index + 2), 16);
          } catch (NumberFormatException e) {
              throw new ParsingException("Invalid hex string: " + str);
          }
        }
        return result;
    }

    /**
     * Convert a date string to the number of milliseconds since January 1, 1970, 00:00:00 GMT.
     *
     * @param dateStr String in the format of yyyy-MM-dd'T'HH:mm:ss'Z'
     * @return number of milliseconds
     * @throws ParsingException
     */
    private static long parseDate(String dateStr) throws ParsingException {
        try {
            DateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            return format.parse(dateStr).getTime();
        } catch (ParseException pe) {
            throw new ParsingException("Badly formatted time: " + dateStr);
        }
    }

    /**
     * Parse an integer string.
     *
     * @param value String of integer value
     * @return int
     * @throws ParsingException
     */
    private static int parseInteger(String value) throws ParsingException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ParsingException("Invalid integer value: " + value);
        }
    }

    /**
     * Parse a string representing a long integer.
     *
     * @param value String of long integer value
     * @return long
     * @throws ParsingException
     */
    private static long parseLong(String value, int radix) throws ParsingException {
        try {
            return Long.parseLong(value, radix);
        } catch (NumberFormatException e) {
            throw new ParsingException("Invalid long integer value: " + value);
        }
    }

    /**
     * Convert a List<Long> to a primitive long array long[].
     *
     * @param list List to be converted
     * @return long[]
     */
    private static long[] convertFromLongList(List<Long> list) {
        Long[] objectArray = list.toArray(new Long[list.size()]);
        long[] primitiveArray = new long[objectArray.length];
        for (int i = 0; i < objectArray.length; i++) {
            primitiveArray[i] = objectArray[i].longValue();
        }
        return primitiveArray;
    }
}
