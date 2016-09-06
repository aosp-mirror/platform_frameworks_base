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
package com.android.test.uibench.recyclerview;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.test.uibench.R;

public abstract class RvCompatListActivity extends AppCompatActivity {
    public static class RecyclerViewFragment extends Fragment {
        RecyclerView.LayoutManager layoutManager;
        RecyclerView.Adapter adapter;

        @Nullable
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                Bundle savedInstanceState) {
            RecyclerView recyclerView = (RecyclerView) inflater.inflate(
                    R.layout.recycler_view, container, false);
            recyclerView.setLayoutManager(layoutManager);
            recyclerView.setAdapter(adapter);
            return recyclerView;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentById(android.R.id.content) == null) {
            RecyclerViewFragment fragment = new RecyclerViewFragment();
            fragment.layoutManager = createLayoutManager(this);
            fragment.adapter = createAdapter();
            fm.beginTransaction().add(android.R.id.content, fragment).commit();
        }
    }

    protected RecyclerView.LayoutManager createLayoutManager(Context context) {
        return new LinearLayoutManager(context);
    }

    protected abstract RecyclerView.Adapter createAdapter();
}
