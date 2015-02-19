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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.view.View;

import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.phone.NotificationPanelView;

/**
 * Wraps a notification containing a custom view.
 */
public class NotificationCustomViewWrapper extends NotificationViewWrapper {

    private final ViewInvertHelper mInvertHelper;

    protected NotificationCustomViewWrapper(View view) {
        super(view);
        mInvertHelper = new ViewInvertHelper(view, NotificationPanelView.DOZE_ANIMATION_DURATION);
    }

    @Override
    public void setDark(boolean dark, boolean fade, long delay) {
        if (fade) {
            mInvertHelper.fade(dark, delay);
        } else {
            mInvertHelper.update(dark);
        }
    }
}
