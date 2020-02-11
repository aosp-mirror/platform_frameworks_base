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

package com.android.systemui.bubbles;

import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_OVERFLOW;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.Activity;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Activity for showing aged out bubbles.
 * Must be public to be accessible to androidx...AppComponentFactory
 */
public class BubbleOverflowActivity extends Activity {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleOverflowActivity" : TAG_BUBBLES;

    private BubbleController mBubbleController;
    private BubbleOverflowAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private List<Bubble> mOverflowBubbles = new ArrayList<>();
    private int mMaxBubbles;

    @Inject
    public BubbleOverflowActivity(BubbleController controller) {
        mBubbleController = controller;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bubble_overflow_activity);
        setBackgroundColor();

        mMaxBubbles = getResources().getInteger(R.integer.bubbles_max_rendered);
        mRecyclerView = findViewById(R.id.bubble_overflow_recycler);
        mRecyclerView.setLayoutManager(
                new GridLayoutManager(getApplicationContext(),
                        getResources().getInteger(R.integer.bubbles_overflow_columns)));

        mAdapter = new BubbleOverflowAdapter(mOverflowBubbles,
                mBubbleController::promoteBubbleFromOverflow);
        mRecyclerView.setAdapter(mAdapter);

        updateData(mBubbleController.getOverflowBubbles());
        mBubbleController.setOverflowCallback(() -> {
            updateData(mBubbleController.getOverflowBubbles());
        });
    }

    void setBackgroundColor() {
        final TypedArray ta = getApplicationContext().obtainStyledAttributes(
                new int[]{android.R.attr.colorBackgroundFloating});
        int bgColor = ta.getColor(0, Color.WHITE);
        ta.recycle();
        findViewById(android.R.id.content).setBackgroundColor(bgColor);
    }

    void updateData(List<Bubble> bubbles) {
        mOverflowBubbles.clear();
        if (bubbles.size() > mMaxBubbles) {
            mOverflowBubbles.addAll(bubbles.subList(mMaxBubbles, bubbles.size()));
        } else {
            mOverflowBubbles.addAll(bubbles);
        }
        mAdapter.notifyDataSetChanged();

        if (DEBUG_OVERFLOW) {
            Log.d(TAG, "Updated overflow bubbles:\n" + BubbleDebugConfig.formatBubblesString(
                    mOverflowBubbles, /*selected*/ null));
        }
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onRestart() {
        super.onRestart();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    public void onDestroy() {
        super.onDestroy();
    }
}

class BubbleOverflowAdapter extends RecyclerView.Adapter<BubbleOverflowAdapter.ViewHolder> {
    private Consumer<Bubble> mPromoteBubbleFromOverflow;
    private List<Bubble> mBubbles;

    public BubbleOverflowAdapter(List<Bubble> list, Consumer<Bubble> promoteBubble) {
        mBubbles = list;
        mPromoteBubbleFromOverflow = promoteBubble;
    }

    @Override
    public BubbleOverflowAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
            int viewType) {
        BadgedImageView view = (BadgedImageView) LayoutInflater.from(parent.getContext())
                .inflate(R.layout.bubble_view, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder vh, int index) {
        Bubble bubble = mBubbles.get(index);

        vh.mBadgedImageView.update(bubble);
        vh.mBadgedImageView.setOnClickListener(view -> {
            mBubbles.remove(bubble);
            notifyDataSetChanged();
            mPromoteBubbleFromOverflow.accept(bubble);
        });
    }

    @Override
    public int getItemCount() {
        return mBubbles.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public BadgedImageView mBadgedImageView;

        public ViewHolder(BadgedImageView v) {
            super(v);
            mBadgedImageView = v;
        }
    }
}