/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

/**
 *
 */
class QuickSettingsTileView extends FrameLayout {

    private int mColSpan;
    private int mRowSpan;
    private int mCellWidth;

    public QuickSettingsTileView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mColSpan = 1;
        mRowSpan = 1;
    }

    void setColumnSpan(int span) {
        mColSpan = span;
    }

    int getColumnSpan() {
        return mColSpan;
    }

    void setContent(int layoutId, LayoutInflater inflater) {
        inflater.inflate(layoutId, this);
    }
}