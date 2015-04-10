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
import android.content.pm.Signature;
import android.os.Environment;
import android.util.Slog;
import android.util.Xml;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

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

    // Data policy override version file.
    private static final String DATA_VERSION_FILE =
            Environment.getDataDirectory() + "/security/current/selinux_version";

    // Base policy version file.
    private static final String BASE_VERSION_FILE = "/selinux_version";

    // Whether override security policies should be loaded.
    private static final boolean USE_OVERRIDE_POLICY = useOverridePolicy();

    // Data override mac_permissions.xml policy file.
    private static final String DATA_MAC_PERMISSIONS =
            Environment.getDataDirectory() + "/security/current/mac_permissions.xml";

    // Base mac_permissions.xml policy file.
    private static final String BASE_MAC_PERMISSIONS =
            Environment.getRootDirectory() + "/etc/security/mac_permissions.xml";

    // Determine which mac_permissions.xml file to use.
    private static final String MAC_PERMISSIONS = USE_OVERRIDE_POLICY ?
            DATA_MAC_PERMISSIONS : BASE_MAC_PERMISSIONS;

    // Data override seapp_contexts policy file.
    private static final String DATA_SEAPP_CONTEXTS =
            Environment.getDataDirectory() + "/security/current/seapp_contexts";

    // Base seapp_contexts policy file.
    private static final String BASE_SEAPP_CONTEXTS = "/seapp_contexts";

    // Determine which seapp_contexts file to use.
    private static final String SEAPP_CONTEXTS = USE_OVERRIDE_POLICY ?
            DATA_SEAPP_CONTEXTS : BASE_SEAPP_CONTEXTS;

    // Stores the hash of the last used seapp_contexts file.
    private static final String SEAPP_HASH_FILE =
            Environment.getDataDirectory().toString() + "/system/seapp_hash";

    /**
     * Load the mac_permissions.xml file containing all seinfo assignments used to
     * label apps. The loaded mac_permissions.xml file is determined by the
     * MAC_PERMISSIONS class variable which is set at class load time which itself
     * is based on the USE_OVERRIDE_POLICY class variable. For further guidance on
     * the proper structure of a mac_permissions.xml file consult the source code
     * located at external/sepolicy/mac_permissions.xml.
     *
     * @return boolean indicating if policy was correctly loaded. A value of false
     *         typically indicates a structural problem with the xml or incorrectly
     *         constructed policy stanzas. A value of true means that all stanzas
     *         were loaded successfully; no partial loading is possible.
     */
    public static boolean readInstallPolicy() {
        // Temp structure to hold the rules while we parse the xml file
        List<Policy> policies = new ArrayList<>();

        FileReader policyFile = null;
        XmlPullParser parser = Xml.newPullParser();
        try {
            policyFile = new FileReader(MAC_PERMISSIONS);
            Slog.d(TAG, "Using policy file " + MAC_PERMISSIONS);

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
                    case "default":
                        policies.add(readDefaultOrThrow(parser));
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
            sb.append(MAC_PERMISSIONS);
            sb.append(":");
            sb.append(ex);
            Slog.w(TAG, sb.toString());
            return false;
        } catch (IOException ioe) {
            Slog.w(TAG, "Exception parsing " + MAC_PERMISSIONS, ioe);
            return false;
        } finally {
            IoUtils.closeQuietly(policyFile);
        }

        // Now sort the policy stanzas
        PolicyComparator policySort = new PolicyComparator();
        Collections.sort(policies, policySort);
        if (policySort.foundDuplicate()) {
            Slog.w(TAG, "ERROR! Duplicate entries found parsing " + MAC_PERMISSIONS);
            return false;
        }

        synchronized (sPolicies) {
            sPolicies = policies;

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
     * Loop over a default element looking for seinfo child tags. A {@link Policy}
     * instance will be created and returned in the process. All other tags encountered
     * will be skipped.
     *
     * @param parser an XmlPullParser object representing a default element.
     * @return the constructed {@link Policy} instance
     * @throws IOException
     * @throws XmlPullParserException
     * @throws IllegalArgumentException if any of the validation checks fail while
     *         parsing tag values.
     * @throws IllegalStateException if any of the invariants fail when constructing
     *         the {@link Policy} instance.
     */
    private static Policy readDefaultOrThrow(XmlPullParser parser) throws IOException,
            XmlPullParserException {

        parser.require(XmlPullParser.START_TAG, null, "default");
        Policy.PolicyBuilder pb = new Policy.PolicyBuilder();
        pb.setAsDefaultPolicy();

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String tagName = parser.getName();
            if ("seinfo".equals(tagName)) {
                String seinfo = parser.getAttributeValue(null, "value");
                pb.setGlobalSeinfoOrThrow(seinfo);
                readSeinfo(parser);
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
     * Applies a security label to a package based on an seinfo tag taken from a matched
     * policy. All signature based policy stanzas are consulted first and, if no match
     * is found, the default policy stanza is then consulted. The security label is
     * attached to the ApplicationInfo instance of the package in the event that a matching
     * policy was found.
     *
     * @param pkg object representing the package to be labeled.
     * @return boolean which determines whether a non null seinfo label was assigned
     *         to the package. A null value simply represents that no policy matched.
     */
    public static boolean assignSeinfoValue(PackageParser.Package pkg) {
        synchronized (sPolicies) {
            for (Policy policy : sPolicies) {
                String seinfo = policy.getMatchedSeinfo(pkg);
                if (seinfo != null) {
                    pkg.applicationInfo.seinfo = seinfo;
                    if (DEBUG_POLICY_INSTALL) {
                        Slog.i(TAG, "package (" + pkg.packageName + ") labeled with " +
                               "seinfo=" + seinfo);
                    }
                    return true;
                }
            }
        }

        if (DEBUG_POLICY_INSTALL) {
            Slog.i(TAG, "package (" + pkg.packageName + ") doesn't match any policy; " +
                   "seinfo will remain null");
        }
        return false;
    }

    /**
     * Determines if a recursive restorecon on /data/data and /data/user is needed.
     * It does this by comparing the SHA-1 of the seapp_contexts file against the
     * stored hash at /data/system/seapp_hash.
     *
     * @return Returns true if the restorecon should occur or false otherwise.
     */
    public static boolean shouldRestorecon() {
        // Any error with the seapp_contexts file should be fatal
        byte[] currentHash = null;
        try {
            currentHash = returnHash(SEAPP_CONTEXTS);
        } catch (IOException ioe) {
            Slog.e(TAG, "Error with hashing seapp_contexts.", ioe);
            return false;
        }

        // Push past any error with the stored hash file
        byte[] storedHash = null;
        try {
            storedHash = IoUtils.readFileAsByteArray(SEAPP_HASH_FILE);
        } catch (IOException ioe) {
            Slog.w(TAG, "Error opening " + SEAPP_HASH_FILE + ". Assuming first boot.");
        }

        return (storedHash == null || !MessageDigest.isEqual(storedHash, currentHash));
    }

    /**
     * Stores the SHA-1 of the seapp_contexts to /data/system/seapp_hash.
     */
    public static void setRestoreconDone() {
        try {
            final byte[] currentHash = returnHash(SEAPP_CONTEXTS);
            dumpHash(new File(SEAPP_HASH_FILE), currentHash);
        } catch (IOException ioe) {
            Slog.e(TAG, "Error with saving hash to " + SEAPP_HASH_FILE, ioe);
        }
    }

    /**
     * Dump the contents of a byte array to a specified file.
     *
     * @param file The file that receives the byte array content.
     * @param content A byte array that will be written to the specified file.
     * @throws IOException if any failed I/O operation occured.
     *         Included is the failure to atomically rename the tmp
     *         file used in the process.
     */
    private static void dumpHash(File file, byte[] content) throws IOException {
        FileOutputStream fos = null;
        File tmp = null;
        try {
            tmp = File.createTempFile("seapp_hash", ".journal", file.getParentFile());
            tmp.setReadable(true);
            fos = new FileOutputStream(tmp);
            fos.write(content);
            fos.getFD().sync();
            if (!tmp.renameTo(file)) {
                throw new IOException("Failure renaming " + file.getCanonicalPath());
            }
        } finally {
            if (tmp != null) {
                tmp.delete();
            }
            IoUtils.closeQuietly(fos);
        }
    }

    /**
     * Return the SHA-1 of a file.
     *
     * @param file The path to the file given as a string.
     * @return Returns the SHA-1 of the file as a byte array.
     * @throws IOException if any failed I/O operations occured.
     */
    private static byte[] returnHash(String file) throws IOException {
        try {
            final byte[] contents = IoUtils.readFileAsByteArray(file);
            return MessageDigest.getInstance("SHA-1").digest(contents);
        } catch (NoSuchAlgorithmException nsae) {
            throw new RuntimeException(nsae);  // impossible
        }
    }

    private static boolean useOverridePolicy() {
        try {
            final String overrideVersion = IoUtils.readFileAsString(DATA_VERSION_FILE);
            final String baseVersion = IoUtils.readFileAsString(BASE_VERSION_FILE);
            if (overrideVersion.equals(baseVersion)) {
                return true;
            }
            Slog.e(TAG, "Override policy version '" + overrideVersion + "' doesn't match " +
                   "base version '" + baseVersion + "'. Skipping override policy files.");
        } catch (FileNotFoundException fnfe) {
            // Override version file doesn't have to exist so silently ignore.
        } catch (IOException ioe) {
            Slog.w(TAG, "Skipping override policy files.", ioe);
        }
        return false;
    }
}

/**
 * Holds valid policy representations of individual stanzas from a mac_permissions.xml
 * file. Each instance can further be used to assign seinfo values to apks using the
 * {@link Policy#getMatchedSeinfo} method. To create an instance of this use the
 * {@link PolicyBuilder} pattern class, where each instance is validated against a set
 * of invariants before being built and returned. Each instance can be guaranteed to
 * hold one valid policy stanza as outlined in the external/sepolicy/mac_permissions.xml
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
 * <p>
 * The following is an example of how to use {@link Policy.PolicyBuilder} to create a
 * default based Policy instance.
 * </p>
 * <pre>
 * {@code
 * Policy policy = new Policy.PolicyBuilder()
 *         .setAsDefaultPolicy()
 *         .setGlobalSeinfoOrThrow("default")
 *         .build();
 * }
 * </pre>
 */
final class Policy {

    private final String mSeinfo;
    private final boolean mDefaultStanza;
    private final Set<Signature> mCerts;
    private final Map<String, String> mPkgMap;

    // Use the PolicyBuilder pattern to instantiate
    private Policy(PolicyBuilder builder) {
        mSeinfo = builder.mSeinfo;
        mDefaultStanza = builder.mDefaultStanza;
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
     * Return whether this policy object represents a default stanza.
     *
     * @return A boolean indicating if this object represents a default policy stanza.
     */
    public boolean isDefaultStanza() {
        return mDefaultStanza;
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
        if (mDefaultStanza) {
            sb.append("defaultStanza=true ");
        }

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
     *   <li> If this Policy instance is defined as a default stanza:
     *       <ul><li>Return the global seinfo value</li></ul>
     *   </li>
     *   <li> If this Policy instance is defined as a signer stanza:
     *     <ul>
     *       <li> All certs used to sign the apk and all certs stored with this policy
     *         instance are tested for set equality. If this fails then null is returned.
     *       </li>
     *       <li> If all certs match then an appropriate inner package stanza is
     *         searched based on package name alone. If matched, the stored seinfo
     *         value for that mapping is returned.
     *       </li>
     *       <li> If all certs matched and no inner package stanza matches then return
     *         the global seinfo value. The returned value can be null in this case.
     *       </li>
     *     </ul>
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
    public String getMatchedSeinfo(PackageParser.Package pkg) {
        if (!mDefaultStanza) {
            // Check for exact signature matches across all certs.
            Signature[] certs = mCerts.toArray(new Signature[0]);
            if (!Signature.areExactMatch(certs, pkg.mSignatures)) {
                return null;
            }

            // Check for inner package name matches given that the
            // signature checks already passed.
            String seinfoValue = mPkgMap.get(pkg.packageName);
            if (seinfoValue != null) {
                return seinfoValue;
            }
        }

        // Return the global seinfo value (even if it's null).
        return mSeinfo;
    }

    /**
     * A nested builder class to create {@link Policy} instances. A {@link Policy}
     * class instance represents one valid policy stanza found in a mac_permissions.xml
     * file. A valid policy stanza is defined to be either a signer or default stanza
     * which obeys the rules outlined in external/sepolicy/mac_permissions.xml. The
     * {@link #build} method ensures a set of invariants are upheld enforcing the correct
     * stanza structure before returning a valid Policy object.
     */
    public static final class PolicyBuilder {

        private String mSeinfo;
        private boolean mDefaultStanza;
        private final Set<Signature> mCerts;
        private final Map<String, String> mPkgMap;

        public PolicyBuilder() {
            mCerts = new HashSet<Signature>(2);
            mPkgMap = new HashMap<String, String>(2);
        }

        /**
         * Sets this stanza as a default stanza. All policy stanzas are assumed to
         * be signer stanzas unless this method is explicitly called. Default stanzas
         * are treated differently with respect to allowable child tags, ordering and
         * when and how policy decisions are enforced.
         *
         * @return The reference to this PolicyBuilder.
         */
        public PolicyBuilder setAsDefaultPolicy() {
            mDefaultStanza = true;
            return this;
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
         * represents the seinfo element that is used in one of two ways depending on
         * its context. When attached to a signer tag the global seinfo represents an
         * assignment when there isn't a further inner package refinement in policy.
         * When used with a default tag, it represents the only allowable assignment
         * value.
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
         * These localized mappings allow the global seinfo to be overriden. This
         * mapping provides no value when used in conjunction with a default stanza;
         * enforced through the {@link #build} method.
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
         *    <ul>
         *      <li> If a default stanza
         *        <ul>
         *          <li> an attached global seinfo tag must be present </li>
         *          <li> no signatures and no package names can be present </li>
         *        </ul>
         *      </li>
         *      <li> If a signer stanza
         *        <ul>
         *           <li> at least one cert must be found </li>
         *           <li> either a global seinfo value is present OR at least one
         *           inner package mapping must be present BUT not both. </li>
         *        </ul>
         *      </li>
         *    </ul>
         *
         * @return an instance of {@link Policy} with the options set from this builder
         * @throws IllegalStateException if an invariant is violated.
         */
        public Policy build() {
            Policy p = new Policy(this);

            if (p.mDefaultStanza) {
                if (p.mSeinfo == null) {
                    String err = "Missing global seinfo tag with default stanza.";
                    throw new IllegalStateException(err);
                }
                if (p.mCerts.size() != 0) {
                    String err = "Certs not allowed with default stanza.";
                    throw new IllegalStateException(err);
                }
                if (!p.mPkgMap.isEmpty()) {
                    String err = "Inner package mappings not allowed with default stanza.";
                    throw new IllegalStateException(err);
                }
            } else {
                if (p.mCerts.size() == 0) {
                    String err = "Missing certs with signer tag. Expecting at least one.";
                    throw new IllegalStateException(err);
                }
                if (!(p.mSeinfo == null ^ p.mPkgMap.isEmpty())) {
                    String err = "Only seinfo tag XOR package tags are allowed within " +
                            "a signer stanza.";
                    throw new IllegalStateException(err);
                }
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
 *   <li> default stanza </li>
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

        // Give precedence to signature stanzas over default stanzas
        if (p1.isDefaultStanza() != p2.isDefaultStanza()) {
            return p1.isDefaultStanza() ? 1 : -1;
        }

        // Give precedence to stanzas with inner package mappings
        if (p1.hasInnerPackages() != p2.hasInnerPackages()) {
            return p1.hasInnerPackages() ? -1 : 1;
        }

        // Check for duplicate entries
        if (p1.getSignatures().equals(p2.getSignatures())) {
            // Checks if default stanza or a signer w/o inner package names
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
