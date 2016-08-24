/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.text.util;

import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;
import com.google.caliper.Benchmark;
import com.google.caliper.Param;

import android.text.Spannable;
import android.text.SpannableString;
import android.util.Patterns;

import java.util.regex.Pattern;

public class LinkifyBenchmark {
    private static final String MATCHING_STR = " http://user:pass@host.com:5432/path?k=v#f " +
            "host.com:5432/path?k=v#f ";

    private static final String NONMATCHING_STR = " Neque porro quisquam est qui dolorem ipsum " +
            "quia dolor sit amet, consectetur, adipisci velit ";

    // this pattern does not recognize strings without http scheme therefore is expected to be
    // faster in MATCHING_STR case.
    private static final Pattern BASIC_PATTERN = Pattern.compile(
            "(?:\\b|$|^)http://[a-zA-Z0-9:\\.@\\?=#/]+(?:\\b|$|^)");

    @Param({"1", "4", "16", "64", "256"})
    private String mParamCopyAmount;

    @Param({MATCHING_STR, NONMATCHING_STR})
    private String mParamBasicText;

    private Spannable mTestSpannable;

    @BeforeExperiment
    protected void setUp() throws Exception {
        int copyAmount = Integer.parseInt(mParamCopyAmount);
        StringBuilder strBuilder = new StringBuilder();
        for (int i = 0; i < copyAmount; i++) {
            strBuilder.append(mParamBasicText);
        }
        mTestSpannable = new SpannableString(strBuilder.toString());
    }

    @AfterExperiment
    protected void tearDown() {
        mTestSpannable = null;
    }

    @Benchmark
    public void timeNewRegEx(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            Linkify.addLinks(mTestSpannable, Patterns.AUTOLINK_WEB_URL, "http://",
                    new String[]{"http://", "https://", "rtsp://"}, null, null);
        }
    }

    @Benchmark
    public void timeOldRegEx(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            Linkify.addLinks(mTestSpannable, Patterns.WEB_URL, "http://",
                    new String[]{"http://", "https://", "rtsp://"}, null, null);
        }
    }

    @Benchmark
    public void timeBasicRegEx(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            Linkify.addLinks(mTestSpannable, BASIC_PATTERN, "http://",
                    new String[]{"http://", "https://", "rtsp://"}, null, null);
        }
    }

}
