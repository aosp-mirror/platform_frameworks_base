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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
        Fragment existingFragment = fm.findFragmentById(android.R.id.content);
        if (existingFragment == null) {
            RecyclerViewFragment fragment = new RecyclerViewFragment();
            initializeRecyclerViewFragment(fragment);
            fm.beginTransaction().add(android.R.id.content, fragment).commit();
        } else if (existingFragment instanceof RecyclerViewFragment) {
            initializeRecyclerViewFragment((RecyclerViewFragment) existingFragment);
        }
    }

    private void initializeRecyclerViewFragment(RecyclerViewFragment fragment) {
        fragment.layoutManager = createLayoutManager(this);
        fragment.adapter = createAdapter();
    }

    protected RecyclerView.LayoutManager createLayoutManager(Context context) {
        return new LinearLayoutManager(context);
    }

    protected abstract RecyclerView.Adapter createAdapter();
}
