/*
 * Copyright 2017 The Android Open Source Project
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

package android.privacy.internal.longitudinalreporting;

import android.privacy.DifferentialPrivacyConfig;
import android.privacy.internal.rappor.RapporConfig;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

/**
 * A class to store {@link LongitudinalReportingEncoder} configuration.
 *
 * <ul>
 * <li> f is probability to flip input value, used in IRR.
 * <li> p is probability to override input value, used in PRR1.
 * <li> q is probability to set input value as 1 when result of PRR(p) is true, used in PRR2.
 * </ul>
 *
 * @hide
 */
public class LongitudinalReportingConfig implements DifferentialPrivacyConfig {

    private static final String ALGORITHM_NAME = "LongitudinalReporting";

    // Probability to flip input value.
    private final double mProbabilityF;

    // Probability to override original value.
    private final double mProbabilityP;
    // Probability to override value with 1.
    private final double mProbabilityQ;

    // IRR config to randomize original value
    private final RapporConfig mIRRConfig;

    private final String mEncoderId;

    /**
     * Constructor to create {@link LongitudinalReportingConfig} used for {@link
     * LongitudinalReportingEncoder}
     *
     * @param encoderId    Unique encoder id.
     * @param probabilityF Probability F used in Longitudinal Reporting algorithm.
     * @param probabilityP Probability P used in Longitudinal Reporting algorithm. This will be
     *                     quantized to the nearest 1/256.
     * @param probabilityQ Probability Q used in Longitudinal Reporting algorithm. This will be
     *                     quantized to the nearest 1/256.
     */
    public LongitudinalReportingConfig(String encoderId, double probabilityF,
            double probabilityP, double probabilityQ) {
        Preconditions.checkArgument(probabilityF >= 0 && probabilityF <= 1,
                "probabilityF must be in range [0.0, 1.0]");
        this.mProbabilityF = probabilityF;
        Preconditions.checkArgument(probabilityP >= 0 && probabilityP <= 1,
                "probabilityP must be in range [0.0, 1.0]");
        this.mProbabilityP = probabilityP;
        Preconditions.checkArgument(probabilityQ >= 0 && probabilityQ <= 1,
                "probabilityQ must be in range [0.0, 1.0]");
        this.mProbabilityQ = probabilityQ;
        Preconditions.checkArgument(!TextUtils.isEmpty(encoderId), "encoderId cannot be empty");
        mEncoderId = encoderId;
        mIRRConfig = new RapporConfig(encoderId, 1, 0.0, probabilityF, 1 - probabilityF, 1, 1);
    }

    @Override
    public String getAlgorithm() {
        return ALGORITHM_NAME;
    }

    RapporConfig getIRRConfig() {
        return mIRRConfig;
    }

    double getProbabilityP() {
        return mProbabilityP;
    }

    double getProbabilityQ() {
        return mProbabilityQ;
    }

    String getEncoderId() {
        return mEncoderId;
    }

    @Override
    public String toString() {
        return String.format("EncoderId: %s, ProbabilityF: %.3f, ProbabilityP: %.3f"
                        + ", ProbabilityQ: %.3f",
                mEncoderId, mProbabilityF, mProbabilityP, mProbabilityQ);
    }
}
