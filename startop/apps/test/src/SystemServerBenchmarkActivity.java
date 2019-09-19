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
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;


class Benchmark {
    // Time limit to run benchmarks in seconds
    public static final int TIME_LIMIT = 5;

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
                    long startTime = System.nanoTime();
                    int count = 0;

                    // Run benchmark
                    while (true) {
                        long elapsed = -System.nanoTime();
                        thunk.run();
                        elapsed += System.nanoTime();

                        count++;
                        double elapsedVariance = (double) elapsed - resultMean;
                        resultMean += elapsedVariance / count;
                        resultStdev += elapsedVariance * ((double) elapsed - resultMean);

                        if (System.nanoTime() - startTime > TIME_LIMIT * 1e9) {
                            break;
                        }
                    }
                    resultStdev = Math.sqrt(resultStdev / (count - 1));

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

        new Benchmark(benchmarkList, "CPU Intensive (1 thread)", () -> {
            CPUIntensive.doSomeWork(1);
        });

        new Benchmark(benchmarkList, "CPU Intensive (2 thread)", () -> {
            CPUIntensive.doSomeWork(2);
        });

        new Benchmark(benchmarkList, "CPU Intensive (4 thread)", () -> {
            CPUIntensive.doSomeWork(4);
        });

        new Benchmark(benchmarkList, "CPU Intensive (8 thread)", () -> {
            CPUIntensive.doSomeWork(8);
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

            new Benchmark(benchmarkList, "getPackagesForUid", () -> {
                pm.getPackagesForUid(app.uid);
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

        new Benchmark(benchmarkList, "getLaunchIntentForPackage", () -> {
            pm.getLaunchIntentForPackage("com.android.startop.test");
        });

        new Benchmark(benchmarkList, "getPackageUid", () -> {
            try {
                pm.getPackageUid("com.android.startop.test", 0);
            } catch (NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        new Benchmark(benchmarkList, "checkPermission", () -> {
            // Check for the first permission I could find.
            pm.checkPermission("android.permission.SEND_SMS", "com.android.startop.test");
        });

        new Benchmark(benchmarkList, "checkSignatures", () -> {
            // Compare with settings, since settings is on both AOSP and Master builds
            pm.checkSignatures("com.android.settings", "com.android.startop.test");
        });

        Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
        new Benchmark(benchmarkList, "queryBroadcastReceivers", () -> {
            pm.queryBroadcastReceivers(intent, 0);
        });

        new Benchmark(benchmarkList, "hasSystemFeature", () -> {
            pm.hasSystemFeature(PackageManager.FEATURE_CAMERA);
        });

        new Benchmark(benchmarkList, "resolveService", () -> {
            pm.resolveService(intent, 0);
        });

        ActivityManager am = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        new Benchmark(benchmarkList, "getRunningAppProcesses", () -> {
            am.getRunningAppProcesses();
        });

    }
}
