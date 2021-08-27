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

package com.android.systemui.screenshot;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.internal.widget.LinearLayoutManager;
import com.android.internal.widget.RecyclerView;
import com.android.internal.widget.RecyclerView.LayoutParams;

import java.util.Random;

public class RecyclerViewActivity extends Activity {
    public static final int CHILD_VIEW_HEIGHT = 300;
    private static final int CHILD_VIEWS = 12;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(new TestAdapter());
        recyclerView.setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));
        setContentView(recyclerView);
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
            view.setText("Child #" + position);
            view.setTextColor(Color.WHITE);
            view.setTextSize(30f);
            view.setBackgroundColor(
                    Color.rgb(mRandom.nextFloat(), mRandom.nextFloat(), mRandom.nextFloat()));
            view.setMinHeight(CHILD_VIEW_HEIGHT);
        }

        @Override
        public int getItemCount() {
            return CHILD_VIEWS;
        }
    }
}
