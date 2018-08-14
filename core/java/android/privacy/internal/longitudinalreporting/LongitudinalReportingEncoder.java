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

import android.privacy.DifferentialPrivacyEncoder;
import android.privacy.internal.rappor.RapporConfig;
import android.privacy.internal.rappor.RapporEncoder;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Differential privacy encoder by using Longitudinal Reporting algorithm.
 *
 * <b>
 * Notes: It supports encodeBoolean() only for now.
 * </b>
 *
 * <p>
 * Definition:
 * PRR = Permanent Randomized Response
 * IRR = Instantaneous Randomized response
 *
 * Algorithm:
 * Step 1: Create long-term secrets x(ignoreOriginalInput)=Ber(P), y=Ber(Q), where Ber denotes
 * Bernoulli distribution on {0, 1}, and we use it as a long-term secret, we implement Ber(x) by
 * using PRR(2x, 0) when x < 1/2, PRR(2(1-x), 1) when x >= 1/2.
 * Step 2: If x is 0, report IRR(F, original), otherwise report IRR(F, y)
 * </p>
 *
 * Reference: go/bit-reporting-with-longitudinal-privacy
 * TODO: Add a public blog / site to explain how it works.
 *
 * @hide
 */
public class LongitudinalReportingEncoder implements DifferentialPrivacyEncoder {

    private static final String TAG = "LongitudinalEncoder";
    private static final boolean DEBUG = false;

    // Suffix that will be added to Rappor's encoder id. There's a (relatively) small risk some
    // other Rappor encoder may re-use the same encoder id.
    private static final String PRR1_ENCODER_ID = "prr1_encoder_id";
    private static final String PRR2_ENCODER_ID = "prr2_encoder_id";

    private final LongitudinalReportingConfig mConfig;

    // IRR encoder to encode input value.
    private final RapporEncoder mIRREncoder;

    // A value that used to replace original value as input, so there's always a chance we are
    // doing IRR on a fake value not actual original value.
    // Null if original value does not need to be replaced.
    private final Boolean mFakeValue;

    // True if encoder is securely randomized.
    private final boolean mIsSecure;

    /**
     * Create {@link LongitudinalReportingEncoder} with
     * {@link LongitudinalReportingConfig} provided.
     *
     * @param config     Longitudinal Reporting parameters to encode input
     * @param userSecret User generated secret that used to generate PRR
     * @return {@link LongitudinalReportingEncoder} instance
     */
    public static LongitudinalReportingEncoder createEncoder(LongitudinalReportingConfig config,
            byte[] userSecret) {
        return new LongitudinalReportingEncoder(config, true, userSecret);
    }

    /**
     * Create <strong>insecure</strong> {@link LongitudinalReportingEncoder} with
     * {@link LongitudinalReportingConfig} provided.
     * Should not use it to process sensitive data.
     *
     * @param config Rappor parameters to encode input.
     * @return {@link LongitudinalReportingEncoder} instance.
     */
    @VisibleForTesting
    public static LongitudinalReportingEncoder createInsecureEncoderForTest(
            LongitudinalReportingConfig config) {
        return new LongitudinalReportingEncoder(config, false, null);
    }

    private LongitudinalReportingEncoder(LongitudinalReportingConfig config,
            boolean secureEncoder, byte[] userSecret) {
        mConfig = config;
        mIsSecure = secureEncoder;
        final boolean ignoreOriginalInput = getLongTermRandomizedResult(config.getProbabilityP(),
                secureEncoder, userSecret, config.getEncoderId() + PRR1_ENCODER_ID);

        if (ignoreOriginalInput) {
            mFakeValue = getLongTermRandomizedResult(config.getProbabilityQ(),
                    secureEncoder, userSecret, config.getEncoderId() + PRR2_ENCODER_ID);
        } else {
            // Not using fake value, so IRR will be processed on real input value.
            mFakeValue = null;
        }

        final RapporConfig irrConfig = config.getIRRConfig();
        mIRREncoder = secureEncoder
                ? RapporEncoder.createEncoder(irrConfig, userSecret)
                : RapporEncoder.createInsecureEncoderForTest(irrConfig);
    }

    @Override
    public byte[] encodeString(String original) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] encodeBoolean(boolean original) {
        if (DEBUG) {
            Log.d(TAG, "encodeBoolean, encoderId:" + mConfig.getEncoderId() + ", original: "
                    + original);
        }
        if (mFakeValue != null) {
            // Use the fake value generated in PRR.
            original = mFakeValue.booleanValue();
            if (DEBUG) Log.d(TAG, "Use fake value: " + original);
        }
        byte[] result = mIRREncoder.encodeBoolean(original);
        if (DEBUG) Log.d(TAG, "result: " + ((result[0] & 0x1) != 0));
        return result;
    }

    @Override
    public byte[] encodeBits(byte[] bits) {
        throw new UnsupportedOperationException();
    }

    @Override
    public LongitudinalReportingConfig getConfig() {
        return mConfig;
    }

    @Override
    public boolean isInsecureEncoderForTest() {
        return !mIsSecure;
    }

    /**
     * Get PRR result that with probability p is 1, probability 1-p is 0.
     */
    @VisibleForTesting
    public static boolean getLongTermRandomizedResult(double p, boolean secureEncoder,
            byte[] userSecret, String encoderId) {
        // Use Rappor to get PRR result. Rappor's P and Q are set to 0 and 1 so IRR will not be
        // effective.
        // As Rappor has rapporF/2 chance returns 0, rapporF/2 chance returns 1, and 1-rapporF
        // chance returns original input.
        // If p < 0.5, setting rapporF=2p and input=0 will make Rappor has p chance to return 1
        // P(output=1 | input=0) = rapporF/2 = 2p/2 = p.
        // If p >= 0.5, setting rapporF=2(1-p) and input=1 will make Rappor has p chance
        // to return 1.
        // P(output=1 | input=1) = rapporF/2 + (1 - rapporF) = 2(1-p)/2 + (1 - 2(1-p)) = p.
        final double effectiveF = p < 0.5f ? p * 2 : (1 - p) * 2;
        final boolean prrInput = p < 0.5f ? false : true;
        final RapporConfig prrConfig = new RapporConfig(encoderId, 1, effectiveF,
                0, 1, 1, 1);
        final RapporEncoder encoder = secureEncoder
                ? RapporEncoder.createEncoder(prrConfig, userSecret)
                : RapporEncoder.createInsecureEncoderForTest(prrConfig);
        return encoder.encodeBoolean(prrInput)[0] > 0;
    }
}
