/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.security;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.framework.protobuf.ByteString;
import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1InputStream;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1Set;
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;
import com.android.internal.org.bouncycastle.asn1.x509.Certificate;

import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@link X509Certificate} attestation extension values for Android Keystore attestations.
 *
 * Pull fields out of the top-level sequence. A full description of this structure is at
 * https://source.android.com/security/keystore/attestation.
 *
 * If a value is null or empty, then it was not set/found in the extension values.
 *
 */
class AndroidKeystoreAttestationVerificationAttributes {
    // The OID for the extension Android Keymaster puts into device-generated certificates.
    private static final String ANDROID_KEYMASTER_KEY_DESCRIPTION_EXTENSION_OID =
            "1.3.6.1.4.1.11129.2.1.17";

    // ASN.1 sequence index values for the Android Keymaster extension.
    private static final int ATTESTATION_VERSION_INDEX = 0;
    private static final int ATTESTATION_SECURITY_LEVEL_INDEX = 1;
    private static final int KEYMASTER_VERSION_INDEX = 2;
    private static final int KEYMASTER_SECURITY_LEVEL_INDEX = 3;
    private static final int ATTESTATION_CHALLENGE_INDEX = 4;
    private static final int KEYMASTER_UNIQUE_ID_INDEX = 5;
    private static final int SW_ENFORCED_INDEX = 6;
    private static final int HW_ENFORCED_INDEX = 7;
    private static final int VERIFIED_BOOT_KEY_INDEX = 0;
    private static final int VERIFIED_BOOT_LOCKED_INDEX = 1;
    private static final int VERIFIED_BOOT_STATE_INDEX = 2;
    private static final int VERIFIED_BOOT_HASH_INDEX = 3;

    // ASN.1 sequence index values for the Android Keystore application id.
    private static final int PACKAGE_INFO_SET_INDEX = 0;
    private static final int PACKAGE_SIGNATURE_SET_INDEX = 1;
    private static final int PACKAGE_INFO_NAME_INDEX = 0;
    private static final int PACKAGE_INFO_VERSION_INDEX = 1;

    // See these AOSP files: hardware/libhardware/include/hardware/hw_auth_token.h
    private static final int HW_AUTH_NONE = 0;

    // Some keymaster constants. See this AOSP file:
    // hardware/libhardware/include/hardware/keymaster_defs.h
    private static final int KM_TAG_NO_AUTH_REQUIRED = 503;
    private static final int KM_TAG_UNLOCKED_DEVICE_REQUIRED = 509;
    private static final int KM_TAG_ALL_APPLICATIONS = 600;
    private static final int KM_TAG_ROOT_OF_TRUST = 704;
    private static final int KM_TAG_OS_VERSION = 705;
    private static final int KM_TAG_OS_PATCHLEVEL = 706;
    private static final int KM_TAG_ATTESTATION_APPLICATION_ID = 709;
    private static final int KM_TAG_ATTESTATION_ID_BRAND = 710;
    private static final int KM_TAG_ATTESTATION_ID_DEVICE = 711;
    private static final int KM_TAG_ATTESTATION_ID_PRODUCT = 712;
    private static final int KM_TAG_VENDOR_PATCHLEVEL = 718;
    private static final int KM_TAG_BOOT_PATCHLEVEL = 719;

    private static final int KM_SECURITY_LEVEL_SOFTWARE = 0;
    private static final int KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1;
    private static final int KM_SECURITY_LEVEL_STRONG_BOX = 2;
    private static final int KM_VERIFIED_BOOT_STATE_VERIFIED = 0;
    private static final int KM_VERIFIED_BOOT_STATE_SELF_SIGNED = 1;
    private static final int KM_VERIFIED_BOOT_STATE_UNVERIFIED = 2;
    private static final int KM_VERIFIED_BOOT_STATE_FAILED = 3;

    private Integer mAttestationVersion = null;
    private SecurityLevel mAttestationSecurityLevel = null;
    private boolean mAttestationHardwareBacked = false;
    private Integer mKeymasterVersion = null;
    private SecurityLevel mKeymasterSecurityLevel = null;
    private boolean mKeymasterHardwareBacked = false;
    private ByteString mAttestationChallenge = null;
    private ByteString mKeymasterUniqueId = null;
    private String mDeviceBrand = null;
    private String mDeviceName = null;
    private String mDeviceProductName = null;
    private boolean mKeyAllowedForAllApplications = false;
    private Integer mKeyAuthenticatorType = null;
    private Integer mKeyBootPatchLevel = null;
    private Integer mKeyOsPatchLevel = null;
    private Integer mKeyOsVersion = null;
    private Integer mKeyVendorPatchLevel = null;
    private Boolean mKeyRequiresUnlockedDevice = null;
    private ByteString mVerifiedBootHash = null;
    private ByteString mVerifiedBootKey = null;
    private Boolean mVerifiedBootLocked = null;
    private VerifiedBootState mVerifiedBootState = null;
    private Map<String, Long> mApplicationPackageNameVersion = null;
    private List<ByteString> mApplicationCertificateDigests = null;

    enum VerifiedBootState {
        VERIFIED,
        SELF_SIGNED,
        UNVERIFIED,
        FAILED
    }

    enum SecurityLevel {
        SOFTWARE,
        TRUSTED_ENVIRONMENT,
        STRONG_BOX
    }

    /**
     * Extracts attestation extension properties from {@link X509Certificate}
     * and returns a {@link AndroidKeystoreAttestationVerificationAttributes} that encapsulates the
     * properties.
     */
    @NonNull
    static AndroidKeystoreAttestationVerificationAttributes fromCertificate(
            @NonNull X509Certificate x509Certificate)
            throws Exception {
        return new AndroidKeystoreAttestationVerificationAttributes(x509Certificate);
    }

    int getAttestationVersion() {
        return mAttestationVersion;
    }

    @Nullable
    SecurityLevel getAttestationSecurityLevel() {
        return mAttestationSecurityLevel;
    }

    boolean isAttestationHardwareBacked() {
        return mAttestationHardwareBacked;
    }

    int getKeymasterVersion() {
        return mKeymasterVersion;
    }

    @Nullable
    SecurityLevel getKeymasterSecurityLevel() {
        return mKeymasterSecurityLevel;
    }

    boolean isKeymasterHardwareBacked() {
        return mKeymasterHardwareBacked;
    }

    @Nullable
    ByteString getAttestationChallenge() {
        return mAttestationChallenge;
    }

    @Nullable
    ByteString getKeymasterUniqueId() {
        return mKeymasterUniqueId;
    }

    @Nullable
    String getDeviceBrand() {
        return mDeviceBrand;
    }

    @Nullable
    String getDeviceName() {
        return mDeviceName;
    }

    @Nullable
    String getDeviceProductName() {
        return mDeviceProductName;
    }

    boolean isKeyAllowedForAllApplications() {
        return mKeyAllowedForAllApplications;
    }

    int getKeyAuthenticatorType() {
        if (mKeyAuthenticatorType == null) {
            throw new IllegalStateException("KeyAuthenticatorType is not set.");
        }
        return mKeyAuthenticatorType;
    }

    int getKeyBootPatchLevel() {
        if (mKeyBootPatchLevel == null) {
            throw new IllegalStateException("KeyBootPatchLevel is not set.");
        }
        return mKeyBootPatchLevel;
    }

    int getKeyOsPatchLevel() {
        if (mKeyOsPatchLevel == null) {
            throw new IllegalStateException("KeyOsPatchLevel is not set.");
        }
        return mKeyOsPatchLevel;
    }

    int getKeyVendorPatchLevel() {
        if (mKeyVendorPatchLevel == null) {
            throw new IllegalStateException("KeyVendorPatchLevel is not set.");
        }
        return mKeyVendorPatchLevel;
    }

    int getKeyOsVersion() {
        if (mKeyOsVersion == null) {
            throw new IllegalStateException("KeyOsVersion is not set.");
        }
        return mKeyOsVersion;
    }

    boolean isKeyRequiresUnlockedDevice() {
        if (mKeyRequiresUnlockedDevice == null) {
            throw new IllegalStateException("KeyRequiresUnlockedDevice is not set.");
        }
        return mKeyRequiresUnlockedDevice;
    }

    @Nullable
    ByteString getVerifiedBootHash() {
        return mVerifiedBootHash;
    }

    @Nullable
    ByteString getVerifiedBootKey() {
        return mVerifiedBootKey;
    }

    boolean isVerifiedBootLocked() {
        if (mVerifiedBootLocked == null) {
            throw new IllegalStateException("VerifiedBootLocked is not set.");
        }
        return mVerifiedBootLocked;
    }

    @Nullable
    VerifiedBootState getVerifiedBootState() {
        return mVerifiedBootState;
    }

    @Nullable
    Map<String, Long> getApplicationPackageNameVersion() {
        return Collections.unmodifiableMap(mApplicationPackageNameVersion);
    }

    @Nullable
    List<ByteString> getApplicationCertificateDigests() {
        return Collections.unmodifiableList(mApplicationCertificateDigests);
    }

    private AndroidKeystoreAttestationVerificationAttributes(X509Certificate x509Certificate)
            throws Exception {
        Certificate certificate = Certificate.getInstance(
                new ASN1InputStream(x509Certificate.getEncoded()).readObject());
        ASN1Sequence keyAttributes = (ASN1Sequence) certificate.getTBSCertificate().getExtensions()
                .getExtensionParsedValue(
                        new ASN1ObjectIdentifier(ANDROID_KEYMASTER_KEY_DESCRIPTION_EXTENSION_OID));
        if (keyAttributes == null) {
            throw new CertificateEncodingException(
                    "No attestation extension found in certificate.");
        }
        this.mAttestationVersion = getIntegerFromAsn1(
                keyAttributes.getObjectAt(ATTESTATION_VERSION_INDEX));
        this.mAttestationSecurityLevel = getSecurityLevelEnum(
                keyAttributes.getObjectAt(ATTESTATION_SECURITY_LEVEL_INDEX));
        this.mAttestationHardwareBacked =
                this.mAttestationSecurityLevel == SecurityLevel.TRUSTED_ENVIRONMENT;
        this.mAttestationChallenge = getOctetsFromAsn1(
                keyAttributes.getObjectAt(ATTESTATION_CHALLENGE_INDEX));
        this.mKeymasterVersion = getIntegerFromAsn1(
                keyAttributes.getObjectAt(KEYMASTER_VERSION_INDEX));
        this.mKeymasterUniqueId = getOctetsFromAsn1(
                keyAttributes.getObjectAt(KEYMASTER_UNIQUE_ID_INDEX));
        this.mKeymasterSecurityLevel = getSecurityLevelEnum(
                keyAttributes.getObjectAt(KEYMASTER_SECURITY_LEVEL_INDEX));
        this.mKeymasterHardwareBacked =
                this.mKeymasterSecurityLevel == SecurityLevel.TRUSTED_ENVIRONMENT;

        ASN1Encodable[] softwareEnforced = ((ASN1Sequence)
                keyAttributes.getObjectAt(SW_ENFORCED_INDEX)).toArray();
        for (ASN1Encodable entry : softwareEnforced) {
            ASN1TaggedObject taggedEntry = (ASN1TaggedObject) entry;
            switch (taggedEntry.getTagNo()) {
                case KM_TAG_ATTESTATION_APPLICATION_ID:
                    parseAttestationApplicationId(
                            getOctetsFromAsn1(taggedEntry.getObject()).toByteArray());
                    break;
                case KM_TAG_UNLOCKED_DEVICE_REQUIRED:
                    this.mKeyRequiresUnlockedDevice = getBoolFromAsn1(taggedEntry.getObject());
                    break;
                default:
                    break;
            }
        }

        ASN1Encodable[] hardwareEnforced = ((ASN1Sequence)
                keyAttributes.getObjectAt(HW_ENFORCED_INDEX)).toArray();
        for (ASN1Encodable entry : hardwareEnforced) {
            ASN1TaggedObject taggedEntry = (ASN1TaggedObject) entry;
            switch (taggedEntry.getTagNo()) {
                case KM_TAG_NO_AUTH_REQUIRED:
                    this.mKeyAuthenticatorType = HW_AUTH_NONE;
                    break;
                case KM_TAG_ALL_APPLICATIONS:
                    this.mKeyAllowedForAllApplications = true;
                    break;
                case KM_TAG_ROOT_OF_TRUST:
                    ASN1Sequence rootOfTrust = (ASN1Sequence) taggedEntry.getObject();
                    this.mVerifiedBootKey =
                            getOctetsFromAsn1(rootOfTrust.getObjectAt(VERIFIED_BOOT_KEY_INDEX));
                    this.mVerifiedBootLocked =
                            getBoolFromAsn1(rootOfTrust.getObjectAt(VERIFIED_BOOT_LOCKED_INDEX));
                    this.mVerifiedBootState =
                            getVerifiedBootStateEnum(
                                    rootOfTrust.getObjectAt(VERIFIED_BOOT_STATE_INDEX));
                    // The verified boot hash was added in structure version 3 (Keymaster 4.0).
                    if (mAttestationVersion >= 3) {
                        this.mVerifiedBootHash =
                                getOctetsFromAsn1(
                                        rootOfTrust.getObjectAt(VERIFIED_BOOT_HASH_INDEX));
                    }
                    break;
                case KM_TAG_OS_VERSION:
                    this.mKeyOsVersion = getIntegerFromAsn1(taggedEntry.getObject());
                    break;
                case KM_TAG_OS_PATCHLEVEL:
                    this.mKeyOsPatchLevel = getIntegerFromAsn1(taggedEntry.getObject());
                    break;
                case KM_TAG_ATTESTATION_ID_BRAND:
                    this.mDeviceBrand = getUtf8FromOctetsFromAsn1(taggedEntry.getObject());
                    break;
                case KM_TAG_ATTESTATION_ID_DEVICE:
                    this.mDeviceName = getUtf8FromOctetsFromAsn1(taggedEntry.getObject());
                    break;
                case KM_TAG_ATTESTATION_ID_PRODUCT:
                    this.mDeviceProductName = getUtf8FromOctetsFromAsn1(taggedEntry.getObject());
                    break;
                case KM_TAG_VENDOR_PATCHLEVEL:
                    this.mKeyVendorPatchLevel = getIntegerFromAsn1(taggedEntry.getObject());
                    break;
                case KM_TAG_BOOT_PATCHLEVEL:
                    this.mKeyBootPatchLevel = getIntegerFromAsn1(taggedEntry.getObject());
                    break;
                default:
                    break;
            }
        }
    }

    private void parseAttestationApplicationId(byte [] attestationApplicationId)
            throws Exception {
        ASN1Sequence outerSequence = ASN1Sequence.getInstance(
                new ASN1InputStream(attestationApplicationId).readObject());
        Map<String, Long> packageNameVersion = new HashMap<>();
        ASN1Set packageInfoSet = (ASN1Set) outerSequence.getObjectAt(PACKAGE_INFO_SET_INDEX);
        for (ASN1Encodable packageInfoEntry : packageInfoSet.toArray()) {
            ASN1Sequence packageInfoSequence = (ASN1Sequence) packageInfoEntry;
            packageNameVersion.put(
                    getUtf8FromOctetsFromAsn1(
                            packageInfoSequence.getObjectAt(PACKAGE_INFO_NAME_INDEX)),
                    getLongFromAsn1(packageInfoSequence.getObjectAt(PACKAGE_INFO_VERSION_INDEX)));
        }
        List<ByteString> certificateDigests = new ArrayList<>();
        ASN1Set certificateDigestSet =
                (ASN1Set) outerSequence.getObjectAt(PACKAGE_SIGNATURE_SET_INDEX);
        for (ASN1Encodable certificateDigestEntry : certificateDigestSet.toArray()) {
            certificateDigests.add(getOctetsFromAsn1(certificateDigestEntry));
        }
        this.mApplicationPackageNameVersion = Collections.unmodifiableMap(packageNameVersion);
        this.mApplicationCertificateDigests = Collections.unmodifiableList(certificateDigests);

    }

    private VerifiedBootState getVerifiedBootStateEnum(ASN1Encodable asn1) {
        int verifiedBoot = getEnumFromAsn1(asn1);
        switch (verifiedBoot) {
            case KM_VERIFIED_BOOT_STATE_VERIFIED:
                return VerifiedBootState.VERIFIED;
            case KM_VERIFIED_BOOT_STATE_SELF_SIGNED:
                return VerifiedBootState.SELF_SIGNED;
            case KM_VERIFIED_BOOT_STATE_UNVERIFIED:
                return VerifiedBootState.UNVERIFIED;
            case KM_VERIFIED_BOOT_STATE_FAILED:
                return VerifiedBootState.FAILED;
            default:
                throw new IllegalArgumentException("Invalid verified boot state.");
        }
    }

    private SecurityLevel getSecurityLevelEnum(ASN1Encodable asn1) {
        int securityLevel = getEnumFromAsn1(asn1);
        switch (securityLevel) {
            case KM_SECURITY_LEVEL_SOFTWARE:
                return SecurityLevel.SOFTWARE;
            case KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT:
                return SecurityLevel.TRUSTED_ENVIRONMENT;
            case KM_SECURITY_LEVEL_STRONG_BOX:
                return SecurityLevel.STRONG_BOX;
            default:
                throw new IllegalArgumentException("Invalid security level.");
        }
    }

    @NonNull
    private ByteString getOctetsFromAsn1(ASN1Encodable asn1) {
        return ByteString.copyFrom(((ASN1OctetString) asn1).getOctets());
    }

    @NonNull
    private String getUtf8FromOctetsFromAsn1(ASN1Encodable asn1) {
        return new String(((ASN1OctetString) asn1).getOctets(), StandardCharsets.UTF_8);
    }

    @NonNull
    private int getIntegerFromAsn1(ASN1Encodable asn1) {
        return ((ASN1Integer) asn1).getValue().intValueExact();
    }

    @NonNull
    private long getLongFromAsn1(ASN1Encodable asn1) {
        return ((ASN1Integer) asn1).getValue().longValueExact();
    }

    @NonNull
    private int getEnumFromAsn1(ASN1Encodable asn1) {
        return ((ASN1Enumerated) asn1).getValue().intValueExact();
    }

    @Nullable
    private Boolean getBoolFromAsn1(ASN1Encodable asn1) {
        if (asn1 instanceof ASN1Boolean) {
            return ((ASN1Boolean) asn1).isTrue();
        }
        return null;
    }
}
