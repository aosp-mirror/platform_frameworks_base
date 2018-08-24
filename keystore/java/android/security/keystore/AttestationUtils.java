/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.security.keystore;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.security.KeyStore;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterCertificateChain;
import android.security.keymaster.KeymasterDefs;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Set;

/**
 * Utilities for attesting the device's hardware identifiers.
 *
 * @hide
 */
@SystemApi
@TestApi
public abstract class AttestationUtils {
    private AttestationUtils() {
    }

    /**
     * Specifies that the device should attest its serial number. For use with
     * {@link #attestDeviceIds}.
     *
     * @see #attestDeviceIds
     */
    public static final int ID_TYPE_SERIAL = 1;

    /**
     * Specifies that the device should attest its IMEIs. For use with {@link #attestDeviceIds}.
     *
     * @see #attestDeviceIds
     */
    public static final int ID_TYPE_IMEI = 2;

    /**
     * Specifies that the device should attest its MEIDs. For use with {@link #attestDeviceIds}.
     *
     * @see #attestDeviceIds
     */
    public static final int ID_TYPE_MEID = 3;

    /**
     * Creates an array of X509Certificates from the provided KeymasterCertificateChain.
     *
     * @hide Only called by the DevicePolicyManager.
     */
    @NonNull public static X509Certificate[] parseCertificateChain(
            final KeymasterCertificateChain kmChain) throws
            KeyAttestationException {
        // Extract certificate chain.
        final Collection<byte[]> rawChain = kmChain.getCertificates();
        if (rawChain.size() < 2) {
            throw new KeyAttestationException("Attestation certificate chain contained "
                    + rawChain.size() + " entries. At least two are required.");
        }
        final ByteArrayOutputStream concatenatedRawChain = new ByteArrayOutputStream();
        try {
            for (final byte[] cert : rawChain) {
                concatenatedRawChain.write(cert);
            }
            return CertificateFactory.getInstance("X.509").generateCertificates(
                    new ByteArrayInputStream(concatenatedRawChain.toByteArray()))
                            .toArray(new X509Certificate[0]);
        } catch (Exception e) {
            throw new KeyAttestationException("Unable to construct certificate chain", e);
        }
    }

    @NonNull private static KeymasterArguments prepareAttestationArgumentsForDeviceId(
            Context context, @NonNull int[] idTypes, @NonNull byte[] attestationChallenge) throws
            DeviceIdAttestationException {
        // Verify that device ID attestation types are provided.
        if (idTypes == null) {
            throw new NullPointerException("Missing id types");
        }

        return prepareAttestationArguments(context, idTypes, attestationChallenge);
    }

    /**
     * Prepares Keymaster Arguments with attestation data.
     * @hide should only be used by KeyChain.
     */
    @NonNull public static KeymasterArguments prepareAttestationArguments(Context context,
            @NonNull int[] idTypes, @NonNull byte[] attestationChallenge) throws
            DeviceIdAttestationException {
        return prepareAttestationArguments(context, idTypes,attestationChallenge, Build.BRAND);
    }

    /**
     * Prepares Keymaster Arguments with attestation data for misprovisioned Pixel 2 device.
     * See http://go/keyAttestationFailure and http://b/69471841 for more info.
     * @hide should only be used by KeyChain.
     */
    @NonNull public static KeymasterArguments prepareAttestationArgumentsIfMisprovisioned(
            Context context, @NonNull int[] idTypes, @NonNull byte[] attestationChallenge) throws
            DeviceIdAttestationException {
        if (!isPotentiallyMisprovisionedDevice(context)) {
            return null;
        }
        Resources resources = context.getResources();
        String misprovisionedBrand = resources.getString(
                com.android.internal.R.string.config_misprovisionedBrandValue);
        return prepareAttestationArguments(
                    context, idTypes, attestationChallenge, misprovisionedBrand);
    }

    @NonNull private static boolean isPotentiallyMisprovisionedDevice(Context context) {
        Resources resources = context.getResources();
        String misprovisionedModel = resources.getString(
                com.android.internal.R.string.config_misprovisionedDeviceModel);
        String misprovisionedBrand = resources.getString(
                com.android.internal.R.string.config_misprovisionedBrandValue);

        return (Build.MODEL.equals(misprovisionedModel));
    }

    @NonNull private static KeymasterArguments prepareAttestationArguments(Context context,
            @NonNull int[] idTypes, @NonNull byte[] attestationChallenge, String brand) throws
            DeviceIdAttestationException {
        // Check method arguments, retrieve requested device IDs and prepare attestation arguments.
        if (attestationChallenge == null) {
            throw new NullPointerException("Missing attestation challenge");
        }
        final KeymasterArguments attestArgs = new KeymasterArguments();
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_CHALLENGE, attestationChallenge);
        // Return early if the caller did not request any device identifiers to be included in the
        // attestation record.
        if (idTypes == null) {
            return attestArgs;
        }
        final Set<Integer> idTypesSet = new ArraySet<>(idTypes.length);
        for (int idType : idTypes) {
            idTypesSet.add(idType);
        }
        TelephonyManager telephonyService = null;
        if (idTypesSet.contains(ID_TYPE_IMEI) || idTypesSet.contains(ID_TYPE_MEID)) {
            telephonyService = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            if (telephonyService == null) {
                throw new DeviceIdAttestationException("Unable to access telephony service");
            }
        }
        for (final Integer idType : idTypesSet) {
            switch (idType) {
                case ID_TYPE_SERIAL:
                    attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_SERIAL,
                            Build.getSerial().getBytes(StandardCharsets.UTF_8));
                    break;
                case ID_TYPE_IMEI: {
                    final String imei = telephonyService.getImei(0);
                    if (imei == null) {
                        throw new DeviceIdAttestationException("Unable to retrieve IMEI");
                    }
                    attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_IMEI,
                            imei.getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case ID_TYPE_MEID: {
                    final String meid = telephonyService.getMeid(0);
                    if (meid == null) {
                        throw new DeviceIdAttestationException("Unable to retrieve MEID");
                    }
                    attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_MEID,
                            meid.getBytes(StandardCharsets.UTF_8));
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unknown device ID type " + idType);
            }
        }
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_BRAND,
                brand.getBytes(StandardCharsets.UTF_8));
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_DEVICE,
                Build.DEVICE.getBytes(StandardCharsets.UTF_8));
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_PRODUCT,
                Build.PRODUCT.getBytes(StandardCharsets.UTF_8));
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_MANUFACTURER,
                Build.MANUFACTURER.getBytes(StandardCharsets.UTF_8));
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_MODEL,
                Build.MODEL.getBytes(StandardCharsets.UTF_8));
        return attestArgs;
    }

    /**
     * Performs attestation of the device's identifiers. This method returns a certificate chain
     * whose first element contains the requested device identifiers in an extension. The device's
     * manufacturer, model, brand, device and product are always also included in the attestation.
     * If the device supports attestation in secure hardware, the chain will be rooted at a
     * trustworthy CA key. Otherwise, the chain will be rooted at an untrusted certificate. See
     * <a href="https://developer.android.com/training/articles/security-key-attestation.html">
     * Key Attestation</a> for the format of the certificate extension.
     * <p>
     * Attestation will only be successful when all of the following are true:
     * 1) The device has been set up to support device identifier attestation at the factory.
     * 2) The user has not permanently disabled device identifier attestation.
     * 3) You have permission to access the device identifiers you are requesting attestation for.
     * <p>
     * For privacy reasons, you cannot distinguish between (1) and (2). If attestation is
     * unsuccessful, the device may not support it in general or the user may have permanently
     * disabled it.
     *
     * @param context the context to use for retrieving device identifiers.
     * @param idTypes the types of device identifiers to attest.
     * @param attestationChallenge a blob to include in the certificate alongside the device
     * identifiers.
     *
     * @return a certificate chain containing the requested device identifiers in the first element
     *
     * @exception SecurityException if you are not permitted to obtain an attestation of the
     * device's identifiers.
     * @exception DeviceIdAttestationException if the attestation operation fails.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @NonNull public static X509Certificate[] attestDeviceIds(Context context,
            @NonNull int[] idTypes, @NonNull byte[] attestationChallenge) throws
            DeviceIdAttestationException {
        final KeymasterArguments attestArgs = prepareAttestationArgumentsForDeviceId(
                context, idTypes, attestationChallenge);

        // Perform attestation.
        final KeymasterCertificateChain outChain = new KeymasterCertificateChain();
        final int errorCode = KeyStore.getInstance().attestDeviceIds(attestArgs, outChain);
        if (errorCode != KeyStore.NO_ERROR) {
            throw new DeviceIdAttestationException("Unable to perform attestation",
                    KeyStore.getKeyStoreException(errorCode));
        }

        try {
            return parseCertificateChain(outChain);
        } catch (KeyAttestationException e) {
            throw new DeviceIdAttestationException(e.getMessage(), e);
        }
    }

    /**
     * Returns true if the attestation chain provided is a valid key attestation chain.
     * @hide
     */
    public static boolean isChainValid(KeymasterCertificateChain chain) {
        return chain != null && chain.getCertificates().size() >= 2;
    }
}
