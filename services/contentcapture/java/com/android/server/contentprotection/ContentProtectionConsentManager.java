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

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

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

    @NonNull private final DevicePolicyManagerInternal mDevicePolicyManagerInternal;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @NonNull
    public final ContentObserver mContentObserver;

    private volatile boolean mCachedPackageVerifierConsent;

    private volatile boolean mCachedContentProtectionConsent;

    public ContentProtectionConsentManager(
            @NonNull Handler handler,
            @NonNull ContentResolver contentResolver,
            @NonNull DevicePolicyManagerInternal devicePolicyManagerInternal) {
        mContentResolver = contentResolver;
        mDevicePolicyManagerInternal = devicePolicyManagerInternal;
        mContentObserver = new SettingsObserver(handler);

        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(KEY_PACKAGE_VERIFIER_USER_CONSENT),
                /* notifyForDescendants= */ false,
                mContentObserver,
                UserHandle.USER_ALL);

        mCachedPackageVerifierConsent = isPackageVerifierConsentGranted();
        mCachedContentProtectionConsent = isContentProtectionConsentGranted();
    }

    /**
     * Returns true if all the consents are granted
     */
    public boolean isConsentGranted(@UserIdInt int userId) {
        return mCachedPackageVerifierConsent
                && mCachedContentProtectionConsent
                && !isUserOrganizationManaged(userId);
    }

    private boolean isPackageVerifierConsentGranted() {
        // Not always cached internally
        return Settings.Global.getInt(
                        mContentResolver, KEY_PACKAGE_VERIFIER_USER_CONSENT, /* def= */ 0)
                >= 1;
    }

    private boolean isContentProtectionConsentGranted() {
        // Not always cached internally
        return Settings.Global.getInt(
                        mContentResolver, KEY_CONTENT_PROTECTION_USER_CONSENT, /* def= */ 0)
                >= 0;
    }

    private boolean isUserOrganizationManaged(@UserIdInt int userId) {
        // Cached internally
        return mDevicePolicyManagerInternal.isUserOrganizationManaged(userId);
    }

    private final class SettingsObserver extends ContentObserver {

        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, @UserIdInt int userId) {
            final String property = uri.getLastPathSegment();
            if (property == null) {
                return;
            }
            switch (property) {
                case KEY_PACKAGE_VERIFIER_USER_CONSENT:
                    mCachedPackageVerifierConsent = isPackageVerifierConsentGranted();
                    return;
                case KEY_CONTENT_PROTECTION_USER_CONSENT:
                    mCachedContentProtectionConsent = isContentProtectionConsentGranted();
                    return;
                default:
                    Slog.w(TAG, "Ignoring unexpected property: " + property);
            }
        }
    }
}
