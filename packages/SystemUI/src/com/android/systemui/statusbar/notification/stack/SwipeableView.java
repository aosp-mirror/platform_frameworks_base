/*
 * Copyright (C) 2019 The Android Open Source Project
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

import androidx.annotation.Nullable;

import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;

/** A View that is swipeable inside of the notification shade. */
public interface SwipeableView {

    /** Has this view finished initializing? */
    boolean hasFinishedInitialization();

    /** Optionally creates a menu for this view. */
    @Nullable NotificationMenuRowPlugin createMenu();

    /** Sets the translation amount for an in-progress swipe. */
    void setTranslation(float translate);

    /** Gets the current translation amount. */
    float getTranslation();

    /** Has this view been removed? */
    boolean isRemoved();

    /** Resets the swipe translation back to zero state. */
    void resetTranslation();
}
