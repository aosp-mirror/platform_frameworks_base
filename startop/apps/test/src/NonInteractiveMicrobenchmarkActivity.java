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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;

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

public class NonInteractiveMicrobenchmarkActivity extends Activity {
    ArrayList<CharSequence> benchmarkNames = new ArrayList();
    ArrayList<Runnable> benchmarkThunks = new ArrayList();

    PrintStream out;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SystemServerBenchmarks.initializeBenchmarks(this, (name, thunk) -> {
            benchmarkNames.add(name);
            benchmarkThunks.add(thunk);
        });

        try {
            out = new PrintStream(new File(getExternalFilesDir(null), "benchmark.csv"));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        out.println("Name,Mean,Stdev");
        runBenchmarks(0);
    }

    void runBenchmarks(int i) {
        if (i < benchmarkNames.size()) {
            SystemServerBenchmarks.runBenchmarkInBackground(benchmarkThunks.get(i),
                    (mean, stdev) -> {
                        out.printf("%s,%.0f,%.0f\n", benchmarkNames.get(i), mean, stdev);
                        runBenchmarks(i + 1);
                    });
        }
    }
}
