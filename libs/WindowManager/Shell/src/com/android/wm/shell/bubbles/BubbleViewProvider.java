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

package com.android.wm.shell.bubbles;

import android.graphics.Bitmap;
import android.graphics.Path;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Interface to represent actual Bubbles and UI elements that act like bubbles, like BubbleOverflow.
 */
public interface BubbleViewProvider {
    @Nullable BubbleExpandedView getExpandedView();

    /**
     * Sets whether the contents of the bubble's TaskView should be visible.
     */
    void setTaskViewVisibility(boolean visible);

    @Nullable View getIconView();

    String getKey();

    /** Bubble icon bitmap with no badge and no dot. */
    Bitmap getBubbleIcon();

    /** App badge drawable to draw above bubble icon. */
    @Nullable Bitmap getAppBadge();

    /** Path of normalized bubble icon to draw dot on. */
    Path getDotPath();

    int getDotColor();

    boolean showDot();

    int getTaskId();
}
