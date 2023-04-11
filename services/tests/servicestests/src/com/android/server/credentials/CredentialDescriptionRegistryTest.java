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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.credentials.CredentialDescription;
import android.credentials.RegisterCredentialDescriptionRequest;
import android.service.credentials.CredentialEntry;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tests for CredentialDescriptionRegistry.
 *
 * atest FrameworksServicesTests:com.android.server.credentials.CredentialDescriptionRegistryTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class CredentialDescriptionRegistryTest {

    private static final int USER_ID_1 = 1;
    private static final int USER_ID_2 = 2;
    private static final String CALLING_PACKAGE_NAME = "com.credman.app";
    private static final String CALLING_PACKAGE_NAME_2 = "com.credman.app2";
    private static final String MDOC_CREDENTIAL_TYPE = "MDOC";
    private static final String PASSKEY_CREDENTIAL_TYPE = "PASSKEY";
    private static final HashSet<String> FLATTENED_REGISTRY = new HashSet<>(List.of(
            "FLATTENED_REQ", "FLATTENED_REQ123", "FLATTENED_REQa"));
    private static final HashSet<String> FLATTENED_REGISTRY_2 =
            new HashSet<>(List.of("FLATTENED_REQ_2"));
    private static final HashSet<String> FLATTENED_REQUEST =
            new HashSet<>(List.of("FLATTENED_REQ;FLATTENED_REQ123"));

    private CredentialDescriptionRegistry mCredentialDescriptionRegistry;
    private CredentialEntry mEntry;
    private CredentialEntry mEntry2;
    private CredentialEntry mEntry3;

    @SuppressWarnings("GuardedBy")
    @Before
    public void setUp() {
        CredentialDescriptionRegistry.clearAllSessions();
        mEntry = mock(CredentialEntry.class);
        mEntry2 = mock(CredentialEntry.class);
        mEntry3 = mock(CredentialEntry.class);
        when(mEntry.getType()).thenReturn(MDOC_CREDENTIAL_TYPE);
        when(mEntry2.getType()).thenReturn(MDOC_CREDENTIAL_TYPE);
        when(mEntry3.getType()).thenReturn(PASSKEY_CREDENTIAL_TYPE);
        mCredentialDescriptionRegistry = CredentialDescriptionRegistry.forUser(USER_ID_1);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testForUser_createsUniqueInstanceForEachUserID() {
        final CredentialDescriptionRegistry secondRegistry = CredentialDescriptionRegistry
                .forUser(USER_ID_2);

        assertThat(mCredentialDescriptionRegistry).isNotSameInstanceAs(secondRegistry);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testForUser_returnsSameInstanceForSameUserID() {
        final CredentialDescriptionRegistry secondRegistry = CredentialDescriptionRegistry
                .forUser(USER_ID_1);

        assertThat(mCredentialDescriptionRegistry).isSameInstanceAs(secondRegistry);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testClearUserSession_removesExistingSessionForUserID() {
        CredentialDescriptionRegistry.clearUserSession(USER_ID_1);
        final CredentialDescriptionRegistry secondRegistry = CredentialDescriptionRegistry
                .forUser(USER_ID_1);

        assertThat(mCredentialDescriptionRegistry).isNotSameInstanceAs(secondRegistry);
    }

    @Test
    public void testEvictProvider_existingProviders_succeeds() {
        final CredentialDescription credentialDescription =
                new CredentialDescription(MDOC_CREDENTIAL_TYPE, FLATTENED_REGISTRY,
                        Collections.emptyList());
        final RegisterCredentialDescriptionRequest registerCredentialDescriptionRequest =
                new RegisterCredentialDescriptionRequest(credentialDescription);
        final CredentialDescription credentialDescription2 =
                new CredentialDescription(MDOC_CREDENTIAL_TYPE, FLATTENED_REGISTRY_2,
                        Collections.emptyList());
        final RegisterCredentialDescriptionRequest registerCredentialDescriptionRequest2 =
                new RegisterCredentialDescriptionRequest(credentialDescription2);


        mCredentialDescriptionRegistry
                .executeRegisterRequest(registerCredentialDescriptionRequest, CALLING_PACKAGE_NAME);
        mCredentialDescriptionRegistry
                .executeRegisterRequest(registerCredentialDescriptionRequest2,
                        CALLING_PACKAGE_NAME);
        mCredentialDescriptionRegistry.evictProviderWithPackageName(CALLING_PACKAGE_NAME);
        Set<CredentialDescriptionRegistry.FilterResult> providers = mCredentialDescriptionRegistry
                .getMatchingProviders(Set.of(FLATTENED_REQUEST));

        assertThat(providers).isEmpty();
    }

    @Test
    public void testGetMatchingProviders_existingProviders_succeeds() {
        final CredentialDescription credentialDescription =
                new CredentialDescription(MDOC_CREDENTIAL_TYPE, FLATTENED_REGISTRY,
                        Collections.emptyList());
        final RegisterCredentialDescriptionRequest registerCredentialDescriptionRequest =
                new RegisterCredentialDescriptionRequest(credentialDescription);
        final CredentialDescription credentialDescription2 =
                new CredentialDescription(MDOC_CREDENTIAL_TYPE, FLATTENED_REGISTRY,
                        Collections.emptyList());
        final RegisterCredentialDescriptionRequest registerCredentialDescriptionRequest2 =
                new RegisterCredentialDescriptionRequest(credentialDescription2);


        mCredentialDescriptionRegistry
                .executeRegisterRequest(registerCredentialDescriptionRequest,
                        CALLING_PACKAGE_NAME);
        mCredentialDescriptionRegistry
                .executeRegisterRequest(registerCredentialDescriptionRequest2,
                        CALLING_PACKAGE_NAME_2);

        Set<CredentialDescriptionRegistry.FilterResult> providers = mCredentialDescriptionRegistry
                .getMatchingProviders(Set.of(FLATTENED_REQUEST));
        Set<String> packageNames = providers.stream().map(
                filterResult -> filterResult.mPackageName).collect(Collectors.toSet());

        assertThat(providers).hasSize(2);
        assertThat(packageNames).contains(CALLING_PACKAGE_NAME);
        assertThat(packageNames).contains(CALLING_PACKAGE_NAME_2);
    }

    @Test
    public void testExecuteRegisterRequest_noProviders_filterSucceedsWithNoResults() {
        List<CredentialDescriptionRegistry.FilterResult> results = mCredentialDescriptionRegistry
                .getFilteredResultForProvider(CALLING_PACKAGE_NAME,
                        FLATTENED_REQUEST).stream().toList();

        assertThat(results).isEmpty();
    }

    @Test
    public void testExecuteRegisterRequest_existingProviders_filterSucceeds() {
        final CredentialDescription credentialDescription =
                new CredentialDescription(MDOC_CREDENTIAL_TYPE,
                        FLATTENED_REGISTRY,
                        List.of(mEntry, mEntry2));
        final CredentialDescription credentialDescription2 =
                new CredentialDescription(PASSKEY_CREDENTIAL_TYPE,
                        FLATTENED_REGISTRY_2,
                        List.of(mEntry3));
        final RegisterCredentialDescriptionRequest registerCredentialDescriptionRequest =
                new RegisterCredentialDescriptionRequest(Set.of(credentialDescription,
                credentialDescription2));

        mCredentialDescriptionRegistry
                .executeRegisterRequest(registerCredentialDescriptionRequest, CALLING_PACKAGE_NAME);

        List<CredentialDescriptionRegistry.FilterResult> results = mCredentialDescriptionRegistry
                .getFilteredResultForProvider(CALLING_PACKAGE_NAME, FLATTENED_REQUEST)
                .stream().toList();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).mCredentialEntries).hasSize(2);
        assertThat(results.get(0).mCredentialEntries.get(0)).isSameInstanceAs(mEntry);
        assertThat(results.get(0).mCredentialEntries.get(1)).isSameInstanceAs(mEntry2);
    }

}
