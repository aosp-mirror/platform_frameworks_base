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


import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.CERT_PATH_ENCODING;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.NAMESPACE;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.OUTPUT_ENCODING;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_ALGORITHM;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_ALIAS;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_APPLICATION_KEY;
import static com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSchema.TAG_APPLICATION_KEYS;
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

import android.annotation.Nullable;
import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.util.Base64;
import android.util.Xml;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.OutputStream;
import java.security.cert.CertPath;
import java.security.cert.CertificateEncodingException;
import java.util.List;

/**
 * Serializes a {@link KeyChainSnapshot} instance to XML.
 */
public class KeyChainSnapshotSerializer {

    /**
     * Serializes {@code keyChainSnapshot} to XML, writing to {@code outputStream}.
     *
     * @throws IOException if there was an IO error writing to the stream.
     * @throws CertificateEncodingException if the {@link CertPath} from
     *     {@link KeyChainSnapshot#getTrustedHardwareCertPath()} is not encoded correctly.
     */
    public static void serialize(KeyChainSnapshot keyChainSnapshot, OutputStream outputStream)
            throws IOException, CertificateEncodingException {
        XmlSerializer xmlSerializer = Xml.newSerializer();
        xmlSerializer.setOutput(outputStream, OUTPUT_ENCODING);
        xmlSerializer.startDocument(
                /*encoding=*/ null,
                /*standalone=*/ null);
        xmlSerializer.startTag(NAMESPACE, TAG_KEY_CHAIN_SNAPSHOT);
        writeKeyChainSnapshotProperties(xmlSerializer, keyChainSnapshot);
        writeKeyChainProtectionParams(xmlSerializer,
                keyChainSnapshot.getKeyChainProtectionParams());
        writeApplicationKeys(xmlSerializer,
                keyChainSnapshot.getWrappedApplicationKeys());
        xmlSerializer.endTag(NAMESPACE, TAG_KEY_CHAIN_SNAPSHOT);
        xmlSerializer.endDocument();
    }

    private static void writeApplicationKeys(
            XmlSerializer xmlSerializer, List<WrappedApplicationKey> wrappedApplicationKeys)
            throws IOException {
        xmlSerializer.startTag(NAMESPACE, TAG_APPLICATION_KEYS);
        for (WrappedApplicationKey key : wrappedApplicationKeys) {
            xmlSerializer.startTag(NAMESPACE, TAG_APPLICATION_KEY);
            writeApplicationKeyProperties(xmlSerializer, key);
            xmlSerializer.endTag(NAMESPACE, TAG_APPLICATION_KEY);
        }
        xmlSerializer.endTag(NAMESPACE, TAG_APPLICATION_KEYS);
    }

    private static void writeApplicationKeyProperties(
            XmlSerializer xmlSerializer, WrappedApplicationKey applicationKey) throws IOException {
        writePropertyTag(xmlSerializer, TAG_ALIAS, applicationKey.getAlias());
        writePropertyTag(xmlSerializer, TAG_KEY_MATERIAL, applicationKey.getEncryptedKeyMaterial());
        writePropertyTag(xmlSerializer, TAG_KEY_METADATA, applicationKey.getMetadata());
    }

    private static void writeKeyChainProtectionParams(
            XmlSerializer xmlSerializer,
            List<KeyChainProtectionParams> keyChainProtectionParamsList) throws IOException {
        xmlSerializer.startTag(NAMESPACE, TAG_KEY_CHAIN_PROTECTION_PARAMS_LIST);
        for (KeyChainProtectionParams keyChainProtectionParams : keyChainProtectionParamsList) {
            xmlSerializer.startTag(NAMESPACE, TAG_KEY_CHAIN_PROTECTION_PARAMS);
            writeKeyChainProtectionParamsProperties(xmlSerializer, keyChainProtectionParams);
            xmlSerializer.endTag(NAMESPACE, TAG_KEY_CHAIN_PROTECTION_PARAMS);
        }
        xmlSerializer.endTag(NAMESPACE, TAG_KEY_CHAIN_PROTECTION_PARAMS_LIST);
    }

    private static void writeKeyChainProtectionParamsProperties(
            XmlSerializer xmlSerializer, KeyChainProtectionParams keyChainProtectionParams)
            throws IOException {
        writePropertyTag(xmlSerializer, TAG_USER_SECRET_TYPE,
                keyChainProtectionParams.getUserSecretType());
        writePropertyTag(xmlSerializer, TAG_LOCK_SCREEN_UI_TYPE,
                keyChainProtectionParams.getLockScreenUiFormat());

        // NOTE: Do not serialize the 'secret' field. It should never be set anyway for snapshots
        // we generate.

        writeKeyDerivationParams(xmlSerializer, keyChainProtectionParams.getKeyDerivationParams());
    }

    private static void writeKeyDerivationParams(
            XmlSerializer xmlSerializer, KeyDerivationParams keyDerivationParams)
            throws IOException {
        xmlSerializer.startTag(NAMESPACE, TAG_KEY_DERIVATION_PARAMS);
        writeKeyDerivationParamsProperties(
                xmlSerializer, keyDerivationParams);
        xmlSerializer.endTag(NAMESPACE, TAG_KEY_DERIVATION_PARAMS);
    }

    private static void writeKeyDerivationParamsProperties(
            XmlSerializer xmlSerializer, KeyDerivationParams keyDerivationParams)
            throws IOException {
        writePropertyTag(xmlSerializer, TAG_ALGORITHM, keyDerivationParams.getAlgorithm());
        writePropertyTag(xmlSerializer, TAG_SALT, keyDerivationParams.getSalt());
        writePropertyTag(xmlSerializer, TAG_MEMORY_DIFFICULTY,
                keyDerivationParams.getMemoryDifficulty());
    }

    private static void writeKeyChainSnapshotProperties(
            XmlSerializer xmlSerializer, KeyChainSnapshot keyChainSnapshot)
            throws IOException, CertificateEncodingException {

        writePropertyTag(xmlSerializer, TAG_SNAPSHOT_VERSION,
                keyChainSnapshot.getSnapshotVersion());
        writePropertyTag(xmlSerializer, TAG_MAX_ATTEMPTS, keyChainSnapshot.getMaxAttempts());
        writePropertyTag(xmlSerializer, TAG_COUNTER_ID, keyChainSnapshot.getCounterId());
        writePropertyTag(xmlSerializer, TAG_RECOVERY_KEY_MATERIAL,
                keyChainSnapshot.getEncryptedRecoveryKeyBlob());
        writePropertyTag(xmlSerializer, TAG_SERVER_PARAMS, keyChainSnapshot.getServerParams());
        writePropertyTag(xmlSerializer, TAG_TRUSTED_HARDWARE_CERT_PATH,
                keyChainSnapshot.getTrustedHardwareCertPath());
    }

    private static void writePropertyTag(
            XmlSerializer xmlSerializer, String propertyName, long propertyValue)
            throws IOException {
        xmlSerializer.startTag(NAMESPACE, propertyName);
        xmlSerializer.text(Long.toString(propertyValue));
        xmlSerializer.endTag(NAMESPACE, propertyName);
    }

    private static void writePropertyTag(
            XmlSerializer xmlSerializer, String propertyName, String propertyValue)
            throws IOException {
        xmlSerializer.startTag(NAMESPACE, propertyName);
        xmlSerializer.text(propertyValue);
        xmlSerializer.endTag(NAMESPACE, propertyName);
    }

    private static void writePropertyTag(
            XmlSerializer xmlSerializer, String propertyName, @Nullable byte[] propertyValue)
            throws IOException {
        if (propertyValue == null) {
            return;
        }
        xmlSerializer.startTag(NAMESPACE, propertyName);
        xmlSerializer.text(Base64.encodeToString(propertyValue, /*flags=*/ Base64.DEFAULT));
        xmlSerializer.endTag(NAMESPACE, propertyName);
    }

    private static void writePropertyTag(
            XmlSerializer xmlSerializer, String propertyName, CertPath certPath)
            throws IOException, CertificateEncodingException {
        writePropertyTag(xmlSerializer, propertyName, certPath.getEncoded(CERT_PATH_ENCODING));
    }

    // Statics only
    private KeyChainSnapshotSerializer() {}
}
