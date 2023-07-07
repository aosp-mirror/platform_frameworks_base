/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.broadcastradio.hal2;

import android.hardware.broadcastradio.V2_0.Result;
import android.hardware.radio.RadioTuner;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.List;

@RunWith(Parameterized.class)
public final class ConvertResultTest {

    private final int mHalResult;
    private final int mTunerResult;

    @Rule
    public final Expect expect = Expect.create();

    public ConvertResultTest(int halResult, int tunerResult) {
        this.mHalResult = halResult;
        this.mTunerResult = tunerResult;
    }

    @Parameterized.Parameters
    public static List<Object[]> inputParameters() {
        return Arrays.asList(new Object[][]{
                {Result.OK, RadioTuner.TUNER_RESULT_OK},
                {Result.INTERNAL_ERROR, RadioTuner.TUNER_RESULT_INTERNAL_ERROR},
                {Result.INVALID_ARGUMENTS, RadioTuner.TUNER_RESULT_INVALID_ARGUMENTS},
                {Result.INVALID_STATE, RadioTuner.TUNER_RESULT_INVALID_STATE},
                {Result.NOT_SUPPORTED, RadioTuner.TUNER_RESULT_NOT_SUPPORTED},
                {Result.TIMEOUT, RadioTuner.TUNER_RESULT_TIMEOUT},
                {Result.UNKNOWN_ERROR, RadioTuner.TUNER_RESULT_UNKNOWN_ERROR}
        });
    }

    @Test
    public void halResultToTunerResult() {
        expect.withMessage("Tuner result converted from HAL 2.0 result %s",
                Result.toString(mHalResult))
                .that(Convert.halResultToTunerResult(mHalResult))
                .isEqualTo(mTunerResult);
    }
}
