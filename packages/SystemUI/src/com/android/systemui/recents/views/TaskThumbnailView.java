/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import com.android.systemui.recents.model.Task;


/** The task thumbnail view */
public class TaskThumbnailView extends ImageView {

    Task mTask;

    // Task bar clipping
    Rect mClipRect;
    boolean mClipTaskBar = true;

    public TaskThumbnailView(Context context) {
        this(context, null);
    }

    public TaskThumbnailView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskThumbnailView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskThumbnailView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setScaleType(ScaleType.FIT_XY);
    }

    @Override
    public void draw(Canvas canvas) {
        if (mClipTaskBar && (mClipRect != null)) {
            // Apply the clip rect
            canvas.clipRect(mClipRect);
        }
        super.draw(canvas);
    }

    /** Updates the clip rect based on the given task bar. */
    void updateTaskBarClip(View taskBar) {
        // If mClipTaskBar is unset first, then we don't bother setting mTaskBar
        if (mClipTaskBar) {
            int top = (int) Math.max(0, taskBar.getTranslationY() +
                    taskBar.getMeasuredHeight() - 1);
            mClipRect = new Rect(0, top, getMeasuredWidth(), getMeasuredHeight());
            invalidate(0, 0, taskBar.getMeasuredWidth(), taskBar.getMeasuredHeight() + 1);
        }
    }

    /** Disables the task bar clipping. */
    void disableClipTaskBarView() {
        mClipTaskBar = false;
        if (mClipRect != null) {
            invalidate(0, 0, mClipRect.width(), mClipRect.top);
        }
    }

    /** Binds the thumbnail view to the task */
    void rebindToTask(Task t, boolean animate) {
        mTask = t;
        if (t.thumbnail != null) {
            setImageBitmap(t.thumbnail);
        }
    }

    /** Unbinds the thumbnail view from the task */
    void unbindFromTask() {
        mTask = null;
        setImageDrawable(null);
    }
}
