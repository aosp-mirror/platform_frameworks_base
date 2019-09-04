/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore.serialization;

import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.CERTIFICATE_FACTORY_TYPE;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.NAMESPACE;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.OUTPUT_ENCODING;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_ALGORITHM;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_ALIAS;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_APPLICATION_KEY;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_APPLICATION_KEYS;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_BACKEND_PUBLIC_KEY;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_COUNTER_ID;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_KEY_CHAIN_PROTECTION_PARAMS;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_KEY_CHAIN_PROTECTION_PARAMS_LIST;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_KEY_CHAIN_SNAPSHOT;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_KEY_DERIVATION_PARAMS;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_KEY_MATERIAL;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_KEY_METADATA;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_LOCK_SCREEN_UI_TYPE;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_MAX_ATTEMPTS;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_MEMORY_DIFFICULTY;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_RECOVERY_KEY_MATERIAL;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_SALT;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_SERVER_PARAMS;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_SNAPSHOT_VERSION;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_TRUSTED_HARDWARE_CERT_PATH;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_USER_SECRET_TYPE;

import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.util.Base64;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertPath;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deserializes a {@link android.security.keystore.recovery.KeyChainSnapshot} instance from XML.
 */
public class KeyChainSnapshotDeserializer {

    /**
     * Deserializes a {@link KeyChainSnapshot} instance from the XML in the {@code inputStream}.
     *
     * @throws IOException if there is an IO error reading from the stream.
     * @throws KeyChainSnapshotParserException if the XML does not conform to the expected XML for
     *     a snapshot.
     */
    public static KeyChainSnapshot deserialize(InputStream inputStream)
            throws KeyChainSnapshotParserException, IOException {
        try {
            return deserializeInternal(inputStream);
        } catch (XmlPullParserException e) {
            throw new KeyChainSnapshotParserException("Malformed KeyChainSnapshot XML", e);
        }
    }

    private static KeyChainSnapshot deserializeInternal(InputStream inputStream) throws IOException,
            XmlPullParserException, KeyChainSnapshotParserException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(inputStream, OUTPUT_ENCODING);

        parser.nextTag();
        parser.require(XmlPullParser.START_TAG, NAMESPACE, TAG_KEY_CHAIN_SNAPSHOT);

        KeyChainSnapshot.Builder builder = new KeyChainSnapshot.Builder();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();

            switch (name) {
                case TAG_SNAPSHOT_VERSION:
                    builder.setSnapshotVersion(readIntTag(parser, TAG_SNAPSHOT_VERSION));
                    break;

                case TAG_RECOVERY_KEY_MATERIAL:
                    builder.setEncryptedRecoveryKeyBlob(
                            readBlobTag(parser, TAG_RECOVERY_KEY_MATERIAL));
                    break;

                case TAG_COUNTER_ID:
                    builder.setCounterId(readLongTag(parser, TAG_COUNTER_ID));
                    break;

                case TAG_SERVER_PARAMS:
                    builder.setServerParams(readBlobTag(parser, TAG_SERVER_PARAMS));
                    break;

                case TAG_MAX_ATTEMPTS:
                    builder.setMaxAttempts(readIntTag(parser, TAG_MAX_ATTEMPTS));
                    break;

                case TAG_TRUSTED_HARDWARE_CERT_PATH:
                    try {
                        builder.setTrustedHardwareCertPath(
                                readCertPathTag(parser, TAG_TRUSTED_HARDWARE_CERT_PATH));
                    } catch (CertificateException e) {
                        throw new KeyChainSnapshotParserException(
                                "Could not set trustedHardwareCertPath", e);
                    }
                    break;

                case TAG_BACKEND_PUBLIC_KEY:
                    // Unused
                    break;

                case TAG_KEY_CHAIN_PROTECTION_PARAMS_LIST:
                    builder.setKeyChainProtectionParams(readKeyChainProtectionParamsList(parser));
                    break;

                case TAG_APPLICATION_KEYS:
                    builder.setWrappedApplicationKeys(readWrappedApplicationKeys(parser));
                    break;

                default:
                    throw new KeyChainSnapshotParserException(String.format(
                            Locale.US, "Unexpected tag %s in keyChainSnapshot", name));
            }
        }

        parser.require(XmlPullParser.END_TAG, NAMESPACE, TAG_KEY_CHAIN_SNAPSHOT);
        try {
            return builder.build();
        } catch (NullPointerException e) {
            throw new KeyChainSnapshotParserException("Failed to build KeyChainSnapshot", e);
        }
    }

    private static List<WrappedApplicationKey> readWrappedApplicationKeys(XmlPullParser parser)
            throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(XmlPullParser.START_TAG, NAMESPACE, TAG_APPLICATION_KEYS);
        ArrayList<WrappedApplicationKey> keys = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            keys.add(readWrappedApplicationKey(parser));
        }
        parser.require(XmlPullParser.END_TAG, NAMESPACE, TAG_APPLICATION_KEYS);
        return keys;
    }

    private static WrappedApplicationKey readWrappedApplicationKey(XmlPullParser parser)
            throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(XmlPullParser.START_TAG, NAMESPACE, TAG_APPLICATION_KEY);
        WrappedApplicationKey.Builder builder = new WrappedApplicationKey.Builder();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();

            switch (name) {
                case TAG_ALIAS:
                    builder.setAlias(readStringTag(parser, TAG_ALIAS));
                    break;

                case TAG_KEY_MATERIAL:
                    builder.setEncryptedKeyMaterial(readBlobTag(parser, TAG_KEY_MATERIAL));
                    break;

                case TAG_KEY_METADATA:
                    builder.setMetadata(readBlobTag(parser, TAG_KEY_METADATA));
                    break;

                default:
                    throw new KeyChainSnapshotParserException(String.format(
                            Locale.US, "Unexpected tag %s in wrappedApplicationKey", name));
            }
        }
        parser.require(XmlPullParser.END_TAG, NAMESPACE, TAG_APPLICATION_KEY);

        try {
            return builder.build();
        } catch (NullPointerException e) {
            throw new KeyChainSnapshotParserException("Failed to build WrappedApplicationKey", e);
        }
    }

    private static List<KeyChainProtectionParams> readKeyChainProtectionParamsList(
            XmlPullParser parser) throws IOException, XmlPullParserException,
            KeyChainSnapshotParserException {
        parser.require(XmlPullParser.START_TAG, NAMESPACE, TAG_KEY_CHAIN_PROTECTION_PARAMS_LIST);

        ArrayList<KeyChainProtectionParams> keyChainProtectionParamsList = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            keyChainProtectionParamsList.add(readKeyChainProtectionParams(parser));
        }

        parser.require(XmlPullParser.END_TAG, NAMESPACE, TAG_KEY_CHAIN_PROTECTION_PARAMS_LIST);
        return keyChainProtectionParamsList;
    }

    private static KeyChainProtectionParams readKeyChainProtectionParams(XmlPullParser parser)
        throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(XmlPullParser.START_TAG, NAMESPACE, TAG_KEY_CHAIN_PROTECTION_PARAMS);

        KeyChainProtectionParams.Builder builder = new KeyChainProtectionParams.Builder();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();

            switch (name) {
                case TAG_LOCK_SCREEN_UI_TYPE:
                    builder.setLockScreenUiFormat(readIntTag(parser, TAG_LOCK_SCREEN_UI_TYPE));
                    break;

                case TAG_USER_SECRET_TYPE:
                    builder.setUserSecretType(readIntTag(parser, TAG_USER_SECRET_TYPE));
                    break;

                case TAG_KEY_DERIVATION_PARAMS:
                    builder.setKeyDerivationParams(readKeyDerivationParams(parser));
                    break;

                default:
                    throw new KeyChainSnapshotParserException(String.format(
                            Locale.US,
                            "Unexpected tag %s in keyChainProtectionParams",
                            name));

            }
        }

        parser.require(XmlPullParser.END_TAG, NAMESPACE, TAG_KEY_CHAIN_PROTECTION_PARAMS);

        try {
            return builder.build();
        } catch (NullPointerException e) {
            throw new KeyChainSnapshotParserException(
                    "Failed to build KeyChainProtectionParams", e);
        }
    }

    private static KeyDerivationParams readKeyDerivationParams(XmlPullParser parser)
            throws XmlPullParserException, IOException, KeyChainSnapshotParserException {
        parser.require(XmlPullParser.START_TAG, NAMESPACE, TAG_KEY_DERIVATION_PARAMS);

        int memoryDifficulty = -1;
        int algorithm = -1;
        byte[] salt = null;

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();

            switch (name) {
                case TAG_MEMORY_DIFFICULTY:
                    memoryDifficulty = readIntTag(parser, TAG_MEMORY_DIFFICULTY);
                    break;

                case TAG_ALGORITHM:
                    algorithm = readIntTag(parser, TAG_ALGORITHM);
                    break;

                case TAG_SALT:
                    salt = readBlobTag(parser, TAG_SALT);
                    break;

                default:
                    throw new KeyChainSnapshotParserException(
                            String.format(
                                    Locale.US,
                                    "Unexpected tag %s in keyDerivationParams",
                                    name));
            }
        }

        if (salt == null) {
            throw new KeyChainSnapshotParserException("salt was not set in keyDerivationParams");
        }

        KeyDerivationParams keyDerivationParams = null;

        switch (algorithm) {
            case KeyDerivationParams.ALGORITHM_SHA256:
                keyDerivationParams = KeyDerivationParams.createSha256Params(salt);
                break;

            case KeyDerivationParams.ALGORITHM_SCRYPT:
                keyDerivationParams = KeyDerivationParams.createScryptParams(
                        salt, memoryDifficulty);
                break;

            default:
                throw new KeyChainSnapshotParserException(
                        "Unknown algorithm in keyDerivationParams");
        }

        parser.require(XmlPullParser.END_TAG, NAMESPACE, TAG_KEY_DERIVATION_PARAMS);
        return keyDerivationParams;
    }

    private static int readIntTag(XmlPullParser parser, String tagName)
            throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(XmlPullParser.START_TAG, NAMESPACE, tagName);
        String text = readText(parser);
        parser.require(XmlPullParser.END_TAG, NAMESPACE, tagName);
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw new KeyChainSnapshotParserException(
                    String.format(
                            Locale.US, "%s expected int but got '%s'", tagName, text), e);
        }
    }

    private static long readLongTag(XmlPullParser parser, String tagName)
            throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(XmlPullParser.START_TAG, NAMESPACE, tagName);
        String text = readText(parser);
        parser.require(XmlPullParser.END_TAG, NAMESPACE, tagName);
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw new KeyChainSnapshotParserException(
                    String.format(
                            Locale.US, "%s expected long but got '%s'", tagName, text), e);
        }
    }

    private static String readStringTag(XmlPullParser parser, String tagName)
            throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, NAMESPACE, tagName);
        String text = readText(parser);
        parser.require(XmlPullParser.END_TAG, NAMESPACE, tagName);
        return text;
    }

    private static byte[] readBlobTag(XmlPullParser parser, String tagName)
            throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(XmlPullParser.START_TAG, NAMESPACE, tagName);
        String text = readText(parser);
        parser.require(XmlPullParser.END_TAG, NAMESPACE, tagName);

        try {
            return Base64.decode(text, /*flags=*/ Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new KeyChainSnapshotParserException(
                    String.format(
                            Locale.US,
                            "%s expected base64 encoded bytes but got '%s'",
                            tagName, text), e);
        }
    }

    private static CertPath readCertPathTag(XmlPullParser parser, String tagName)
            throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        byte[] bytes = readBlobTag(parser, tagName);
        try {
            return CertificateFactory.getInstance(CERTIFICATE_FACTORY_TYPE)
                    .generateCertPath(new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            throw new KeyChainSnapshotParserException("Could not parse CertPath in tag " + tagName,
                    e);
        }
    }

    private static String readText(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        String result = "";
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.getText();
            parser.nextTag();
        }
        return result;
    }

    // Statics only
    private KeyChainSnapshotDeserializer() {}
}
