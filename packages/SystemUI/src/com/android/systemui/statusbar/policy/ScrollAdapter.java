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

package com.android.systemui.statusbar.policy;

import android.view.View;

/**
 * A scroll adapter which can be queried for meta information about the scroll state
 */
public interface ScrollAdapter {

    /**
     * @return Whether the view returned by {@link #getHostView()} is scrolled to the top
     */
    public boolean isScrolledToTop();

    /**
     * @return Whether the view returned by {@link #getHostView()} is scrolled to the bottom
     */
    public boolean isScrolledToBottom();

    /**
     * @return The view in which the scrolling is performed
     */
    public View getHostView();
}
