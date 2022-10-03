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

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceControl;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Random;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class SurfaceFlingerPerfTest {
    private static final String TAG = "SurfaceFlingerPerfTest";
    private final ActivityScenarioRule<SurfaceFlingerTestActivity> mActivityRule =
            new ActivityScenarioRule<>(SurfaceFlingerTestActivity.class);
    private SurfaceFlingerTestActivity mActivity;
    private static final int BUFFER_COUNT = 2;
    private static final int MAX_BUFFERS = 10;
    private static final int MAX_POSITION = 10;
    private static final float MAX_SCALE = 2.0f;

    private static final String ARGUMENT_PROFILING_ITERATIONS = "profiling-iterations";
    private static final String DEFAULT_PROFILING_ITERATIONS = "100";
    private static int sProfilingIterations;
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();

    @Rule
    public final RuleChain mAllRules = RuleChain
            .outerRule(mActivityRule);

    @BeforeClass
    public static void suiteSetup() {
        final Bundle arguments = InstrumentationRegistry.getArguments();
        sProfilingIterations = Integer.parseInt(
                arguments.getString(ARGUMENT_PROFILING_ITERATIONS, DEFAULT_PROFILING_ITERATIONS));
        Log.d(TAG, "suiteSetup: mProfilingIterations = " + sProfilingIterations);
    }

    @Before
    public void setup() {
        mActivityRule.getScenario().onActivity(activity -> mActivity = activity);
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        for (int i = 0; i < MAX_BUFFERS; i++) {
            SurfaceControl sc = createSurfaceControl();
            BufferFlinger bufferTracker = createBufferTracker(Color.argb(getRandomColorComponent(),
                    getRandomColorComponent(), getRandomColorComponent(),
                    getRandomColorComponent()));
            bufferTracker.addBuffer(t, sc);
            t.setPosition(sc, i * 10, i * 10);
        }
        t.apply(true);
    }

    @After
    public void teardown() {
        mSurfaceControls.forEach(SurfaceControl::release);
        mBufferTrackers.forEach(BufferFlinger::freeBuffers);
    }

    static int getRandomColorComponent() {
        return new Random().nextInt(155) + 100;
    }

    private final ArrayList<BufferFlinger> mBufferTrackers = new ArrayList<>();
    private BufferFlinger createBufferTracker(int color) {
        BufferFlinger bufferTracker = new BufferFlinger(BUFFER_COUNT, color);
        mBufferTrackers.add(bufferTracker);
        return bufferTracker;
    }

    private final ArrayList<SurfaceControl> mSurfaceControls = new ArrayList<>();
    private SurfaceControl createSurfaceControl() {
        SurfaceControl sc = mActivity.createChildSurfaceControl();
        mSurfaceControls.add(sc);
        return sc;
    }

    @Test
    public void singleBuffer() throws Exception {
        for (int i = 0; i < sProfilingIterations; i++) {
            mBufferTrackers.get(0).addBuffer(mTransaction, mSurfaceControls.get(0));
            mTransaction.show(mSurfaceControls.get(0)).apply(true);
        }
    }

    @Test
    public void multipleBuffers() throws Exception {
        for (int j = 0; j < sProfilingIterations; j++) {
            for (int i = 0; i < MAX_BUFFERS; i++) {
                mBufferTrackers.get(i).addBuffer(mTransaction, mSurfaceControls.get(i));
                mTransaction.show(mSurfaceControls.get(i));
            }
            mTransaction.apply(true);
        }
    }

    @Test
    public void multipleOpaqueBuffers() throws Exception {
        for (int j = 0; j < sProfilingIterations; j++) {
            for (int i = 0; i < MAX_BUFFERS; i++) {
                mBufferTrackers.get(i).addBuffer(mTransaction, mSurfaceControls.get(i));
                mTransaction.show(mSurfaceControls.get(i)).setOpaque(mSurfaceControls.get(i), true);
            }
            mTransaction.apply(true);
        }
    }

    @Test
    public void geometryChanges() throws Exception {
        int step = 0;
        for (int i = 0; i < sProfilingIterations; i++) {
            step = ++step % MAX_POSITION;
            mTransaction.setPosition(mSurfaceControls.get(0), step, step);
            float scale = ((step * MAX_SCALE) / MAX_POSITION) + 0.5f;
            mTransaction.setScale(mSurfaceControls.get(0), scale, scale);
            mTransaction.show(mSurfaceControls.get(0)).apply(true);
        }
    }

    @Test
    public void geometryWithBufferChanges() throws Exception {
        int step = 0;
        for (int i = 0; i < sProfilingIterations; i++) {
            step = ++step % MAX_POSITION;
            mTransaction.setPosition(mSurfaceControls.get(0), step, step);
            float scale = ((step * MAX_SCALE) / MAX_POSITION) + 0.5f;
            mTransaction.setScale(mSurfaceControls.get(0), scale, scale);
            mBufferTrackers.get(0).addBuffer(mTransaction, mSurfaceControls.get(0));
            mTransaction.show(mSurfaceControls.get(0)).apply(true);
        }
    }

    @Test
    public void addRemoveLayers() throws Exception {
        for (int i = 0; i < sProfilingIterations; i++) {
            SurfaceControl childSurfaceControl =  new SurfaceControl.Builder()
                    .setName("childLayer").setBLASTLayer().build();
            mBufferTrackers.get(0).addBuffer(mTransaction, childSurfaceControl);
            mTransaction.reparent(childSurfaceControl, mSurfaceControls.get(0));
            mTransaction.show(childSurfaceControl).show(mSurfaceControls.get(0));
            mTransaction.apply(true);
            mTransaction.remove(childSurfaceControl).apply(true);
        }
    }

    @Test
    public void displayScreenshot() throws Exception {
        for (int i = 0; i < sProfilingIterations; i++) {
            Bitmap screenshot =
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            screenshot.recycle();
            mTransaction.apply(true);
        }
    }

    @Test
    public void layerScreenshot() throws Exception {
        for (int i = 0; i < sProfilingIterations; i++) {
            Bitmap screenshot =
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot(
                            mActivity.getWindow());
            screenshot.recycle();
            mTransaction.apply(true);
        }
    }
}
