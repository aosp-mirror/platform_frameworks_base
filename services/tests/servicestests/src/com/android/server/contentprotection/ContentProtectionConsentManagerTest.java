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

import static android.app.admin.DevicePolicyManager.CONTENT_PROTECTION_DISABLED;
import static android.app.admin.DevicePolicyManager.CONTENT_PROTECTION_ENABLED;
import static android.app.admin.DevicePolicyManager.CONTENT_PROTECTION_NOT_CONTROLLED_BY_POLICY;
import static android.view.contentprotection.flags.Flags.FLAG_MANAGE_DEVICE_POLICY_ENABLED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyCache;
import android.app.admin.DevicePolicyManagerInternal;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.testing.TestableContentResolver;
import android.testing.TestableContext;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;

import org.junit.Before;
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

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private final TestableContentResolver mTestableContentResolver =
            mTestableContext.getContentResolver();

    @Mock private DevicePolicyManagerInternal mMockDevicePolicyManagerInternal;

    @Mock private DevicePolicyCache mMockDevicePolicyCache;

    @Before
    public void setup() {
        setupLocalService(DevicePolicyManagerInternal.class, mMockDevicePolicyManagerInternal);
    }

    @Test
    public void isConsentGranted_policyFlagDisabled_packageVerifierNotGranted() {
        mSetFlagsRule.disableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_FALSE, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockDevicePolicyManagerInternal);
        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void isConsentGranted_policyFlagEnabled_packageVerifierNotGranted() {
        mSetFlagsRule.enableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_FALSE, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockDevicePolicyManagerInternal);
        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void isConsentGranted_policyFlagDisabled_contentProtectionNotGranted() {
        mSetFlagsRule.disableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_FALSE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockDevicePolicyManagerInternal);
        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void isConsentGranted_policyFlagDisabled_packageVerifierGranted_userNotManaged() {
        mSetFlagsRule.disableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isTrue();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void isConsentGranted_policyFlagDisabled_packageVerifierGranted_userManaged() {
        mSetFlagsRule.disableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        when(mMockDevicePolicyManagerInternal.isUserOrganizationManaged(TEST_USER_ID))
                .thenReturn(true);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void isConsentGranted_policyFlagEnabled_packageVerifierGranted_userNotManaged_contentProtectionNotGranted() {
        mSetFlagsRule.enableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_FALSE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void isConsentGranted_policyFlagEnabled_packageVerifierGranted_userNotManaged_contentProtectionGranted() {
        mSetFlagsRule.enableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isTrue();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void isConsentGranted_policyFlagEnabled_packageVerifierGranted_userManaged_policyDisabled() {
        mSetFlagsRule.enableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        when(mMockDevicePolicyManagerInternal.isUserOrganizationManaged(TEST_USER_ID))
                .thenReturn(true);
        when(mMockDevicePolicyCache.getContentProtectionPolicy(TEST_USER_ID))
                .thenReturn(CONTENT_PROTECTION_DISABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
        verify(mMockDevicePolicyCache).getContentProtectionPolicy(TEST_USER_ID);
    }

    @Test
    public void isConsentGranted_policyFlagEnabled_packageVerifierGranted_userManaged_policyEnabled() {
        mSetFlagsRule.enableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        when(mMockDevicePolicyManagerInternal.isUserOrganizationManaged(TEST_USER_ID))
                .thenReturn(true);
        when(mMockDevicePolicyCache.getContentProtectionPolicy(TEST_USER_ID))
                .thenReturn(CONTENT_PROTECTION_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_FALSE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isTrue();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
        verify(mMockDevicePolicyCache).getContentProtectionPolicy(TEST_USER_ID);
    }

    @Test
    public void isConsentGranted_policyFlagEnabled_packageVerifierGranted_userManaged_policyNotControlled_contentProtectionGranted() {
        mSetFlagsRule.enableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        when(mMockDevicePolicyManagerInternal.isUserOrganizationManaged(TEST_USER_ID))
                .thenReturn(true);
        when(mMockDevicePolicyCache.getContentProtectionPolicy(TEST_USER_ID))
                .thenReturn(CONTENT_PROTECTION_NOT_CONTROLLED_BY_POLICY);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isTrue();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
        verify(mMockDevicePolicyCache).getContentProtectionPolicy(TEST_USER_ID);
    }

    @Test
    public void isConsentGranted_policyFlagEnabled_packageVerifierGranted_userManaged_policyNotControlled_contentProtectionNotGranted() {
        mSetFlagsRule.enableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        when(mMockDevicePolicyManagerInternal.isUserOrganizationManaged(TEST_USER_ID))
                .thenReturn(true);
        when(mMockDevicePolicyCache.getContentProtectionPolicy(TEST_USER_ID))
                .thenReturn(CONTENT_PROTECTION_NOT_CONTROLLED_BY_POLICY);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_FALSE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
        verify(mMockDevicePolicyCache).getContentProtectionPolicy(TEST_USER_ID);
    }

    @Test
    public void isConsentGranted_policyFlagEnabled_packageVerifierGranted_userManaged_policyNotControlled_contentProtectionDefault() {
        mSetFlagsRule.enableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        when(mMockDevicePolicyManagerInternal.isUserOrganizationManaged(TEST_USER_ID))
                .thenReturn(true);
        when(mMockDevicePolicyCache.getContentProtectionPolicy(TEST_USER_ID))
                .thenReturn(CONTENT_PROTECTION_NOT_CONTROLLED_BY_POLICY);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_DEFAULT);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isTrue();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
        verify(mMockDevicePolicyCache).getContentProtectionPolicy(TEST_USER_ID);
    }

    @Test
    public void isConsentGranted_policyFlagDisabled_packageVerifierDefault() {
        mSetFlagsRule.disableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_DEFAULT, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockDevicePolicyManagerInternal);
        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void isConsentGranted_policyFlagEnabled_packageVerifierDefault() {
        mSetFlagsRule.enableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_DEFAULT, VALUE_TRUE);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockDevicePolicyManagerInternal);
        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void isConsentGranted_policyFlagDisabled_contentProtectionDefault() {
        mSetFlagsRule.disableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_DEFAULT);

        boolean actual = manager.isConsentGranted(TEST_USER_ID);

        assertThat(actual).isTrue();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);
        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void contentObserver_policyFlagDisabled_packageVerifier() {
        mSetFlagsRule.disableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_FALSE, VALUE_TRUE);

        boolean firstActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(firstActual).isFalse();
        verify(mMockDevicePolicyManagerInternal, never()).isUserOrganizationManaged(anyInt());

        putGlobalSettings(KEY_PACKAGE_VERIFIER_USER_CONSENT, VALUE_TRUE);
        boolean secondActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(secondActual).isFalse();
        verify(mMockDevicePolicyManagerInternal, never()).isUserOrganizationManaged(anyInt());

        notifyContentObserver(manager, URI_PACKAGE_VERIFIER_USER_CONSENT);
        boolean thirdActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(thirdActual).isTrue();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);

        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void contentObserver_policyFlagEnabled_packageVerifier() {
        mSetFlagsRule.enableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_FALSE, VALUE_TRUE);

        boolean firstActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(firstActual).isFalse();
        verify(mMockDevicePolicyManagerInternal, never()).isUserOrganizationManaged(anyInt());

        putGlobalSettings(KEY_PACKAGE_VERIFIER_USER_CONSENT, VALUE_TRUE);
        boolean secondActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(secondActual).isFalse();
        verify(mMockDevicePolicyManagerInternal, never()).isUserOrganizationManaged(anyInt());

        notifyContentObserver(manager, URI_PACKAGE_VERIFIER_USER_CONSENT);
        boolean thirdActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(thirdActual).isTrue();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);

        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void contentObserver_policyFlagDisabled_contentProtection() {
        mSetFlagsRule.disableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_FALSE);

        boolean firstActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(firstActual).isFalse();
        verify(mMockDevicePolicyManagerInternal, never()).isUserOrganizationManaged(anyInt());

        putGlobalSettings(KEY_CONTENT_PROTECTION_USER_CONSENT, VALUE_TRUE);
        boolean secondActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(secondActual).isFalse();
        verify(mMockDevicePolicyManagerInternal, never()).isUserOrganizationManaged(anyInt());

        notifyContentObserver(manager, URI_CONTENT_PROTECTION_USER_CONSENT);
        boolean thirdActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(thirdActual).isTrue();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);

        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    @Test
    public void contentObserver_policyFlagEnabled_contentProtection() {
        mSetFlagsRule.enableFlags(FLAG_MANAGE_DEVICE_POLICY_ENABLED);
        ContentProtectionConsentManager manager =
                createContentProtectionConsentManager(VALUE_TRUE, VALUE_FALSE);

        boolean firstActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(firstActual).isFalse();
        verify(mMockDevicePolicyManagerInternal).isUserOrganizationManaged(TEST_USER_ID);

        putGlobalSettings(KEY_CONTENT_PROTECTION_USER_CONSENT, VALUE_TRUE);
        boolean secondActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(secondActual).isFalse();
        verify(mMockDevicePolicyManagerInternal, times(2)).isUserOrganizationManaged(TEST_USER_ID);

        notifyContentObserver(manager, URI_CONTENT_PROTECTION_USER_CONSENT);
        boolean thirdActual = manager.isConsentGranted(TEST_USER_ID);
        assertThat(thirdActual).isTrue();
        verify(mMockDevicePolicyManagerInternal, times(3)).isUserOrganizationManaged(TEST_USER_ID);

        verifyZeroInteractions(mMockDevicePolicyCache);
    }

    private void putGlobalSettings(String key, int value) {
        Settings.Global.putInt(mTestableContentResolver, key, value);
    }

    private void notifyContentObserver(ContentProtectionConsentManager manager, Uri uri) {
        // Observer has to be called manually, mTestableContentResolver is not propagating
        manager.mContentObserver.onChange(/* selfChange= */ false, uri, TEST_USER_ID);
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
        return new ContentProtectionConsentManager(
                new Handler(Looper.getMainLooper()),
                mTestableContentResolver,
                mMockDevicePolicyCache);
    }

    private <T> void setupLocalService(Class<T> clazz, T service) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, service);
    }
}
