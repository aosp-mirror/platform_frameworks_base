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

public class PerformanceTest extends
        ActivityInstrumentationTestCase2<ProfileActivity> {

    private class StatAggregator extends PlaybackGraphs {
        private HashMap<String, Double> mDataMap = new HashMap<String, Double>();
        private int mCount = 0;

        public void aggregate() {
            mCount++;
            Resources resources = mView.getResources();
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
            for (Map.Entry<String, Double> e : mSingleStats.entrySet()) {
                double aggVal = mDataMap.containsKey(e.getKey())
                        ? mDataMap.get(e.getKey()) : 0;
                mDataMap.put(e.getKey(), aggVal + e.getValue());
            }
        }

        public Bundle getBundle() {
            Bundle b = new Bundle();
            int count = 0 == mCount ? Integer.MAX_VALUE : mCount;
            for (Map.Entry<String, Double> e : mDataMap.entrySet()) {
                b.putDouble(e.getKey(), e.getValue() / count);
            }
            return b;
        }
    }

    ProfileActivity mActivity;
    ProfiledWebView mView;
    StatAggregator mStats = new StatAggregator();

    private static final String LOGTAG = "PerformanceTest";
    private static final String TEST_LOCATION = "webkit/page_cycler";
    private static final String URL_PREFIX = "file://";
    private static final String URL_POSTFIX = "/index.html?skip=true";
    private static final int MAX_ITERATIONS = 4;
    private static final String TEST_DIRS[] = {
        "intl1"//, "alexa_us", "android", "dom", "intl2", "moz", "moz2"
    };

    public PerformanceTest() {
        super(ProfileActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mActivity = getActivity();
        mView = (ProfiledWebView) mActivity.findViewById(R.id.web);
    }

    private boolean loadUrl(final String url) {
        try {
            Log.d(LOGTAG, "test starting for url " + url);
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mView.loadUrl(url);
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

    private boolean runIteration() {
        File sdFile = Environment.getExternalStorageDirectory();
        for (String testDirName : TEST_DIRS) {
            File testDir = new File(sdFile, TEST_LOCATION + "/" + testDirName);
            Log.d(LOGTAG, "Testing dir: '" + testDir.getAbsolutePath()
                    + "', exists=" + testDir.exists());
            for (File siteDir : testDir.listFiles()) {
                if (!siteDir.isDirectory())
                    continue;

                if (!loadUrl(URL_PREFIX + siteDir.getAbsolutePath()
                        + URL_POSTFIX)) {
                    return false;
                }
            }
        }
        return true;
    }

    public void testMetrics() {
        String state = Environment.getExternalStorageState();

        if (!Environment.MEDIA_MOUNTED.equals(state)
                && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            Log.d(LOGTAG, "ARG Can't access sd card!");
            // Can't read the SD card, fail and die!
            getInstrumentation().sendStatus(1, null);
            return;
        }

        // use mGraphs as a condition variable between the UI thread and
        // this(the testing) thread
        mActivity.setCallback(new ProfileCallback() {
            @Override
            public void profileCallback(RunData data) {
                Log.d(LOGTAG, "test completion callback");
                mStats.setData(data);
                synchronized (mStats) {
                    mStats.notify();
                }
            }
        });

        for (int i = 0; i < MAX_ITERATIONS; i++)
            if (!runIteration()) {
                getInstrumentation().sendStatus(1, null);
                return;
            }
        getInstrumentation().sendStatus(0, mStats.getBundle());
    }
}
