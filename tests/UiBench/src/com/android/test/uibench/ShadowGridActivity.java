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
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;

import androidx.fragment.app.ListFragment;

import com.android.test.uibench.listview.CompatListActivity;

public class ShadowGridActivity extends CompatListActivity {
    public static class NoDividerListFragment extends ListFragment {
        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            getListView().setDivider(null);
        }
    };

    @Override
    protected ListAdapter createListAdapter() {
        return new ArrayAdapter<>(this, R.layout.card_row, R.id.card_text,
                TextUtils.buildSimpleStringList());
    }

    @Override
    protected ListFragment createListFragment() {
        return new NoDividerListFragment();
    }
}
