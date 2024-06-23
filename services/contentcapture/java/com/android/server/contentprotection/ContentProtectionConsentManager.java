/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.contentprotection;

import static android.view.contentprotection.flags.Flags.manageDevicePolicyEnabled;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.admin.DevicePolicyCache;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

/**
 * Manages consent for content protection.
 *
 * @hide
 */
public class ContentProtectionConsentManager {

    private static final String TAG = "ContentProtectionConsentManager";

    private static final String KEY_PACKAGE_VERIFIER_USER_CONSENT = "package_verifier_user_consent";

    private static final String KEY_CONTENT_PROTECTION_USER_CONSENT =
            "content_protection_user_consent";

    @NonNull private final ContentResolver mContentResolver;

    @NonNull private final DevicePolicyCache mDevicePolicyCache;

    @NonNull private final DevicePolicyManagerInternal mDevicePolicyManagerInternal;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @NonNull
    public final ContentObserver mContentObserver;

    private volatile boolean mCachedPackageVerifierConsent;

    private volatile boolean mCachedContentProtectionUserConsent;

    public ContentProtectionConsentManager(
            @NonNull Handler handler,
            @NonNull ContentResolver contentResolver,
            @NonNull DevicePolicyCache devicePolicyCache) {
        mContentResolver = contentResolver;
        mDevicePolicyCache = devicePolicyCache;
        mDevicePolicyManagerInternal = LocalServices.getService(DevicePolicyManagerInternal.class);
        mContentObserver = new SettingsObserver(handler);

        registerSettingsGlobalObserver(KEY_PACKAGE_VERIFIER_USER_CONSENT);
        registerSettingsGlobalObserver(KEY_CONTENT_PROTECTION_USER_CONSENT);
        readPackageVerifierConsentGranted();
        readContentProtectionUserConsentGranted();
    }

    /** Returns true if the consent is ultimately granted. */
    public boolean isConsentGranted(@UserIdInt int userId) {
        return mCachedPackageVerifierConsent && isContentProtectionConsentGranted(userId);
    }

    /**
     * Not always cached internally and can be expensive, when possible prefer to use {@link
     * #mCachedPackageVerifierConsent} instead.
     */
    private boolean isPackageVerifierConsentGranted() {
        return Settings.Global.getInt(
                        mContentResolver, KEY_PACKAGE_VERIFIER_USER_CONSENT, /* def= */ 0)
                >= 1;
    }

    /**
     * Not always cached internally and can be expensive, when possible prefer to use {@link
     * #mCachedContentProtectionUserConsent} instead.
     */
    private boolean isContentProtectionUserConsentGranted() {
        return Settings.Global.getInt(
                        mContentResolver, KEY_CONTENT_PROTECTION_USER_CONSENT, /* def= */ 0)
                >= 0;
    }

    private void readPackageVerifierConsentGranted() {
        mCachedPackageVerifierConsent = isPackageVerifierConsentGranted();
    }

    private void readContentProtectionUserConsentGranted() {
        mCachedContentProtectionUserConsent = isContentProtectionUserConsentGranted();
    }

    /** Always cached internally, cheap and safe to use. */
    private boolean isUserOrganizationManaged(@UserIdInt int userId) {
        return mDevicePolicyManagerInternal.isUserOrganizationManaged(userId);
    }

    /** Always cached internally, cheap and safe to use. */
    private boolean isContentProtectionPolicyGranted(@UserIdInt int userId) {
        if (!manageDevicePolicyEnabled()) {
            return false;
        }

        @DevicePolicyManager.ContentProtectionPolicy
        int policy = mDevicePolicyCache.getContentProtectionPolicy(userId);

        return switch (policy) {
            case DevicePolicyManager.CONTENT_PROTECTION_ENABLED -> true;
            case DevicePolicyManager.CONTENT_PROTECTION_NOT_CONTROLLED_BY_POLICY ->
                    mCachedContentProtectionUserConsent;
            default -> false;
        };
    }

    /** Always cached internally, cheap and safe to use. */
    private boolean isContentProtectionConsentGranted(@UserIdInt int userId) {
        if (!manageDevicePolicyEnabled()) {
            return mCachedContentProtectionUserConsent && !isUserOrganizationManaged(userId);
        }

        return isUserOrganizationManaged(userId)
                ? isContentProtectionPolicyGranted(userId)
                : mCachedContentProtectionUserConsent;
    }

    private void registerSettingsGlobalObserver(@NonNull String key) {
        registerSettingsObserver(Settings.Global.getUriFor(key));
    }

    private void registerSettingsObserver(@NonNull Uri uri) {
        mContentResolver.registerContentObserver(
                uri, /* notifyForDescendants= */ false, mContentObserver, UserHandle.USER_ALL);
    }

    private final class SettingsObserver extends ContentObserver {

        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, @Nullable Uri uri, @UserIdInt int userId) {
            if (uri == null) {
                return;
            }
            final String property = uri.getLastPathSegment();
            if (property == null) {
                return;
            }
            switch (property) {
                case KEY_PACKAGE_VERIFIER_USER_CONSENT:
                    readPackageVerifierConsentGranted();
                    return;
                case KEY_CONTENT_PROTECTION_USER_CONSENT:
                    readContentProtectionUserConsentGranted();
                    return;
                default:
                    Slog.w(TAG, "Ignoring unexpected property: " + property);
            }
        }
    }
}
