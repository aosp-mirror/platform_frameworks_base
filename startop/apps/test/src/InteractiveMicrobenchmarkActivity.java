/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.startop.test;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;

public class InteractiveMicrobenchmarkActivity extends Activity implements BenchmarkRunner {
    protected GridLayout mBenchmarkList;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.system_server_benchmark_page);

        mBenchmarkList = findViewById(R.id.benchmark_list);

        addBenchmark("Empty", () -> {
        });
        addHeader("Application Benchmarks");
        ApplicationBenchmarks.initializeBenchmarks(this, this);
        addHeader("CPU Intensive Benchmarks");
        CPUIntensiveBenchmarks.initializeBenchmarks(this, this);
        addHeader("Init Check Overhead Benchmarks");
        InitCheckOverheadBenchmarks.initializeBenchmarks(this, this);
        addHeader("System Server Benchmarks");
        SystemServerBenchmarks.initializeBenchmarks(this, this);
    }

    /**
     * Add a heading for a group of related benchmarks
     *
     * @param name The name of this group of benchmarks
     */
    public void addHeader(CharSequence name) {
        Context context = mBenchmarkList.getContext();
        TextView header = new TextView(context);
        header.setText(name);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.columnSpec = GridLayout.spec(0, 3);
        mBenchmarkList.addView(header, params);
    }

    /**
     * Adds a benchmark to the set to run.
     *
     * @param name A short name that shows up in the UI or benchmark results
     */
    public void addBenchmark(CharSequence name, Runnable thunk) {
        Context context = mBenchmarkList.getContext();
        Button button = new Button(context);
        TextView mean = new TextView(context);
        TextView stdev = new TextView(context);

        button.setText(name);
        mean.setText("");
        stdev.setText("");

        button.setOnClickListener((_button) -> {
            mean.setText("Running...");
            stdev.setText("");

            SystemServerBenchmarks.runBenchmarkInBackground(thunk, (resultMean, resultStdev) -> {
                mean.setText(String.format("%.3f", resultMean / 1e6));
                stdev.setText(String.format("%.3f", resultStdev / 1e6));
            });
        });

        mBenchmarkList.addView(button);
        mBenchmarkList.addView(mean);
        mBenchmarkList.addView(stdev);
    }
}
