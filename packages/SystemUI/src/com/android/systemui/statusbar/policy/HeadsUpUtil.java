/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.view.View;

import com.android.systemui.R;

/**
 * A class of utility static methods for heads up notifications.
 */
public final class HeadsUpUtil {
    private static final int TAG_CLICKED_NOTIFICATION = R.id.is_clicked_heads_up_tag;

    /**
     * Set the given view as clicked or not-clicked.
     * @param view The view to be set the flag to.
     * @param clicked True to set as clicked. False to not-clicked.
     */
    public static void setIsClickedHeadsUpNotification(View view, boolean clicked) {
        view.setTag(TAG_CLICKED_NOTIFICATION, clicked ? true : null);
    }

    /**
     * Check if the given view has the flag of "clicked notification"
     * @param view The view to be checked.
     * @return True if the view has clicked. False othrewise.
     */
    public static boolean isClickedHeadsUpNotification(View view) {
        Boolean clicked = (Boolean) view.getTag(TAG_CLICKED_NOTIFICATION);
        return clicked != null && clicked;
    }
}
