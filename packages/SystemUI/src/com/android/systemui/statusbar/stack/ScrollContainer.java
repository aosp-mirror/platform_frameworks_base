/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.stack;

import android.view.View;

/**
 * Interface for container layouts that scroll and listen for long presses. A child that
 * wants to handle long press can use this to cancel the parents long press logic or request
 * to be made visible by scrolling to it.
 */
public interface ScrollContainer {
    /**
     * Request that the view does not perform long press for the current touch.
     */
    void requestDisallowLongPress();

    /**
     * Request that the view is made visible by scrolling to it.
     */
    void scrollTo(View v);

    /**
     * Request that the view does not dismiss for the current touch.
     */
    void requestDisallowDismiss();
}
