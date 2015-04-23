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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Immutable value type that names an Android app asset.
 *
 * <p>An Android app can be named by its package name and certificate fingerprints using this JSON
 * string: { "namespace": "android_app", "package_name": "[Java package name]",
 * "sha256_cert_fingerprints": ["[SHA256 fingerprint of signing cert]", "[additional cert]", ...] }
 *
 * <p>For example, { "namespace": "android_app", "package_name": "com.test.mytestapp",
 * "sha256_cert_fingerprints": ["24:D9:B4:57:A6:42:FB:E6:E5:B8:D6:9E:7B:2D:C2:D1:CB:D1:77:17:1D:7F:D4:A9:16:10:11:AB:92:B9:8F:3F"]
 * }
 *
 * <p>Given a signed APK, Java 7's commandline keytool can compute the fingerprint using:
 * {@code keytool -list -printcert -jarfile signed_app.apk}
 *
 * <p>Each entry in "sha256_cert_fingerprints" is a colon-separated hex string (e.g. 14:6D:E9:...)
 * representing the certificate SHA-256 fingerprint.
 */
/* package private */ final class AndroidAppAsset extends AbstractAsset {

    private static final String MISSING_FIELD_FORMAT_STRING = "Expected %s to be set.";
    private static final String MISSING_APPCERTS_FORMAT_STRING =
            "Expected %s to be non-empty array.";
    private static final String APPCERT_NOT_STRING_FORMAT_STRING = "Expected all %s to be strings.";

    private final List<String> mCertFingerprints;
    private final String mPackageName;

    public List<String> getCertFingerprints() {
        return Collections.unmodifiableList(mCertFingerprints);
    }

    public String getPackageName() {
        return mPackageName;
    }

    @Override
    public String toJson() {
        AssetJsonWriter writer = new AssetJsonWriter();

        writer.writeFieldLower(Utils.NAMESPACE_FIELD, Utils.NAMESPACE_ANDROID_APP);
        writer.writeFieldLower(Utils.ANDROID_APP_ASSET_FIELD_PACKAGE_NAME, mPackageName);
        writer.writeArrayUpper(Utils.ANDROID_APP_ASSET_FIELD_CERT_FPS, mCertFingerprints);

        return writer.closeAndGetString();
    }

    @Override
    public String toString() {
        StringBuilder asset = new StringBuilder();
        asset.append("AndroidAppAsset: ");
        asset.append(toJson());
        return asset.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AndroidAppAsset)) {
            return false;
        }

        return ((AndroidAppAsset) o).toJson().equals(toJson());
    }

    @Override
    public int hashCode() {
        return toJson().hashCode();
    }

    @Override
    public int lookupKey() {
        return getPackageName().hashCode();
    }

    @Override
    public boolean followInsecureInclude() {
        // Non-HTTPS includes are not allowed in Android App assets.
        return false;
    }

    /**
     * Checks that the input is a valid Android app asset.
     *
     * @param asset a JSONObject that has "namespace", "package_name", and
     *              "sha256_cert_fingerprints" fields.
     * @throws AssociationServiceException if the asset is not well formatted.
     */
    public static AndroidAppAsset create(JSONObject asset)
            throws AssociationServiceException {
        String packageName = asset.optString(Utils.ANDROID_APP_ASSET_FIELD_PACKAGE_NAME);
        if (packageName.equals("")) {
            throw new AssociationServiceException(String.format(MISSING_FIELD_FORMAT_STRING,
                    Utils.ANDROID_APP_ASSET_FIELD_PACKAGE_NAME));
        }

        JSONArray certArray = asset.optJSONArray(Utils.ANDROID_APP_ASSET_FIELD_CERT_FPS);
        if (certArray == null || certArray.length() == 0) {
            throw new AssociationServiceException(
                    String.format(MISSING_APPCERTS_FORMAT_STRING,
                            Utils.ANDROID_APP_ASSET_FIELD_CERT_FPS));
        }
        List<String> certFingerprints = new ArrayList<>(certArray.length());
        for (int i = 0; i < certArray.length(); i++) {
            try {
                certFingerprints.add(certArray.getString(i));
            } catch (JSONException e) {
                throw new AssociationServiceException(
                        String.format(APPCERT_NOT_STRING_FORMAT_STRING,
                                Utils.ANDROID_APP_ASSET_FIELD_CERT_FPS));
            }
        }

        return new AndroidAppAsset(packageName, certFingerprints);
    }

    /**
     * Creates a new AndroidAppAsset.
     *
     * @param packageName the package name of the Android app.
     * @param certFingerprints at least one of the Android app signing certificate sha-256
     *                         fingerprint.
     */
    public static AndroidAppAsset create(String packageName, List<String> certFingerprints) {
        if (packageName == null || packageName.equals("")) {
            throw new AssertionError("Expected packageName to be set.");
        }
        if (certFingerprints == null || certFingerprints.size() == 0) {
            throw new AssertionError("Expected certFingerprints to be set.");
        }
        List<String> lowerFps = new ArrayList<String>(certFingerprints.size());
        for (String fp : certFingerprints) {
            lowerFps.add(fp.toUpperCase(Locale.US));
        }
        return new AndroidAppAsset(packageName, lowerFps);
    }

    private AndroidAppAsset(String packageName, List<String> certFingerprints) {
        if (packageName.equals("")) {
            mPackageName = null;
        } else {
            mPackageName = packageName;
        }

        if (certFingerprints == null || certFingerprints.size() == 0) {
            mCertFingerprints = null;
        } else {
            mCertFingerprints = Collections.unmodifiableList(sortAndDeDuplicate(certFingerprints));
        }
    }

    /**
     * Returns an ASCII-sorted copy of the list of certs with all duplicates removed.
     */
    private List<String> sortAndDeDuplicate(List<String> certs) {
        if (certs.size() <= 1) {
            return certs;
        }
        HashSet<String> set = new HashSet<>(certs);
        List<String> result = new ArrayList<>(set);
        Collections.sort(result);
        return result;
    }

}
