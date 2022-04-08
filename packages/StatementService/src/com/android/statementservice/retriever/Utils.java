/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.statementservice.retriever;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Utility library for computing certificate fingerprints. Also includes fields name used by
 * Statement JSON string.
 */
public final class Utils {

    private Utils() {}

    /**
     * Field name for namespace.
     */
    public static final String NAMESPACE_FIELD = "namespace";

    /**
     * Supported asset namespaces.
     */
    public static final String NAMESPACE_WEB = "web";
    public static final String NAMESPACE_ANDROID_APP = "android_app";

    /**
     * Field names in a web asset descriptor.
     */
    public static final String WEB_ASSET_FIELD_SITE = "site";

    /**
     * Field names in a Android app asset descriptor.
     */
    public static final String ANDROID_APP_ASSET_FIELD_PACKAGE_NAME = "package_name";
    public static final String ANDROID_APP_ASSET_FIELD_CERT_FPS = "sha256_cert_fingerprints";

    /**
     * Field names in a statement.
     */
    public static final String ASSET_DESCRIPTOR_FIELD_RELATION = "relation";
    public static final String ASSET_DESCRIPTOR_FIELD_TARGET = "target";
    public static final String DELEGATE_FIELD_DELEGATE = "include";

    private static final char[] HEX_DIGITS = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'A', 'B', 'C', 'D', 'E', 'F' };

    /**
     * Joins a list of strings, by placing separator between each string. For example,
     * {@code joinStrings("; ", Arrays.asList(new String[]{"a", "b", "c"}))} returns
     * "{@code a; b; c}".
     */
    public static String joinStrings(String separator, List<String> strings) {
        switch(strings.size()) {
            case 0:
                return "";
            case 1:
                return strings.get(0);
            default:
                StringBuilder joiner = new StringBuilder();
                boolean first = true;
                for (String field : strings) {
                    if (first) {
                        first = false;
                    } else {
                        joiner.append(separator);
                    }
                    joiner.append(field);
                }
                return joiner.toString();
        }
    }

    /**
     * Returns the normalized sha-256 fingerprints of a given package according to the Android
     * package manager.
     */
    public static List<String> getCertFingerprintsFromPackageManager(String packageName,
            Context context) throws NameNotFoundException {
        Signature[] signatures = context.getPackageManager().getPackageInfo(packageName,
                PackageManager.GET_SIGNATURES).signatures;
        ArrayList<String> result = new ArrayList<String>(signatures.length);
        for (Signature sig : signatures) {
            result.add(computeNormalizedSha256Fingerprint(sig.toByteArray()));
        }
        return result;
    }

    /**
     * Computes the hash of the byte array using the specified algorithm, returning a hex string
     * with a colon between each byte.
     */
    public static String computeNormalizedSha256Fingerprint(byte[] signature) {
        MessageDigest digester;
        try {
            digester = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("No SHA-256 implementation found.");
        }
        digester.update(signature);
        return byteArrayToHexString(digester.digest());
    }

    /**
     * Returns true if there is at least one common string between the two lists of string.
     */
    public static boolean hasCommonString(List<String> list1, List<String> list2) {
        HashSet<String> set2 = new HashSet<>(list2);
        for (String string : list1) {
            if (set2.contains(string)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts the byte array to an lowercase hexadecimal digits String with a colon character (:)
     * between each byte.
     */
    private static String byteArrayToHexString(byte[] array) {
        if (array.length == 0) {
          return "";
        }
        char[] buf = new char[array.length * 3 - 1];

        int bufIndex = 0;
        for (int i = 0; i < array.length; i++) {
            byte b = array[i];
            if (i > 0) {
                buf[bufIndex++] = ':';
            }
            buf[bufIndex++] = HEX_DIGITS[(b >>> 4) & 0x0F];
            buf[bufIndex++] = HEX_DIGITS[b & 0x0F];
        }
        return new String(buf);
    }
}
