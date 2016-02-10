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
package com.android.documentsui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.ListView;

/**
 * The list in the navigation drawer. This class exists for the purpose of overriding the key
 * handler on ListView. Ignoring keystrokes (e.g. the tab key) cannot be properly done using
 * View.OnKeyListener.
 */
public class RootsList extends ListView {

    // Multiple constructors are needed to handle all the different ways this View could be
    // constructed by the framework. Don't remove them!
    public RootsList(Context context) {
        super(context);
    }

    public RootsList(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public RootsList(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public RootsList(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            // Ignore tab key events - this causes them to bubble up to the global key handler where
            // they are appropriately handled. See BaseActivity.onKeyDown.
            case KeyEvent.KEYCODE_TAB:
                return false;
            // Prevent left/right arrow keystrokes from shifting focus away from the roots list.
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
