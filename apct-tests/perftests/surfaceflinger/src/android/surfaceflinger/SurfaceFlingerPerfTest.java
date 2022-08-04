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

package android.surfaceflinger;

import android.graphics.Color;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.view.SurfaceControl;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class SurfaceFlingerPerfTest {
    protected ActivityScenarioRule<SurfaceFlingerTestActivity> mActivityRule =
            new ActivityScenarioRule<>(SurfaceFlingerTestActivity.class);
    protected PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    private SurfaceFlingerTestActivity mActivity;
    static final int BUFFER_COUNT = 2;

    @Rule
    public final RuleChain mAllRules = RuleChain
            .outerRule(mPerfStatusReporter)
            .around(mActivityRule);
    @Before
    public void setup() {
        mActivityRule.getScenario().onActivity(activity -> mActivity = activity);
    }
    @Test
    public void submitSingleBuffer() throws Exception {
        SurfaceControl sc = mActivity.getChildSurfaceControl();
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        BufferFlinger bufferflinger = new BufferFlinger(BUFFER_COUNT, Color.GREEN);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        t.show(sc);

        while (state.keepRunning()) {
            bufferflinger.addBuffer(t, sc);
            t.apply();
        }
    }
}

