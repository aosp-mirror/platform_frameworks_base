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

import android.app.ActionBar;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.ListFragment;
import android.support.v7.app.AppCompatActivity;
import android.view.Window;
import android.widget.ListAdapter;

import com.android.benchmark.R;

/**
 * Simple list activity base class
 */
public abstract class ListActivityBase extends AppCompatActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_fragment);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setTitle(getName());
        }

        if (findViewById(R.id.list_fragment_container) != null) {
            FragmentManager fm = getSupportFragmentManager();
            ListFragment listView = new ListFragment();
            listView.setListAdapter(createListAdapter());
            fm.beginTransaction().add(R.id.list_fragment_container, listView).commit();
        }
    }

    protected abstract ListAdapter createListAdapter();
    protected abstract String getName();
}

