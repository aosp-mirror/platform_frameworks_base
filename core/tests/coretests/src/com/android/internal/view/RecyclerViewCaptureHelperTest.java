/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.view;

import android.annotation.UiThread;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.widget.LinearLayoutManager;
import com.android.internal.widget.RecyclerView;

import org.junit.runner.RunWith;

import java.util.Random;

@RunWith(AndroidJUnit4.class)
public class RecyclerViewCaptureHelperTest
        extends AbsCaptureHelperTest<ViewGroup, RecyclerViewCaptureHelper> {

    private static final int CHILD_VIEW_HEIGHT = 300;
    private static final int CHILD_VIEWS = CONTENT_HEIGHT / CHILD_VIEW_HEIGHT;

    @Override
    protected RecyclerViewCaptureHelper createHelper() {
        return new RecyclerViewCaptureHelper();
    }

    @Override
    protected RecyclerView createScrollableContent(ViewGroup parent) {
        RecyclerView recyclerView = new RecyclerView(parent.getContext());
        recyclerView.setAdapter(new TestAdapter());
        LinearLayoutManager layoutManager =
                new LinearLayoutManager(parent.getContext(), LinearLayoutManager.VERTICAL, false);
        recyclerView.setLayoutManager(layoutManager);
        return recyclerView;
    }

    @UiThread
    protected void setInitialScrollPosition(ViewGroup target, ScrollPosition position) {
        switch (position) {
            case MIDDLE:
                target.scrollBy(0, WINDOW_HEIGHT);
                break;
            case BOTTOM:
                target.scrollBy(0, WINDOW_HEIGHT * 2);
                break;
        }
    }

    static final class TestViewHolder extends RecyclerView.ViewHolder {
        TestViewHolder(View itemView) {
            super(itemView);
        }
    }

    static final class TestAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Random mRandom = new Random();

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new TestViewHolder(new TextView(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TextView view = (TextView) holder.itemView;
            view.setText("Item #" + position);
            view.setTextSize(30f);
            view.setBackgroundColor(ITEM_COLORS[position % ITEM_COLORS.length]);
            view.setMinHeight(CHILD_VIEW_HEIGHT);
        }

        @Override
        public int getItemCount() {
            return CHILD_VIEWS;
        }
    }
}
