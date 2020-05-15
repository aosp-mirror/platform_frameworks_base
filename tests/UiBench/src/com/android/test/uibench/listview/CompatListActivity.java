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
package com.android.test.uibench.listview;

import android.os.Bundle;
import android.widget.ListAdapter;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.ListFragment;

public abstract class CompatListActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initializeActivity();

        int containerViewId = getListFragmentContainerViewId();
        FragmentManager fm = getSupportFragmentManager();
        Fragment fragment = fm.findFragmentById(containerViewId);
        if (fragment == null) {
            ListFragment listFragment = createListFragment();
            listFragment.setListAdapter(createListAdapter());
            fm.beginTransaction().add(containerViewId, listFragment).commit();
        } else if (fragment instanceof ListFragment) {
            ((ListFragment) fragment).setListAdapter(createListAdapter());
        }
    }

    protected abstract ListAdapter createListAdapter();

    protected ListFragment createListFragment() {
        return new ListFragment();
    }

    protected int getListFragmentContainerViewId() {
        return android.R.id.content;
    }

    protected void initializeActivity() {
    }
}
