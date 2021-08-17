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

package com.android.systemui.statusbar.notification.stack;

import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.util.AttributeSet;

import com.android.systemui.statusbar.notification.row.ExpandableView;

/**
 * Root view to insert Lock screen media controls into the notification stack.
 */
public class MediaHeaderView extends ExpandableView {

    public MediaHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public long performRemoveAnimation(long duration, long delay, float translationDirection,
            boolean isHeadsUpAnimation, float endLocation, Runnable onFinishedRunnable,
            AnimatorListenerAdapter animationListener) {
        return 0;
    }

    @Override
    public void performAddAnimation(long delay, long duration, boolean isHeadsUpAppear) {
        // No animation, it doesn't need it, this would be local
    }
}
