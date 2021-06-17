/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

/**
 * This view will measure itself as having 0 size if all of its children are {@link #GONE}.
 * Otherwise it acts like a normal {@link FrameLayout}.
 */
@RemoteViews.RemoteView
public class NotificationVanishingFrameLayout extends FrameLayout {
    public NotificationVanishingFrameLayout(Context context) {
        this(context, null, 0, 0);
    }

    public NotificationVanishingFrameLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0, 0);
    }

    public NotificationVanishingFrameLayout(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationVanishingFrameLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (allChildrenGone()) {
            int zeroSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY);
            super.onMeasure(zeroSpec, zeroSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private boolean allChildrenGone() {
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child =  getChildAt(i);
            if (child != null && child.getVisibility() != GONE) {
                return false;
            }
        }
        return true;
    }
}
