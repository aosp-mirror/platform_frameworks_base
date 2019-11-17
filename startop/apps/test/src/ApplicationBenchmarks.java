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
import android.view.LayoutInflater;

final class ApplicationBenchmarks {

    public static final void initializeBenchmarks(Activity parent, BenchmarkRunner benchmarks) {
        LayoutInflater inflater = LayoutInflater.from(parent);

        benchmarks.addBenchmark("Complex Layout", () -> {
            inflater.inflate(R.layout.activity_main, null);
        });

        benchmarks.addBenchmark("TextView List Layout", () -> {
            inflater.inflate(R.layout.textview_list, null);
        });

        benchmarks.addBenchmark("FrameLayout List Layout", () -> {
            inflater.inflate(R.layout.framelayout_list, null);
        });
    }
}
