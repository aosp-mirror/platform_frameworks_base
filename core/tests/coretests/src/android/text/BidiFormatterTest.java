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

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BidiFormatterTest {
    private static final BidiFormatter LTR_FMT = BidiFormatter.getInstance(false /* LTR context */);
    private static final BidiFormatter RTL_FMT = BidiFormatter.getInstance(true /* RTL context */);

    private static final String EN = "abba";
    private static final String HE = "\u05E0\u05E1";

    private static final String LRM = "\u200E";
    private static final String RLM = "\u200F";

    @Test
    public void testMarkAfter() {
        assertEquals("uniform dir matches LTR context",
                "", LTR_FMT.markAfter(EN, TextDirectionHeuristics.LTR));
        assertEquals("uniform dir matches RTL context",
                "", RTL_FMT.markAfter(HE, TextDirectionHeuristics.RTL));

        assertEquals("exit dir opposite to LTR context",
                LRM, LTR_FMT.markAfter(EN + HE, TextDirectionHeuristics.LTR));
        assertEquals("exit dir opposite to RTL context",
                RLM, RTL_FMT.markAfter(HE + EN, TextDirectionHeuristics.RTL));

        assertEquals("overall dir (but not exit dir) opposite to LTR context",
                LRM, LTR_FMT.markAfter(HE + EN, TextDirectionHeuristics.RTL));
        assertEquals("overall dir (but not exit dir) opposite to RTL context",
                RLM, RTL_FMT.markAfter(EN + HE, TextDirectionHeuristics.LTR));

        assertEquals("exit dir neutral, overall dir matches LTR context",
                "", LTR_FMT.markAfter(".", TextDirectionHeuristics.LTR));
        assertEquals("exit dir neutral, overall dir matches RTL context",
                "", RTL_FMT.markAfter(".", TextDirectionHeuristics.RTL));
    }

    @Test
    public void testMarkBefore() {
        assertEquals("uniform dir matches LTR context",
                "", LTR_FMT.markBefore(EN, TextDirectionHeuristics.LTR));
        assertEquals("uniform dir matches RTL context",
                "", RTL_FMT.markBefore(HE, TextDirectionHeuristics.RTL));

        assertEquals("entry dir opposite to LTR context",
                LRM, LTR_FMT.markBefore(HE + EN, TextDirectionHeuristics.LTR));
        assertEquals("entry dir opposite to RTL context",
                RLM, RTL_FMT.markBefore(EN + HE, TextDirectionHeuristics.RTL));

        assertEquals("overall dir (but not entry dir) opposite to LTR context",
                LRM, LTR_FMT.markBefore(EN + HE, TextDirectionHeuristics.RTL));
        assertEquals("overall dir (but not entry dir) opposite to RTL context",
                RLM, RTL_FMT.markBefore(HE + EN, TextDirectionHeuristics.LTR));

        assertEquals("exit dir neutral, overall dir matches LTR context",
                "", LTR_FMT.markBefore(".", TextDirectionHeuristics.LTR));
        assertEquals("exit dir neutral, overall dir matches RTL context",
                "", RTL_FMT.markBefore(".", TextDirectionHeuristics.RTL));
    }
}
