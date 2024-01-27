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

import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.app.Activity;
import android.app.slice.Slice;
import android.app.slice.SliceSpec;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.credentials.Credential;
import android.credentials.CredentialOption;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialResponse;
import android.credentials.selection.GetCredentialProviderData;
import android.credentials.selection.ProviderPendingIntentResponse;
import android.net.Uri;
import android.os.Bundle;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CredentialEntry;
import android.service.credentials.CredentialProviderService;
import android.service.credentials.GetCredentialRequest;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for CredentialDescriptionRegistry.
 *
 * atest FrameworksServicesTests:com.android.server.credentials.ProviderRegistryGetSessionTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ProviderRegistryGetSessionTest {

    private static final String CALLING_PACKAGE_NAME = "com.credman.app";
    private static final int USER_ID_1 = 1;
    private static final ArrayList<String> FLATTENED_REQUEST =
            new ArrayList<>(List.of("FLATTENED_REQ"));
    private static final String CP_SERVICE_NAME = "CredentialProvider";
    private static final ComponentName CREDENTIAL_PROVIDER_COMPONENT =
            new ComponentName(CALLING_PACKAGE_NAME, CP_SERVICE_NAME);
    private static final String GET_CREDENTIAL_EXCEPTION_TYPE = "TYPE";
    private static final String CREDENTIAL_TYPE = "MDOC";
    private static final String GET_CREDENTIAL_EXCEPTION_MESSAGE = "MESSAGE";

    private ProviderRegistryGetSession mProviderRegistryGetSession;
    @Mock private GetRequestSession mGetRequestSession;
    private CredentialOption mGetCredentialOption;
    @Mock private ServiceInfo mServiceInfo;
    private CallingAppInfo mCallingAppInfo;
    @Mock private CredentialDescriptionRegistry mCredentialDescriptionRegistry;
    private Bundle mRetrievalData;
    @Mock private CredentialEntry mEntry;
    @Mock private CredentialEntry mEntry2;
    private Slice mSlice;
    private Slice mSlice2;
    private CredentialDescriptionRegistry.FilterResult mResult;
    private Set<CredentialDescriptionRegistry.FilterResult> mResponse;

    @SuppressWarnings("GuardedBy")
    @Before
    public void setUp() throws CertificateException {
        MockitoAnnotations.initMocks(this);
        final Context context = ApplicationProvider.getApplicationContext();
        mRetrievalData = new Bundle();
        mRetrievalData.putStringArrayList(CredentialOption.SUPPORTED_ELEMENT_KEYS,
                FLATTENED_REQUEST);
        mCallingAppInfo = createCallingAppInfo();
        mGetCredentialOption = new CredentialOption(CREDENTIAL_TYPE, mRetrievalData,
                new Bundle(), false);
        when(mServiceInfo.getComponentName()).thenReturn(CREDENTIAL_PROVIDER_COMPONENT);
        CredentialDescriptionRegistry.setSession(USER_ID_1, mCredentialDescriptionRegistry);
        mResponse = new HashSet<>();
        mSlice = createSlice();
        mSlice2 = createSlice();
        when(mEntry.getSlice()).thenReturn(mSlice);
        when(mEntry2.getSlice()).thenReturn(mSlice2);
        mResult = new CredentialDescriptionRegistry.FilterResult(CALLING_PACKAGE_NAME,
                new HashSet<>(FLATTENED_REQUEST),
                List.of(mEntry, mEntry2));
        mResponse.add(mResult);
        when(mCredentialDescriptionRegistry.getFilteredResultForProvider(anyString(), anySet()))
                .thenReturn(mResponse);
        mProviderRegistryGetSession = ProviderRegistryGetSession
                .createNewSession(context, USER_ID_1, mGetRequestSession,
                        mCallingAppInfo,
                        CALLING_PACKAGE_NAME,
                        mGetCredentialOption);
    }

    @Test
    public void testInvokeSession_existingProvider_setsResults() {
        final ArgumentCaptor<String> packageNameCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<Set<String>> flattenedRequestCaptor =
                ArgumentCaptor.forClass(Set.class);
        final ArgumentCaptor<ProviderSession.Status> statusCaptor =
                ArgumentCaptor.forClass(ProviderSession.Status.class);
        final ArgumentCaptor<ComponentName> cpComponentNameCaptor =
                ArgumentCaptor.forClass(ComponentName.class);

        mProviderRegistryGetSession.invokeSession();

        verify(mCredentialDescriptionRegistry).getFilteredResultForProvider(
                packageNameCaptor.capture(),
                flattenedRequestCaptor.capture());
        assertThat(packageNameCaptor.getValue()).isEqualTo(CALLING_PACKAGE_NAME);
        assertThat(flattenedRequestCaptor.getValue()).containsExactly(FLATTENED_REQUEST);
        verify(mGetRequestSession).onProviderStatusChanged(statusCaptor.capture(),
                cpComponentNameCaptor.capture(), ProviderSession.CredentialsSource.REGISTRY);
        assertThat(statusCaptor.getValue()).isEqualTo(ProviderSession.Status.CREDENTIALS_RECEIVED);
        assertThat(cpComponentNameCaptor.getValue()).isEqualTo(CREDENTIAL_PROVIDER_COMPONENT);
        assertThat(mProviderRegistryGetSession.mCredentialEntries).hasSize(2);
        assertThat(mProviderRegistryGetSession.mCredentialEntries.get(0)).isSameInstanceAs(mEntry);
        assertThat(mProviderRegistryGetSession.mCredentialEntries.get(1)).isSameInstanceAs(mEntry2);
    }

    @Test
    public void testPrepareUiData_statusNonUIInvoking_throwsIllegalStateException() {
        mProviderRegistryGetSession.setStatus(ProviderSession.Status.CREDENTIALS_RECEIVED);

        assertThrows(IllegalStateException.class,
                () -> mProviderRegistryGetSession.prepareUiData());
    }

    @Test
    public void testPrepareUiData_statusUIInvokingNoResults_returnsNull() {
        mProviderRegistryGetSession.setStatus(ProviderSession.Status.CANCELED);

        assertThat(mProviderRegistryGetSession.prepareUiData()).isNull();
    }

    @Test
    public void testPrepareUiData_invokeCalledSuccessfully_returnsCorrectData() {
        mProviderRegistryGetSession.invokeSession();
        GetCredentialProviderData providerData = (GetCredentialProviderData)
                mProviderRegistryGetSession.prepareUiData();

        assertThat(providerData).isNotNull();
        assertThat(providerData.getCredentialEntries()).hasSize(2);
        assertThat(providerData.getCredentialEntries().get(0).getSlice()).isSameInstanceAs(mSlice);
        assertThat(providerData.getCredentialEntries().get(1).getSlice()).isSameInstanceAs(mSlice2);
        Intent intent = providerData.getCredentialEntries().get(0).getFrameworkExtrasIntent();
        GetCredentialRequest getRequest = intent.getParcelableExtra(CredentialProviderService
                .EXTRA_GET_CREDENTIAL_REQUEST, GetCredentialRequest.class);
        assertThat(getRequest.getCallingAppInfo()).isSameInstanceAs(mCallingAppInfo);
        assertThat(getRequest.getCredentialOptions().get(0)).isSameInstanceAs(mGetCredentialOption);
        Intent intent2 = providerData.getCredentialEntries().get(0).getFrameworkExtrasIntent();
        GetCredentialRequest getRequest2 = intent2.getParcelableExtra(CredentialProviderService
                .EXTRA_GET_CREDENTIAL_REQUEST, GetCredentialRequest.class);
        assertThat(getRequest2.getCallingAppInfo()).isSameInstanceAs(mCallingAppInfo);
        assertThat(getRequest2.getCredentialOptions().get(0))
                .isSameInstanceAs(mGetCredentialOption);
    }

    @Test
    public void testOnUiEntrySelected_wrongEntryKey_doesNothing() {
        final Intent intent = new Intent();
        final GetCredentialResponse response =
                new GetCredentialResponse(new Credential(CREDENTIAL_TYPE, new Bundle()));
        intent.putExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_RESPONSE, response);
        final ProviderPendingIntentResponse providerPendingIntentResponse = new
                ProviderPendingIntentResponse(Activity.RESULT_OK, intent);

        mProviderRegistryGetSession.onUiEntrySelected(
                ProviderRegistryGetSession.CREDENTIAL_ENTRY_KEY,
                "unsupportedKey", providerPendingIntentResponse);

        verifyZeroInteractions(mGetRequestSession);
    }

    @Test
    public void testOnUiEntrySelected_nullPendingIntentResponse_doesNothing() {
        mProviderRegistryGetSession.onUiEntrySelected(
                ProviderRegistryGetSession.CREDENTIAL_ENTRY_KEY,
                ProviderRegistryGetSession.CREDENTIAL_ENTRY_KEY, null);

        verifyZeroInteractions(mGetRequestSession);
    }

    @Test
    public void testOnUiEntrySelected_pendingIntentWithException_callbackWithGivenException() {
        final ArgumentCaptor<String> exceptionTypeCaptor =
                ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> exceptionMessageCaptor =
                ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<ComponentName> cpComponentNameCaptor =
                ArgumentCaptor.forClass(ComponentName.class);
        final ArgumentCaptor<ProviderSession.Status> statusCaptor =
                ArgumentCaptor.forClass(ProviderSession.Status.class);
        final GetCredentialException exception =
                new GetCredentialException(GET_CREDENTIAL_EXCEPTION_TYPE,
                        GET_CREDENTIAL_EXCEPTION_MESSAGE);
        mProviderRegistryGetSession.invokeSession();
        GetCredentialProviderData providerData = (GetCredentialProviderData)
                mProviderRegistryGetSession.prepareUiData();
        String entryKey = providerData.getCredentialEntries().get(0).getSubkey();
        final Intent intent = new Intent();
        intent.putExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_EXCEPTION, exception);
        final ProviderPendingIntentResponse providerPendingIntentResponse = new
                ProviderPendingIntentResponse(Activity.RESULT_OK, intent);

        mProviderRegistryGetSession.onUiEntrySelected(
                ProviderRegistryGetSession.CREDENTIAL_ENTRY_KEY,
                entryKey, providerPendingIntentResponse);

        assertThat(statusCaptor.getValue()).isEqualTo(ProviderSession.Status.CREDENTIALS_RECEIVED);
        verify(mGetRequestSession).onFinalErrorReceived(cpComponentNameCaptor.capture(),
                exceptionTypeCaptor.capture(), exceptionMessageCaptor.capture());
        assertThat(cpComponentNameCaptor.getValue())
                .isSameInstanceAs(CREDENTIAL_PROVIDER_COMPONENT);
        assertThat(exceptionTypeCaptor.getValue()).isEqualTo(GET_CREDENTIAL_EXCEPTION_TYPE);
        assertThat(exceptionMessageCaptor.getValue()).isEqualTo(GET_CREDENTIAL_EXCEPTION_MESSAGE);
    }

    @Test
    public void testOnUiEntrySelected_pendingIntentWithException_callbackWithCancelledException() {
        final ArgumentCaptor<String> exceptionTypeCaptor =
                ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> exceptionMessageCaptor =
                ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<ComponentName> cpComponentNameCaptor =
                ArgumentCaptor.forClass(ComponentName.class);
        final ArgumentCaptor<ProviderSession.Status> statusCaptor =
                ArgumentCaptor.forClass(ProviderSession.Status.class);

        mProviderRegistryGetSession.invokeSession();
        GetCredentialProviderData providerData = (GetCredentialProviderData)
                mProviderRegistryGetSession.prepareUiData();
        String entryKey = providerData.getCredentialEntries().get(0).getSubkey();
        final Intent intent = new Intent();
        final ProviderPendingIntentResponse providerPendingIntentResponse = new
                ProviderPendingIntentResponse(Activity.RESULT_CANCELED, intent);

        mProviderRegistryGetSession.onUiEntrySelected(
                ProviderRegistryGetSession.CREDENTIAL_ENTRY_KEY,
                entryKey, providerPendingIntentResponse);

        assertThat(statusCaptor.getValue()).isEqualTo(ProviderSession.Status.CREDENTIALS_RECEIVED);
        verify(mGetRequestSession).onFinalErrorReceived(cpComponentNameCaptor.capture(),
                exceptionTypeCaptor.capture(), exceptionMessageCaptor.capture());
        assertThat(cpComponentNameCaptor.getValue())
                .isSameInstanceAs(CREDENTIAL_PROVIDER_COMPONENT);
        assertThat(exceptionTypeCaptor.getValue())
                .isEqualTo(GetCredentialException.TYPE_USER_CANCELED);
    }

    @Test
    public void testOnUiEntrySelected_correctEntryKeyPendingIntentResponseExists_succeeds() {
        final ArgumentCaptor<GetCredentialResponse> getCredentialResponseCaptor =
                ArgumentCaptor.forClass(GetCredentialResponse.class);
        final ArgumentCaptor<ComponentName> cpComponentNameCaptor =
                ArgumentCaptor.forClass(ComponentName.class);
        final ArgumentCaptor<ProviderSession.Status> statusCaptor =
                ArgumentCaptor.forClass(ProviderSession.Status.class);
        mProviderRegistryGetSession.invokeSession();
        GetCredentialProviderData providerData = (GetCredentialProviderData)
                mProviderRegistryGetSession.prepareUiData();
        final Intent intent = new Intent();
        final GetCredentialResponse response =
                new GetCredentialResponse(new Credential(CREDENTIAL_TYPE, new Bundle()));
        intent.putExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_RESPONSE, response);
        String entryKey = providerData.getCredentialEntries().get(0).getSubkey();
        final ProviderPendingIntentResponse providerPendingIntentResponse = new
                ProviderPendingIntentResponse(Activity.RESULT_OK, intent);

        mProviderRegistryGetSession.onUiEntrySelected(
                ProviderRegistryGetSession.CREDENTIAL_ENTRY_KEY,
                entryKey, providerPendingIntentResponse);

        assertThat(statusCaptor.getValue()).isEqualTo(ProviderSession.Status.CREDENTIALS_RECEIVED);
        verify(mGetRequestSession).onFinalResponseReceived(cpComponentNameCaptor.capture(),
                getCredentialResponseCaptor.capture());
        assertThat(cpComponentNameCaptor.getValue())
                .isSameInstanceAs(CREDENTIAL_PROVIDER_COMPONENT);
        assertThat(getCredentialResponseCaptor.getValue()).isSameInstanceAs(response);
    }

    private static Slice createSlice() {
        return new Slice.Builder(Uri.EMPTY, new SliceSpec("", 0)).build();
    }

    private static CallingAppInfo createCallingAppInfo() throws CertificateException {
        return new CallingAppInfo(CALLING_PACKAGE_NAME,
                new SigningInfo(
                        new SigningDetails(new Signature[]{}, 0,
                                null)));
    }
}
