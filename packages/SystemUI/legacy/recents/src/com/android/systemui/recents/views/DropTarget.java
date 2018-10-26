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
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import android.graphics.Rect;

/**
 * Represents a drop target for a drag gesture.
 */
public interface DropTarget {

    /**
     * Returns whether this target can accept this drop.  The x,y are relative to the top level
     * RecentsView, and the width/height are of the RecentsView.
     */
    boolean acceptsDrop(int x, int y, int width, int height, Rect insets, boolean isCurrentTarget);
}
