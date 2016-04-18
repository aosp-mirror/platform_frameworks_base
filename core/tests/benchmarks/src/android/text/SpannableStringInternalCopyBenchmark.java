/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.text;

import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;
import com.google.caliper.Benchmark;
import com.google.caliper.Param;

public class SpannableStringInternalCopyBenchmark {

    @Param({"1", "4", "16"})
    private String paramStringMult;

    private SpannedString spanned;

    @BeforeExperiment
    protected void setUp() throws Exception {
        int strSize = Integer.parseInt(paramStringMult);
        StringBuilder strBuilder = new StringBuilder();
        for (int i = 0; i < strSize; i++) {
            strBuilder.append(SpannableStringBuilderBenchmark.TEST_STRING);
        }
        Spanned source = Html.fromHtml(strBuilder.toString());
        spanned = new SpannedString(source);
    }

    @AfterExperiment
    protected void tearDown() {
        spanned = null;
    }

    @Benchmark
    public void timeCopyConstructor(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            new SpannedString(spanned);
        }
    }

    @Benchmark
    public void timeSubsequence(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            spanned.subSequence(1, spanned.length()-1);
        }
    }

}
