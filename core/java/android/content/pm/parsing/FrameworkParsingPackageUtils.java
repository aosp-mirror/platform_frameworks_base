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

package android.content.pm.parsing;

import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;

import android.annotation.CheckResult;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.os.Build;
import android.os.FileUtils;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Slog;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.util.ArrayUtils;
import com.android.modules.utils.build.UnboundedSdkLevel;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/** @hide */
public class FrameworkParsingPackageUtils {

    private static final String TAG = "FrameworkParsingPackageUtils";

    /**
     * For those names would be used as a part of the file name. Limits size to 223 and reserves 32
     * for the OS.
     */
    private static final int MAX_FILE_NAME_SIZE = 223;

    public static final int PARSE_IGNORE_OVERLAY_REQUIRED_SYSTEM_PROPERTY = 1 << 7;

    /**
     * Check if the given name is valid.
     *
     * @param name The name to check.
     * @param requireSeparator {@code true} if the name requires containing a separator at least.
     * @param requireFilename {@code true} to apply file name validation to the given name. It also
     *                        limits length of the name to the {@link #MAX_FILE_NAME_SIZE}.
     * @return Success if it's valid.
     */
    public static String validateName(String name, boolean requireSeparator,
            boolean requireFilename) {
        final int N = name.length();
        boolean hasSep = false;
        boolean front = true;
        for (int i = 0; i < N; i++) {
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
            return "bad character '" + c + "'";
        }
        if (requireFilename) {
            if (!FileUtils.isValidExtFilename(name)) {
                return "Invalid filename";
            } else if (N > MAX_FILE_NAME_SIZE) {
                return "the length of the name is greater than " + MAX_FILE_NAME_SIZE;
            }
        }
        return hasSep || !requireSeparator ? null : "must have at least one '.' separator";
    }

    /**
     * @see #validateName(String, boolean, boolean)
     */
    public static ParseResult validateName(ParseInput input, String name, boolean requireSeparator,
            boolean requireFilename) {
        final String errorMessage = validateName(name, requireSeparator, requireFilename);
        if (errorMessage != null) {
            return input.error(errorMessage);
        }
        return input.success(null);
    }

    /**
     * @return {@link PublicKey} of a given encoded public key.
     */
    public static PublicKey parsePublicKey(final String encodedPublicKey) {
        if (encodedPublicKey == null) {
            Slog.w(TAG, "Could not parse null public key");
            return null;
        }

        try {
            return parsePublicKey(Base64.decode(encodedPublicKey, Base64.DEFAULT));
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Could not parse verifier public key; invalid Base64");
            return null;
        }
    }

    /**
     * @return {@link PublicKey} of the given byte array of a public key.
     */
    public static PublicKey parsePublicKey(final byte[] publicKey) {
        if (publicKey == null) {
            Slog.w(TAG, "Could not parse null public key");
            return null;
        }

        final EncodedKeySpec keySpec;
        try {
            keySpec = new X509EncodedKeySpec(publicKey);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Could not parse verifier public key; invalid Base64");
            return null;
        }

        /* First try the key as an RSA key. */
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) {
            Slog.wtf(TAG, "Could not parse public key: RSA KeyFactory not included in build");
        } catch (InvalidKeySpecException e) {
            // Not a RSA public key.
        }

        /* Now try it as a ECDSA key. */
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) {
            Slog.wtf(TAG, "Could not parse public key: EC KeyFactory not included in build");
        } catch (InvalidKeySpecException e) {
            // Not a ECDSA public key.
        }

        /* Now try it as a DSA key. */
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("DSA");
            return keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) {
            Slog.wtf(TAG, "Could not parse public key: DSA KeyFactory not included in build");
        } catch (InvalidKeySpecException e) {
            // Not a DSA public key.
        }

        /* Not a supported key type */
        return null;
    }

    /**
     * Returns {@code true} if both the property name and value are empty or if the given system
     * property is set to the specified value. Properties can be one or more, and if properties are
     * more than one, they must be separated by comma, and count of names and values must be equal,
     * and also every given system property must be set to the corresponding value.
     * In all other cases, returns {@code false}
     */
    public static boolean checkRequiredSystemProperties(@Nullable String rawPropNames,
            @Nullable String rawPropValues) {
        if (TextUtils.isEmpty(rawPropNames) || TextUtils.isEmpty(rawPropValues)) {
            if (!TextUtils.isEmpty(rawPropNames) || !TextUtils.isEmpty(rawPropValues)) {
                // malformed condition - incomplete
                Slog.w(TAG, "Disabling overlay - incomplete property :'" + rawPropNames
                        + "=" + rawPropValues + "' - require both requiredSystemPropertyName"
                        + " AND requiredSystemPropertyValue to be specified.");
                return false;
            }
            // no valid condition set - so no exclusion criteria, overlay will be included.
            return true;
        }

        final String[] propNames = rawPropNames.split(",");
        final String[] propValues = rawPropValues.split(",");

        if (propNames.length != propValues.length) {
            Slog.w(TAG, "Disabling overlay - property :'" + rawPropNames
                    + "=" + rawPropValues + "' - require both requiredSystemPropertyName"
                    + " AND requiredSystemPropertyValue lists to have the same size.");
            return false;
        }
        for (int i = 0; i < propNames.length; i++) {
            // Check property value: make sure it is both set and equal to expected value
            final String currValue = SystemProperties.get(propNames[i]);
            if (!TextUtils.equals(currValue, propValues[i])) {
                return false;
            }
        }
        return true;
    }

    @CheckResult
    public static ParseResult<SigningDetails> getSigningDetails(ParseInput input,
            String baseCodePath, boolean skipVerify, boolean isStaticSharedLibrary,
            @NonNull SigningDetails existingSigningDetails, int targetSdk) {
        int minSignatureScheme = ApkSignatureVerifier.getMinimumSignatureSchemeVersionForTargetSdk(
                targetSdk);
        if (isStaticSharedLibrary) {
            // must use v2 signing scheme
            minSignatureScheme = SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2;
        }
        final ParseResult<SigningDetails> verified;
        if (skipVerify) {
            // systemDir APKs are already trusted, save time by not verifying; since the
            // signature is not verified and some system apps can have their V2+ signatures
            // stripped allow pulling the certs from the jar signature.
            verified = ApkSignatureVerifier.unsafeGetCertsWithoutVerification(input, baseCodePath,
                    SigningDetails.SignatureSchemeVersion.JAR);
        } else {
            verified = ApkSignatureVerifier.verify(input, baseCodePath, minSignatureScheme);
        }

        if (verified.isError()) {
            return input.error(verified);
        }

        // Verify that entries are signed consistently with the first pkg
        // we encountered. Note that for splits, certificates may have
        // already been populated during an earlier parse of a base APK.
        if (existingSigningDetails == SigningDetails.UNKNOWN) {
            return verified;
        } else {
            if (!Signature.areExactMatch(existingSigningDetails.getSignatures(),
                    verified.getResult().getSignatures())) {
                return input.error(INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                        baseCodePath + " has mismatched certificates");
            }

            return input.success(existingSigningDetails);
        }
    }

    /**
     * Computes the minSdkVersion to use at runtime. If the package is not compatible with this
     * platform, populates {@code outError[0]} with an error message.
     * <p>
     * If {@code minCode} is not specified, e.g. the value is {@code null}, then behavior varies
     * based on the {@code platformSdkVersion}:
     * <ul>
     * <li>If the platform SDK version is greater than or equal to the
     * {@code minVers}, returns the {@code mniVers} unmodified.
     * <li>Otherwise, returns -1 to indicate that the package is not
     * compatible with this platform.
     * </ul>
     * <p>
     * Otherwise, the behavior varies based on whether the current platform
     * is a pre-release version, e.g. the {@code platformSdkCodenames} array
     * has length > 0:
     * <ul>
     * <li>If this is a pre-release platform and the value specified by
     * {@code targetCode} is contained within the array of allowed pre-release
     * codenames, this method will return {@link Build.VERSION_CODES#CUR_DEVELOPMENT}.
     * <li>If this is a released platform, this method will return -1 to
     * indicate that the package is not compatible with this platform.
     * </ul>
     *
     * @param minVers              minSdkVersion number, if specified in the application manifest,
     *                             or 1 otherwise
     * @param minCode              minSdkVersion code, if specified in the application manifest, or
     *                             {@code null} otherwise
     * @param platformSdkVersion   platform SDK version number, typically Build.VERSION.SDK_INT
     * @param platformSdkCodenames array of allowed prerelease SDK codenames for this platform
     * @return the minSdkVersion to use at runtime if successful
     */
    public static ParseResult<Integer> computeMinSdkVersion(@IntRange(from = 1) int minVers,
            @Nullable String minCode, @IntRange(from = 1) int platformSdkVersion,
            @NonNull String[] platformSdkCodenames, @NonNull ParseInput input) {
        // If it's a release SDK, make sure we meet the minimum SDK requirement.
        if (minCode == null) {
            if (minVers <= platformSdkVersion) {
                return input.success(minVers);
            }

            // We don't meet the minimum SDK requirement.
            return input.error(PackageManager.INSTALL_FAILED_OLDER_SDK,
                    "Requires newer sdk version #" + minVers
                            + " (current version is #" + platformSdkVersion + ")");
        }

        // If it's a pre-release SDK and the codename matches this platform, we
        // definitely meet the minimum SDK requirement.
        if (matchTargetCode(platformSdkCodenames, minCode)) {
            return input.success(Build.VERSION_CODES.CUR_DEVELOPMENT);
        }

        // Otherwise, we're looking at an incompatible pre-release SDK.
        if (platformSdkCodenames.length > 0) {
            return input.error(PackageManager.INSTALL_FAILED_OLDER_SDK,
                    "Requires development platform " + minCode
                            + " (current platform is any of "
                            + Arrays.toString(platformSdkCodenames) + ")");
        } else {
            return input.error(PackageManager.INSTALL_FAILED_OLDER_SDK,
                    "Requires development platform " + minCode
                            + " but this is a release platform.");
        }
    }

    /**
     * Computes the targetSdkVersion to use at runtime. If the package is not compatible with this
     * platform, populates {@code outError[0]} with an error message.
     * <p>
     * If {@code targetCode} is not specified, e.g. the value is {@code null}, then the {@code
     * targetVers} will be returned unmodified.
     * <p>
     * When {@code allowUnknownCodenames} is false, the behavior varies based on whether the
     * current platform is a pre-release version, e.g. the {@code platformSdkCodenames} array has
     * length > 0:
     * <ul>
     * <li>If this is a pre-release platform and the value specified by
     * {@code targetCode} is contained within the array of allowed pre-release
     * codenames, this method will return {@link Build.VERSION_CODES#CUR_DEVELOPMENT}.
     * <li>If this is a released platform, this method will return -1 to
     * indicate that the package is not compatible with this platform.
     * </ul>
     * <p>
     * When {@code allowUnknownCodenames} is true, any codename that is not known (presumed to be
     * a codename announced after the build of the current device) is allowed and this method will
     * return {@link Build.VERSION_CODES#CUR_DEVELOPMENT}.
     *
     * @param targetVers            targetSdkVersion number, if specified in the application
     *                              manifest, or 0 otherwise
     * @param targetCode            targetSdkVersion code, if specified in the application manifest,
     *                              or {@code null} otherwise
     * @param platformSdkCodenames  array of allowed pre-release SDK codenames for this platform
     * @param allowUnknownCodenames allow unknown codenames, if true this method will accept unknown
     *                              (presumed to be future) codenames
     * @return the targetSdkVersion to use at runtime if successful
     */
    public static ParseResult<Integer> computeTargetSdkVersion(@IntRange(from = 0) int targetVers,
            @Nullable String targetCode, @NonNull String[] platformSdkCodenames,
            @NonNull ParseInput input, boolean allowUnknownCodenames) {
        // If it's a release SDK, return the version number unmodified.
        if (targetCode == null) {
            return input.success(targetVers);
        }

        if (allowUnknownCodenames && UnboundedSdkLevel.isAtMost(targetCode)) {
            return input.success(Build.VERSION_CODES.CUR_DEVELOPMENT);
        }

        // If it's a pre-release SDK and the codename matches this platform, it
        // definitely targets this SDK.
        if (matchTargetCode(platformSdkCodenames, targetCode)) {
            return input.success(Build.VERSION_CODES.CUR_DEVELOPMENT);
        }

        // Otherwise, we're looking at an incompatible pre-release SDK.
        if (platformSdkCodenames.length > 0) {
            return input.error(PackageManager.INSTALL_FAILED_OLDER_SDK,
                    "Requires development platform " + targetCode
                            + " (current platform is any of "
                            + Arrays.toString(platformSdkCodenames) + ")");
        } else {
            return input.error(PackageManager.INSTALL_FAILED_OLDER_SDK,
                    "Requires development platform " + targetCode
                            + " but this is a release platform.");
        }
    }

    /**
     * Computes the maxSdkVersion. If the package is not compatible with this platform, populates
     * {@code outError[0]} with an error message.
     * <p>
     * {@code maxVers} is compared against {@code platformSdkVersion}. If {@code maxVers} is less
     * than the {@code platformSdkVersion} then populates {@code outError[0]} with an error message.
     * Otherwise, it returns {@code maxVers} unmodified.
     *
     * @param maxVers maxSdkVersion number, if specified in the application manifest, or {@code
     *                Integer.MAX_VALUE} otherwise
     * @param platformSdkVersion   platform SDK version number, typically Build.VERSION.SDK_INT
     * @return the maxSdkVersion that was recognised or an error if the condition is not satisfied
     */
    public static ParseResult<Integer> computeMaxSdkVersion(@IntRange(from = 0) int maxVers,
            @IntRange(from = 1) int platformSdkVersion, @NonNull ParseInput input) {
        if (platformSdkVersion > maxVers) {
            return input.error(PackageManager.INSTALL_FAILED_NEWER_SDK,
                    "Requires max SDK version " + maxVers + " but is "
                            + platformSdkVersion);
        } else {
            return input.success(maxVers);
        }
    }

    /**
     * Matches a given {@code targetCode} against a set of release codeNames. Target codes can
     * either be of the form {@code [codename]}" (e.g {@code "Q"}) or of the form {@code
     * [codename].[fingerprint]} (e.g {@code "Q.cafebc561"}).
     */
    private static boolean matchTargetCode(@NonNull String[] codeNames,
            @NonNull String targetCode) {
        final String targetCodeName;
        final int targetCodeIdx = targetCode.indexOf('.');
        if (targetCodeIdx == -1) {
            targetCodeName = targetCode;
        } else {
            targetCodeName = targetCode.substring(0, targetCodeIdx);
        }
        return ArrayUtils.contains(codeNames, targetCodeName);
    }
}
