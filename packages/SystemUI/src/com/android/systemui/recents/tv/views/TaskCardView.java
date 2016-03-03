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
import android.graphics.Rect;
import android.util.AttributeSet;
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

    public Rect getFocusedRect() {
        Rect r = new Rect();
        getFocusedRect(r);
        return r;
    }

    public Rect getGlobalRect() {
        Rect r = new Rect();
        getGlobalVisibleRect(r);
        return r;
    }
}
