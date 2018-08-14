/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the
 * License.
 *
 */

package com.android.benchmark.registry;

import android.content.ComponentName;
import android.content.Intent;
import android.view.View;
import android.widget.CheckBox;

/**
 * Logical grouping of benchmarks
 */
public class BenchmarkGroup {
    public static final String BENCHMARK_EXTRA_ENABLED_TESTS =
            "com.android.benchmark.EXTRA_ENABLED_BENCHMARK_IDS";

    public static final String BENCHMARK_EXTRA_RUN_COUNT =
            "com.android.benchmark.EXTRA_RUN_COUNT";
    public static final String BENCHMARK_EXTRA_FINISH = "com.android.benchmark.FINISH_WHEN_DONE";

    public static class Benchmark implements CheckBox.OnClickListener {
        /** The name of this individual benchmark test */
        private final String mName;

        /** The category of this individual benchmark test */
        private final @BenchmarkCategory int mCategory;

        /** Human-readable description of the benchmark */
        private final String mDescription;

        private final int mId;

        private boolean mEnabled;

        Benchmark(int id, String name, @BenchmarkCategory int category, String description) {
            mId = id;
            mName = name;
            mCategory = category;
            mDescription = description;
            mEnabled = true;
        }

        public boolean isEnabled() { return mEnabled; }

        public void setEnabled(boolean enabled) { mEnabled = enabled; }

        public int getId() { return mId; }

        public String getDescription() { return mDescription; }

        public @BenchmarkCategory int getCategory() { return mCategory; }

        public String getName() { return mName; }

        @Override
        public void onClick(View view) {
            setEnabled(((CheckBox)view).isChecked());
        }
    }

    /**
     * Component for this benchmark group.
     */
    private final ComponentName mComponentName;

    /**
     * Benchmark title, showed in the {@link android.widget.ListView}
     */
    private final String mTitle;

    /**
     * List of all benchmarks exported by this group
     */
    private final Benchmark[] mBenchmarks;

    /**
     * The intent to launch the benchmark
     */
    private final Intent mIntent;

    /** Human-readable description of the benchmark group */
    private final String mDescription;

    BenchmarkGroup(ComponentName componentName, String title,
                   String description, Benchmark[] benchmarks, Intent intent) {
        mComponentName = componentName;
        mTitle = title;
        mBenchmarks = benchmarks;
        mDescription = description;
        mIntent = intent;
    }

    public Intent getIntent() {
        int[] enabledBenchmarksIds = getEnabledBenchmarksIds();
        if (enabledBenchmarksIds.length != 0) {
            mIntent.putExtra(BENCHMARK_EXTRA_ENABLED_TESTS, enabledBenchmarksIds);
            return mIntent;
        }

        return null;
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    public String getTitle() {
        return mTitle;
    }

    public Benchmark[] getBenchmarks() {
        return mBenchmarks;
    }

    public String getDescription() {
        return mDescription;
    }

    private int[] getEnabledBenchmarksIds() {
        int enabledBenchmarkCount = 0;
        for (int i = 0; i < mBenchmarks.length; i++) {
            if (mBenchmarks[i].isEnabled()) {
                enabledBenchmarkCount++;
            }
        }

        int writeIndex = 0;
        int[] enabledBenchmarks = new int[enabledBenchmarkCount];
        for (int i = 0; i < mBenchmarks.length; i++) {
            if (mBenchmarks[i].isEnabled()) {
                enabledBenchmarks[writeIndex++] = mBenchmarks[i].getId();
            }
        }

        return enabledBenchmarks;
    }
}
