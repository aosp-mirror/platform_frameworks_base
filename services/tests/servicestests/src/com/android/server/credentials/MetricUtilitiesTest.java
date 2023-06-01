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

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.credentials.metrics.BrowsedAuthenticationMetric;
import com.android.server.credentials.metrics.CandidateAggregateMetric;
import com.android.server.credentials.metrics.CandidateBrowsingPhaseMetric;
import com.android.server.credentials.metrics.ChosenProviderFinalPhaseMetric;
import com.android.server.credentials.metrics.InitialPhaseMetric;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Given the secondary-system nature of the MetricUtilities, we expect absolutely nothing to
 * throw an error. If one presents itself, that is problematic.
 *
 * atest FrameworksServicesTests:com.android.server.credentials.MetricUtilitiesTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public final class MetricUtilitiesTest {

    @Before
    public void setUp() throws CertificateException {
        final Context context = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void logApiCalledInitialPhase_nullInitPhaseMetricAndNegativeSequence_success() {
        MetricUtilities.logApiCalledInitialPhase(null, -1);
    }

    @Test
    public void logApiCalledInitialPhase_invalidInitPhaseMetricAndPositiveSequence_success() {
        MetricUtilities.logApiCalledInitialPhase(new InitialPhaseMetric(-1), 1);
    }

    @Test
    public void logApiCalledInitialPhase_validInitPhaseMetric_success() {
        InitialPhaseMetric validInitPhaseMetric = new InitialPhaseMetric(
                MetricUtilities.getHighlyUniqueInteger());
        MetricUtilities.logApiCalledInitialPhase(validInitPhaseMetric, 1);
    }

    @Test
    public void logApiCalledTotalCandidate_nullCandidateNegativeSequence_success() {
        MetricUtilities.logApiCalledAggregateCandidate(null, -1);
    }

    @Test
    public void logApiCalledTotalCandidate_invalidCandidatePhasePositiveSequence_success() {
        MetricUtilities.logApiCalledAggregateCandidate(new CandidateAggregateMetric(-1), 1);
    }

    @Test
    public void logApiCalledTotalCandidate_validPhaseMetric_success() {
        MetricUtilities.logApiCalledAggregateCandidate(
                new CandidateAggregateMetric(MetricUtilities.getHighlyUniqueInteger()), 1);
    }

    @Test
    public void logApiCalledNoUidFinal_nullNoUidFinalNegativeSequenceAndStatus_success() {
        MetricUtilities.logApiCalledNoUidFinal(null, null,
                -1, -1);
    }

    @Test
    public void logApiCalledNoUidFinal_invalidNoUidFinalPhasePositiveSequenceAndStatus_success() {
        MetricUtilities.logApiCalledNoUidFinal(new ChosenProviderFinalPhaseMetric(-1, -1),
                List.of(new CandidateBrowsingPhaseMetric()), 1, 1);
    }

    @Test
    public void logApiCalledNoUidFinal_validNoUidFinalMetric_success() {
        MetricUtilities.logApiCalledNoUidFinal(
                new ChosenProviderFinalPhaseMetric(MetricUtilities.getHighlyUniqueInteger(),
                        MetricUtilities.getHighlyUniqueInteger()),
                List.of(new CandidateBrowsingPhaseMetric()), 1, 1);
    }

    @Test
    public void logApiCalledCandidate_nullMapNullInitFinalNegativeSequence_success() {
        MetricUtilities.logApiCalledCandidatePhase(null, -1,
                null);
    }

    @Test
    public void logApiCalledCandidate_invalidProvidersCandidatePositiveSequence_success() {
        Map<String, ProviderSession> testMap = new HashMap<>();
        testMap.put("s", null);
        MetricUtilities.logApiCalledCandidatePhase(testMap, 1,
                null);
    }

    @Test
    public void logApiCalledCandidateGet_nullMapFinalNegativeSequence_success() {
        MetricUtilities.logApiCalledCandidateGetMetric(null, -1);
    }

    @Test
    public void logApiCalledCandidateGet_invalidProvidersCandidatePositiveSequence_success() {
        Map<String, ProviderSession> testMap = new HashMap<>();
        testMap.put("s", null);
        MetricUtilities.logApiCalledCandidateGetMetric(testMap, 1);
    }

    @Test
    public void logApiCalledAuthMetric_nullAuthMetricNegativeSequence_success() {
        MetricUtilities.logApiCalledAuthenticationMetric(null, -1);
    }

    @Test
    public void logApiCalledAuthMetric_invalidAuthMetricPositiveSequence_success() {
        MetricUtilities.logApiCalledAuthenticationMetric(new BrowsedAuthenticationMetric(-1), 1);
    }

    @Test
    public void logApiCalledAuthMetric_nullAuthMetricPositiveSequence_success() {
        MetricUtilities.logApiCalledAuthenticationMetric(
                new BrowsedAuthenticationMetric(MetricUtilities.getHighlyUniqueInteger()), -1);
    }

    @Test
    public void logApiCalledFinal_nullFinalNegativeSequenceAndStatus_success() {
        MetricUtilities.logApiCalledFinalPhase(null, null,
                -1, -1);
    }

    @Test
    public void logApiCalledFinal_invalidFinalPhasePositiveSequenceAndStatus_success() {
        MetricUtilities.logApiCalledFinalPhase(new ChosenProviderFinalPhaseMetric(-1, -1),
                List.of(new CandidateBrowsingPhaseMetric()), 1, 1);
    }

    @Test
    public void logApiCalledFinal_validFinalMetric_success() {
        MetricUtilities.logApiCalledFinalPhase(
                new ChosenProviderFinalPhaseMetric(MetricUtilities.getHighlyUniqueInteger(),
                        MetricUtilities.getHighlyUniqueInteger()),
                List.of(new CandidateBrowsingPhaseMetric()), 1, 1);
    }
}
