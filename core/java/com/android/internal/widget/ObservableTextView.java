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
 * limitations under the License
 */

package com.android.internal.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * A text view whose visibility can be observed.
 */
@RemoteViews.RemoteView
public class ObservableTextView extends TextView {

    private Consumer<Integer> mOnVisibilityChangedListener;

    public ObservableTextView(Context context) {
        super(context);
    }

    public ObservableTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ObservableTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ObservableTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this) {
            if (mOnVisibilityChangedListener != null) {
                mOnVisibilityChangedListener.accept(visibility);
            }
        }
    }

    public void setOnVisibilityChangedListener(Consumer<Integer> listener) {
        mOnVisibilityChangedListener = listener;
    }
}
