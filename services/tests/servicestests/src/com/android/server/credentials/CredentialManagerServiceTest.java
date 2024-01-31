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
package com.android.server.credentials;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.cert.CertificateException;
import java.util.HashSet;
import java.util.Set;

/** atest FrameworksServicesTests:com.android.server.credentials.CredentialManagerServiceTest */
@RunWith(AndroidJUnit4.class)
@SmallTest
public final class CredentialManagerServiceTest {

    Context mContext = null;
    MockSettingsWrapper mSettingsWrapper = null;

    @Before
    public void setUp() throws CertificateException {
        mContext = ApplicationProvider.getApplicationContext();
        mSettingsWrapper = new MockSettingsWrapper(mContext);
    }

    @Test
    public void getStoredProviders_emptyValue_success() {
        Set<String> providers = CredentialManagerService.getStoredProviders("", "");
        assertThat(providers.size()).isEqualTo(0);
    }

    @Test
    public void getStoredProviders_nullValue_success() {
        Set<String> providers = CredentialManagerService.getStoredProviders(null, null);
        assertThat(providers.size()).isEqualTo(0);
    }

    @Test
    public void getStoredProviders_success() {
        Set<String> providers =
                CredentialManagerService.getStoredProviders(
                        "com.example.test/.TestActivity:com.example.test/.TestActivity2:"
                                + "com.example.test2/.TestActivity:blank",
                        "com.example.test");
        assertThat(providers.size()).isEqualTo(1);
        assertThat(providers.contains("com.example.test2/com.example.test2.TestActivity")).isTrue();
    }

    @Test
    public void onProviderRemoved_success() {
        setSettingsKey(
                Settings.Secure.AUTOFILL_SERVICE,
                CredentialManagerService.AUTOFILL_PLACEHOLDER_VALUE);
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE,
                "com.example.test/com.example.test.TestActivity:com.example.test2/com.example.test2.TestActivity");
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
                "com.example.test/com.example.test.TestActivity");

        CredentialManagerService.updateProvidersWhenPackageRemoved(
                mSettingsWrapper, "com.example.test");

        assertThat(getSettingsKey(Settings.Secure.AUTOFILL_SERVICE)).isEqualTo("");
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE))
                .isEqualTo("com.example.test2/com.example.test2.TestActivity");
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY)).isEqualTo("");
    }

    @Test
    public void onProviderRemoved_notPrimaryRemoved_success() {
        final String testCredentialPrimaryValue = "com.example.test/com.example.test.TestActivity";
        final String testCredentialValue =
                "com.example.test/com.example.test.TestActivity:com.example.test2/com.example.test2.TestActivity";

        setSettingsKey(
                Settings.Secure.AUTOFILL_SERVICE,
                CredentialManagerService.AUTOFILL_PLACEHOLDER_VALUE);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, testCredentialValue);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, testCredentialPrimaryValue);

        CredentialManagerService.updateProvidersWhenPackageRemoved(
                mSettingsWrapper, "com.example.test3");

        // Since the provider removed was not a primary provider then we should do nothing.
        assertThat(getSettingsKey(Settings.Secure.AUTOFILL_SERVICE))
                .isEqualTo(CredentialManagerService.AUTOFILL_PLACEHOLDER_VALUE);
        assertCredentialPropertyEquals(
                getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE), testCredentialValue);
        assertCredentialPropertyEquals(
                getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY),
                testCredentialPrimaryValue);
    }

    @Test
    public void onProviderRemoved_isAlsoAutofillProvider_success() {
        setSettingsKey(
                Settings.Secure.AUTOFILL_SERVICE,
                "com.example.test/com.example.test.AutofillProvider");
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE,
                "com.example.test/com.example.test.TestActivity:com.example.test2/com.example.test2.TestActivity");
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
                "com.example.test/com.example.test.TestActivity");

        CredentialManagerService.updateProvidersWhenPackageRemoved(
                mSettingsWrapper, "com.example.test");

        assertThat(getSettingsKey(Settings.Secure.AUTOFILL_SERVICE)).isEqualTo("");
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE))
                .isEqualTo("com.example.test2/com.example.test2.TestActivity");
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY)).isEqualTo("");
    }

    @Test
    public void onProviderRemoved_notPrimaryRemoved_isAlsoAutofillProvider_success() {
        final String testCredentialPrimaryValue = "com.example.test/com.example.test.TestActivity";
        final String testCredentialValue =
                "com.example.test/com.example.test.TestActivity:com.example.test2/com.example.test2.TestActivity";
        final String testAutofillValue = "com.example.test/com.example.test.TestAutofillActivity";

        setSettingsKey(Settings.Secure.AUTOFILL_SERVICE, testAutofillValue);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, testCredentialValue);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, testCredentialPrimaryValue);

        CredentialManagerService.updateProvidersWhenPackageRemoved(
                mSettingsWrapper, "com.example.test3");

        // Since the provider removed was not a primary provider then we should do nothing.
        assertCredentialPropertyEquals(
                getSettingsKey(Settings.Secure.AUTOFILL_SERVICE), testAutofillValue);
        assertCredentialPropertyEquals(
                getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE), testCredentialValue);
        assertCredentialPropertyEquals(
                getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY),
                testCredentialPrimaryValue);
    }

    private void assertCredentialPropertyEquals(String actualValue, String newValue) {
        Set<ComponentName> actualValueSet = new HashSet<>();
        for (String rawComponentName : actualValue.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(rawComponentName);
            if (cn != null) {
                actualValueSet.add(cn);
            }
        }

        Set<ComponentName> newValueSet = new HashSet<>();
        for (String rawComponentName : newValue.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(rawComponentName);
            if (cn != null) {
                newValueSet.add(cn);
            }
        }

        assertThat(actualValueSet).isEqualTo(newValueSet);
    }

    private void setSettingsKey(String name, String value) {
        assertThat(
                        mSettingsWrapper.putStringForUser(
                                name, value, UserHandle.myUserId(), true))
                .isTrue();
    }

    private String getSettingsKey(String name) {
        return mSettingsWrapper.getStringForUser(name, UserHandle.myUserId());
    }

    private static final class MockSettingsWrapper
            extends CredentialManagerService.SettingsWrapper {

        MockSettingsWrapper(@NonNull Context context) {
            super(context);
        }

        /** Updates the string value of a system setting */
        @Override
        public boolean putStringForUser(
                String name,
                String value,
                int userHandle,
                boolean overrideableByRestore) {
            // This will ensure that when the settings putStringForUser method is called by
            // CredentialManagerService that the overrideableByRestore bit is true.
            assertThat(overrideableByRestore).isTrue();

            return Settings.Secure.putStringForUser(getContentResolver(), name, value, userHandle);
        }
    }
}
