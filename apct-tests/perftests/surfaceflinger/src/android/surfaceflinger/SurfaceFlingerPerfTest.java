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

import static android.server.wm.CtsWindowInfoUtils.waitForWindowOnTop;
import static android.provider.Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS;

import android.app.Instrumentation;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.SystemUtil;
import com.android.helpers.SimpleperfHelper;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.file.Path;
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

    private int mTransformHint;
    private SimpleperfHelper mSimpleperfHelper = new SimpleperfHelper();
    private static String sImmersiveModeConfirmationValue;
    /** Start simpleperf sampling. */
    public void startSimpleperf(String subcommand, String arguments) {
        if (!mSimpleperfHelper.startCollecting(subcommand, arguments)) {
            Log.e(TAG, "Simpleperf did not start successfully.");
        }
    }

    /** Stop simpleperf sampling and dump the collected file into the given path. */
    private void stopSimpleperf(Path path) {
        if (!mSimpleperfHelper.stopCollecting(path.toString())) {
            Log.e(TAG, "Failed to collect the simpleperf output.");
        }
    }

    @BeforeClass
    public static void suiteSetup() {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            // hide immersive mode confirmation dialog
            final ContentResolver resolver =
                    InstrumentationRegistry.getInstrumentation().getContext().getContentResolver();
            sImmersiveModeConfirmationValue =
                    Settings.Secure.getString(resolver, IMMERSIVE_MODE_CONFIRMATIONS);
            Settings.Secure.putString(
                    resolver,
                    IMMERSIVE_MODE_CONFIRMATIONS,
                    "confirmed");
        });
        final Bundle arguments = InstrumentationRegistry.getArguments();
        sProfilingIterations = Integer.parseInt(
                arguments.getString(ARGUMENT_PROFILING_ITERATIONS, DEFAULT_PROFILING_ITERATIONS));
        Log.d(TAG, "suiteSetup: mProfilingIterations = " + sProfilingIterations);
        // disable transaction tracing
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand("service call SurfaceFlinger 1041 i32 -1");
    }

    @AfterClass
    public static void suiteTeardown() {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            // Restore the immersive mode confirmation state.
            Settings.Secure.putString(
                    InstrumentationRegistry.getInstrumentation().getContext().getContentResolver(),
                    IMMERSIVE_MODE_CONFIRMATIONS,
                    sImmersiveModeConfirmationValue);
        });
    }


    @Before
    public void setup() {
        mActivityRule.getScenario().onActivity(activity -> mActivity = activity);
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        for (int i = 0; i < MAX_BUFFERS; i++) {
            SurfaceControl sc = createSurfaceControl();
            BufferFlinger bufferTracker =
                    createBufferTracker(
                            Color.argb(
                                    getRandomColorComponent(),
                                    getRandomColorComponent(),
                                    getRandomColorComponent(),
                                    getRandomColorComponent()),
                            mActivity.getBufferTransformHint());
            bufferTracker.addBuffer(t, sc);
            t.setPosition(sc, i * 10, i * 10);
        }
        t.apply(true);
        mBufferTrackers.get(0).addBuffer(mTransaction, mSurfaceControls.get(0));
        mTransaction.show(mSurfaceControls.get(0)).apply(true);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        instrumentation.waitForIdleSync();
        // Wait for device animation that shows above the activity to leave.
        try {
            waitForWindowOnTop(mActivity.getWindow());
        } catch (InterruptedException e) {
            Log.e(TAG, "Failed to wait for window", e);
        }
        String args =
                "-o /data/local/tmp/perf.data -g -e"
                    + " instructions,cpu-cycles,raw-l3d-cache-refill,sched:sched_waking -p "
                        + mSimpleperfHelper.getPID("surfaceflinger")
                        + ","
                        + mSimpleperfHelper.getPID("android.perftests.surfaceflinger");
        startSimpleperf("record", args);
    }

    @After
    public void teardown() {
        try {
            mSimpleperfHelper.stopSimpleperf();
        } catch (IOException e) {
            Log.e(TAG, "Failed to stop simpleperf", e);
        }
        mSurfaceControls.forEach(SurfaceControl::release);
        mBufferTrackers.forEach(BufferFlinger::freeBuffers);
    }

    static int getRandomColorComponent() {
        return new Random().nextInt(155) + 100;
    }

    private final ArrayList<BufferFlinger> mBufferTrackers = new ArrayList<>();

    private BufferFlinger createBufferTracker(int color, int bufferTransformHint) {
        BufferFlinger bufferTracker = new BufferFlinger(BUFFER_COUNT, color, bufferTransformHint);
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

    @Test
    public void bufferQueue() throws Exception {
        SurfaceView testSV = mActivity.mTestSurfaceView;
        SurfaceHolder holder = testSV.getHolder();
        holder.getSurface();
        for (int i = 0; i < sProfilingIterations; i++) {
            Canvas canvas = holder.lockCanvas();
            holder.unlockCanvasAndPost(canvas);
            mTransaction.apply(true);
        }
    }
}
