/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.benchmark.ui;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.ListFragment;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.android.benchmark.R;
import com.android.benchmark.ui.automation.Automator;
import com.android.benchmark.ui.automation.Interaction;

public class ShadowGridActivity extends AppCompatActivity {
    private Automator mAutomator;
    public static class MyListFragment extends ListFragment {
	    @Override
	    public void onViewCreated(View view, Bundle savedInstanceState) {
		    super.onViewCreated(view, savedInstanceState);
		    getListView().setDivider(null);
	    }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int runId = getIntent().getIntExtra("com.android.benchmark.RUN_ID", 0);
        final int iteration = getIntent().getIntExtra("com.android.benchmark.ITERATION", -1);

        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentById(android.R.id.content) == null) {
            ListFragment listFragment = new MyListFragment();

            listFragment.setListAdapter(new ArrayAdapter<>(this,
                    R.layout.card_row, R.id.card_text, Utils.buildStringList(200)));
            fm.beginTransaction().add(android.R.id.content, listFragment).commit();

            String testName = getString(R.string.shadow_grid_name);

            mAutomator = new Automator(testName, runId, iteration, getWindow(),
                    new Automator.AutomateCallback() {
                @Override
                public void onPostAutomate() {
                    Intent result = new Intent();
                    setResult(RESULT_OK, result);
                    finish();
                }

                @Override
                public void onAutomate() {
                    ListView v = (ListView) findViewById(android.R.id.list);

                    int[] coordinates = new int[2];
                    v.getLocationOnScreen(coordinates);

                    int x = coordinates[0];
                    int y = coordinates[1];

                    float width = v.getWidth();
                    float height = v.getHeight();

                    float middleX = (x + width) / 2;
                    float middleY = (y + height) / 2;

                    Interaction flingUp = Interaction.newFlingUp(middleX, middleY);
                    Interaction flingDown = Interaction.newFlingDown(middleX, middleY);

                    addInteraction(flingUp);
                    addInteraction(flingDown);
                    addInteraction(flingUp);
                    addInteraction(flingDown);
                    addInteraction(flingUp);
                    addInteraction(flingDown);
                    addInteraction(flingUp);
                    addInteraction(flingDown);
                }
            });
            mAutomator.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mAutomator != null) {
            mAutomator.cancel();
            mAutomator = null;
        }
    }
}
