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

package com.android.benchmark.ui;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.view.FrameMetrics;
import android.view.MotionEvent;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListAdapter;

import com.android.benchmark.R;
import com.android.benchmark.ui.automation.Automator;
import com.android.benchmark.ui.automation.Interaction;

import java.io.File;
import java.util.List;

public class ListViewScrollActivity extends ListActivityBase {

    private static final int LIST_SIZE = 400;
    private static final int INTERACTION_COUNT = 4;

    private Automator mAutomator;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int runId = getIntent().getIntExtra("com.android.benchmark.RUN_ID", 0);
        final int iteration = getIntent().getIntExtra("com.android.benchmark.ITERATION", -1);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(getTitle());
        }

        mAutomator = new Automator(getName(), runId, iteration, getWindow(),
                new Automator.AutomateCallback() {
            @Override
            public void onPostAutomate() {
                Intent result = new Intent();
                setResult(RESULT_OK, result);
                finish();
            }

            @Override
            public void onPostInteraction(List<FrameMetrics> metrics) {}

            @Override
            public void onAutomate() {
                FrameLayout v = (FrameLayout) findViewById(R.id.list_fragment_container);

                int[] coordinates = new int[2];
                v.getLocationOnScreen(coordinates);

                int x = coordinates[0];
                int y = coordinates[1];

                float width = v.getWidth();
                float height = v.getHeight();

                float middleX = (x + width) / 5;
                float middleY = (y + height) / 5;

                Interaction flingUp = Interaction.newFlingUp(middleX, middleY);
                Interaction flingDown = Interaction.newFlingDown(middleX, middleY);

                for (int i = 0; i < INTERACTION_COUNT; i++) {
                    addInteraction(flingUp);
                    addInteraction(flingDown);
                }
            }
        });

        mAutomator.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mAutomator != null) {
            mAutomator.cancel();
            mAutomator = null;
        }
    }

    @Override
    protected ListAdapter createListAdapter() {
        return new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                Utils.buildStringList(LIST_SIZE));
    }

    @Override
    protected String getName() {
        return getString(R.string.list_view_scroll_name);
    }
}
