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

package com.android.server.credentials.metrics;

import android.annotation.NonNull;
import android.service.credentials.BeginCreateCredentialResponse;
import android.service.credentials.BeginGetCredentialResponse;
import android.service.credentials.CredentialEntry;
import android.util.Log;

import com.android.server.credentials.MetricUtilities;

import java.util.stream.Collectors;

/**
 * Provides contextual metric collection for objects generated from
 * {@link com.android.server.credentials.ProviderSession} flows to isolate metric
 * collection from the core codebase. For any future additions to the ProviderSession subclass
 * list, metric collection should be added to this file.
 */
public class ProviderSessionMetric {

    private static final String TAG = "ProviderSessionMetric";

    // Specific candidate provider metric for the provider this session handles
    @NonNull
    protected final CandidatePhaseMetric mCandidatePhasePerProviderMetric =
            new CandidatePhaseMetric();

    public ProviderSessionMetric() {}

    /**
     * Retrieve the candidate provider phase metric and the data it contains.
     */
    public CandidatePhaseMetric getCandidatePhasePerProviderMetric() {
        return mCandidatePhasePerProviderMetric;
    }

    /**
     * This collects for ProviderSessions, with respect to the candidate providers, whether
     * an exception occurred in the candidate call.
     *
     * @param hasException indicates if the candidate provider associated with an exception
     */
    public void collectCandidateExceptionStatus(boolean hasException) {
        mCandidatePhasePerProviderMetric.setHasException(hasException);
    }

    /**
     * Collects the framework only exception encountered in a candidate flow.
     * @param exceptionType the string, cut to desired length, of the exception type
     */
    public void collectCandidateFrameworkException(String exceptionType) {
        try {
            mCandidatePhasePerProviderMetric.setFrameworkException(exceptionType);
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * Used to collect metrics at the update stage when a candidate provider gives back an update.
     *
     * @param isFailureStatus indicates the candidate provider sent back a terminated response
     * @param isCompletionStatus indicates the candidate provider sent back a completion response
     * @param providerSessionUid the uid of the provider
     */
    public void collectCandidateMetricUpdate(boolean isFailureStatus,
            boolean isCompletionStatus, int providerSessionUid) {
        try {
            mCandidatePhasePerProviderMetric.setCandidateUid(providerSessionUid);
            mCandidatePhasePerProviderMetric
                    .setQueryFinishTimeNanoseconds(System.nanoTime());
            if (isFailureStatus) {
                mCandidatePhasePerProviderMetric.setQueryReturned(false);
                mCandidatePhasePerProviderMetric.setProviderQueryStatus(
                        ProviderStatusForMetrics.QUERY_FAILURE
                                .getMetricCode());
            } else if (isCompletionStatus) {
                mCandidatePhasePerProviderMetric.setQueryReturned(true);
                mCandidatePhasePerProviderMetric.setProviderQueryStatus(
                        ProviderStatusForMetrics.QUERY_SUCCESS
                                .getMetricCode());
            }
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * Starts the collection of a single provider metric in the candidate phase of the API flow.
     * It's expected that this should be called at the start of the query phase so that session id
     * and timestamps can be shared. They can be accessed granular-ly through the underlying
     * objects, but for {@link com.android.server.credentials.ProviderSession} context metrics,
     * it's recommended to use these context-specified methods.
     *
     * @param initMetric the pre candidate phase metric collection object of type
     * {@link InitialPhaseMetric} used to transfer initial information
     */
    public void collectCandidateMetricSetupViaInitialMetric(InitialPhaseMetric initMetric) {
        try {
            mCandidatePhasePerProviderMetric.setSessionId(initMetric.getSessionId());
            mCandidatePhasePerProviderMetric.setServiceBeganTimeNanoseconds(
                    initMetric.getCredentialServiceStartedTimeNanoseconds());
            mCandidatePhasePerProviderMetric.setStartQueryTimeNanoseconds(System.nanoTime());
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * Once candidate providers give back entries, this helps collect their info for metric
     * purposes.
     *
     * @param response contains entries and data from the candidate provider responses
     * @param <R> the response type associated with the API flow in progress
     */
    public <R> void collectCandidateEntryMetrics(R response) {
        try {
            if (response instanceof BeginGetCredentialResponse) {
                beginGetCredentialResponseCollectionCandidateEntryMetrics(
                        (BeginGetCredentialResponse) response);
            } else if (response instanceof BeginCreateCredentialResponse) {
                beginCreateCredentialResponseCollectionCandidateEntryMetrics(
                        (BeginCreateCredentialResponse) response);
            } else {
                Log.i(TAG, "Your response type is unsupported for metric logging");
            }

        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    private void beginCreateCredentialResponseCollectionCandidateEntryMetrics(
            BeginCreateCredentialResponse response) {
        var createEntries = response.getCreateEntries();
        int numRemoteEntry = MetricUtilities.ZERO;
        if (response.getRemoteCreateEntry() != null) {
            numRemoteEntry = MetricUtilities.UNIT;
            mCandidatePhasePerProviderMetric.addEntry(EntryEnum.REMOTE_ENTRY);
        }
        int numCreateEntries =
                createEntries == null ? MetricUtilities.ZERO : createEntries.size();
        if (numCreateEntries > MetricUtilities.ZERO) {
            createEntries.forEach(c ->
                    mCandidatePhasePerProviderMetric.addEntry(EntryEnum.CREDENTIAL_ENTRY));
        }
        mCandidatePhasePerProviderMetric.setNumEntriesTotal(numCreateEntries + numRemoteEntry);
        mCandidatePhasePerProviderMetric.setRemoteEntryCount(numRemoteEntry);
        mCandidatePhasePerProviderMetric.setCredentialEntryCount(numCreateEntries);
        mCandidatePhasePerProviderMetric.setCredentialEntryTypeCount(MetricUtilities.UNIT);
    }

    private void beginGetCredentialResponseCollectionCandidateEntryMetrics(
            BeginGetCredentialResponse response) {
        int numCredEntries = response.getCredentialEntries().size();
        int numActionEntries = response.getActions().size();
        int numAuthEntries = response.getAuthenticationActions().size();
        int numRemoteEntry = MetricUtilities.ZERO;
        if (response.getRemoteCredentialEntry() != null) {
            numRemoteEntry = MetricUtilities.UNIT;
            mCandidatePhasePerProviderMetric.addEntry(EntryEnum.REMOTE_ENTRY);
        }
        response.getCredentialEntries().forEach(c ->
                mCandidatePhasePerProviderMetric.addEntry(EntryEnum.CREDENTIAL_ENTRY));
        response.getActions().forEach(c ->
                mCandidatePhasePerProviderMetric.addEntry(EntryEnum.ACTION_ENTRY));
        response.getAuthenticationActions().forEach(c ->
                mCandidatePhasePerProviderMetric.addEntry(EntryEnum.AUTHENTICATION_ENTRY));
        mCandidatePhasePerProviderMetric.setNumEntriesTotal(numCredEntries + numAuthEntries
                + numActionEntries + numRemoteEntry);
        mCandidatePhasePerProviderMetric.setCredentialEntryCount(numCredEntries);
        int numTypes = (response.getCredentialEntries().stream()
                .map(CredentialEntry::getType).collect(
                        Collectors.toSet())).size(); // Dedupe type strings
        mCandidatePhasePerProviderMetric.setCredentialEntryTypeCount(numTypes);
        mCandidatePhasePerProviderMetric.setActionEntryCount(numActionEntries);
        mCandidatePhasePerProviderMetric.setAuthenticationEntryCount(numAuthEntries);
        mCandidatePhasePerProviderMetric.setRemoteEntryCount(numRemoteEntry);
    }
}
