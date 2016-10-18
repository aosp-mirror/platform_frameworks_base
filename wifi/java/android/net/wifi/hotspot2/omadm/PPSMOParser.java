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
import android.net.wifi.hotspot2.pps.HomeSP;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 *
 * @hide
 */
public final class PPSMOParser {
    private static final String TAG = "PPSMOParser";

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
     * Fields under HomeSP subtree.
     */
    private static final String NODE_HOMESP = "HomeSP";
    private static final String NODE_FQDN = "FQDN";
    private static final String NODE_FRIENDLY_NAME = "FriendlyName";
    private static final String NODE_ROAMING_CONSORTIUM_OI = "RoamingConsortiumOI";

    /**
     * Fields under Credential subtree.
     */
    private static final String NODE_CREDENTIAL = "Credential";
    private static final String NODE_USERNAME_PASSWORD = "UsernamePassword";
    private static final String NODE_USERNAME = "Username";
    private static final String NODE_PASSWORD = "Password";
    private static final String NODE_EAP_METHOD = "EAPMethod";
    private static final String NODE_EAP_TYPE = "EAPType";
    private static final String NODE_INNER_METHOD = "InnerMethod";
    private static final String NODE_DIGITAL_CERTIFICATE = "DigitalCertificate";
    private static final String NODE_CERTIFICATE_TYPE = "CertificateType";
    private static final String NODE_CERT_SHA256_FINGERPRINT = "CertSHA256FingerPrint";
    private static final String NODE_REALM = "Realm";
    private static final String NODE_SIM = "SIM";
    private static final String NODE_SIM_IMSI = "IMSI";

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
     * Convert a XML string representation of a PPS MO (PerProviderSubscription
     * Management Object) tree to a {@link PasspointConfiguration} object.
     *
     * @param xmlString XML string representation of a PPS MO tree
     * @return {@link PasspointConfiguration} or null
     */
    public static PasspointConfiguration parseMOText(String xmlString) {
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
        for (XMLNode child : node.getChildren()) {
            switch (child.getTag()) {
                case TAG_NODE_NAME:
                    if (nodeName != null) {
                        throw new ParsingException("Duplicant NodeName: " + child.getText());
                    }
                    nodeName = child.getText();
                    if (!TextUtils.equals(nodeName, NODE_PER_PROVIDER_SUBSCRIPTION)) {
                        throw new ParsingException("Unexpected NodeName: " + nodeName);
                    }
                    break;
                case TAG_NODE:
                    // Only one PerProviderSubscription instance is expected and allowed.
                    if (config != null) {
                        throw new ParsingException("Multiple PPS instance");
                    }
                    // Convert the XML tree to a PPS tree.
                    PPSNode ppsInstanceRoot = buildPpsNode(child);
                    config = parsePpsInstance(ppsInstanceRoot);
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
                    config.homeSp = parseHomeSP(child);
                    break;
                case NODE_CREDENTIAL:
                    config.credential = parseCredential(child);
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
    private static HomeSP parseHomeSP(PPSNode node) throws ParsingException {
        if (node.isLeaf()) {
            throw new ParsingException("Leaf node not expected for HomeSP");
        }

        HomeSP homeSp = new HomeSP();
        for (PPSNode child : node.getChildren()) {
            switch (child.getName()) {
                case NODE_FQDN:
                    homeSp.fqdn = getPpsNodeValue(child);
                    break;
                case NODE_FRIENDLY_NAME:
                    homeSp.friendlyName = getPpsNodeValue(child);
                    break;
                case NODE_ROAMING_CONSORTIUM_OI:
                    homeSp.roamingConsortiumOIs =
                            parseRoamingConsortiumOI(getPpsNodeValue(child));
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
            try {
                oiArray[i] = Long.parseLong(oiStrArray[i], 16);
            } catch (NumberFormatException e) {
                throw new ParsingException("Invalid OI: " + oiStrArray[i]);
            }
        }
        return oiArray;
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
                case NODE_USERNAME_PASSWORD:
                    credential.userCredential = parseUserCredential(child);
                    break;
                case NODE_DIGITAL_CERTIFICATE:
                    credential.certCredential = parseCertificateCredential(child);
                    break;
                case NODE_REALM:
                    credential.realm = getPpsNodeValue(child);
                    break;
                case NODE_SIM:
                    credential.simCredential = parseSimCredential(child);
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
                    userCred.username = getPpsNodeValue(child);
                    break;
                case NODE_PASSWORD:
                    userCred.password = getPpsNodeValue(child);
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
                    userCred.eapType = parseInteger(getPpsNodeValue(child));
                    break;
                case NODE_INNER_METHOD:
                    userCred.nonEapInnerMethod = getPpsNodeValue(child);
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
                    certCred.certType = getPpsNodeValue(child);
                    break;
                case NODE_CERT_SHA256_FINGERPRINT:
                    certCred.certSha256FingerPrint = parseHexString(getPpsNodeValue(child));
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
                    simCred.imsi = getPpsNodeValue(child);
                    break;
                case NODE_EAP_TYPE:
                    simCred.eapType = parseInteger(getPpsNodeValue(child));
                    break;
                default:
                    throw new ParsingException("Unknown node under SIM: " + child.getName());
            }
        }
        return simCred;
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
}
