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

package com.android.server.pm;

import android.content.pm.PackageParser;
import android.content.pm.PackageParser.SigningDetails;
import android.content.pm.Signature;
import android.os.Environment;
import android.util.Slog;
import android.util.Xml;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centralized access to SELinux MMAC (middleware MAC) implementation. This
 * class is responsible for loading the appropriate mac_permissions.xml file
 * as well as providing an interface for assigning seinfo values to apks.
 *
 * {@hide}
 */
public final class SELinuxMMAC {

    static final String TAG = "SELinuxMMAC";

    private static final boolean DEBUG_POLICY = false;
    private static final boolean DEBUG_POLICY_INSTALL = DEBUG_POLICY || false;
    private static final boolean DEBUG_POLICY_ORDER = DEBUG_POLICY || false;

    // All policy stanzas read from mac_permissions.xml. This is also the lock
    // to synchronize access during policy load and access attempts.
    private static List<Policy> sPolicies = new ArrayList<>();
    /** Whether or not the policy files have been read */
    private static boolean sPolicyRead;

    /** Required MAC permissions files */
    private static List<File> sMacPermissions = new ArrayList<>();

    private static final String DEFAULT_SEINFO = "default";

    // Append privapp to existing seinfo label
    private static final String PRIVILEGED_APP_STR = ":privapp";

    // Append v2 to existing seinfo label
    private static final String SANDBOX_V2_STR = ":v2";

    // Append targetSdkVersion=n to existing seinfo label where n is the app's targetSdkVersion
    private static final String TARGETSDKVERSION_STR = ":targetSdkVersion=";

    // Only initialize sMacPermissions once.
    static {
        // Platform mac permissions.
        sMacPermissions.add(new File(
            Environment.getRootDirectory(), "/etc/selinux/plat_mac_permissions.xml"));

        // Product mac permissions (optional).
        final File productMacPermission = new File(
                Environment.getProductDirectory(), "/etc/selinux/product_mac_permissions.xml");
        if (productMacPermission.exists()) {
            sMacPermissions.add(productMacPermission);
        }

        // Vendor mac permissions.
        // The filename has been renamed from nonplat_mac_permissions to
        // vendor_mac_permissions. Either of them should exist.
        final File vendorMacPermission = new File(
            Environment.getVendorDirectory(), "/etc/selinux/vendor_mac_permissions.xml");
        if (vendorMacPermission.exists()) {
            sMacPermissions.add(vendorMacPermission);
        } else {
            // For backward compatibility.
            sMacPermissions.add(new File(Environment.getVendorDirectory(),
                                         "/etc/selinux/nonplat_mac_permissions.xml"));
        }

        // ODM mac permissions (optional).
        final File odmMacPermission = new File(
            Environment.getOdmDirectory(), "/etc/selinux/odm_mac_permissions.xml");
        if (odmMacPermission.exists()) {
            sMacPermissions.add(odmMacPermission);
        }
    }

    /**
     * Load the mac_permissions.xml file containing all seinfo assignments used to
     * label apps. The loaded mac_permissions.xml files are plat_mac_permissions.xml and
     * vendor_mac_permissions.xml, on /system and /vendor partitions, respectively.
     * odm_mac_permissions.xml on /odm partition is optional. For further guidance on
     * the proper structure of a mac_permissions.xml file consult the source code
     * located at system/sepolicy/private/mac_permissions.xml.
     *
     * @return boolean indicating if policy was correctly loaded. A value of false
     *         typically indicates a structural problem with the xml or incorrectly
     *         constructed policy stanzas. A value of true means that all stanzas
     *         were loaded successfully; no partial loading is possible.
     */
    public static boolean readInstallPolicy() {
        synchronized (sPolicies) {
            if (sPolicyRead) {
                return true;
            }
        }

        // Temp structure to hold the rules while we parse the xml file
        List<Policy> policies = new ArrayList<>();

        FileReader policyFile = null;
        XmlPullParser parser = Xml.newPullParser();

        final int count = sMacPermissions.size();
        for (int i = 0; i < count; ++i) {
            final File macPermission = sMacPermissions.get(i);
            try {
                policyFile = new FileReader(macPermission);
                Slog.d(TAG, "Using policy file " + macPermission);

                parser.setInput(policyFile);
                parser.nextTag();
                parser.require(XmlPullParser.START_TAG, null, "policy");

                while (parser.next() != XmlPullParser.END_TAG) {
                    if (parser.getEventType() != XmlPullParser.START_TAG) {
                        continue;
                    }

                    switch (parser.getName()) {
                        case "signer":
                            policies.add(readSignerOrThrow(parser));
                            break;
                        default:
                            skip(parser);
                    }
                }
            } catch (IllegalStateException | IllegalArgumentException |
                     XmlPullParserException ex) {
                StringBuilder sb = new StringBuilder("Exception @");
                sb.append(parser.getPositionDescription());
                sb.append(" while parsing ");
                sb.append(macPermission);
                sb.append(":");
                sb.append(ex);
                Slog.w(TAG, sb.toString());
                return false;
            } catch (IOException ioe) {
                Slog.w(TAG, "Exception parsing " + macPermission, ioe);
                return false;
            } finally {
                IoUtils.closeQuietly(policyFile);
            }
        }

        // Now sort the policy stanzas
        PolicyComparator policySort = new PolicyComparator();
        Collections.sort(policies, policySort);
        if (policySort.foundDuplicate()) {
            Slog.w(TAG, "ERROR! Duplicate entries found parsing mac_permissions.xml files");
            return false;
        }

        synchronized (sPolicies) {
            sPolicies.clear();
            sPolicies.addAll(policies);
            sPolicyRead = true;

            if (DEBUG_POLICY_ORDER) {
                for (Policy policy : sPolicies) {
                    Slog.d(TAG, "Policy: " + policy.toString());
                }
            }
        }

        return true;
    }

    /**
     * Loop over a signer tag looking for seinfo, package and cert tags. A {@link Policy}
     * instance will be created and returned in the process. During the pass all other
     * tag elements will be skipped.
     *
     * @param parser an XmlPullParser object representing a signer element.
     * @return the constructed {@link Policy} instance
     * @throws IOException
     * @throws XmlPullParserException
     * @throws IllegalArgumentException if any of the validation checks fail while
     *         parsing tag values.
     * @throws IllegalStateException if any of the invariants fail when constructing
     *         the {@link Policy} instance.
     */
    private static Policy readSignerOrThrow(XmlPullParser parser) throws IOException,
            XmlPullParserException {

        parser.require(XmlPullParser.START_TAG, null, "signer");
        Policy.PolicyBuilder pb = new Policy.PolicyBuilder();

        // Check for a cert attached to the signer tag. We allow a signature
        // to appear as an attribute as well as those attached to cert tags.
        String cert = parser.getAttributeValue(null, "signature");
        if (cert != null) {
            pb.addSignature(cert);
        }

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String tagName = parser.getName();
            if ("seinfo".equals(tagName)) {
                String seinfo = parser.getAttributeValue(null, "value");
                pb.setGlobalSeinfoOrThrow(seinfo);
                readSeinfo(parser);
            } else if ("package".equals(tagName)) {
                readPackageOrThrow(parser, pb);
            } else if ("cert".equals(tagName)) {
                String sig = parser.getAttributeValue(null, "signature");
                pb.addSignature(sig);
                readCert(parser);
            } else {
                skip(parser);
            }
        }

        return pb.build();
    }

    /**
     * Loop over a package element looking for seinfo child tags. If found return the
     * value attribute of the seinfo tag, otherwise return null. All other tags encountered
     * will be skipped.
     *
     * @param parser an XmlPullParser object representing a package element.
     * @param pb a Policy.PolicyBuilder instance to build
     * @throws IOException
     * @throws XmlPullParserException
     * @throws IllegalArgumentException if any of the validation checks fail while
     *         parsing tag values.
     * @throws IllegalStateException if there is a duplicate seinfo tag for the current
     *         package tag.
     */
    private static void readPackageOrThrow(XmlPullParser parser, Policy.PolicyBuilder pb) throws
            IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, "package");
        String pkgName = parser.getAttributeValue(null, "name");

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String tagName = parser.getName();
            if ("seinfo".equals(tagName)) {
                String seinfo = parser.getAttributeValue(null, "value");
                pb.addInnerPackageMapOrThrow(pkgName, seinfo);
                readSeinfo(parser);
            } else {
                skip(parser);
            }
        }
    }

    private static void readCert(XmlPullParser parser) throws IOException,
            XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, "cert");
        parser.nextTag();
    }

    private static void readSeinfo(XmlPullParser parser) throws IOException,
            XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, "seinfo");
        parser.nextTag();
    }

    private static void skip(XmlPullParser p) throws IOException, XmlPullParserException {
        if (p.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (p.next()) {
            case XmlPullParser.END_TAG:
                depth--;
                break;
            case XmlPullParser.START_TAG:
                depth++;
                break;
            }
        }
    }

    /**
     * Selects a security label to a package based on input parameters and the seinfo tag taken
     * from a matched policy. All signature based policy stanzas are consulted and, if no match
     * is found, the default seinfo label of 'default' is used. The security label is attached to
     * the ApplicationInfo instance of the package.
     *
     * @param pkg object representing the package to be labeled.
     * @param isPrivileged boolean.
     * @param targetSandboxVersion int.
     * @param targetSdkVersion int. If this pkg runs as a sharedUser, targetSdkVersion is the
     *        greater of: lowest targetSdk for all pkgs in the sharedUser, or
     *        MINIMUM_TARGETSDKVERSION.
     * @return String representing the resulting seinfo.
     */
    public static String getSeInfo(PackageParser.Package pkg, boolean isPrivileged,
            int targetSandboxVersion, int targetSdkVersion) {
        String seInfo = null;
        synchronized (sPolicies) {
            if (!sPolicyRead) {
                if (DEBUG_POLICY) {
                    Slog.d(TAG, "Policy not read");
                }
            } else {
                for (Policy policy : sPolicies) {
                    seInfo = policy.getMatchedSeInfo(pkg);
                    if (seInfo != null) {
                        break;
                    }
                }
            }
        }

        if (seInfo == null) {
            seInfo = DEFAULT_SEINFO;
        }

        if (targetSandboxVersion == 2) {
            seInfo += SANDBOX_V2_STR;
        }

        if (isPrivileged) {
            seInfo += PRIVILEGED_APP_STR;
        }

        seInfo += TARGETSDKVERSION_STR + targetSdkVersion;

        if (DEBUG_POLICY_INSTALL) {
            Slog.i(TAG, "package (" + pkg.packageName + ") labeled with " +
                    "seinfo=" + seInfo);
        }
        return seInfo;
    }
}

/**
 * Holds valid policy representations of individual stanzas from a mac_permissions.xml
 * file. Each instance can further be used to assign seinfo values to apks using the
 * {@link Policy#getMatchedSeinfo} method. To create an instance of this use the
 * {@link PolicyBuilder} pattern class, where each instance is validated against a set
 * of invariants before being built and returned. Each instance can be guaranteed to
 * hold one valid policy stanza as outlined in the system/sepolicy/mac_permissions.xml
 * file.
 * <p>
 * The following is an example of how to use {@link Policy.PolicyBuilder} to create a
 * signer based Policy instance with only inner package name refinements.
 * </p>
 * <pre>
 * {@code
 * Policy policy = new Policy.PolicyBuilder()
 *         .addSignature("308204a8...")
 *         .addSignature("483538c8...")
 *         .addInnerPackageMapOrThrow("com.foo.", "bar")
 *         .addInnerPackageMapOrThrow("com.foo.other", "bar")
 *         .build();
 * }
 * </pre>
 * <p>
 * The following is an example of how to use {@link Policy.PolicyBuilder} to create a
 * signer based Policy instance with only a global seinfo tag.
 * </p>
 * <pre>
 * {@code
 * Policy policy = new Policy.PolicyBuilder()
 *         .addSignature("308204a8...")
 *         .addSignature("483538c8...")
 *         .setGlobalSeinfoOrThrow("paltform")
 *         .build();
 * }
 * </pre>
 */
final class Policy {

    private final String mSeinfo;
    private final Set<Signature> mCerts;
    private final Map<String, String> mPkgMap;

    // Use the PolicyBuilder pattern to instantiate
    private Policy(PolicyBuilder builder) {
        mSeinfo = builder.mSeinfo;
        mCerts = Collections.unmodifiableSet(builder.mCerts);
        mPkgMap = Collections.unmodifiableMap(builder.mPkgMap);
    }

    /**
     * Return all the certs stored with this policy stanza.
     *
     * @return A set of Signature objects representing all the certs stored
     *         with the policy.
     */
    public Set<Signature> getSignatures() {
        return mCerts;
    }

    /**
     * Return whether this policy object contains package name mapping refinements.
     *
     * @return A boolean indicating if this object has inner package name mappings.
     */
    public boolean hasInnerPackages() {
        return !mPkgMap.isEmpty();
    }

    /**
     * Return the mapping of all package name refinements.
     *
     * @return A Map object whose keys are the package names and whose values are
     *         the seinfo assignments.
     */
    public Map<String, String> getInnerPackages() {
        return mPkgMap;
    }

    /**
     * Return whether the policy object has a global seinfo tag attached.
     *
     * @return A boolean indicating if this stanza has a global seinfo tag.
     */
    public boolean hasGlobalSeinfo() {
        return mSeinfo != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Signature cert : mCerts) {
            sb.append("cert=" + cert.toCharsString().substring(0, 11) + "... ");
        }

        if (mSeinfo != null) {
            sb.append("seinfo=" + mSeinfo);
        }

        for (String name : mPkgMap.keySet()) {
            sb.append(" " + name + "=" + mPkgMap.get(name));
        }

        return sb.toString();
    }

    /**
     * <p>
     * Determine the seinfo value to assign to an apk. The appropriate seinfo value
     * is determined using the following steps:
     * </p>
     * <ul>
     *   <li> All certs used to sign the apk and all certs stored with this policy
     *     instance are tested for set equality. If this fails then null is returned.
     *   </li>
     *   <li> If all certs match then an appropriate inner package stanza is
     *     searched based on package name alone. If matched, the stored seinfo
     *     value for that mapping is returned.
     *   </li>
     *   <li> If all certs matched and no inner package stanza matches then return
     *     the global seinfo value. The returned value can be null in this case.
     *   </li>
     * </ul>
     * <p>
     * In all cases, a return value of null should be interpreted as the apk failing
     * to match this Policy instance; i.e. failing this policy stanza.
     * </p>
     * @param pkg the apk to check given as a PackageParser.Package object
     * @return A string representing the seinfo matched during policy lookup.
     *         A value of null can also be returned if no match occured.
     */
    public String getMatchedSeInfo(PackageParser.Package pkg) {
        // Check for exact signature matches across all certs.
        Signature[] certs = mCerts.toArray(new Signature[0]);
        if (pkg.mSigningDetails != SigningDetails.UNKNOWN
                && !Signature.areExactMatch(certs, pkg.mSigningDetails.signatures)) {

            // certs aren't exact match, but the package may have rotated from the known system cert
            if (certs.length > 1 || !pkg.mSigningDetails.hasCertificate(certs[0])) {
                return null;
            }
        }

        // Check for inner package name matches given that the
        // signature checks already passed.
        String seinfoValue = mPkgMap.get(pkg.packageName);
        if (seinfoValue != null) {
            return seinfoValue;
        }

        // Return the global seinfo value.
        return mSeinfo;
    }

    /**
     * A nested builder class to create {@link Policy} instances. A {@link Policy}
     * class instance represents one valid policy stanza found in a mac_permissions.xml
     * file. A valid policy stanza is defined to be a signer stanza which obeys the rules
     * outlined in system/sepolicy/mac_permissions.xml. The {@link #build} method
     * ensures a set of invariants are upheld enforcing the correct stanza structure
     * before returning a valid Policy object.
     */
    public static final class PolicyBuilder {

        private String mSeinfo;
        private final Set<Signature> mCerts;
        private final Map<String, String> mPkgMap;

        public PolicyBuilder() {
            mCerts = new HashSet<Signature>(2);
            mPkgMap = new HashMap<String, String>(2);
        }

        /**
         * Adds a signature to the set of certs used for validation checks. The purpose
         * being that all contained certs will need to be matched against all certs
         * contained with an apk.
         *
         * @param cert the signature to add given as a String.
         * @return The reference to this PolicyBuilder.
         * @throws IllegalArgumentException if the cert value fails validation;
         *         null or is an invalid hex-encoded ASCII string.
         */
        public PolicyBuilder addSignature(String cert) {
            if (cert == null) {
                String err = "Invalid signature value " + cert;
                throw new IllegalArgumentException(err);
            }

            mCerts.add(new Signature(cert));
            return this;
        }

        /**
         * Set the global seinfo tag for this policy stanza. The global seinfo tag
         * when attached to a signer tag represents the assignment when there isn't a
         * further inner package refinement in policy.
         *
         * @param seinfo the seinfo value given as a String.
         * @return The reference to this PolicyBuilder.
         * @throws IllegalArgumentException if the seinfo value fails validation;
         *         null, zero length or contains non-valid characters [^a-zA-Z_\._0-9].
         * @throws IllegalStateException if an seinfo value has already been found
         */
        public PolicyBuilder setGlobalSeinfoOrThrow(String seinfo) {
            if (!validateValue(seinfo)) {
                String err = "Invalid seinfo value " + seinfo;
                throw new IllegalArgumentException(err);
            }

            if (mSeinfo != null && !mSeinfo.equals(seinfo)) {
                String err = "Duplicate seinfo tag found";
                throw new IllegalStateException(err);
            }

            mSeinfo = seinfo;
            return this;
        }

        /**
         * Create a package name to seinfo value mapping. Each mapping represents
         * the seinfo value that will be assigned to the described package name.
         * These localized mappings allow the global seinfo to be overriden.
         *
         * @param pkgName the android package name given to the app
         * @param seinfo the seinfo value that will be assigned to the passed pkgName
         * @return The reference to this PolicyBuilder.
         * @throws IllegalArgumentException if the seinfo value fails validation;
         *         null, zero length or contains non-valid characters [^a-zA-Z_\.0-9].
         *         Or, if the package name isn't a valid android package name.
         * @throws IllegalStateException if trying to reset a package mapping with a
         *         different seinfo value.
         */
        public PolicyBuilder addInnerPackageMapOrThrow(String pkgName, String seinfo) {
            if (!validateValue(pkgName)) {
                String err = "Invalid package name " + pkgName;
                throw new IllegalArgumentException(err);
            }
            if (!validateValue(seinfo)) {
                String err = "Invalid seinfo value " + seinfo;
                throw new IllegalArgumentException(err);
            }

            String pkgValue = mPkgMap.get(pkgName);
            if (pkgValue != null && !pkgValue.equals(seinfo)) {
                String err = "Conflicting seinfo value found";
                throw new IllegalStateException(err);
            }

            mPkgMap.put(pkgName, seinfo);
            return this;
        }

        /**
         * General validation routine for the attribute strings of an element. Checks
         * if the string is non-null, positive length and only contains [a-zA-Z_\.0-9].
         *
         * @param name the string to validate.
         * @return boolean indicating if the string was valid.
         */
        private boolean validateValue(String name) {
            if (name == null)
                return false;

            // Want to match on [0-9a-zA-Z_.]
            if (!name.matches("\\A[\\.\\w]+\\z")) {
                return false;
            }

            return true;
        }

        /**
         * <p>
         * Create a {@link Policy} instance based on the current configuration. This
         * method checks for certain policy invariants used to enforce certain guarantees
         * about the expected structure of a policy stanza.
         * Those invariants are:
         * </p>
         * <ul>
         *   <li> at least one cert must be found </li>
         *   <li> either a global seinfo value is present OR at least one
         *     inner package mapping must be present BUT not both. </li>
         * </ul>
         * @return an instance of {@link Policy} with the options set from this builder
         * @throws IllegalStateException if an invariant is violated.
         */
        public Policy build() {
            Policy p = new Policy(this);

            if (p.mCerts.isEmpty()) {
                String err = "Missing certs with signer tag. Expecting at least one.";
                throw new IllegalStateException(err);
            }
            if (!(p.mSeinfo == null ^ p.mPkgMap.isEmpty())) {
                String err = "Only seinfo tag XOR package tags are allowed within " +
                        "a signer stanza.";
                throw new IllegalStateException(err);
            }

            return p;
        }
    }
}

/**
 * Comparision imposing an ordering on Policy objects. It is understood that Policy
 * objects can only take one of three forms and ordered according to the following
 * set of rules most specific to least.
 * <ul>
 *   <li> signer stanzas with inner package mappings </li>
 *   <li> signer stanzas with global seinfo tags </li>
 * </ul>
 * This comparison also checks for duplicate entries on the input selectors. Any
 * found duplicates will be flagged and can be checked with {@link #foundDuplicate}.
 */

final class PolicyComparator implements Comparator<Policy> {

    private boolean duplicateFound = false;

    public boolean foundDuplicate() {
        return duplicateFound;
    }

    @Override
    public int compare(Policy p1, Policy p2) {

        // Give precedence to stanzas with inner package mappings
        if (p1.hasInnerPackages() != p2.hasInnerPackages()) {
            return p1.hasInnerPackages() ? -1 : 1;
        }

        // Check for duplicate entries
        if (p1.getSignatures().equals(p2.getSignatures())) {
            // Checks if signer w/o inner package names
            if (p1.hasGlobalSeinfo()) {
                duplicateFound = true;
                Slog.e(SELinuxMMAC.TAG, "Duplicate policy entry: " + p1.toString());
            }

            // Look for common inner package name mappings
            final Map<String, String> p1Packages = p1.getInnerPackages();
            final Map<String, String> p2Packages = p2.getInnerPackages();
            if (!Collections.disjoint(p1Packages.keySet(), p2Packages.keySet())) {
                duplicateFound = true;
                Slog.e(SELinuxMMAC.TAG, "Duplicate policy entry: " + p1.toString());
            }
        }

        return 0;
    }
}
