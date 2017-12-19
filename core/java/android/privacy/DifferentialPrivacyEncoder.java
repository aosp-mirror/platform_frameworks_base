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

package android.privacy;

/**
 * An interface for differential privacy encoder.
 * Applications can use it to convert privacy sensitive data to privacy protected report.
 * There is no decoder implemented in Android as it is not possible decode a single report by
 * design.
 *
 * <p>Each type of log should have its own encoder, otherwise it may leak
 * some information about Permanent Randomized Response(PRR, is used to create a “noisy”
 * answer which is memoized by the client and permanently reused in place of the real answer).
 *
 * <p>Some encoders may not support all encoding methods, and it will throw {@link
 * UnsupportedOperationException} if you call unsupported encoding method.
 *
 * <p><b>WARNING:</b> Privacy protection works only when encoder uses a suitable DP configuration,
 * and the configuration and algorithm that is suitable is highly dependent on the use case.
 * If the configuration is not suitable for the use case, it may hurt privacy or utility or both.
 *
 * @hide
 */
public interface DifferentialPrivacyEncoder {

    /**
     * Apply differential privacy to encode a string.
     *
     * @param original An arbitrary string
     * @return Differential privacy encoded bytes derived from the string
     */
    byte[] encodeString(String original);

    /**
     * Apply differential privacy to encode a boolean.
     *
     * @param original An arbitrary boolean.
     * @return Differential privacy encoded bytes derived from the boolean
     */
    byte[] encodeBoolean(boolean original);

    /**
     * Apply differential privacy to encode sequence of bytes.
     *
     * @param original An arbitrary byte array.
     * @return Differential privacy encoded bytes derived from the bytes
     */
    byte[] encodeBits(byte[] original);

    /**
     * Returns the configuration that this encoder is using.
     */
    DifferentialPrivacyConfig getConfig();

    /**
     * Return True if the output from encoder is NOT securely randomized, otherwise encoder should
     * be secure to randomize input.
     *
     * <b> A non-secure encoder is intended only for testing only and must not be used to process
     * real data.
     * </b>
     */
    boolean isInsecureEncoderForTest();
}
