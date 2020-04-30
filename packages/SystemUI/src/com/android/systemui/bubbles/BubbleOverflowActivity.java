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
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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

    private LinearLayout mEmptyState;
    private ImageView mEmptyStateImage;
    private BubbleController mBubbleController;
    private BubbleOverflowAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private List<Bubble> mOverflowBubbles = new ArrayList<>();

    private class NoScrollGridLayoutManager extends GridLayoutManager {
        NoScrollGridLayoutManager(Context context, int columns) {
            super(context, columns);
        }
        @Override
        public boolean canScrollVertically() {
            return false;
        }
    }

    @Inject
    public BubbleOverflowActivity(BubbleController controller) {
        mBubbleController = controller;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bubble_overflow_activity);
        setBackgroundColor();

        mEmptyState = findViewById(R.id.bubble_overflow_empty_state);
        mRecyclerView = findViewById(R.id.bubble_overflow_recycler);
        mEmptyStateImage = findViewById(R.id.bubble_overflow_empty_state_image);

        Resources res = getResources();
        final int columns = res.getInteger(R.integer.bubbles_overflow_columns);
        mRecyclerView.setLayoutManager(
                new NoScrollGridLayoutManager(getApplicationContext(), columns));

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        final int recyclerViewWidth = (displayMetrics.widthPixels
                - res.getDimensionPixelSize(R.dimen.bubble_overflow_padding));
        final int viewWidth = recyclerViewWidth / columns;

        final int maxOverflowBubbles = res.getInteger(R.integer.bubbles_max_overflow);
        final int rows = (int) Math.ceil((double) maxOverflowBubbles / columns);
        final int recyclerViewHeight = res.getDimensionPixelSize(R.dimen.bubble_overflow_height)
                - res.getDimensionPixelSize(R.dimen.bubble_overflow_padding);
        final int viewHeight = recyclerViewHeight / rows;

        mAdapter = new BubbleOverflowAdapter(mOverflowBubbles,
                mBubbleController::promoteBubbleFromOverflow, viewWidth, viewHeight);
        mRecyclerView.setAdapter(mAdapter);

        mOverflowBubbles.addAll(mBubbleController.getOverflowBubbles());
        mAdapter.notifyDataSetChanged();
        setEmptyStateVisibility();

        mBubbleController.setOverflowListener(mDataListener);
    }

    /**
     * Handle theme changes.
     */
    void onThemeChanged() {
        final int mode =
                getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        switch (mode) {
            case Configuration.UI_MODE_NIGHT_NO:
                if (DEBUG_OVERFLOW) {
                    Log.d(TAG, "Set overflow UI to light mode");
                }
                mEmptyStateImage.setImageDrawable(
                        getResources().getDrawable(R.drawable.ic_empty_bubble_overflow_light));
                break;
            case Configuration.UI_MODE_NIGHT_YES:
                if (DEBUG_OVERFLOW) {
                    Log.d(TAG, "Set overflow UI to dark mode");
                }
                mEmptyStateImage.setImageDrawable(
                        getResources().getDrawable(R.drawable.ic_empty_bubble_overflow_dark));
                break;
        }
    }

    void setEmptyStateVisibility() {
        if (mOverflowBubbles.isEmpty()) {
            mEmptyState.setVisibility(View.VISIBLE);
        } else {
            mEmptyState.setVisibility(View.GONE);
        }
    }

    void setBackgroundColor() {
        final TypedArray ta = getApplicationContext().obtainStyledAttributes(
                new int[]{android.R.attr.colorBackgroundFloating});
        int bgColor = ta.getColor(0, Color.WHITE);
        ta.recycle();
        findViewById(android.R.id.content).setBackgroundColor(bgColor);
    }

    private final BubbleData.Listener mDataListener = new BubbleData.Listener() {

        @Override
        public void applyUpdate(BubbleData.Update update) {

            Bubble toRemove = update.removedOverflowBubble;
            if (toRemove != null) {
                if (DEBUG_OVERFLOW) {
                    Log.d(TAG, "remove: " + toRemove);
                }
                toRemove.cleanupViews();
                int i = mOverflowBubbles.indexOf(toRemove);
                mOverflowBubbles.remove(toRemove);
                mAdapter.notifyItemRemoved(i);
            }

            Bubble toAdd = update.addedOverflowBubble;
            if (toAdd != null) {
                if (DEBUG_OVERFLOW) {
                    Log.d(TAG, "add: " + toAdd);
                }
                mOverflowBubbles.add(0, toAdd);
                mAdapter.notifyItemInserted(0);
            }

            setEmptyStateVisibility();

            if (DEBUG_OVERFLOW) {
                Log.d(TAG, BubbleDebugConfig.formatBubblesString(
                        mBubbleController.getOverflowBubbles(),
                        null));
            }
        }
    };

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
        onThemeChanged();
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
    private int mWidth;
    private int mHeight;

    public BubbleOverflowAdapter(List<Bubble> list, Consumer<Bubble> promoteBubble, int width,
            int height) {
        mBubbles = list;
        mPromoteBubbleFromOverflow = promoteBubble;
        mWidth = width;
        mHeight = height;
    }

    @Override
    public BubbleOverflowAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
            int viewType) {
        LinearLayout overflowView = (LinearLayout) LayoutInflater.from(parent.getContext())
                .inflate(R.layout.bubble_overflow_view, parent, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.width = mWidth;
        params.height = mHeight;
        overflowView.setLayoutParams(params);
        return new ViewHolder(overflowView);
    }

    @Override
    public void onBindViewHolder(ViewHolder vh, int index) {
        Bubble b = mBubbles.get(index);

        vh.iconView.setRenderedBubble(b);
        vh.iconView.removeDotSuppressionFlag(BadgedImageView.SuppressionFlag.FLYOUT_VISIBLE);
        vh.iconView.setOnClickListener(view -> {
            mBubbles.remove(b);
            notifyDataSetChanged();
            mPromoteBubbleFromOverflow.accept(b);
        });

        Bubble.FlyoutMessage message = b.getFlyoutMessage();
        if (message != null && message.senderName != null) {
            vh.textView.setText(message.senderName);
        } else {
            vh.textView.setText(b.getAppName());
        }
    }

    @Override
    public int getItemCount() {
        return mBubbles.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public BadgedImageView iconView;
        public TextView textView;

        public ViewHolder(LinearLayout v) {
            super(v);
            iconView = v.findViewById(R.id.bubble_view);
            textView = v.findViewById(R.id.bubble_view_name);
        }
    }
}