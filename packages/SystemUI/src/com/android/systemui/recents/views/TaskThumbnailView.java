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
import android.util.AttributeSet;
import android.widget.ImageView;
import com.android.systemui.recents.model.Task;


/** The task thumbnail view */
public class TaskThumbnailView extends ImageView {
    Task mTask;

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

    /** Binds the thumbnail view to the task */
    void rebindToTask(Task t, boolean animate) {
        mTask = t;
        if (t.thumbnail != null) {
            setImageBitmap(t.thumbnail);
            if (animate) {
                // XXX: Investigate how expensive it will be to create a second bitmap and crossfade
            }
        }
    }

    /** Unbinds the thumbnail view from the task */
    void unbindFromTask() {
        mTask = null;
        setImageDrawable(null);
    }
}
