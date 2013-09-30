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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

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
    private static final HashMap<Signature, String> sSigSeinfo =
        new HashMap<Signature, String>();

    // Package name seinfo values read from policy.
    private static final HashMap<String, String> sPackageSeinfo =
        new HashMap<String, String>();

    // Locations of potential install policy files.
    private static final File[] INSTALL_POLICY_FILE = {
        new File(Environment.getDataDirectory(), "security/mac_permissions.xml"),
        new File(Environment.getRootDirectory(), "etc/security/mac_permissions.xml"),
        null};

    private static void flushInstallPolicy() {
        sSigSeinfo.clear();
        sPackageSeinfo.clear();
    }

    /**
     * Parses an MMAC install policy from a predefined list of locations.
     * @param none
     * @return boolean indicating whether an install policy was correctly parsed.
     */
    public static boolean readInstallPolicy() {

        return readInstallPolicy(INSTALL_POLICY_FILE);
    }

    /**
     * Parses an MMAC install policy given as an argument.
     * @param File object representing the path of the policy.
     * @return boolean indicating whether the install policy was correctly parsed.
     */
    public static boolean readInstallPolicy(File policyFile) {

        return readInstallPolicy(new File[]{policyFile,null});
    }

    private static boolean readInstallPolicy(File[] policyFiles) {

        FileReader policyFile = null;
        int i = 0;
        while (policyFile == null && policyFiles != null && policyFiles[i] != null) {
            try {
                policyFile = new FileReader(policyFiles[i]);
                break;
            } catch (FileNotFoundException e) {
                Slog.d(TAG,"Couldn't find install policy " + policyFiles[i].getPath());
            }
            i++;
        }

        if (policyFile == null) {
            Slog.d(TAG, "No policy file found. All seinfo values will be null.");
            return false;
        }

        Slog.d(TAG, "Using install policy file " + policyFiles[i].getPath());

        flushInstallPolicy();

        try {
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
                    String seinfo = readSeinfoTag(parser);
                    if (seinfo != null) {
                        if (DEBUG_POLICY_INSTALL)
                            Slog.i(TAG, "<signer> tag: (" + cert + ") assigned seinfo="
                                   + seinfo);

                        sSigSeinfo.put(signature, seinfo);
                    }
                } else if ("default".equals(tagName)) {
                    String seinfo = readSeinfoTag(parser);
                    if (seinfo != null) {
                        if (DEBUG_POLICY_INSTALL)
                            Slog.i(TAG, "<default> tag assigned seinfo=" + seinfo);

                        // The 'null' signature is the default seinfo value
                        sSigSeinfo.put(null, seinfo);
                    }
                } else if ("package".equals(tagName)) {
                    String pkgName = parser.getAttributeValue(null, "name");
                    if (pkgName == null) {
                        Slog.w(TAG, "<package> without name at "
                               + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    String seinfo = readSeinfoTag(parser);
                    if (seinfo != null) {
                        if (DEBUG_POLICY_INSTALL)
                            Slog.i(TAG, "<package> tag: (" + pkgName +
                                   ") assigned seinfo=" + seinfo);

                        sPackageSeinfo.put(pkgName, seinfo);
                    }
                } else {
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }
            }
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Got execption parsing ", e);
        } catch (IOException e) {
            Slog.w(TAG, "Got execption parsing ", e);
        }
        try {
            policyFile.close();
        } catch (IOException e) {
            //omit
        }
        return true;
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
                String seinfoValue = parser.getAttributeValue(null, "value");
                if (validateValue(seinfoValue)) {
                    seinfo = seinfoValue;
                } else {
                    Slog.w(TAG, "<seinfo> without valid value at "
                           + parser.getPositionDescription());
                }
            }
            XmlUtils.skipCurrentTag(parser);
        }
        return seinfo;
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
     * @param PackageParser.Package object representing the package
     *         to labeled.
     * @return String holding the value of the seinfo label that was assigned.
     *         Value may be null which indicates no seinfo label was assigned.
     */
    public static void assignSeinfoValue(PackageParser.Package pkg) {

        /*
         * Non system installed apps should be treated the same. This
         * means that any post-loaded apk will be assigned the default
         * tag, if one exists in the policy, else null, without respect
         * to the signing key.
         */
        if (((pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) ||
            ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)) {

            // We just want one of the signatures to match.
            for (Signature s : pkg.mSignatures) {
                if (s == null)
                    continue;

                if (sSigSeinfo.containsKey(s)) {
                    String seinfo = pkg.applicationInfo.seinfo = sSigSeinfo.get(s);
                    if (DEBUG_POLICY_INSTALL)
                        Slog.i(TAG, "package (" + pkg.packageName +
                               ") labeled with seinfo=" + seinfo);

                    return;
                }
            }

            // Check for seinfo labeled by package.
            if (sPackageSeinfo.containsKey(pkg.packageName)) {
                String seinfo = pkg.applicationInfo.seinfo = sPackageSeinfo.get(pkg.packageName);
                if (DEBUG_POLICY_INSTALL)
                    Slog.i(TAG, "package (" + pkg.packageName +
                           ") labeled with seinfo=" + seinfo);
                return;
            }
        }

        // If we have a default seinfo value then great, otherwise
        // we set a null object and that is what we started with.
        String seinfo = pkg.applicationInfo.seinfo = sSigSeinfo.get(null);
        if (DEBUG_POLICY_INSTALL)
            Slog.i(TAG, "package (" + pkg.packageName +
                   ") labeled with seinfo=" + (seinfo == null ? "null" : seinfo));
    }
}
