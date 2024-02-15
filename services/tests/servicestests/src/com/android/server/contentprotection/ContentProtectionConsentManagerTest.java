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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManagerInternal;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.TestableContentResolver;
import android.testing.TestableContext;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Test for {@link ContentProtectionConsentManager}.
 *
 * <p>Run with: {@code atest
 * FrameworksServicesTests:com.android.server.contentprotection.ContentProtectionConsentManagerTest}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ContentProtectionConsentManagerTest {

    private static final String KEY_PACKAGE_VERIFIER_USER_CONSENT = "package_verifier_user_consent";

    private static final String KEY_CONTENT_PROTECTION_USER_CONSENT =
            "content_protection_user_consent";

    private static final Uri URI_PACKAGE_VERIFIER_USER_CONSENT =
            Settings.Global.getUriFor(KEY_PACKAGE_VERIFIER_USER_CONSENT);

    private static final Uri URI_CONTENT_PROTECTION_USER_CONSENT =
            Settings.Global.getUriFor(KEY_CONTENT_PROTECTION_USER_CONSENT);

    private static final int VALUE_TRUE = 1;

    private static final int VALUE_FALSE = -1;

    private static final int VALUE_DEFAULT = 0;

    private static final int TEST_USER_ID = 1234;

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Rule
    public final TestableContext mTestableContext =
            new TestableContext(ApplicationProvider.getApplicationContext());

    private final TestableContentResolver mTestableContentResolver =
            mTestableContext.getContentResolver();

    @Mock private ContentResolver mMockContentResolver;

    @Mock private DevicePolicyManagerInternal mMockDevicePolicyManagerInternal;

    @Test
    public void constructor_registersContentObserver() {
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(mMockContentResolver);

        assertThat(manager.mContentObserver).isNotNull();
        verify(mMockContentResolver)
                .registerContentObserver(
                        URI_PACKAGE_VERIFIER_USER_CONSENT,
                        /* notifyForDescendants= */ false,
                        manager.mContentObserver,
                        UserHandle.USER_ALL);
    }

    @Test
    public void isConsentGranted_packageVerifierNotGranted() {
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_FALSE, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockDevicePolicyManagerInternal);
    }

    @Test
    public void isConsentGranted_contentProtectionNotGranted() {
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_FALSE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockDevicePolicyManagerInternal);
    }

    @Test
    public void isConsentGranted_packageVerifierGranted_userNotManaged() {
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isTrue();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
    }

    @Test
    public void isConsentGranted_packageVerifierGranted_userManaged() {
        when(mMockDevicePolicyManagerInternal.isUserOrganizationManaged(TEST_USER_ID))
                .thenReturn(true);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
    }

    @Test
    public void isConsentGranted_packageVerifierDefault() {
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_DEFAULT, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockDevicePolicyManagerInternal);
    }

    @Test
    public void isConsentGranted_contentProtectionDefault() {
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_DEFAULT);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isTrue();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
    }

    @Test
    public void contentObserver_packageVerifier() {
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_DEFAULT);
        boolean firstActual = manager.isConsentGranted(TEST_USER_ID);

        notifyContentObserver(
                manager,
                URI_PACKAGE_VERIFIER_USER_CONSENT,
                KEY_PACKAGE_VERIFIER_USER_CONSENT,
                VALUE_FALSE);
        boolean secondActual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(firstActual).isTrue();
        assertThat(secondActual).isFalse();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
    }

    @Test
    public void contentObserver_contentProtection() {
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_DEFAULT);
        boolean firstActual = manager.isConsentGranted(TEST_USER_ID);

        notifyContentObserver(
                manager,
                URI_CONTENT_PROTECTION_USER_CONSENT,
                KEY_CONTENT_PROTECTION_USER_CONSENT,
                VALUE_FALSE);
        boolean secondActual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(firstActual).isTrue();
        assertThat(secondActual).isFalse();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
    }

    private void notifyContentObserver(
            ContentProtectionConsentManager manager, Uri uri, String key, int value) {
        Settings.Global.putInt(mTestableContentResolver, key, value);
        // Observer has to be called manually, mTestableContentResolver is not propagating
        manager.mContentObserver.onChange(/* selfChange= */ false, uri, TEST_USER_ID);
    }

    private ContentProtectionConsentManager createContentProtectionConsentManager(
            ContentResolver contentResolver) {
        return new ContentProtectionConsentManager(
                new Handler(Looper.getMainLooper()),
                contentResolver,
                mMockDevicePolicyManagerInternal);
    }

    private ContentProtectionConsentManager createContentProtectionConsentManager(
            int valuePackageVerifierUserConsent, int valueContentProtectionUserConsent) {
        Settings.Global.putInt(
                mTestableContentResolver,
                KEY_PACKAGE_VERIFIER_USER_CONSENT,
                valuePackageVerifierUserConsent);
        Settings.Global.putInt(
                mTestableContentResolver,
                KEY_CONTENT_PROTECTION_USER_CONSENT,
                valueContentProtectionUserConsent);
        return createContentProtectionConsentManager(mTestableContentResolver);
    }
}
