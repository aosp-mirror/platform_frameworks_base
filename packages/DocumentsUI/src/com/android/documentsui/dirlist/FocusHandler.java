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
 * limitations under the License.
 */

package com.android.documentsui.dirlist;

import android.view.KeyEvent;
import android.view.View;

/**
 * A class that handles navigation and focus within the DirectoryFragment.
 */
interface FocusHandler extends View.OnFocusChangeListener {

    /**
     * Handles navigation (setting focus, adjusting selection if needed) arising from incoming key
     * events.
     *
     * @param doc The DocumentHolder receiving the key event.
     * @param keyCode
     * @param event
     * @return Whether the event was handled.
     */
    boolean handleKey(DocumentHolder doc, int keyCode, KeyEvent event);

    @Override
    void onFocusChange(View v, boolean hasFocus);

    /**
     * Requests focus on the item that last had focus. Scrolls to that item if necessary.
     */
    void restoreLastFocus();

    /**
     * @return The adapter position of the last focused item.
     */
    int getFocusPosition();

}
