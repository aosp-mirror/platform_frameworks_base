/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.test.tilebenchmark;

import com.test.tilebenchmark.ProfileActivity.ProfileCallback;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import android.content.res.Resources;
import android.os.Bundle;
import android.os.Environment;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.webkit.WebSettings;
import android.widget.Spinner;

public class PerformanceTest extends
        ActivityInstrumentationTestCase2<ProfileActivity> {

    public static class AnimStat {
        double aggVal = 0;
        double aggSqrVal = 0;
        double count = 0;
    }

    private class StatAggregator extends PlaybackGraphs {
        private HashMap<String, Double> mDataMap = new HashMap<String, Double>();
        private HashMap<String, AnimStat> mAnimDataMap = new HashMap<String, AnimStat>();
        private int mCount = 0;


        public void aggregate() {
            boolean inAnimTests = mAnimTests != null;
            Resources resources = mWeb.getResources();
            String animFramerateString = resources.getString(R.string.animation_framerate);
            for (Map.Entry<String, Double> e : mSingleStats.entrySet()) {
                String name = e.getKey();
                if (inAnimTests) {
                    if (name.equals(animFramerateString)) {
                        // in animation testing phase, record animation framerate and aggregate
                        // stats, differentiating on values of mAnimTestNr and mDoubleBuffering
                        String fullName = ANIM_TEST_NAMES[mAnimTestNr] + " " + name;
                        fullName += mDoubleBuffering ? " tiled" : " webkit";

                        if (!mAnimDataMap.containsKey(fullName)) {
                            mAnimDataMap.put(fullName, new AnimStat());
                        }
                        AnimStat statVals = mAnimDataMap.get(fullName);
                        statVals.aggVal += e.getValue();
                        statVals.aggSqrVal += e.getValue() * e.getValue();
                        statVals.count += 1;
                    }
                } else {
                    double aggVal = mDataMap.containsKey(name)
                            ? mDataMap.get(name) : 0;
                    mDataMap.put(name, aggVal + e.getValue());
                }
            }

            if (inAnimTests) {
                return;
            }

            mCount++;
            for (int metricIndex = 0; metricIndex < Metrics.length; metricIndex++) {
                for (int statIndex = 0; statIndex < Stats.length; statIndex++) {
                    String metricLabel = resources.getString(
                            Metrics[metricIndex].getLabelId());
                    String statLabel = resources.getString(
                            Stats[statIndex].getLabelId());

                    String label = metricLabel + " " + statLabel;
                    double aggVal = mDataMap.containsKey(label) ? mDataMap
                            .get(label) : 0;

                    aggVal += mStats[metricIndex][statIndex];
                    mDataMap.put(label, aggVal);
                }
            }

        }

        // build the final bundle of results
        public Bundle getBundle() {
            Bundle b = new Bundle();
            int count = (0 == mCount) ? Integer.MAX_VALUE : mCount;
            for (Map.Entry<String, Double> e : mDataMap.entrySet()) {
                b.putDouble(e.getKey(), e.getValue() / count);
            }

            for (Map.Entry<String, AnimStat> e : mAnimDataMap.entrySet()) {
                String statName = e.getKey();
                AnimStat statVals = e.getValue();

                double avg = statVals.aggVal/statVals.count;
                double stdDev = Math.sqrt((statVals.aggSqrVal / statVals.count) - avg * avg);

                b.putDouble(statName, avg);
                b.putDouble(statName + " STD DEV", stdDev);
            }

            return b;
        }
    }

    ProfileActivity mActivity;
    ProfiledWebView mWeb;
    Spinner mMovementSpinner;
    StatAggregator mStats;

    private static final String LOGTAG = "PerformanceTest";
    private static final String TEST_LOCATION = "webkit/page_cycler";
    private static final String URL_PREFIX = "file://";
    private static final String URL_POSTFIX = "/index.html?skip=true";
    private static final int MAX_ITERATIONS = 4;
    private static final String SCROLL_TEST_DIRS[] = {
        "alexa25_2011"
    };
    private static final String ANIM_TEST_DIRS[] = {
        "dhtml"
    };

    public PerformanceTest() {
        super(ProfileActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mActivity = getActivity();
        mWeb = (ProfiledWebView) mActivity.findViewById(R.id.web);
        mMovementSpinner = (Spinner) mActivity.findViewById(R.id.movement);
        mStats = new StatAggregator();

        // use mStats as a condition variable between the UI thread and
        // this(the testing) thread
        mActivity.setCallback(new ProfileCallback() {
            @Override
            public void profileCallback(RunData data) {
                mStats.setData(data);
                synchronized (mStats) {
                    mStats.notify();
                }
            }
        });

    }

    private boolean loadUrl(final String url) {
        try {
            Log.d(LOGTAG, "test starting for url " + url);
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mWeb.loadUrl(url);
                }
            });
            synchronized (mStats) {
                mStats.wait();
            }

            mStats.aggregate();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean validTest(String nextTest) {
        // if testing animations, test must be in mAnimTests
        if (mAnimTests == null)
            return true;

        for (String test : mAnimTests) {
            if (test.equals(nextTest)) {
                return true;
            }
        }
        return false;
    }

    private boolean runIteration(String[] testDirs) {
        File sdFile = Environment.getExternalStorageDirectory();
        for (String testDirName : testDirs) {
            File testDir = new File(sdFile, TEST_LOCATION + "/" + testDirName);
            Log.d(LOGTAG, "Testing dir: '" + testDir.getAbsolutePath()
                    + "', exists=" + testDir.exists());

            for (File siteDir : testDir.listFiles()) {
                if (!siteDir.isDirectory() || !validTest(siteDir.getName())) {
                    continue;
                }

                if (!loadUrl(URL_PREFIX + siteDir.getAbsolutePath()
                        + URL_POSTFIX)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean  runTestDirs(String[] testDirs) {
        for (int i = 0; i < MAX_ITERATIONS; i++)
            if (!runIteration(testDirs)) {
                return false;
            }
        return true;
    }

    private void pushDoubleBuffering() {
        getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                mWeb.setDoubleBuffering(mDoubleBuffering);
            }
        });
    }

    private void setScrollingTestingMode(final boolean scrolled) {
        getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                mMovementSpinner.setSelection(scrolled ? 0 : 2);
            }
        });
    }


    private String[] mAnimTests = null;
    private int mAnimTestNr = -1;
    private boolean mDoubleBuffering = true;
    private static final String[] ANIM_TEST_NAMES = {
        "slow", "fast"
    };
    private static final String[][] ANIM_TESTS = {
        {"scrolling", "replaceimages", "layers5", "layers1"},
        {"slidingballs", "meter", "slidein", "fadespacing", "colorfade",
                "mozilla", "movingtext", "diagball", "zoom", "imageslide"},
    };

    private boolean checkMedia() {
        String state = Environment.getExternalStorageState();

        if (!Environment.MEDIA_MOUNTED.equals(state)
                && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            Log.d(LOGTAG, "ARG Can't access sd card!");
            // Can't read the SD card, fail and die!
            getInstrumentation().sendStatus(1, null);
            return false;
        }
        return true;
    }

    public void testMetrics() {
        setScrollingTestingMode(true);
        if (checkMedia() && runTestDirs(SCROLL_TEST_DIRS)) {
            getInstrumentation().sendStatus(0, mStats.getBundle());
        } else {
            getInstrumentation().sendStatus(1, null);
        }
    }

    public void testMetricsMinimalMemory() {
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mWeb.setUseMinimalMemory(true);
            }
        });

        setScrollingTestingMode(true);
        if (checkMedia() && runTestDirs(SCROLL_TEST_DIRS)) {
            getInstrumentation().sendStatus(0, mStats.getBundle());
        } else {
            getInstrumentation().sendStatus(1, null);
        }
    }

    private boolean runAnimationTests() {
        for (int doubleBuffer = 0; doubleBuffer <= 1; doubleBuffer++) {
            mDoubleBuffering = doubleBuffer == 1;
            pushDoubleBuffering();
            for (mAnimTestNr = 0; mAnimTestNr < ANIM_TESTS.length; mAnimTestNr++) {
                mAnimTests = ANIM_TESTS[mAnimTestNr];
                if (!runTestDirs(ANIM_TEST_DIRS)) {
                    return false;
                }
            }
        }
        return true;
    }

    public void testAnimations() {
        // instead of autoscrolling, load each page until either an timer fires,
        // or the animation signals complete via javascript
        setScrollingTestingMode(false);

        if (checkMedia() && runAnimationTests()) {
            getInstrumentation().sendStatus(0, mStats.getBundle());
        } else {
            getInstrumentation().sendStatus(1, null);
        }
    }
}
