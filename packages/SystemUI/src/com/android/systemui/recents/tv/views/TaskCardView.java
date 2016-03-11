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
package com.android.systemui.recents.tv.views;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Display;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.recents.tv.animations.ViewFocusAnimator;
import com.android.systemui.recents.model.Task;

public class TaskCardView extends LinearLayout {

    private ImageView mThumbnailView;
    private TextView mTitleTextView;
    private ImageView mBadgeView;
    private Task mTask;

    private ViewFocusAnimator mViewFocusAnimator;

    public TaskCardView(Context context) {
        this(context, null);
    }

    public TaskCardView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskCardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mViewFocusAnimator = new ViewFocusAnimator(this);
    }

    @Override
    protected void onFinishInflate() {
        mThumbnailView = (ImageView) findViewById(R.id.card_view_thumbnail);
        mTitleTextView = (TextView) findViewById(R.id.card_title_text);
        mBadgeView = (ImageView) findViewById(R.id.card_extra_badge);
    }

    public void init(Task task) {
        mTask = task;
        mThumbnailView.setImageBitmap(task.thumbnail);
        mTitleTextView.setText(task.title);
        mBadgeView.setImageDrawable(task.icon);
    }

    public Task getTask() {
        return mTask;
    }

    @Override
    public void getFocusedRect(Rect r) {
        mThumbnailView.getFocusedRect(r);
    }

    public Rect getFocusedThumbnailRect() {
        Rect r = new Rect();
        mThumbnailView.getGlobalVisibleRect(r);
        TypedValue out = new TypedValue();
        getContext().getResources().getValue(R.integer.selected_scale, out, true);
        float deltaScale = (out.getFloat() - 1.0f) / 2;
        r.set((int) (r.left - r.left * deltaScale),
                (int) (r.top - r.top * deltaScale),
                (int) (r.right + r.right * deltaScale),
                (int) (r.bottom + r.bottom * deltaScale));
        return r;
    }

    public static Rect getStartingCardThumbnailRect(Context context) {
        Resources res = context.getResources();

        TypedValue out = new TypedValue();
        res.getValue(R.integer.selected_scale, out, true);
        float scale = out.getFloat();

        int width = res.getDimensionPixelOffset(R.dimen.recents_tv_card_width);
        int widthDelta = (int) (width * scale - width);
        int height = (int) (res.getDimensionPixelOffset(
                R.dimen.recents_tv_screenshot_height) * scale);
        int padding = res.getDimensionPixelOffset(R.dimen.recents_tv_grid_row_padding);

        int headerHeight = (int) ((res.getDimensionPixelOffset(
                R.dimen.recents_tv_card_extra_badge_size) +
                res.getDimensionPixelOffset(R.dimen.recents_tv_icon_padding_bottom)) * scale);

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int screenWidth = size.x;
        int screenHeight = size.y;

        return new Rect(screenWidth - width - padding - widthDelta / 2,
                screenHeight / 2 - height / 2 + headerHeight / 2,
                screenWidth - padding + widthDelta / 2,
                screenHeight / 2 + height / 2 + headerHeight / 2);
    }
}
