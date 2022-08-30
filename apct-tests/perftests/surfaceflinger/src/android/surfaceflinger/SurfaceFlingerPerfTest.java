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
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.view.SurfaceControl;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Random;

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

    @After
    public void teardown() {
        mSurfaceControls.forEach(SurfaceControl::release);
        mByfferTrackers.forEach(BufferFlinger::freeBuffers);
    }


    private ArrayList<BufferFlinger> mByfferTrackers = new ArrayList<>();
    private BufferFlinger createBufferTracker(int color) {
        BufferFlinger bufferTracker = new BufferFlinger(BUFFER_COUNT, color);
        mByfferTrackers.add(bufferTracker);
        return bufferTracker;
    }

    private ArrayList<SurfaceControl> mSurfaceControls = new ArrayList<>();
    private SurfaceControl createSurfaceControl() throws InterruptedException {
        SurfaceControl sc = mActivity.createChildSurfaceControl();
        mSurfaceControls.add(sc);
        return sc;
    }

    @Test
    public void singleBuffer() throws Exception {
        SurfaceControl sc = createSurfaceControl();
        BufferFlinger bufferTracker = createBufferTracker(Color.GREEN);
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        bufferTracker.addBuffer(t, sc);
        t.show(sc);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            bufferTracker.addBuffer(t, sc);
            t.apply();
        }
    }

    static int getRandomColorComponent() {
        return new Random().nextInt(155) + 100;
    }

    @Test
    public void multipleBuffers() throws Exception {
        final int MAX_BUFFERS = 10;

        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        for (int i = 0; i < MAX_BUFFERS; i++) {
            SurfaceControl sc = createSurfaceControl();
            BufferFlinger bufferTracker = createBufferTracker(Color.argb(getRandomColorComponent(),
                    getRandomColorComponent(), getRandomColorComponent(),
                    getRandomColorComponent()));
            bufferTracker.addBuffer(t, sc);
            t.setPosition(sc, i * 10, i * 10);
            t.show(sc);
        }
        t.apply(true);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < MAX_BUFFERS; i++) {
                mByfferTrackers.get(i).addBuffer(t, mSurfaceControls.get(i));
            }
            t.apply();
        }
    }

    @Test
    public void multipleOpaqueBuffers() throws Exception {
        final int MAX_BUFFERS = 10;

        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        for (int i = 0; i < MAX_BUFFERS; i++) {
            SurfaceControl sc = createSurfaceControl();
            BufferFlinger bufferTracker = createBufferTracker(Color.rgb(getRandomColorComponent(),
                    getRandomColorComponent(), getRandomColorComponent()));
            bufferTracker.addBuffer(t, sc);
            t.setOpaque(sc, true);
            t.setPosition(sc, i * 10, i * 10);
            t.show(sc);
        }
        t.apply(true);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < MAX_BUFFERS; i++) {
                mByfferTrackers.get(i).addBuffer(t, mSurfaceControls.get(i));
            }
            t.apply();
        }
    }

    @Test
    public void geometryChanges() throws Exception {
        final int MAX_POSITION = 10;
        final float MAX_SCALE = 2.0f;

        SurfaceControl sc = createSurfaceControl();
        BufferFlinger bufferTracker = createBufferTracker(Color.GREEN);
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        bufferTracker.addBuffer(t, sc);
        t.show(sc).apply(true);

        int step = 0;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            step = ++step % MAX_POSITION;
            t.setPosition(sc, step, step);
            float scale = ((step * MAX_SCALE) / MAX_POSITION) + 0.5f;
            t.setScale(sc, scale, scale);
            t.apply();
        }
    }

    @Test
    public void geometryWithBufferChanges() throws Exception {
        final int MAX_POSITION = 10;

        SurfaceControl sc = createSurfaceControl();
        BufferFlinger bufferTracker = createBufferTracker(Color.GREEN);
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        bufferTracker.addBuffer(t, sc);
        t.show(sc).apply(true);

        int step = 0;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            step = ++step % MAX_POSITION;
            t.setPosition(sc, step, step);
            float scale = ((step * 2.0f) / MAX_POSITION) + 0.5f;
            t.setScale(sc, scale, scale);
            bufferTracker.addBuffer(t, sc);
            t.apply();
        }
    }

    @Test
    public void addRemoveLayers() throws Exception {
        SurfaceControl sc = createSurfaceControl();
        BufferFlinger bufferTracker = createBufferTracker(Color.GREEN);
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            SurfaceControl childSurfaceControl =  new SurfaceControl.Builder()
                    .setName("childLayer").setBLASTLayer().build();
            bufferTracker.addBuffer(t, childSurfaceControl);
            t.reparent(childSurfaceControl, sc);
            t.apply();
            t.remove(childSurfaceControl).apply();
        }
    }

    @Test
    public void displayScreenshot() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Bitmap screenshot =
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            screenshot.recycle();
        }
    }

    @Test
    public void layerScreenshot() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Bitmap screenshot =
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot(
                            mActivity.getWindow());
            screenshot.recycle();
        }
    }

}
