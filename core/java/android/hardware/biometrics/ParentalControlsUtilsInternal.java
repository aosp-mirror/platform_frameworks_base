/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware.biometrics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.app.supervision.SupervisionManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * "Base" functionality. For settings-specific functionality (which may rely on this base
 * functionality), see {@link com.android.settings.biometrics.ParentalControlsUtils}
 * @hide
 */
public class ParentalControlsUtilsInternal {

    private static final String TEST_ALWAYS_REQUIRE_CONSENT_PACKAGE =
            "android.hardware.biometrics.ParentalControlsUtilsInternal.require_consent_package";
    private static final String TEST_ALWAYS_REQUIRE_CONSENT_CLASS =
            "android.hardware.biometrics.ParentalControlsUtilsInternal.require_consent_class";

    /**
     * ComponentName of Parent Consent activity for testing Biometric authentication disabled by
     * Parental Controls.
     *
     * <p>Component could be defined by values of {@link #TEST_ALWAYS_REQUIRE_CONSENT_PACKAGE} and
     * {@link #TEST_ALWAYS_REQUIRE_CONSENT_CLASS} Secure settings.
     */
    public static ComponentName getTestComponentName(@NonNull Context context, int userId) {
        if (Build.IS_USERDEBUG || Build.IS_ENG) {
            final String pkg = Settings.Secure.getStringForUser(context.getContentResolver(),
                    TEST_ALWAYS_REQUIRE_CONSENT_PACKAGE, userId);
            final String cls = Settings.Secure.getStringForUser(context.getContentResolver(),
                    TEST_ALWAYS_REQUIRE_CONSENT_CLASS, userId);
            return pkg != null && cls != null ? new ComponentName(pkg, cls) : null;
        }
        return null;
    }

    /** @return true if parental consent is required in order for biometric sensors to be used. */
    public static boolean parentConsentRequired(
            @NonNull Context context,
            @NonNull DevicePolicyManager dpm,
            @Nullable SupervisionManager sm,
            @BiometricAuthenticator.Modality int modality,
            @NonNull UserHandle userHandle) {
        if (getTestComponentName(context, userHandle.getIdentifier()) != null) {
            return true;
        }

        return parentConsentRequired(dpm, sm, modality, userHandle);
    }

    /**
     * @return true if parental consent is required in order for biometric sensors to be used.
     */
    public static boolean parentConsentRequired(
            @NonNull DevicePolicyManager dpm,
            @Nullable SupervisionManager sm,
            @BiometricAuthenticator.Modality int modality,
            @NonNull UserHandle userHandle) {
        final int keyguardDisabledFeatures;

        if (android.app.supervision.flags.Flags.deprecateDpmSupervisionApis()) {
            if (sm != null && !sm.isSupervisionEnabledForUser(userHandle.getIdentifier())) {
                return false;
            }
            // Check for keyguard features disabled by any admin.
            keyguardDisabledFeatures = dpm.getKeyguardDisabledFeatures(/* admin= */ null);
        } else {
            final ComponentName cn = getSupervisionComponentName(dpm, userHandle);
            if (cn == null) {
                return false;
            }
            keyguardDisabledFeatures = dpm.getKeyguardDisabledFeatures(cn);
        }

        final boolean dpmFpDisabled = containsFlag(keyguardDisabledFeatures,
                DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT);
        final boolean dpmFaceDisabled = containsFlag(keyguardDisabledFeatures,
                DevicePolicyManager.KEYGUARD_DISABLE_FACE);
        final boolean dpmIrisDisabled = containsFlag(keyguardDisabledFeatures,
                DevicePolicyManager.KEYGUARD_DISABLE_IRIS);

        final boolean consentRequired;
        if (containsFlag(modality, BiometricAuthenticator.TYPE_FINGERPRINT) && dpmFpDisabled) {
            consentRequired = true;
        } else if (containsFlag(modality, BiometricAuthenticator.TYPE_FACE) && dpmFaceDisabled) {
            consentRequired = true;
        } else if (containsFlag(modality, BiometricAuthenticator.TYPE_IRIS) && dpmIrisDisabled) {
            consentRequired = true;
        } else {
            consentRequired = false;
        }

        return consentRequired;
    }

    /** @deprecated Use {@link SupervisionManager} to check for supervision. */
    @Nullable
    @Deprecated
    public static ComponentName getSupervisionComponentName(@NonNull DevicePolicyManager dpm,
            @NonNull UserHandle userHandle) {
        return dpm.getProfileOwnerOrDeviceOwnerSupervisionComponent(userHandle);
    }

    private static boolean containsFlag(int haystack, int needle) {
        return (haystack & needle) != 0;
    }
}
