/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.wm.shell.bubbles.bar;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;

/**
 * Handle / menu view to show at the top of a bubble bar expanded view.
 */
public class HandleView extends LinearLayout {

    // TODO(b/273307221): implement the manage menu in this view.
    public HandleView(Context context) {
        super(context);
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER);
    }

    /**
     * The menu extends past the top of the TaskView because of the rounded corners. This means
     * to center content in the menu we must subtract the radius (i.e. the amount of space covered
     * by TaskView).
     */
    public void setCornerRadius(float radius) {
        setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), (int) radius);
    }
}
