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
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Trace;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;

import java.util.Arrays;

class Benchmark {
    public static final int NUM_ITERATIONS = 1000;

    public Benchmark(ViewGroup parent, CharSequence name, Runnable thunk) {
        Context context = parent.getContext();
        Button button = new Button(context);
        TextView mean = new TextView(context);
        TextView stdev = new TextView(context);

        button.setText(name);
        mean.setText("");
        stdev.setText("");

        button.setOnClickListener((_button) -> {
            mean.setText("Running...");
            stdev.setText("");

            new AsyncTask() {
                double resultMean = 0;
                double resultStdev = 0;

                @Override
                protected Object doInBackground(Object... _args) {
                    long[] results = new long[NUM_ITERATIONS];

                    // Run benchmark
                    for (int i = 0; i < results.length; i++) {
                        results[i] = -System.nanoTime();
                        thunk.run();
                        results[i] += System.nanoTime();
                    }

                    // Compute mean
                    long sum = Arrays.stream(results).sum();
                    resultMean = (double) sum / results.length;

                    // Compute standard deviation
                    double variance = 0;
                    for (long i : results) {
                        double t = (double) i - resultMean;
                        variance += t * t;
                    }
                    variance /= results.length - 1;

                    resultStdev = Math.sqrt(variance);

                    return null;
                }

                @Override
                protected void onPostExecute(Object _result) {
                    mean.setText(String.format("%.3f", resultMean / 1e6));
                    stdev.setText(String.format("%.3f", resultStdev / 1e6));
                }
            }.execute(new Object());
        });

        parent.addView(button);
        parent.addView(mean);
        parent.addView(stdev);
    }
}

public class SystemServerBenchmarkActivity extends Activity {
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.system_server_benchmark_page);

        GridLayout benchmarkList = findViewById(R.id.benchmark_list);

        new Benchmark(benchmarkList, "Empty", () -> {
        });

        PackageManager pm = getPackageManager();
        new Benchmark(benchmarkList, "getInstalledApplications", () -> {
            pm.getInstalledApplications(PackageManager.MATCH_SYSTEM_ONLY);
        });

        new Benchmark(benchmarkList, "getInstalledPackages", () -> {
            pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
        });

        new Benchmark(benchmarkList, "getPackageInfo", () -> {
            try {
                pm.getPackageInfo("com.android.startop.test", 0);
            } catch (NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        new Benchmark(benchmarkList, "getApplicationInfo", () -> {
            try {
                pm.getApplicationInfo("com.android.startop.test", 0);
            } catch (NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        try {
            ApplicationInfo app = pm.getApplicationInfo("com.android.startop.test", 0);
            new Benchmark(benchmarkList, "getResourcesForApplication", () -> {
                try {
                    pm.getResourcesForApplication(app);
                } catch (NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        ComponentName component = new ComponentName(this, this.getClass());
        new Benchmark(benchmarkList, "getActivityInfo", () -> {
            try {
                pm.getActivityInfo(component, PackageManager.GET_META_DATA);
            } catch (NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

    }
}
