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

package com.android.systemui.recent;

import android.view.View;

public interface RecentsCallback {
    static final int SWIPE_LEFT = 0;
    static final int SWIPE_RIGHT = 1;
    static final int SWIPE_UP = 2;
    static final int SWIPE_DOWN = 3;

    void handleOnClick(View selectedView);
    void handleSwipe(View selectedView);
    void handleLongPress(View selectedView, View anchorView, View thumbnailView);
    void dismiss();
}
