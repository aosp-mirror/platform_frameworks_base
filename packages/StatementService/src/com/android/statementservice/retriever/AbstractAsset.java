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

import android.util.JsonReader;

import org.json.JSONException;

import java.io.IOException;
import java.io.StringReader;

/**
 * A handle representing the identity and address of some digital asset. An asset is an online
 * entity that typically provides some service or content. Examples of assets are websites, Android
 * apps, Twitter feeds, and Plus Pages.
 *
 * <p> Asset can be represented by a JSON string. For example, the web site https://www.google.com
 * can be represented by
 * <pre>
 * {"namespace": "web", "site": "https://www.google.com"}
 * </pre>
 *
 * <p> The Android app with package name com.google.test that is signed by a certificate with sha256
 * fingerprint 11:22:33 can be represented by
 * <pre>
 * {"namespace": "android_app",
 *  "package_name": "com.google.test",
 *  "sha256_cert_fingerprints": ["11:22:33"]}
 * </pre>
 *
 * <p>Given a signed APK, Java 7's commandline keytool can compute the fingerprint using:
 * {@code keytool -list -printcert -jarfile signed_app.apk}
 */
public abstract class AbstractAsset {

    /**
     * Returns a JSON string representation of this asset. The strings returned by this function are
     * normalized -- they can be used for equality testing.
     */
    public abstract String toJson();

    /**
     * Returns a key that can be used by {@link AbstractAssetMatcher} to lookup the asset.
     *
     * <p> An asset will match an {@code AssetMatcher} only if the value of this method is equal to
     * {@code AssetMatcher.getMatchedLookupKey()}.
     */
    public abstract int lookupKey();

    /**
     * Creates a new Asset from its JSON string representation.
     *
     * @throws AssociationServiceException if the assetJson is not well formatted.
     */
    public static AbstractAsset create(String assetJson)
            throws AssociationServiceException {
        JsonReader reader = new JsonReader(new StringReader(assetJson));
        reader.setLenient(false);
        try {
            return AssetFactory.create(JsonParser.parse(reader));
        } catch (JSONException | IOException e) {
            throw new AssociationServiceException(
                    "Input is not a well formatted asset descriptor.", e);
        }
    }

    /**
     * If this is the source asset of a statement file, should the retriever follow
     * any insecure (non-HTTPS) include statements made by the asset.
     */
    public abstract boolean followInsecureInclude();
}
