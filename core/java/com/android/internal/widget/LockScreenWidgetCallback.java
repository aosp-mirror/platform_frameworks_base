/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.view.View;

/**
 * An interface used by LockScreenWidgets to send messages to lock screen.
 */
public interface LockScreenWidgetCallback {
    // Sends a message to lock screen requesting the given view be shown.  May be ignored, depending
    // on lock screen state. View must be the top-level lock screen widget or it will be ignored.
    public void requestShow(View self);

    // Sends a message to lock screen requesting the view to be hidden.
    public void requestHide(View self);

    // Whether or not this view is currently visible on LockScreen
    public boolean isVisible(View self);

    // Sends a message to lock screen that user has interacted with widget. This should be used
    // exclusively in response to user activity, i.e. user hits a button in the view.
    public void userActivity(View self);

}
