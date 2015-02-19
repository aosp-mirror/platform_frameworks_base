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

import android.content.Context;
import android.view.View;

import com.android.internal.R;

/**
 * Wraps the actual notification content view; used to implement behaviors which are different for
 * the individual templates and custom views.
 */
public abstract class NotificationViewWrapper {

    protected final View mView;

    public static NotificationViewWrapper wrap(Context ctx, View v) {

        // TODO: Figure out a better way to find out which template the view is.
        if (v.findViewById(com.android.internal.R.id.media_actions) != null) {
            return new NotificationMediaViewWrapper(ctx, v);
        } else if (v.getId() == com.android.internal.R.id.status_bar_latest_event_content) {
            return new NotificationTemplateViewWrapper(ctx, v);
        } else {
            return new NotificationCustomViewWrapper(v);
        }
    }

    protected NotificationViewWrapper(View view) {
        mView = view;
    }

    /**
     * In dark mode, we draw as little as possible, assuming a black background.
     *
     * @param dark whether we should display ourselves in dark mode
     * @param fade whether to animate the transition if the mode changes
     * @param delay if fading, the delay of the animation
     */
    public abstract void setDark(boolean dark, boolean fade, long delay);

    /**
     * Notifies this wrapper that the content of the view might have changed.
     */
    public void notifyContentUpdated() {}
}
