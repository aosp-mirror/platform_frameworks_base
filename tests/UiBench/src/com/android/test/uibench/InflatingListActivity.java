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
package com.android.test.uibench;

import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.ListFragment;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;

public class InflatingListActivity extends AppCompatActivity {
    private ListAdapter createListAdapter() {
        return new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, TrivialListActivity.buildStringList()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                // pathological getView behavior: drop convertView on the floor to force inflation
                return super.getView(position, null, parent);
            }
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentById(android.R.id.content) == null) {
            ListFragment listFragment = new ListFragment();
            listFragment.setListAdapter(createListAdapter());
            fm.beginTransaction().add(android.R.id.content, listFragment).commit();
        }
    }
}
