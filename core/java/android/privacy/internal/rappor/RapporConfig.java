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

package android.privacy.internal.rappor;

import android.privacy.DifferentialPrivacyConfig;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

/**
 * A class to store {@link RapporEncoder} config.
 *
 * @hide
 */
public class RapporConfig implements DifferentialPrivacyConfig {

    private static final String ALGORITHM_NAME = "Rappor";

    final String mEncoderId;
    final int mNumBits;
    final double mProbabilityF;
    final double mProbabilityP;
    final double mProbabilityQ;
    final int mNumCohorts;
    final int mNumBloomHashes;

    /**
     * Constructor for {@link RapporConfig}.
     *
     * @param encoderId      Unique id for encoder.
     * @param numBits        Number of bits to be encoded in Rappor algorithm.
     * @param probabilityF   Probability F that used in Rappor algorithm. This will be
     *                       quantized to the nearest 1/128.
     * @param probabilityP   Probability P that used in Rappor algorithm.
     * @param probabilityQ   Probability Q that used in Rappor algorithm.
     * @param numCohorts     Number of cohorts that used in Rappor algorithm.
     * @param numBloomHashes Number of bloom hashes that used in Rappor algorithm.
     */
    public RapporConfig(String encoderId, int numBits, double probabilityF,
            double probabilityP, double probabilityQ, int numCohorts, int numBloomHashes) {
        Preconditions.checkArgument(!TextUtils.isEmpty(encoderId), "encoderId cannot be empty");
        this.mEncoderId = encoderId;
        Preconditions.checkArgument(numBits > 0, "numBits needs to be > 0");
        this.mNumBits = numBits;
        Preconditions.checkArgument(probabilityF >= 0 && probabilityF <= 1,
                "probabilityF must be in range [0.0, 1.0]");
        this.mProbabilityF = probabilityF;
        Preconditions.checkArgument(probabilityP >= 0 && probabilityP <= 1,
                "probabilityP must be in range [0.0, 1.0]");
        this.mProbabilityP = probabilityP;
        Preconditions.checkArgument(probabilityQ >= 0 && probabilityQ <= 1,
                "probabilityQ must be in range [0.0, 1.0]");
        this.mProbabilityQ = probabilityQ;
        Preconditions.checkArgument(numCohorts > 0, "numCohorts needs to be > 0");
        this.mNumCohorts = numCohorts;
        Preconditions.checkArgument(numBloomHashes > 0, "numBloomHashes needs to be > 0");
        this.mNumBloomHashes = numBloomHashes;
    }

    @Override
    public String getAlgorithm() {
        return ALGORITHM_NAME;
    }

    @Override
    public String toString() {
        return String.format(
                "EncoderId: %s, NumBits: %d, ProbabilityF: %.3f, ProbabilityP: %.3f"
                        + ", ProbabilityQ: %.3f, NumCohorts: %d, NumBloomHashes: %d",
                mEncoderId, mNumBits, mProbabilityF, mProbabilityP, mProbabilityQ,
                mNumCohorts, mNumBloomHashes);
    }
}
