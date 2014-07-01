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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.content.pm.Signature;
import android.os.Environment;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.HashMap;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Centralized access to SELinux MMAC (middleware MAC) implementation.
 * {@hide}
 */
public final class SELinuxMMAC {

    private static final String TAG = "SELinuxMMAC";

    private static final boolean DEBUG_POLICY = false;
    private static final boolean DEBUG_POLICY_INSTALL = DEBUG_POLICY || false;

    // Signature seinfo values read from policy.
    private static HashMap<Signature, Policy> sSigSeinfo = new HashMap<Signature, Policy>();

    // Default seinfo read from policy.
    private static String sDefaultSeinfo = null;

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


    // Signature policy stanzas
    static class Policy {
        private String seinfo;
        private final HashMap<String, String> pkgMap;

        Policy() {
            seinfo = null;
            pkgMap = new HashMap<String, String>();
        }

        void putSeinfo(String seinfoValue) {
            seinfo = seinfoValue;
        }

        void putPkg(String pkg, String seinfoValue) {
            pkgMap.put(pkg, seinfoValue);
        }

        // Valid policy stanza means there exists a global
        // seinfo value or at least one package policy.
        boolean isValid() {
            return (seinfo != null) || (!pkgMap.isEmpty());
        }

        String checkPolicy(String pkgName) {
            // Check for package name seinfo value first.
            String seinfoValue = pkgMap.get(pkgName);
            if (seinfoValue != null) {
                return seinfoValue;
            }

            // Return the global seinfo value.
            return seinfo;
        }
    }

    private static void flushInstallPolicy() {
        sSigSeinfo.clear();
        sDefaultSeinfo = null;
    }

    public static boolean readInstallPolicy() {
        // Temp structures to hold the rules while we parse the xml file.
        // We add all the rules together once we know there's no structural problems.
        HashMap<Signature, Policy> sigSeinfo = new HashMap<Signature, Policy>();
        String defaultSeinfo = null;

        FileReader policyFile = null;
        try {
            policyFile = new FileReader(MAC_PERMISSIONS);
            Slog.d(TAG, "Using policy file " + MAC_PERMISSIONS);

            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(policyFile);

            XmlUtils.beginDocument(parser, "policy");
            while (true) {
                XmlUtils.nextElement(parser);
                if (parser.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                String tagName = parser.getName();
                if ("signer".equals(tagName)) {
                    String cert = parser.getAttributeValue(null, "signature");
                    if (cert == null) {
                        Slog.w(TAG, "<signer> without signature at "
                               + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    Signature signature;
                    try {
                        signature = new Signature(cert);
                    } catch (IllegalArgumentException e) {
                        Slog.w(TAG, "<signer> with bad signature at "
                               + parser.getPositionDescription(), e);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    Policy policy = readPolicyTags(parser);
                    if (policy.isValid()) {
                        sigSeinfo.put(signature, policy);
                    }
                } else if ("default".equals(tagName)) {
                    // Value is null if default tag is absent or seinfo tag is malformed.
                    defaultSeinfo = readSeinfoTag(parser);
                    if (DEBUG_POLICY_INSTALL)
                        Slog.i(TAG, "<default> tag assigned seinfo=" + defaultSeinfo);

                } else {
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        } catch (XmlPullParserException xpe) {
            Slog.w(TAG, "Got exception parsing " + MAC_PERMISSIONS, xpe);
            return false;
        } catch (IOException ioe) {
            Slog.w(TAG, "Got exception parsing " + MAC_PERMISSIONS, ioe);
            return false;
        } finally {
            IoUtils.closeQuietly(policyFile);
        }

        flushInstallPolicy();
        sSigSeinfo = sigSeinfo;
        sDefaultSeinfo = defaultSeinfo;

        return true;
    }

    private static Policy readPolicyTags(XmlPullParser parser) throws
            IOException, XmlPullParserException {

        int type;
        int outerDepth = parser.getDepth();
        Policy policy = new Policy();
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                   || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if ("seinfo".equals(tagName)) {
                String seinfo = parseSeinfo(parser);
                if (seinfo != null) {
                    policy.putSeinfo(seinfo);
                }
                XmlUtils.skipCurrentTag(parser);
            } else if ("package".equals(tagName)) {
                String pkg = parser.getAttributeValue(null, "name");
                if (!validatePackageName(pkg)) {
                    Slog.w(TAG, "<package> without valid name at "
                           + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }

                String seinfo = readSeinfoTag(parser);
                if (seinfo != null) {
                    policy.putPkg(pkg, seinfo);
                }
            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }
        return policy;
    }

    private static String readSeinfoTag(XmlPullParser parser) throws
            IOException, XmlPullParserException {

        int type;
        int outerDepth = parser.getDepth();
        String seinfo = null;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                   || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if ("seinfo".equals(tagName)) {
                seinfo = parseSeinfo(parser);
            }
            XmlUtils.skipCurrentTag(parser);
        }
        return seinfo;
    }

    private static String parseSeinfo(XmlPullParser parser) {

        String seinfoValue = parser.getAttributeValue(null, "value");
        if (!validateValue(seinfoValue)) {
            Slog.w(TAG, "<seinfo> without valid value at "
                   + parser.getPositionDescription());
            seinfoValue = null;
        }
        return seinfoValue;
    }

    /**
     * General validation routine for package names.
     * Returns a boolean indicating if the passed string
     * is a valid android package name.
     */
    private static boolean validatePackageName(String name) {
        if (name == null)
            return false;

        final int N = name.length();
        boolean hasSep = false;
        boolean front = true;
        for (int i=0; i<N; i++) {
            final char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                front = false;
                continue;
            }
            if (!front) {
                if ((c >= '0' && c <= '9') || c == '_') {
                    continue;
                }
            }
            if (c == '.') {
                hasSep = true;
                front = true;
                continue;
            }
            return false;
        }
        return hasSep;
    }

    /**
     * General validation routine for tag values.
     * Returns a boolean indicating if the passed string
     * contains only letters or underscores.
     */
    private static boolean validateValue(String name) {
        if (name == null)
            return false;

        final int N = name.length();
        if (N == 0)
            return false;

        for (int i = 0; i < N; i++) {
            final char c = name.charAt(i);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c != '_')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Labels a package based on an seinfo tag from install policy.
     * The label is attached to the ApplicationInfo instance of the package.
     * @param pkg object representing the package to be labeled.
     * @return boolean which determines whether a non null seinfo label
     *         was assigned to the package. A null value simply meaning that
     *         no policy matched.
     */
    public static boolean assignSeinfoValue(PackageParser.Package pkg) {

        // We just want one of the signatures to match.
        for (Signature s : pkg.mSignatures) {
            if (s == null)
                continue;

            Policy policy = sSigSeinfo.get(s);
            if (policy != null) {
                String seinfo = policy.checkPolicy(pkg.packageName);
                if (seinfo != null) {
                    pkg.applicationInfo.seinfo = seinfo;
                    if (DEBUG_POLICY_INSTALL)
                        Slog.i(TAG, "package (" + pkg.packageName +
                               ") labeled with seinfo=" + seinfo);

                    return true;
                }
            }
        }

        // If we have a default seinfo value then great, otherwise
        // we set a null object and that is what we started with.
        pkg.applicationInfo.seinfo = sDefaultSeinfo;
        if (DEBUG_POLICY_INSTALL)
            Slog.i(TAG, "package (" + pkg.packageName + ") labeled with seinfo="
                   + (sDefaultSeinfo == null ? "null" : sDefaultSeinfo));

        return (sDefaultSeinfo != null);
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
