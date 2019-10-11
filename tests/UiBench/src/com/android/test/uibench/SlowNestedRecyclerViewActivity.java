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
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;

import com.android.test.uibench.recyclerview.RvCompatListActivity;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class SlowNestedRecyclerViewActivity extends RvCompatListActivity {
    private static final int OUTER_ITEM_COUNT = 100;
    private static final int INNER_ITEM_COUNT = 20;

    private static final long INNER_ITEM_CREATE_NS = TimeUnit.MILLISECONDS.toNanos(6);
    private static final long INNER_ITEM_BIND_NS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long INNER_ITEM_ATTACH_NS = TimeUnit.MILLISECONDS.toNanos(1);

    private static final long OUTER_ITEM_CREATE_NS = TimeUnit.MILLISECONDS.toNanos(3);
    private static final long OUTER_ITEM_BIND_NS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long OUTER_ITEM_ATTACH_NS = TimeUnit.MILLISECONDS.toNanos(1);

    private SizeData mSizeData;

    private static class SizeData {
        final int innerItemWidth;
        final int innerItemHeight;
        final int headerHeight;

        SizeData(Resources resources) {
            innerItemWidth = (int) (resources.getDisplayMetrics().widthPixels / 3.3f);
            innerItemHeight = (int) (innerItemWidth * 1.6f);
            headerHeight = (int) (resources.getDisplayMetrics().heightPixels * 0.5f);
        }
    }

    private SizeData getSizeData(Resources resources) {
        if (mSizeData == null) {
            mSizeData = new SizeData(resources);
        }
        return mSizeData;
    }

    @Override
    protected RecyclerView.LayoutManager createLayoutManager(Context context) {
        return new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
    }

    @Override
    protected RecyclerView.Adapter createAdapter() {
        return new OuterAdapter();
    }

    private class InnerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final long start = System.nanoTime();

            final float density = parent.getResources().getDisplayMetrics().density;
            View view = new View(parent.getContext()) {
                @Override
                protected void onAttachedToWindow() {
                    final long start = System.nanoTime();
                    super.onAttachedToWindow();
                    while (System.nanoTime() - start < INNER_ITEM_ATTACH_NS);
                }
            };

            SizeData sizeData = getSizeData(parent.getResources());
            view.setMinimumWidth(sizeData.innerItemWidth);
            view.setMinimumHeight(sizeData.innerItemHeight);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(10 * density);
            bg.setColor(Color.BLACK);
            final int pad = (int)(10 * density);
            view.setPadding(pad, pad, pad, pad);
            view.setBackgroundDrawable(new InsetDrawable(bg, pad));
            RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(view) {};

            while (System.nanoTime() - start < INNER_ITEM_CREATE_NS);
            return holder;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final long start = System.nanoTime();
            while (System.nanoTime() - start < INNER_ITEM_BIND_NS);
        }

        @Override
        public int getItemCount() { return INNER_ITEM_COUNT; }
    }

    private class OuterAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        static final int TYPE_HEADER = 0;
        static final int TYPE_RECYCLER = 1;

        ArrayList<InnerAdapter> mAdapters = new ArrayList<>();
        RecyclerView.RecycledViewPool mSharedPool = new RecyclerView.RecycledViewPool();

        OuterAdapter() {
            for (int i = 0; i < OUTER_ITEM_COUNT; i++) {
                mAdapters.add(new InnerAdapter());
            }
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            SizeData sizeData = getSizeData(parent.getResources());
            if (viewType == TYPE_HEADER) {
                View view = new View(parent.getContext());
                view.setMinimumHeight(sizeData.headerHeight);
                return new RecyclerView.ViewHolder(view) {};
            } else {
                final long start = System.nanoTime();

                RecyclerView rv = new RecyclerView(parent.getContext()) {
                    @Override
                    protected void onAttachedToWindow() {
                        final long start = System.nanoTime();
                        super.onAttachedToWindow();
                        while (System.nanoTime() - start < OUTER_ITEM_ATTACH_NS);

                    }
                };

                rv.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, sizeData.innerItemHeight));
                rv.setLayoutManager(new LinearLayoutManager(parent.getContext(),
                        LinearLayoutManager.HORIZONTAL, false));
                rv.setRecycledViewPool(mSharedPool);
                RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(rv) {};

                while (System.nanoTime() - start < OUTER_ITEM_CREATE_NS);
                return holder;
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (getItemViewType(position) == TYPE_RECYCLER) {
                final long start = System.nanoTime();
                ((RecyclerView)holder.itemView).setAdapter(mAdapters.get(position));
                while (System.nanoTime() - start < OUTER_ITEM_BIND_NS);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_HEADER : TYPE_RECYCLER;
        }

        @Override
        public int getItemCount() {
            return mAdapters.size();
        }
    }
}
