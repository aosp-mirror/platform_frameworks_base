/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.car.userswitcher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Container for the user switcher which intercepts the key events. */
public class UserSwitcherContainer extends LinearLayout {

    private KeyEventHandler mKeyEventHandler;

    public UserSwitcherContainer(@NonNull Context context) {
        super(context);
    }

    public UserSwitcherContainer(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public UserSwitcherContainer(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public UserSwitcherContainer(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (super.dispatchKeyEvent(event)) {
            return true;
        }

        if (mKeyEventHandler != null) {
            return mKeyEventHandler.dispatchKeyEvent(event);
        }

        return false;
    }

    /** Sets a {@link KeyEventHandler} to help interact with the notification panel. */
    public void setKeyEventHandler(KeyEventHandler keyEventHandler) {
        mKeyEventHandler = keyEventHandler;
    }

    /** An interface to help interact with the notification panel. */
    public interface KeyEventHandler {
        /** Allows handling of a {@link KeyEvent} if it wasn't already handled by the superclass. */
        boolean dispatchKeyEvent(KeyEvent event);
    }
}
