/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.test.uibench;

import android.content.Context;
import android.os.Trace;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import com.android.test.uibench.recyclerview.RvBoxAdapter;
import com.android.test.uibench.recyclerview.RvCompatListActivity;

import java.util.concurrent.TimeUnit;

public class SlowBindRecyclerViewActivity extends RvCompatListActivity {
    /**
     * Spin wait. Used instead of sleeping so a core is used up for the duration, and so
     * traces/sampled profiling show the sections as expensive, and not just a scheduling mistake.
     */
    private static void spinWaitMs(long ms) {
        long start = System.nanoTime();
        while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(ms));
    }

    @Override
    protected RecyclerView.LayoutManager createLayoutManager(Context context) {
        return new GridLayoutManager(context, 3);
    }

    @Override
    protected RecyclerView.Adapter createAdapter() {
        return new RvBoxAdapter(this, TextUtils.buildSimpleStringList()) {
            @Override
            public void onBindViewHolder(ViewHolder holder, int position) {
                Trace.beginSection("bind item " + position);

                spinWaitMs(3);
                super.onBindViewHolder(holder, position);
                Trace.endSection();
            }
        };
    }
}
