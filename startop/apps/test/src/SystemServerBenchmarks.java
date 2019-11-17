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
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.os.Process;
import android.os.UserManager;

/**
 * An interface for running benchmarks and collecting results. Used so we can have both an
 * interactive runner and a non-interactive runner.
 */
interface BenchmarkRunner {
    void addBenchmark(CharSequence name, Runnable thunk);
}

interface ResultListener {
    /**
     * Called when a benchmark result is ready
     *
     * @param mean  The average iteration time in nanoseconds
     * @param stdev The standard deviation of iteration times in nanoseconds
     */
    void onResult(double mean, double stdev);
}

class SystemServerBenchmarks {
    // Time limit to run benchmarks in seconds
    public static final int TIME_LIMIT = 5;

    static void initializeBenchmarks(Activity parent, BenchmarkRunner benchmarks) {
        final String packageName = parent.getPackageName();

        PackageManager pm = parent.getPackageManager();
        benchmarks.addBenchmark("getInstalledApplications", () -> {
            pm.getInstalledApplications(PackageManager.MATCH_SYSTEM_ONLY);
        });

        benchmarks.addBenchmark("getInstalledPackages", () -> {
            pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
        });

        benchmarks.addBenchmark("getPackageInfo", () -> {
            try {
                pm.getPackageInfo(packageName, 0);
            } catch (NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        benchmarks.addBenchmark("getApplicationInfo", () -> {
            try {
                pm.getApplicationInfo(packageName, 0);
            } catch (NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        try {
            ApplicationInfo app = pm.getApplicationInfo(packageName, 0);
            benchmarks.addBenchmark("getResourcesForApplication", () -> {
                try {
                    pm.getResourcesForApplication(app);
                } catch (NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            });

            benchmarks.addBenchmark("getPackagesForUid", () -> {
                pm.getPackagesForUid(app.uid);
            });
        } catch (NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        ComponentName component = new ComponentName(parent, parent.getClass());
        benchmarks.addBenchmark("getActivityInfo", () -> {
            try {
                pm.getActivityInfo(component, PackageManager.GET_META_DATA);
            } catch (NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        benchmarks.addBenchmark("getLaunchIntentForPackage", () -> {
            pm.getLaunchIntentForPackage(packageName);
        });

        benchmarks.addBenchmark("getPackageUid", () -> {
            try {
                pm.getPackageUid(packageName, 0);
            } catch (NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        benchmarks.addBenchmark("checkPermission", () -> {
            // Check for the first permission I could find.
            pm.checkPermission("android.permission.SEND_SMS", packageName);
        });

        benchmarks.addBenchmark("checkSignatures", () -> {
            // Compare with settings, since settings is on both AOSP and Master builds
            pm.checkSignatures("com.android.settings", packageName);
        });

        Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
        benchmarks.addBenchmark("queryBroadcastReceivers", () -> {
            pm.queryBroadcastReceivers(intent, 0);
        });

        benchmarks.addBenchmark("hasSystemFeature", () -> {
            pm.hasSystemFeature(PackageManager.FEATURE_CAMERA);
        });

        benchmarks.addBenchmark("resolveService", () -> {
            pm.resolveService(intent, 0);
        });

        ActivityManager am = (ActivityManager) parent.getSystemService(Context.ACTIVITY_SERVICE);
        benchmarks.addBenchmark("getRunningAppProcesses", () -> {
            am.getRunningAppProcesses();
        });

        // We use PendingIntent.getCreatorPackage, since
        // getPackageIntentForSender is not public to us, but getCreatorPackage
        // is just a thin wrapper around it.
        PendingIntent pi = PendingIntent.getActivity(parent, 0, new Intent(), 0);
        benchmarks.addBenchmark("getPackageIntentForSender", () -> {
            pi.getCreatorPackage();
        });

        PowerManager pwr = (PowerManager) parent.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pwr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "benchmark tag");
        benchmarks.addBenchmark("WakeLock Acquire/Release", () -> {
            wl.acquire();
            wl.release();
        });

        AppOpsManager appOps = (AppOpsManager) parent.getSystemService(Context.APP_OPS_SERVICE);
        int uid = Process.myUid();
        benchmarks.addBenchmark("AppOpsService.checkOperation", () -> {
            appOps.checkOp(AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE, uid, packageName);
        });

        benchmarks.addBenchmark("AppOpsService.checkPackage", () -> {
            appOps.checkPackage(uid, packageName);
        });

        benchmarks.addBenchmark("AppOpsService.noteOperation", () -> {
            appOps.noteOp(AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE, uid, packageName);
        });

        benchmarks.addBenchmark("AppOpsService.noteProxyOperation", () -> {
            appOps.noteProxyOp(AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE, packageName);
        });

        UserManager userManager = (UserManager) parent.getSystemService(Context.USER_SERVICE);
        benchmarks.addBenchmark("isUserUnlocked", () -> {
            userManager.isUserUnlocked();
        });

        benchmarks.addBenchmark("getIntentSender", () -> {
            pi.getIntentSender();
        });

        ConnectivityManager cm = (ConnectivityManager) parent
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        benchmarks.addBenchmark("getActiveNetworkInfo", () -> {
            cm.getActiveNetworkInfo();
        });
    }

    /**
     * A helper method for benchark runners to actually run the benchmark and gather stats
     *
     * @param thunk    The code whose performance we want to measure
     * @param reporter What to do with the results
     */
    static void runBenchmarkInBackground(Runnable thunk, ResultListener reporter) {
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
                reporter.onResult(resultMean, resultStdev);
            }
        }.execute(new Object());
    }
}
